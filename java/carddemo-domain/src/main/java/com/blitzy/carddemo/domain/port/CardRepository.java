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

import com.blitzy.carddemo.domain.record.CardRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port for the {@code CARDDATA} VSAM KSDS dataset (150-byte
 * fixed-width records, 16-byte {@code CARD-NUM} primary key, copybook
 * {@code CVACT02Y}). Concrete adapters live in
 * {@code carddemo-adapter-file} (fixed-width file I/O via
 * {@code java.nio.file}) and optionally in {@code carddemo-adapter-db}
 * (JDBC). The composition root in {@code carddemo-app} wires the
 * appropriate implementation at startup per AAP &sect;0.3.6.
 *
 * <h2>COBOL consumers</h2>
 * This port consolidates the {@code CARDDATA} access modes exercised by
 * the following COBOL programs:
 * <ul>
 *   <li><b>CBACT02C</b> ({@code app/cbl/CBACT02C.cbl:L1-L80}) &mdash;
 *       batch sequential dump driven by
 *       {@code READ CARDFILE-FILE INTO CARD-RECORD} over an
 *       {@code ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL
 *       RECORD KEY IS FD-CARD-NUM} SELECT clause. Maps to
 *       {@link #streamSequential()}.</li>
 *   <li><b>COCRDLIC</b> ({@code app/cbl/COCRDLIC.cbl:L1129-L1154})
 *       &mdash; paged online browse using
 *       {@code EXEC CICS STARTBR DATASET(LIT-CARD-FILE)
 *       RIDFLD(WS-CARD-RID-CARDNUM) GTEQ} followed by a
 *       {@code READNEXT} loop. Maps to {@link #streamFrom(String)}.</li>
 *   <li><b>COCRDSLC</b> ({@code app/cbl/COCRDSLC.cbl:L742-L783}) &mdash;
 *       random online detail view via
 *       {@code EXEC CICS READ FILE(LIT-CARDFILENAME)
 *       RIDFLD(WS-CARD-RID-CARDNUM) INTO(CARD-RECORD)}. Maps to
 *       {@link #findByCardNumber(String)}.</li>
 *   <li><b>COCRDUPC</b> &mdash; random online read at
 *       {@code app/cbl/COCRDUPC.cbl:L1382-L1390} followed by
 *       {@code EXEC CICS READ ... UPDATE} at
 *       {@code app/cbl/COCRDUPC.cbl:L1427-L1436} and a subsequent
 *       {@code REWRITE} in the {@code 9200-WRITE-PROCESSING} paragraph.
 *       Maps to {@link #findByCardNumber(String)} +
 *       {@link #save(CardRecord)}.</li>
 *   <li><b>CARDFILE.jcl</b> &mdash; IDCAMS {@code DELETE} /
 *       {@code DEFINE} / {@code REPRO} cycle. Maps to
 *       {@link #delete(String)} (pre-define cleanup) and
 *       {@link #save(CardRecord)} (bulk load).</li>
 * </ul>
 *
 * <h2>Key semantics ({@code CARD-NUM})</h2>
 * Per copybook {@code app/cpy/CVACT02Y.cpy}, {@code CARD-NUM} is declared
 * {@code PIC X(16)} &mdash; alphanumeric, NOT numeric. Leading zeros and
 * any non-digit characters MUST be preserved verbatim. The
 * {@link CardRecord} canonical constructor enforces the 16-character
 * upper bound on the key value; this interface accepts {@link String}
 * keys without additional length validation here so that adapters may
 * report a richer "not found" diagnostic for malformed keys.
 *
 * <p>Note that COCRDSLC and COCRDUPC both COMMENT OUT the alternative
 * account-id access path ({@code MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID}
 * at {@code app/cbl/COCRDSLC.cbl:L739} and
 * {@code app/cbl/COCRDUPC.cbl:L1379}) and use the alphanumeric
 * {@code CC-CARD-NUM} path exclusively for random reads &mdash; this
 * port mirrors that choice by exposing only string-keyed random reads.
 *
 * <h2>Ordering</h2>
 * All streaming methods return records in ascending {@code CARD-NUM}
 * lexicographic byte order &mdash; matching the COBOL VSAM KSDS
 * primary-key traversal order. Implementations MUST preserve this
 * order; any reordering would change the observable output of CBACT02C
 * and the COCRDLIC paging logic, which is forbidden by AAP &sect;0.7.1
 * ("All file naming conventions, sort orders, and batch sequencing").
 *
 * <h2>Resource lifecycle</h2>
 * The {@link Stream} returned by {@link #streamSequential()} and
 * {@link #streamFrom(String)} extends {@link AutoCloseable} (via
 * {@link java.util.stream.BaseStream}). Callers MUST close the returned
 * stream &mdash; typically via try-with-resources &mdash; so that the
 * adapter can release the underlying file channel, DB cursor, or
 * pre-allocated record buffer. The repository instance itself is also
 * {@link AutoCloseable} for the same reason; implementations MAY be
 * no-op for in-memory adapters and MUST be idempotent.
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
 *   <li>Never log the {@link CardRecord#cardNum()} component of a
 *       {@link CardRecord} returned by or accepted into this port. Use
 *       {@link CardRecord#maskedPan()} where logging is required.</li>
 *   <li>Never include the PAN in exception messages that may cross the
 *       application / operator boundary.</li>
 * </ul>
 *
 * <h2>No framework dependencies</h2>
 * This interface deliberately avoids Spring, Hibernate, JPA, and Lombok
 * imports per AAP &sect;0.1.1 (the user mandate that no framework be
 * introduced that the existing COBOL program does not require). Concrete
 * adapters provide their own dependency-injection wiring via the
 * composition root.
 *
 * @see CardRecord
 * @see com.blitzy.carddemo.domain.record.CardRecord#maskedPan()
 * @since 1.0.0
 */
public interface CardRepository extends AutoCloseable {

    /**
     * Random read by primary key ({@code CARD-NUM}).
     *
     * <p>Corresponds to
     * {@code EXEC CICS READ FILE(LIT-CARDFILENAME)
     * RIDFLD(WS-CARD-RID-CARDNUM) INTO(CARD-RECORD)} in COCRDUPC
     * ({@code app/cbl/COCRDUPC.cbl:L1382-L1390}) and the equivalent
     * read in COCRDSLC {@code 9100-GETCARD-BYACCTCARD} at
     * {@code app/cbl/COCRDSLC.cbl:L742}.
     *
     * <h4>Return semantics</h4>
     * <ul>
     *   <li>{@code Optional.of(record)} &mdash; corresponds to the
     *       {@code WHEN DFHRESP(NORMAL)} branch of the COBOL
     *       {@code EVALUATE WS-RESP-CD}
     *       ({@code app/cbl/COCRDSLC.cbl:L752-L755},
     *       {@code app/cbl/COCRDUPC.cbl:L1392-L1395}).</li>
     *   <li>{@link Optional#empty()} &mdash; corresponds to
     *       {@code WHEN DFHRESP(NOTFND)} (the
     *       {@code SET DID-NOT-FIND-ACCTCARD-COMBO TO TRUE} branch in
     *       COCRDSLC / COCRDUPC).</li>
     *   <li>A {@link RuntimeException} &mdash; corresponds to the
     *       {@code WHEN OTHER} branch of the COBOL EVALUATE. This
     *       interface does not prescribe a specific exception class so
     *       that adapters may surface the original file-status or
     *       DFHRESP / DFHRESP2 codes verbatim.</li>
     * </ul>
     *
     * <h4>Parameter contract</h4>
     * {@code cardNumber} carries the {@code CARD-NUM} key (PIC X(16) per
     * {@code app/cpy/CVACT02Y.cpy}) &mdash; alphanumeric, preserving
     * leading zeros. The {@link CardRecord} canonical constructor
     * enforces the 16-character upper bound; this method accepts the
     * key as-is so adapters may report a richer diagnostic for malformed
     * keys. Implementations MUST NOT log this value in plaintext per AAP
     * &sect;0.7.2; mask all but the last 4 digits.
     *
     * @param cardNumber the {@code CARD-NUM} primary key; never
     *                   {@code null}.
     * @return an {@link Optional} carrying the matching
     *         {@link CardRecord}, or {@link Optional#empty()} if no
     *         record exists with that key.
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     */
    Optional<CardRecord> findByCardNumber(String cardNumber);

    /**
     * Stream every card in ascending {@code CARD-NUM} order.
     *
     * <p>Corresponds to the CBACT02C main loop
     * ({@code PERFORM UNTIL END-OF-FILE = 'Y' ... READ CARDFILE-FILE
     * INTO CARD-RECORD ...}, {@code app/cbl/CBACT02C.cbl:L1-L80}). The
     * COBOL {@code SELECT} declares
     * {@code ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL
     * RECORD KEY IS FD-CARD-NUM} (lines L29-L33), which traverses the
     * KSDS in primary-key order &mdash; the Java translation MUST emit
     * the same order.
     *
     * <h4>Resource lifecycle</h4>
     * The returned {@link Stream} backs an underlying file channel or
     * DB cursor. Callers MUST close the stream &mdash; typically via
     * try-with-resources:
     * <pre>{@code
     *   try (Stream<CardRecord> cards = repository.streamSequential()) {
     *       cards.forEach(this::process);
     *   }
     * }</pre>
     * Failure to close may leak file handles in long-running processes.
     *
     * @return a closeable {@link Stream} of every {@link CardRecord} in
     *         the dataset, in ascending {@code CARD-NUM} key order.
     */
    Stream<CardRecord> streamSequential();

    /**
     * Stream every card with
     * {@code CARD-NUM >= startCardNumber} in ascending key order.
     *
     * <p>Corresponds to the COCRDLIC {@code 9000-READ-FORWARD} paragraph
     * ({@code app/cbl/COCRDLIC.cbl:L1129-L1154}):
     * {@code EXEC CICS STARTBR DATASET(LIT-CARD-FILE)
     * RIDFLD(WS-CARD-RID-CARDNUM) KEYLENGTH(LENGTH OF
     * WS-CARD-RID-CARDNUM) GTEQ} followed by a {@code READNEXT} loop.
     * The {@code GTEQ} clause positions the browse cursor at the first
     * record AT OR AFTER the supplied key &mdash; this method MUST
     * honor the same "at-or-after" semantics so that the COBOL paging
     * behavior is preserved bit-for-bit.
     *
     * <h4>Null / blank start-key</h4>
     * If {@code startCardNumber} is {@code null} or {@link String#isBlank()
     * blank}, this method behaves identically to
     * {@link #streamSequential()} &mdash; that is, it starts at the
     * lowest key in the dataset. This matches the COCRDLIC convention
     * of initializing the browse with
     * {@code MOVE LOW-VALUES TO WS-ALL-ROWS} prior to STARTBR
     * ({@code app/cbl/COCRDLIC.cbl:L1124}), which positions the cursor
     * at the beginning of the file when no explicit starting key has
     * been supplied by the user.
     *
     * <h4>Resource lifecycle</h4>
     * Same as {@link #streamSequential()} &mdash; callers MUST close
     * the returned stream.
     *
     * @param startCardNumber the inclusive lower-bound {@code CARD-NUM}
     *                        key, or {@code null} / blank to start from
     *                        the beginning of the dataset.
     * @return a closeable {@link Stream} of cards with key &gt;=
     *         {@code startCardNumber} in ascending order.
     */
    Stream<CardRecord> streamFrom(String startCardNumber);

    /**
     * Upsert a card record &mdash; inserts when no record exists for
     * the supplied {@code CARD-NUM} primary key, REWRITEs otherwise.
     *
     * <p>Corresponds to {@code EXEC CICS REWRITE FILE(LIT-CARDFILENAME)
     * FROM(CARD-RECORD)} in COCRDUPC {@code 9200-WRITE-PROCESSING}
     * &mdash; the {@code REWRITE} statement follows the
     * {@code EXEC CICS READ ... UPDATE} at
     * {@code app/cbl/COCRDUPC.cbl:L1427-L1436} that locks the record.
     * This method also covers the bulk IDCAMS REPRO load of CARDDATA
     * via {@code app/jcl/CARDFILE.jcl}.
     *
     * <h4>Byte fidelity (AAP &sect;0.6.5)</h4>
     * Implementations MUST persist the entire 150-byte fixed-width
     * record image including the 59-byte {@code FILLER}
     * ({@link CardRecord#filler()}). The FILLER is structural padding
     * and not business data, but byte-for-byte round-trip equality
     * {@code parse(b).encode() == b} is the formal contract with
     * external file consumers per AAP &sect;0.6.5. Truncating,
     * normalising, or synthesising the FILLER bytes is FORBIDDEN.
     *
     * <h4>Logging</h4>
     * Implementations MUST NOT log {@link CardRecord#cardNum()} or any
     * other field that would expose the full PAN. See the class-level
     * PCI / security note.
     *
     * @param card the card record to upsert; never {@code null}.
     * @throws NullPointerException if {@code card} is {@code null}.
     */
    void save(CardRecord card);

    /**
     * Delete the card record with the supplied {@code CARD-NUM} primary
     * key. Throws if no such record exists.
     *
     * <p>No active COBOL program issues {@code EXEC CICS DELETE} against
     * CARDDATA at runtime; this operation is provided for adapter
     * completeness and to support the IDCAMS
     * {@code DELETE} / {@code DEFINE} cycle exercised by
     * {@code app/jcl/CARDFILE.jcl} during dataset (re-)load.
     *
     * <h4>Not-found semantics</h4>
     * If no card exists with the supplied key, implementations MUST
     * throw a {@link RuntimeException} (typically a typed adapter-level
     * exception). This mirrors the {@code DFHRESP(NOTFND)} fall-through
     * on a {@code DELETE} against a missing key &mdash; there is no
     * compensating no-op in the COBOL idiom.
     *
     * <h4>Logging</h4>
     * Implementations MUST NOT log the full 16-character key in
     * plaintext. Mask all but the last 4 digits per AAP &sect;0.7.2.
     *
     * @param cardNumber the {@code CARD-NUM} primary key; never
     *                   {@code null}.
     * @throws NullPointerException if {@code cardNumber} is {@code null}.
     * @throws RuntimeException     if no record exists with that key.
     */
    void delete(String cardNumber);

    /**
     * Release any underlying resources held by this repository &mdash;
     * file channels, DB cursors, pre-allocated buffers, etc.
     * Implementations MAY be no-op for in-memory adapters and MUST be
     * idempotent so that callers can defensively close the same
     * repository multiple times without observable side-effects.
     *
     * <p>Declared without {@code throws Exception} to relieve callers of
     * checked-exception boilerplate; concrete adapter exceptions MUST be
     * wrapped in {@link RuntimeException} subtypes.
     */
    @Override
    void close();
}
