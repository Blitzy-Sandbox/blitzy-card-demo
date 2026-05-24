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

import com.blitzy.carddemo.domain.record.TranCatBalRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port abstraction for the {@code TCATBALF} VSAM KSDS dataset
 * &mdash; the per-account, per-(transaction-type + category) cumulative
 * balance table. Each record is a 50-byte fixed-width row composed of a
 * 17-byte composite key
 * ({@code TRANCAT-ACCT-ID PIC 9(11) + TRANCAT-TYPE-CD PIC X(02) +
 * TRANCAT-CD PIC 9(04)}), an 11-byte signed monetary
 * {@code TRAN-CAT-BAL PIC S9(09)V99}, and a 22-byte trailing
 * {@code FILLER PIC X(22)} per copybook {@code CVTRA01Y}
 * {@code [app/cpy/CVTRA01Y.cpy:L4-L10]}.
 *
 * <h2>COBOL source provenance</h2>
 * <p>The IDCAMS {@code DEFINE CLUSTER} for {@code TCATBALF} declares
 * {@code KEYS(17 0) RECORDSIZE(50 50) INDEXED}
 * {@code [app/jcl/TCATBALF.jcl:STEP10]} &mdash; the canonical 17-byte
 * key and 50-byte fixed record. The dataset is consumed from COBOL
 * via two distinct access modes:
 * <ul>
 *   <li><b>Random access (CBTRN02C, daily transaction posting)</b>
 *       &mdash; {@code SELECT TCATBAL-FILE ASSIGN TO TCATBALF
 *       ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM RECORD KEY IS
 *       FD-TRAN-CAT-KEY FILE STATUS IS TCATBALF-STATUS}
 *       {@code [app/cbl/CBTRN02C.cbl:L57-L61]}. Opened with
 *       {@code OPEN I-O TCATBAL-FILE}
 *       {@code [app/cbl/CBTRN02C.cbl:L329]} for the
 *       read-modify-rewrite cycle that maintains running balances as
 *       each posted transaction is applied.</li>
 *   <li><b>Sequential access (CBACT04C, interest calculation)</b>
 *       &mdash; {@code SELECT TCATBAL-FILE ASSIGN TO TCATBALF
 *       ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY
 *       IS FD-TRAN-CAT-KEY FILE STATUS IS TCATBALF-STATUS}
 *       {@code [app/cbl/CBACT04C.cbl:L28-L32]}. Opened with
 *       {@code OPEN INPUT TCATBAL-FILE}
 *       {@code [app/cbl/CBACT04C.cbl:L236]} for the end-of-month
 *       interest computation pass that walks every category balance in
 *       composite-key order.</li>
 * </ul>
 *
 * <h2>COBOL consumers (per AAP &sect;0.4.1)</h2>
 * <ul>
 *   <li><b>CBTRN02C</b> &mdash; transaction posting engine. Paragraph
 *       {@code 2700-UPDATE-TCATBAL}
 *       {@code [app/cbl/CBTRN02C.cbl:L467-L501]} composes the
 *       17-byte key from the daily-transaction record, issues a random
 *       {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD INVALID KEY
 *       ...} {@code [app/cbl/CBTRN02C.cbl:L474-L479]}, and dispatches
 *       on the {@code INVALID KEY} flag
 *       ({@code WS-CREATE-TRANCAT-REC = 'Y'}) to either the create
 *       branch (paragraph {@code 2700-A-CREATE-TCATBAL-REC} at
 *       {@code [app/cbl/CBTRN02C.cbl:L503-L524]}, issuing
 *       {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
 *       at {@code [app/cbl/CBTRN02C.cbl:L510]}) or the update branch
 *       (paragraph {@code 2700-B-UPDATE-TCATBAL-REC} at
 *       {@code [app/cbl/CBTRN02C.cbl:L526-L540]}, issuing
 *       {@code REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
 *       at {@code [app/cbl/CBTRN02C.cbl:L528]}). Maps to
 *       {@link #findByKey(long, String, int)} and {@link #save(TranCatBalRecord)}.</li>
 *   <li><b>CBACT04C</b> &mdash; monthly interest computation engine.
 *       Paragraph {@code 1000-TCATBALF-GET-NEXT}
 *       {@code [app/cbl/CBACT04C.cbl:L325-L348]} issues
 *       {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD}
 *       {@code [app/cbl/CBACT04C.cbl:L326]} repeatedly in
 *       composite-key sequential order, computing per-category
 *       interest via
 *       {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE)
 *       / 1200} {@code [app/cbl/CBACT04C.cbl:L465]} and synthesising
 *       posting transactions. Maps to {@link #streamSequential()}.</li>
 *   <li><b>PRTCATBL.jcl</b> &mdash; sequential print of the entire
 *       {@code TCATBALF} dataset for operational reporting. Loads via
 *       {@code REPRO} from
 *       {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS} and emits a
 *       fixed-width flat file at {@code LRECL=50 RECFM=FB}
 *       {@code [app/jcl/PRTCATBL.jcl]} suitable for SORT by
 *       {@code TRANCAT-ACCT-ID,1,11,ZD}. Maps to
 *       {@link #streamSequential()}.</li>
 *   <li><b>TCATBALF.jcl</b> &mdash; IDCAMS
 *       {@code DELETE CLUSTER} / {@code DEFINE CLUSTER} /
 *       {@code REPRO} initial seed-load cycle
 *       {@code [app/jcl/TCATBALF.jcl:STEP05-STEP15]}. The bulk
 *       {@code REPRO} maps to repeated {@link #save(TranCatBalRecord)}
 *       calls; the dataset-level {@code DELETE} maps to
 *       {@link #delete(long, String, int)} for the
 *       {@code DefineTcatBalApp} composition root.</li>
 * </ul>
 *
 * <h2>Composite key contract (TRAN-CAT-KEY, 17 bytes)</h2>
 * <p>The composite key is exposed across three ordered parameters
 * {@code (accountId, tranTypeCd, tranCatCd)} in the SAME byte order
 * as the COBOL declaration of {@code FD-TRAN-CAT-KEY}
 * {@code [app/cbl/CBTRN02C.cbl:L93-L96]}:
 * <ul>
 *   <li>{@code accountId}: bytes 0&ndash;10 &mdash;
 *       {@code TRANCAT-ACCT-ID PIC 9(11)}, unsigned 11-digit account
 *       ID, range {@code 0L..99_999_999_999L}.</li>
 *   <li>{@code tranTypeCd}: bytes 11&ndash;12 &mdash;
 *       {@code TRANCAT-TYPE-CD PIC X(02)}, exactly 2 characters
 *       alphanumeric.</li>
 *   <li>{@code tranCatCd}: bytes 13&ndash;16 &mdash;
 *       {@code TRANCAT-CD PIC 9(04)}, unsigned 4-digit category code,
 *       range {@code 0..9999}.</li>
 * </ul>
 * Implementations MUST preserve this positional ordering when
 * composing the 17-byte key for VSAM KSDS random reads, sequential
 * cursor positioning, IDCAMS-style {@code REPRO} loads, or any
 * underlying adapter (JDBC, in-memory, file). The same ordering MUST
 * be used by {@link #findByKey(long, String, int)} and
 * {@link #delete(long, String, int)} so call sites are syntactically
 * parallel.
 *
 * <h2>Upsert semantics &mdash; one Java {@code save} replaces two
 * COBOL paragraphs</h2>
 * <p>The COBOL transaction-posting flow (CBTRN02C paragraph
 * {@code 2700-UPDATE-TCATBAL} at
 * {@code [app/cbl/CBTRN02C.cbl:L467-L501]}) splits the
 * read-modify-persist operation into three distinct paragraphs:
 * <ol>
 *   <li>{@code 2700-UPDATE-TCATBAL}
 *       {@code [app/cbl/CBTRN02C.cbl:L467-L501]} &mdash; issues the
 *       random {@code READ TCATBAL-FILE} and sets the
 *       {@code WS-CREATE-TRANCAT-REC} flag based on the
 *       {@code INVALID KEY} outcome.</li>
 *   <li>{@code 2700-A-CREATE-TCATBAL-REC}
 *       {@code [app/cbl/CBTRN02C.cbl:L503-L524]} &mdash; issues
 *       {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
 *       on the create branch.</li>
 *   <li>{@code 2700-B-UPDATE-TCATBAL-REC}
 *       {@code [app/cbl/CBTRN02C.cbl:L526-L540]} &mdash; issues
 *       {@code REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
 *       on the update branch.</li>
 * </ol>
 * Per AAP &sect;0.1.2 (translation rule
 * "COBOL {@code WRITE}/{@code REWRITE} on packed-decimal &rarr;
 * {@code BigDecimal} operations via {@code Decimals} utility") and the
 * binding agent prompt &sect;4.3, the Java {@link #save(TranCatBalRecord)}
 * method collapses both the WRITE (create) and REWRITE (update)
 * branches into a single upsert call: callers do NOT need to invoke
 * {@link #findByKey(long, String, int)} beforehand. The implementation
 * inspects the composite key and either inserts a new row or replaces
 * an existing one. This preserves the observable COBOL behavior
 * (after the posting completes, the dataset contains the new or
 * updated row regardless of pre-existing state) while removing one
 * step from the application-layer call site.
 *
 * <h2>FILE STATUS '00' OR '23' semantics on the read path</h2>
 * <p>The COBOL paragraph {@code 2700-UPDATE-TCATBAL} explicitly
 * treats VSAM file-status code {@code '23'} (record not found) as
 * equivalent to {@code '00'} (success) for the read:
 * <pre>{@code
 *   IF  TCATBALF-STATUS = '00'  OR '23'
 *       MOVE 0 TO APPL-RESULT
 *   ELSE
 *       MOVE 12 TO APPL-RESULT
 *   END-IF
 * }</pre>
 * &mdash; {@code [app/cbl/CBTRN02C.cbl:L481-L485]}. This means a
 * missing record is NOT an error; it is the normal trigger for the
 * create branch. The Java translation expresses this via the
 * {@link Optional} return type of {@link #findByKey(long, String, int)}:
 * empty Optional &harr; INVALID KEY &harr; file status {@code '23'}
 * &harr; {@code WS-CREATE-TRANCAT-REC = 'Y'} &harr; subsequent
 * {@link #save(TranCatBalRecord)} performs an insert. Adapter
 * implementations MUST NOT throw or abend on a missing key during
 * the read; they MUST return {@link Optional#empty()}.
 *
 * <h2>FILLER preservation contract (byte-for-byte fidelity,
 * AAP &sect;0.6.5)</h2>
 * <p>The trailing 22-byte {@code FILLER PIC X(22)}
 * {@code [app/cpy/CVTRA01Y.cpy:L10]} carries no semantic meaning at
 * the application level but MUST be persisted verbatim by any
 * implementation of {@link #save(TranCatBalRecord)} so that the
 * round-trip identity
 * {@code Arrays.equals(buf, TranCatBalRecord.parse(buf).encode()) == true}
 * holds for any record read from storage and written back. The
 * {@link TranCatBalRecord} record itself defensively clones the
 * FILLER on construction and accessor (see
 * {@link TranCatBalRecord#filler()}); implementations of this port
 * MUST NOT strip, normalise, or zero-fill the FILLER bytes. The
 * byte-for-byte invariant is the formal contract with external file
 * consumers and is asserted by the golden-record harness on every PR
 * (AAP &sect;0.6.11).
 *
 * <h2>Decimal-arithmetic fidelity for TRAN-CAT-BAL (AAP &sect;0.6.1)</h2>
 * <p>The signed monetary {@code TRAN-CAT-BAL PIC S9(09)V99} field is
 * carried as a {@link java.math.BigDecimal} with scale exactly 2.
 * The {@link TranCatBalRecord} canonical constructor normalises the
 * scale via {@code setScale(2, RoundingMode.HALF_EVEN)} (banker's
 * rounding); callers passing a {@link TranCatBalRecord} to
 * {@link #save(TranCatBalRecord)} can therefore rely on the encoded
 * byte image preserving trailing zeros (a value of {@code 1.20} is
 * encoded as {@code "00000000120"} with the canonical overpunch on
 * the last byte, NOT as {@code "0000000012"}). Implementations MUST
 * NOT rescale or truncate the monetary field beyond what
 * {@link TranCatBalRecord} has already enforced. Per AAP &sect;0.6.1,
 * any monetary {@code BigDecimal} operation that intermediate code
 * performs on the balance value MUST use
 * {@code java.math.MathContext.DECIMAL128} and
 * {@code java.math.RoundingMode.HALF_EVEN} (banker's rounding) when
 * the COBOL source specifies {@code ROUNDED}, or
 * {@code RoundingMode.DOWN} (truncation) otherwise &mdash; centralised
 * in the {@code com.blitzy.carddemo.domain.util.Decimals} utility
 * (AAP &sect;0.3.3).
 *
 * <h2>Ordering invariant on streamSequential</h2>
 * <p>{@link #streamSequential()} returns records in ascending
 * composite-key order &mdash; {@code TRANCAT-ACCT-ID} first, then
 * {@code TRANCAT-TYPE-CD}, then {@code TRANCAT-CD} &mdash; matching
 * the lexicographic ordering of the underlying VSAM KSDS index
 * defined by {@code KEYS(17 0)} in {@code TCATBALF.jcl}
 * {@code [app/jcl/TCATBALF.jcl:STEP10]}. This is the order observed
 * by CBACT04C's interest engine and by the PRTCATBL print report.
 * Reordering is FORBIDDEN per AAP &sect;0.1.3
 * ("virtual threads are NOT a license to reorder records, change sort
 * orders, or break sequencing") and per AAP &sect;0.7.1
 * ("All file naming conventions, sort orders, and batch sequencing"
 * preserve-as-is). Implementations MUST preserve ascending
 * composite-key order even when streaming on virtual threads.
 *
 * <h2>Resource ownership</h2>
 * <p>The {@link Stream} returned by {@link #streamSequential()}
 * extends {@link AutoCloseable} (via
 * {@link java.util.stream.BaseStream}). Callers MUST close the
 * returned stream &mdash; typically via try-with-resources &mdash; so
 * that the adapter can release the underlying file channel, DB
 * cursor, or pre-allocated record buffer:
 * <pre>{@code
 *   try (Stream<TranCatBalRecord> tcatBal = repository.streamSequential()) {
 *       tcatBal.forEach(this::process);
 *   }
 * }</pre>
 * Failure to close may leak file handles in long-running batch jobs
 * such as the monthly interest computation (CBACT04C) or the
 * PRTCATBL print pass.
 *
 * <p>The repository instance itself is also {@link AutoCloseable}
 * (matching the lifecycle convention used by all sibling
 * big-dataset port interfaces in this package &mdash;
 * {@link AccountRepository}, {@link CardRepository},
 * {@link CardXrefRepository}, {@link CustomerRepository},
 * {@link DailyTransactionRepository}, {@link TransactionRepository},
 * {@link DiscountGroupRepository}). Implementations MAY be a no-op
 * for in-memory adapters and MUST be idempotent so that callers can
 * defensively close the same repository multiple times without
 * observable side-effects. This mirrors the COBOL
 * {@code 9000-TCATBALF-CLOSE} paragraph at
 * {@code [app/cbl/CBACT04C.cbl:L522-L540]}
 * ({@code CLOSE TCATBAL-FILE}) and the analogous
 * {@code CLOSE TCATBAL-FILE} at the end of CBTRN02C.
 *
 * <h2>Forbidden in implementations (per AAP &sect;0.6 / &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring / Hibernate / JPA / Lombok &mdash; constructor
 *       injection and plain factories only.</li>
 *   <li>No {@code java.io.File} &mdash; use
 *       {@code java.nio.file.Files} and {@code SeekableByteChannel}
 *       for all file I/O (AAP &sect;0.1.1).</li>
 *   <li>No {@code java.util.Date} / {@code Calendar} &mdash; use
 *       {@code java.time.*} for any date/time logic (AAP
 *       &sect;0.1.1).</li>
 *   <li>No {@code double} / {@code float} for any monetary value
 *       &mdash; always {@link java.math.BigDecimal} (AAP
 *       &sect;0.6.1).</li>
 *   <li>No {@code ThreadLocal} &mdash; use
 *       {@code java.lang.ScopedValue} for any per-request context
 *       propagation (AAP &sect;0.6.6).</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Per AAP &sect;0.6.6, batch drivers may fan out per-record work
 * onto virtual threads where the COBOL processing is serial but the
 * per-record work is independent. Implementations of this port
 * SHOULD be safe to call concurrently from multiple virtual threads
 * for {@link #findByKey(long, String, int)} (read-only); concurrency
 * on {@link #save(TranCatBalRecord)} and
 * {@link #delete(long, String, int)} is implementation-defined and
 * is typically gated by a per-key lock or single-writer constraint
 * in the file adapter. Stream-returning methods
 * ({@link #streamSequential()}) are not required to be thread-safe
 * at the stream level; each caller should obtain its own stream and
 * close it within the same thread or virtual-thread scope.
 *
 * @see TranCatBalRecord
 * @see com.blitzy.carddemo.domain.port.TransactionCategoryRepository
 * @see com.blitzy.carddemo.domain.port.TransactionTypeRepository
 * @see com.blitzy.carddemo.domain.port.DiscountGroupRepository
 * @see com.blitzy.carddemo.domain.util.Decimals
 * @since 1.0.0
 */
public interface TransactionCategoryBalanceRepository extends AutoCloseable {

    /**
     * Random read of the {@link TranCatBalRecord} keyed by the
     * composite {@code (accountId, tranTypeCd, tranCatCd)} triple
     * &mdash; the Java equivalent of the COBOL
     * {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD INVALID KEY ...}
     * statement
     * {@code [app/cbl/CBTRN02C.cbl:L474-L479]} in paragraph
     * {@code 2700-UPDATE-TCATBAL}, where the composite key is
     * composed via three MOVE statements immediately prior:
     * <pre>{@code
     *   MOVE XREF-ACCT-ID     TO FD-TRANCAT-ACCT-ID
     *   MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD
     *   MOVE DALYTRAN-CAT-CD  TO FD-TRANCAT-CD
     *   ...
     *   READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
     *      INVALID KEY
     *        DISPLAY 'TCATBAL record not found for key : '
     *           FD-TRAN-CAT-KEY '.. Creating.'
     *        MOVE 'Y' TO WS-CREATE-TRANCAT-REC
     *   END-READ.
     * }</pre>
     * &mdash; {@code [app/cbl/CBTRN02C.cbl:L469-L479]}.
     *
     * <h3>Return semantics</h3>
     * <ul>
     *   <li>{@link Optional#of(Object) Optional.of(record)} &mdash;
     *       mirrors the COBOL {@code FILE STATUS = '00'} branch at
     *       {@code [app/cbl/CBTRN02C.cbl:L481-L482]}, where the
     *       record was found and the application proceeds to the
     *       update branch (paragraph
     *       {@code 2700-B-UPDATE-TCATBAL-REC} at
     *       {@code [app/cbl/CBTRN02C.cbl:L526]}). Equivalent to
     *       {@code WS-CREATE-TRANCAT-REC = 'N'} on entry to the
     *       paragraph.</li>
     *   <li>{@link Optional#empty()} &mdash; mirrors the COBOL
     *       {@code INVALID KEY} clause / {@code FILE STATUS = '23'}
     *       branch at {@code [app/cbl/CBTRN02C.cbl:L475-L478]},
     *       which sets {@code WS-CREATE-TRANCAT-REC = 'Y'} so the
     *       subsequent {@link #save(TranCatBalRecord)} call performs
     *       a WRITE (insert) rather than a REWRITE (update). The
     *       {@code IF TCATBALF-STATUS = '00' OR '23'} check at
     *       {@code [app/cbl/CBTRN02C.cbl:L481]} explicitly treats
     *       file-status {@code '23'} as a non-error condition.
     *       Adapters MUST NOT throw on a missing key.</li>
     *   <li>An unchecked {@link RuntimeException} &mdash; corresponds
     *       to the COBOL {@code ELSE MOVE 12 TO APPL-RESULT} branch
     *       at {@code [app/cbl/CBTRN02C.cbl:L483-L484]} for any
     *       non-{@code '00'}/non-{@code '23'} file-status code (true
     *       I/O failure). The COBOL paragraph
     *       {@code PERFORM 9999-ABEND-PROGRAM} on this branch; the
     *       Java equivalent surfaces an unchecked exception. This
     *       interface does not prescribe a specific exception class
     *       so that adapters may surface the original VSAM
     *       {@code FILE STATUS} code verbatim.</li>
     * </ul>
     *
     * <h3>Parameter constraints</h3>
     * <ul>
     *   <li>{@code accountId}: in the inclusive range
     *       {@code 0L..99_999_999_999L} (COBOL
     *       {@code TRANCAT-ACCT-ID PIC 9(11)}
     *       {@code [app/cpy/CVTRA01Y.cpy:L6]}). Implementations MUST
     *       reject negative values with an
     *       {@link IllegalArgumentException} &mdash; the field cannot
     *       be encoded into the 11-digit unsigned slot. Note: this is
     *       a {@code long} primitive (no boxing) to match the
     *       sibling-record contract on
     *       {@link TranCatBalRecord.TranCatKey#trancatAcctId()}.</li>
     *   <li>{@code tranTypeCd}: non-null, exactly 2 characters
     *       (COBOL {@code TRANCAT-TYPE-CD PIC X(02)}
     *       {@code [app/cpy/CVTRA01Y.cpy:L7]}). Implementations MAY
     *       right-pad with spaces if a shorter value is supplied;
     *       however callers are expected to provide the exact 2-byte
     *       code to preserve byte fidelity with the KSDS key. The
     *       {@link TranCatBalRecord.TranCatKey} canonical constructor
     *       enforces an at-most-2 character constraint
     *       {@code [TranCatBalRecord.java:L301-L305]}.</li>
     *   <li>{@code tranCatCd}: in the inclusive range
     *       {@code 0..9999} (COBOL {@code TRANCAT-CD PIC 9(04)}
     *       {@code [app/cpy/CVTRA01Y.cpy:L8]}). Implementations MUST
     *       reject values outside this range with an
     *       {@link IllegalArgumentException} &mdash; the field cannot
     *       be encoded into the 4-digit slot without corrupting the
     *       record layout. Note: this is an {@code int} primitive
     *       (no boxing) to match the sibling-record contract on
     *       {@link TranCatBalRecord.TranCatKey#trancatCd()}.</li>
     * </ul>
     *
     * <h3>Composite-key ordering</h3>
     * <p>The parameter order {@code (accountId, tranTypeCd, tranCatCd)}
     * matches the byte-positional layout of {@code FD-TRAN-CAT-KEY}
     * {@code [app/cbl/CBTRN02C.cbl:L93-L96]}: account-id at bytes
     * 0&ndash;10, type-code at bytes 11&ndash;12, category-code at
     * bytes 13&ndash;16. The same parameter order is used by
     * {@link #delete(long, String, int)} so call sites remain
     * syntactically parallel.
     *
     * @param accountId  the unsigned 11-digit account ID; range
     *                   {@code 0L..99_999_999_999L}
     * @param tranTypeCd the 2-character transaction type code; must
     *                   be non-null and at most 2 characters
     * @param tranCatCd  the unsigned 4-digit category code; range
     *                   {@code 0..9999}
     * @return {@link Optional#of(Object)} holding the matching
     *         {@link TranCatBalRecord} when found, or
     *         {@link Optional#empty()} when no record exists with the
     *         supplied composite key (mirroring the COBOL
     *         {@code INVALID KEY} / {@code FILE STATUS = '23'}
     *         outcome that triggers the create branch in
     *         {@code [app/cbl/CBTRN02C.cbl:L475-L478]})
     * @throws NullPointerException     if {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code accountId} is
     *                                  negative, {@code tranTypeCd}
     *                                  exceeds 2 characters, or
     *                                  {@code tranCatCd} is outside
     *                                  {@code 0..9999}
     */
    Optional<TranCatBalRecord> findByKey(long accountId, String tranTypeCd, int tranCatCd);

    /**
     * Streams every {@link TranCatBalRecord} in the underlying
     * {@code TCATBALF} dataset in ascending composite-key order
     * &mdash; the Java equivalent of the COBOL sequential
     * {@code OPEN INPUT TCATBAL-FILE} followed by repeated
     * {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} calls in the
     * baseline (KSDS sequential cursor over the index).
     *
     * <p>Corresponds to the sequential traversal of the
     * {@code TCATBALF} dataset:
     * <ul>
     *   <li><b>CBACT04C interest engine</b> &mdash; paragraph
     *       {@code 1000-TCATBALF-GET-NEXT} at
     *       {@code [app/cbl/CBACT04C.cbl:L325-L348]} issues
     *       {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD}
     *       {@code [app/cbl/CBACT04C.cbl:L326]} per iteration.
     *       The {@code SELECT TCATBAL-FILE ASSIGN TO TCATBALF
     *       ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL
     *       RECORD KEY IS FD-TRAN-CAT-KEY} clause at
     *       {@code [app/cbl/CBACT04C.cbl:L28-L32]} declares the
     *       indexed-sequential organisation; the
     *       {@code OPEN INPUT TCATBAL-FILE}
     *       {@code [app/cbl/CBACT04C.cbl:L236]} opens it for
     *       the read-only interest pass; the
     *       {@code CLOSE TCATBAL-FILE}
     *       {@code [app/cbl/CBACT04C.cbl:L524]} (paragraph
     *       {@code 9000-TCATBALF-CLOSE}) closes it.</li>
     *   <li><b>PRTCATBL print job</b> &mdash; sequential print of
     *       the entire {@code TCATBALF} dataset for operational
     *       reporting. The {@code REPRO} step at
     *       {@code [app/jcl/PRTCATBL.jcl:STEP05R]} unloads the
     *       VSAM cluster to a fixed-width
     *       {@code LRECL=50 RECFM=FB} flat file
     *       {@code [app/jcl/PRTCATBL.jcl]} in the same
     *       composite-key sequential order observed by CBACT04C.</li>
     * </ul>
     *
     * <h3>Ordering invariant</h3>
     * <p>Ascending composite-key order
     * ({@code TRANCAT-ACCT-ID} ascending, then
     * {@code TRANCAT-TYPE-CD} ascending, then {@code TRANCAT-CD}
     * ascending) is part of the observable contract. Per AAP
     * &sect;0.1.3, reordering is FORBIDDEN: virtual threads "are NOT
     * a license to reorder records, change sort orders, or break
     * sequencing." Any reordering would change the byte-for-byte
     * output of CBACT04C's per-category interest postings and the
     * PRTCATBL paginated print, breaking the golden-record harness
     * (AAP &sect;0.6.11).
     *
     * <h3>Resource lifecycle</h3>
     * <p>The returned {@link Stream} backs an underlying file channel
     * or DB cursor. Callers MUST close the stream &mdash; typically
     * via try-with-resources:
     * <pre>{@code
     *   try (Stream<TranCatBalRecord> tcatBal = repository.streamSequential()) {
     *       tcatBal.forEach(this::process);
     *   }
     * }</pre>
     * Failure to close may leak file handles in long-running
     * processes such as the monthly interest computation (CBACT04C)
     * or the PRTCATBL print pass.
     *
     * <h3>Empty-dataset semantics</h3>
     * <p>If the dataset is empty, this method returns an empty
     * (closeable) {@link Stream} rather than {@code null}. This
     * matches the COBOL behavior where the
     * {@code PERFORM UNTIL END-OF-FILE} loop in CBACT04C simply
     * terminates on the first {@code READ} returning end-of-file
     * (file-status {@code '10'} mapped to {@code APPL-EOF}
     * {@code [app/cbl/CBACT04C.cbl:L330-L334,L339-L340]}), without
     * processing any records.
     *
     * @return an {@link AutoCloseable} {@link Stream} of every
     *         {@link TranCatBalRecord} in ascending composite-key
     *         {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)}
     *         order; never {@code null} (empty stream when the
     *         dataset has no records)
     */
    Stream<TranCatBalRecord> streamSequential();

    /**
     * Persists the supplied {@link TranCatBalRecord} into the
     * {@code TCATBALF} dataset using <b>upsert</b> semantics &mdash;
     * insert when no record with the same composite key exists,
     * replace when it does. This single Java method collapses both
     * branches of the COBOL transaction-posting flow:
     * <ul>
     *   <li><b>WRITE (create) branch</b> &mdash; corresponds to
     *       {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM
     *       TRAN-CAT-BAL-RECORD} in paragraph
     *       {@code 2700-A-CREATE-TCATBAL-REC} at
     *       {@code [app/cbl/CBTRN02C.cbl:L510]}. Triggered when the
     *       prior {@code READ} returned {@code INVALID KEY}
     *       ({@code WS-CREATE-TRANCAT-REC = 'Y'} branch at
     *       {@code [app/cbl/CBTRN02C.cbl:L495-L496]}). The COBOL
     *       paragraph first calls {@code INITIALIZE
     *       TRAN-CAT-BAL-RECORD} at
     *       {@code [app/cbl/CBTRN02C.cbl:L504]} to zero the buffer,
     *       then populates the key fields and the balance via
     *       {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL}
     *       {@code [app/cbl/CBTRN02C.cbl:L508]} before issuing the
     *       {@code WRITE}. The application layer mirrors this by
     *       constructing a fresh {@link TranCatBalRecord} with an
     *       all-spaces FILLER (see
     *       {@link TranCatBalRecord#emptyFiller()}) and the computed
     *       starting balance, then calling this method.</li>
     *   <li><b>REWRITE (update) branch</b> &mdash; corresponds to
     *       {@code REWRITE FD-TRAN-CAT-BAL-RECORD FROM
     *       TRAN-CAT-BAL-RECORD} in paragraph
     *       {@code 2700-B-UPDATE-TCATBAL-REC} at
     *       {@code [app/cbl/CBTRN02C.cbl:L528]}. Triggered when the
     *       prior {@code READ} succeeded
     *       ({@code WS-CREATE-TRANCAT-REC = 'N'} branch at
     *       {@code [app/cbl/CBTRN02C.cbl:L497-L498]}). The COBOL
     *       paragraph first applies
     *       {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL}
     *       {@code [app/cbl/CBTRN02C.cbl:L527]} to the in-memory
     *       record, then issues the {@code REWRITE}. The application
     *       layer mirrors this by calling
     *       {@link TranCatBalRecord#withBalanceAdjustment(java.math.BigDecimal)}
     *       on the previously-read record, then calling this
     *       method.</li>
     * </ul>
     *
     * <h3>Why upsert collapses two COBOL paragraphs</h3>
     * <p>Per the binding agent prompt &sect;4.3 and AAP &sect;0.1.2
     * (translation rule), splitting WRITE and REWRITE into separate
     * Java methods would force every Java caller to first call
     * {@link #findByKey(long, String, int)} and then dispatch on the
     * {@link Optional} result &mdash; effectively duplicating the
     * COBOL {@code WS-CREATE-TRANCAT-REC} flag at every call site.
     * Collapsing into a single {@code save} preserves the observable
     * COBOL behavior (after the call, the dataset contains the
     * record passed in regardless of pre-existing state) while
     * removing the application-layer branch. The adapter is the only
     * code that needs to dispatch on existing-key vs new-key, which
     * mirrors the underlying VSAM operation:
     * {@code WRITE} on a new key (file-status {@code '00'}), or
     * {@code REWRITE} after a prior {@code READ} for an existing key
     * (file-status {@code '00'}).
     *
     * <h3>FILLER preservation (AAP &sect;0.6.5)</h3>
     * <p>The trailing 22-byte {@code FILLER PIC X(22)}
     * {@code [app/cpy/CVTRA01Y.cpy:L10]} MUST be persisted verbatim
     * exactly as carried on the supplied {@link TranCatBalRecord};
     * the implementation MUST NOT strip, normalise, or zero-fill the
     * FILLER bytes. This invariant is what makes the round-trip
     * identity
     * {@code Arrays.equals(buf, TranCatBalRecord.parse(buf).encode()) == true}
     * hold &mdash; the formal byte-for-byte fidelity contract
     * asserted by the golden-record harness on every PR.
     *
     * <h3>Decimal encoding (TRAN-CAT-BAL)</h3>
     * <p>The {@code TRAN-CAT-BAL PIC S9(09)V99} field MUST be
     * encoded as signed packed-decimal-equivalent {@code USAGE
     * DISPLAY} zoned-decimal byte-for-byte identical to the COBOL
     * baseline. The {@link TranCatBalRecord#encode()} method
     * delegates this encoding to
     * {@code com.blitzy.carddemo.domain.util.Decimals#encodeZonedDecimal(java.math.BigDecimal, int, int)},
     * which centralises {@code MathContext.DECIMAL128} and
     * {@code RoundingMode.HALF_EVEN} per AAP &sect;0.6.1.
     * Implementations of this {@code save} method MUST round-trip
     * the encoded bytes verbatim; they MUST NOT re-decode and
     * re-encode (which would risk losing trailing zeros or
     * normalising the sign nybble).
     *
     * <h3>Validation</h3>
     * <p>Implementations MUST verify that the
     * {@link TranCatBalRecord} is non-null. Field-level validation
     * (composite-key components in range, balance scale exactly 2,
     * FILLER length exactly 22) is already enforced by the compact
     * canonical constructor of {@link TranCatBalRecord} and its
     * nested {@link TranCatBalRecord.TranCatKey}, so a successfully
     * constructed record is always safe to persist
     * {@code [TranCatBalRecord.java:L295-L357]}.
     *
     * <h3>Error semantics</h3>
     * <p>Any non-{@code '00'} VSAM file-status code on the underlying
     * {@code WRITE} or {@code REWRITE} corresponds to the COBOL
     * {@code MOVE 12 TO APPL-RESULT} branch at
     * {@code [app/cbl/CBTRN02C.cbl:L514-L515]} (create branch) or
     * {@code [app/cbl/CBTRN02C.cbl:L532-L533]} (update branch),
     * which triggers {@code PERFORM 9999-ABEND-PROGRAM}
     * {@code [app/cbl/CBTRN02C.cbl:L523,L539]}. The Java equivalent
     * surfaces an unchecked {@link RuntimeException}; this interface
     * does not prescribe a specific exception class so that adapters
     * may surface the original VSAM {@code FILE STATUS} code
     * verbatim.
     *
     * @param record the {@link TranCatBalRecord} to insert or
     *               replace; must be non-null
     * @throws NullPointerException if {@code record} is {@code null}
     */
    void save(TranCatBalRecord record);

    /**
     * Deletes the {@link TranCatBalRecord} keyed by the composite
     * {@code (accountId, tranTypeCd, tranCatCd)} triple. This
     * operation exists exclusively to support the IDCAMS
     * {@code DELETE CLUSTER} / re-{@code DEFINE} cycle declared in
     * {@code TCATBALF.jcl}
     * {@code [app/jcl/TCATBALF.jcl:STEP05-STEP10]}; <b>no
     * translated COBOL application program issues a runtime
     * {@code DELETE} against {@code TCATBALF}</b>. The dataset is
     * append-only / update-in-place at the program level (records
     * are added by {@code 2700-A-CREATE-TCATBAL-REC} at
     * {@code [app/cbl/CBTRN02C.cbl:L503-L524]} and updated by
     * {@code 2700-B-UPDATE-TCATBAL-REC} at
     * {@code [app/cbl/CBTRN02C.cbl:L526-L540]}; nothing ever
     * deletes a row at runtime).
     *
     * <p>Provided here so the {@code DefineTcatBalApp} composition
     * root (translation of the IDCAMS {@code DELETE CLUSTER}
     * dataset-level operation) can express the reset-and-reload
     * idiom symmetrically with the corresponding sibling apps for
     * {@code TRANSACT}, {@code TRANCATG}, etc.
     *
     * <h3>Not-found behaviour</h3>
     * <p>Implementations MUST throw a
     * {@link java.util.NoSuchElementException} when no record with
     * the supplied composite key exists &mdash; this mirrors the
     * COBOL {@code INVALID KEY} clause on
     * {@code DELETE TCATBAL-FILE} (file-status {@code '23'}, record
     * not found). Callers that want idempotent delete semantics
     * should call {@link #findByKey(long, String, int)} first and
     * skip the delete when the optional is empty. This convention
     * matches the sibling
     * {@link TransactionCategoryRepository#delete(String, int)} and
     * keeps delete behavior consistent across the port package.
     *
     * <h3>Parameter constraints</h3>
     * <p>Same as {@link #findByKey(long, String, int)}:
     * {@code accountId} must be in the inclusive range
     * {@code 0L..99_999_999_999L}; {@code tranTypeCd} must be
     * non-null and at most 2 characters long; {@code tranCatCd}
     * must be in the inclusive range {@code 0..9999}.
     *
     * <h3>Composite-key ordering</h3>
     * <p>The parameter order {@code (accountId, tranTypeCd, tranCatCd)}
     * matches the byte-positional layout of {@code FD-TRAN-CAT-KEY}
     * {@code [app/cbl/CBTRN02C.cbl:L93-L96]}: account-id at bytes
     * 0&ndash;10, type-code at bytes 11&ndash;12, category-code at
     * bytes 13&ndash;16. The same parameter order is used by
     * {@link #findByKey(long, String, int)} so call sites remain
     * syntactically parallel.
     *
     * @param accountId  the unsigned 11-digit account ID; range
     *                   {@code 0L..99_999_999_999L}
     * @param tranTypeCd the 2-character transaction type code; must
     *                   be non-null and at most 2 characters
     * @param tranCatCd  the unsigned 4-digit category code; range
     *                   {@code 0..9999}
     * @throws NullPointerException           if {@code tranTypeCd} is
     *                                        {@code null}
     * @throws IllegalArgumentException       if {@code accountId} is
     *                                        negative, {@code tranTypeCd}
     *                                        exceeds 2 characters, or
     *                                        {@code tranCatCd} is
     *                                        outside {@code 0..9999}
     * @throws java.util.NoSuchElementException if no record with the
     *                                          supplied composite key
     *                                          exists in the dataset
     */
    void delete(long accountId, String tranTypeCd, int tranCatCd);

    /**
     * Releases any adapter-level resources held by this repository
     * &mdash; file channels, DB connections, cursor handles,
     * pre-allocated record buffers, etc.
     *
     * <p>Corresponds to the COBOL {@code CLOSE TCATBAL-FILE} verb in
     * paragraph {@code 9000-TCATBALF-CLOSE}
     * {@code [app/cbl/CBACT04C.cbl:L522-L540]} and the analogous
     * {@code CLOSE TCATBAL-FILE} at the tail of CBTRN02C's
     * processing flow.
     *
     * <p>Implementations MUST be idempotent so that callers can
     * defensively close the same repository multiple times without
     * observable side-effects &mdash; this is what makes the
     * {@code closeQuietly(tcatBalRepository, "...")} pattern used
     * by {@code CbAct04C}
     * {@code [java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CbAct04C.java:L389]}
     * and {@code CbTrn02C}
     * {@code [java/carddemo-application/src/main/java/com/blitzy/carddemo/application/transaction/CbTrn02C.java:L602]}
     * safe even when an earlier paragraph already closed the
     * repository.
     *
     * <p>This method narrows the {@link AutoCloseable#close()}
     * declaration so that implementations are NOT required to
     * declare {@code throws Exception} &mdash; matching the
     * non-checked, idempotent close convention shared by all
     * sibling port interfaces in this package
     * ({@link AccountRepository}, {@link CardRepository},
     * {@link CardXrefRepository}, {@link CustomerRepository},
     * {@link DailyTransactionRepository}, {@link TransactionRepository},
     * {@link DiscountGroupRepository}). Adapters that need to
     * surface a failure during close SHOULD wrap any underlying
     * cause in an unchecked {@link RuntimeException} subtype rather
     * than raising a checked exception.
     */
    @Override
    void close();
}
