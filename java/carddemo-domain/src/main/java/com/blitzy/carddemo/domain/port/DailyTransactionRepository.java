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

import com.blitzy.carddemo.domain.record.DalyTranRecord;

import java.util.stream.Stream;

/**
 * Domain port for the {@code DALYTRAN} (daily-transaction) sequential
 * input dataset and its companion {@code DALYREJS} (daily-rejects)
 * sequential output dataset. {@code DALYTRAN} carries 350-byte
 * fixed-width records translated from copybook
 * {@code app/cpy/CVTRA06Y.cpy} as {@link DalyTranRecord}; {@code DALYREJS}
 * carries those same 350 bytes followed by an 80-byte validation
 * trailer (reason code + description). Both files are sequential-only
 * in the COBOL baseline &mdash; there is NO random-key access, NO
 * {@code STARTBR} cursor, NO {@code REWRITE}, and NO {@code DELETE}
 * issued against either dataset by any program in this codebase.
 *
 * <p>This port is intentionally narrow: only the two operations that
 * the COBOL posting engine actually performs at runtime are surfaced.
 * Concrete adapters live in {@code carddemo-adapter-file} (fixed-width
 * file I/O via {@code java.nio.file}) and optionally in
 * {@code carddemo-adapter-db} (JDBC). The composition root in
 * {@code carddemo-app} wires the appropriate implementation at startup
 * per AAP &sect;0.3.6.
 *
 * <h2>COBOL consumers</h2>
 * <ul>
 *   <li><b>CBTRN01C</b> &mdash; daily transaction loader / sequential
 *       dump. Declares
 *       {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN ORGANIZATION IS
 *       SEQUENTIAL ACCESS MODE IS SEQUENTIAL FILE STATUS IS
 *       DALYTRAN-STATUS} at
 *       {@code app/cbl/CBTRN01C.cbl:L29-L32}. The
 *       {@code 0000-DALYTRAN-OPEN} paragraph opens the file with
 *       {@code OPEN INPUT DALYTRAN-FILE}; the
 *       {@code 1000-DALYTRAN-GET-NEXT} paragraph at
 *       {@code app/cbl/CBTRN01C.cbl:L202-L225} drives the main loop
 *       via {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} with file
 *       status {@code '00'} &rarr; continue, {@code '10'} &rarr; EOF.
 *       Maps to {@link #streamSequential()}.</li>
 *   <li><b>CBTRN02C</b> &mdash; full transaction-posting batch engine
 *       and the sole writer of the {@code DALYREJS} reject file.
 *       Declares
 *       {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN ORGANIZATION IS
 *       SEQUENTIAL ACCESS MODE IS SEQUENTIAL FILE STATUS IS
 *       DALYTRAN-STATUS} at {@code app/cbl/CBTRN02C.cbl:L29-L32} and
 *       {@code SELECT DALYREJS-FILE ASSIGN TO DALYREJS ORGANIZATION IS
 *       SEQUENTIAL ACCESS MODE IS SEQUENTIAL FILE STATUS IS
 *       DALYREJS-STATUS} at {@code app/cbl/CBTRN02C.cbl:L46-L49}. The
 *       {@code 1000-DALYTRAN-GET-NEXT} paragraph at
 *       {@code app/cbl/CBTRN02C.cbl:L345-L368} drives the sequential
 *       read; the {@code 2500-WRITE-REJECT-REC} paragraph at
 *       {@code app/cbl/CBTRN02C.cbl:L446-L465} writes the 430-byte
 *       reject image via {@code WRITE FD-REJS-RECORD FROM
 *       REJECT-RECORD}. Maps to {@link #streamSequential()} +
 *       {@link #appendReject(DalyTranRecord, int, String)}.</li>
 * </ul>
 *
 * <h2>DALYREJS record layout</h2>
 * The {@code DALYREJS} file is composed of two contiguous regions per
 * record. The first region is a byte-for-byte copy of the
 * {@code DALYTRAN-RECORD} that failed validation; the second region is
 * the validation trailer that records why it failed:
 * <pre>{@code
 * FD  DALYREJS-FILE.                                       (app/cbl/CBTRN02C.cbl:L81-L84)
 * 01  FD-REJS-RECORD.
 *     05 FD-REJECT-RECORD                  PIC X(350).     (the original DALYTRAN-RECORD)
 *     05 FD-VALIDATION-TRAILER             PIC X(80).      (reason + description)
 *
 * 01 REJECT-RECORD.                                        (app/cbl/CBTRN02C.cbl:L176-L178)
 *    05 REJECT-TRAN-DATA          PIC X(350).
 *    05 VALIDATION-TRAILER        PIC X(80).
 *
 * 01 WS-VALIDATION-TRAILER.                                (app/cbl/CBTRN02C.cbl:L180-L182)
 *    05 WS-VALIDATION-FAIL-REASON      PIC 9(04).
 *    05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
 * }</pre>
 * Total reject-record length is 430 bytes (350 + 4 + 76). Adapters
 * MUST encode each {@link #appendReject(DalyTranRecord, int, String)}
 * call as exactly this 430-byte image, with the reason code
 * zero-left-padded to 4 ASCII digits and the description right-space-
 * padded (or truncated) to 76 ASCII characters &mdash; matching the
 * COBOL {@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER} group
 * move at {@code app/cbl/CBTRN02C.cbl:L448} which inherits the COBOL
 * PIC-driven padding semantics.
 *
 * <h2>Observed validation reason codes</h2>
 * The COBOL source assigns the following reason codes in
 * {@code app/cbl/CBTRN02C.cbl} (paragraphs
 * {@code 1500-A-LOOKUP-XREF} and {@code 1500-B-LOOKUP-ACCT}):
 * <ul>
 *   <li>{@code 100} &mdash; {@code 'INVALID CARD NUMBER FOUND'}
 *       (XREF lookup miss; {@code app/cbl/CBTRN02C.cbl:L385-L387}).</li>
 *   <li>{@code 101} &mdash; {@code 'ACCOUNT RECORD NOT FOUND'}
 *       (account lookup miss; {@code app/cbl/CBTRN02C.cbl:L397-L399}).</li>
 *   <li>{@code 102} &mdash; {@code 'OVERLIMIT TRANSACTION'} (the
 *       computed running balance exceeds {@code ACCT-CREDIT-LIMIT};
 *       {@code app/cbl/CBTRN02C.cbl:L410-L412}).</li>
 *   <li>{@code 103} &mdash; {@code 'TRANSACTION RECEIVED AFTER ACCT
 *       EXPIRATION'} (the transaction date is after the account
 *       expiration date; {@code app/cbl/CBTRN02C.cbl:L417-L419}).</li>
 * </ul>
 * The Java translation MUST preserve these exact codes and exact
 * descriptions byte-for-byte per AAP &sect;0.7.1
 * ("All business rules, validation logic, calculation formulas, and
 * reporting outputs ... shall be preserved exactly as-is"). New
 * application-layer code MUST NOT invent additional reason codes or
 * alter the description strings.
 *
 * <h2>DALYTRAN access mode &mdash; sequential ONLY</h2>
 * The COBOL declarations cited above use {@code ORGANIZATION IS
 * SEQUENTIAL ACCESS MODE IS SEQUENTIAL}, NOT {@code INDEXED}.
 * Consequently:
 * <ul>
 *   <li>There is NO {@code RECORD KEY} clause on {@code DALYTRAN}
 *       and NO {@code FD-DALYTRAN} primary key field declared in
 *       either consumer. The natural key of a daily-transaction
 *       record is {@link DalyTranRecord#dalytranId()}
 *       ({@code DALYTRAN-ID PIC X(16)}), but that field is NOT used
 *       to index the file &mdash; it travels through to the
 *       {@code TRAN-ID} of the posted {@code TRANSACT} record via
 *       {@code MOVE DALYTRAN-ID TO TRAN-ID} at
 *       {@code app/cbl/CBTRN02C.cbl:L425}.</li>
 *   <li>There is NO random read, NO {@code STARTBR / READNEXT / READPREV}
 *       cursor, and NO {@code REWRITE} against {@code DALYTRAN}
 *       anywhere in this codebase. The only access is the forward
 *       sequential scan exposed here as {@link #streamSequential()}.</li>
 *   <li>There is NO {@code WRITE} against {@code DALYTRAN} in any
 *       translated COBOL program either &mdash; the {@code DALYTRAN}
 *       file is produced by an upstream process that is OUT OF SCOPE
 *       for this refactor (per the agent prompt for this file:
 *       "the DALYTRAN file is produced by an upstream process not
 *       part of this refactor"). Therefore NO {@code save(...)} or
 *       {@code append(...)} method covering DALYTRAN input writes is
 *       provided. The matching JCL job {@code app/jcl/DALYREJS.jcl}
 *       defines and seeds the DALYREJS sink, which is read by the
 *       Java reject append path.</li>
 * </ul>
 *
 * <h2>DALYREJS access mode &mdash; sequential append ONLY</h2>
 * The COBOL {@code DALYREJS-FILE} is declared with
 * {@code ORGANIZATION IS SEQUENTIAL ACCESS MODE IS SEQUENTIAL}
 * ({@code app/cbl/CBTRN02C.cbl:L46-L49}) and opened with
 * {@code OPEN OUTPUT DALYREJS-FILE} in the {@code 0300-DALYREJS-OPEN}
 * paragraph. Every reject is appended via a single {@code WRITE
 * FD-REJS-RECORD FROM REJECT-RECORD} at
 * {@code app/cbl/CBTRN02C.cbl:L451}. No {@code REWRITE} or
 * {@code DELETE} is ever issued. Consequently
 * {@link #appendReject(DalyTranRecord, int, String)} is the ONLY
 * mutation operation surfaced by this port. Implementations MAY hold
 * the underlying file handle open across many
 * {@code appendReject} calls (matching the COBOL {@code OPEN OUTPUT}
 * &rarr; many {@code WRITE} &rarr; {@code CLOSE} lifecycle) and MUST
 * release it via {@link #close()}.
 *
 * <h2>Ordering</h2>
 * <ul>
 *   <li>{@link #streamSequential()} returns records in the physical
 *       order they appear in the source file &mdash; matching the
 *       COBOL sequential-file traversal exercised by CBTRN01C and
 *       CBTRN02C. Implementations MUST preserve this order;
 *       reordering would change the observable output of CBTRN01C
 *       (the {@code DISPLAY DALYTRAN-RECORD} loop at
 *       {@code app/cbl/CBTRN01C.cbl:L168}), would change the order
 *       in which transactions are posted by CBTRN02C against the
 *       shared {@code TRANSACT}, {@code ACCOUNT}, and {@code TCATBAL}
 *       files (forbidden by AAP &sect;0.1.3: "virtual threads are
 *       NOT a license to reorder records, change sort orders, or
 *       break sequencing"), and would change the order in which
 *       rejects appear in the {@code DALYREJS} output file
 *       (forbidden by AAP &sect;0.7.1: "All file naming conventions,
 *       sort orders, and batch sequencing").</li>
 *   <li>{@link #appendReject(DalyTranRecord, int, String)} appends
 *       records to {@code DALYREJS} in call order. The COBOL idiom
 *       only ever appends from within a single sequential scan of
 *       {@code DALYTRAN}, so reject order is deterministically the
 *       order in which transactions failed validation during the
 *       scan. The Java translation MUST preserve this property:
 *       virtual-thread fan-out at the application layer that
 *       reorders the reject append sequence would change the
 *       byte-for-byte content of {@code DALYREJS} and is FORBIDDEN.</li>
 * </ul>
 *
 * <h2>Resource lifecycle</h2>
 * The {@link Stream} returned by {@link #streamSequential()} extends
 * {@link AutoCloseable} (via {@link java.util.stream.BaseStream}).
 * Callers MUST close the returned stream &mdash; typically via
 * try-with-resources &mdash; so that the adapter can release the
 * underlying file channel, DB cursor, or pre-allocated record buffer:
 * <pre>{@code
 *   try (Stream<DalyTranRecord> daily = repository.streamSequential()) {
 *       daily.forEach(this::processOne);
 *   }
 * }</pre>
 * Failure to close may leak file handles in long-running batch jobs.
 *
 * <p>The repository instance itself is also {@link AutoCloseable}
 * (matching the lifecycle convention used by all sibling batch-style
 * port interfaces in this package &mdash; AccountRepository,
 * CardRepository, CardXrefRepository, CustomerRepository,
 * TransactionRepository, etc.). Implementations MAY be no-op for
 * in-memory adapters and MUST be idempotent so that callers can
 * defensively close the same repository multiple times without
 * observable side-effects. This mirrors the COBOL
 * {@code CLOSE DALYTRAN-FILE} verb in the {@code 9000-DALYTRAN-CLOSE}
 * paragraph and {@code CLOSE DALYREJS-FILE} verb in the
 * {@code 9300-DALYREJS-CLOSE} paragraph of CBTRN02C.
 *
 * <h2>Byte fidelity (AAP &sect;0.6.5)</h2>
 * Implementations MUST persist the entire 350-byte fixed-width
 * record image including the 20-byte trailing {@code FILLER}
 * ({@link DalyTranRecord#filler()}) when serialising the reject
 * record's {@code REJECT-TRAN-DATA} region. The FILLER is structural
 * padding and not business data, but byte-for-byte round-trip
 * equality {@code parse(b).encode() == b} is the formal contract
 * with external file consumers per AAP &sect;0.6.5. Truncating,
 * normalising, or synthesising the FILLER bytes is FORBIDDEN.
 *
 * <p>For the 80-byte validation trailer:
 * <ul>
 *   <li>The {@code int validationFailReason} parameter is encoded as
 *       4 ASCII digits, zero-left-padded ({@code String.format("%04d",
 *       validationFailReason)}). Values outside the {@code [0, 9999]}
 *       range cannot be encoded in {@code PIC 9(04)} and MUST be
 *       rejected by implementations with an
 *       {@link IllegalArgumentException}; see the parameter
 *       constraint section on
 *       {@link #appendReject(DalyTranRecord, int, String)}.</li>
 *   <li>The {@code String validationFailDescription} parameter is
 *       encoded as exactly 76 ASCII bytes &mdash; truncated if longer
 *       than 76 characters, right-space-padded if shorter
 *       &mdash; matching the COBOL {@code MOVE 'INVALID CARD NUMBER
 *       FOUND' TO WS-VALIDATION-FAIL-REASON-DESC} (et al.) group-move
 *       semantics where the source literal is automatically padded to
 *       the destination {@code PIC X(76)} width with trailing
 *       spaces.</li>
 * </ul>
 *
 * <h2>PCI / security note (AAP &sect;0.7.2)</h2>
 * The {@link DalyTranRecord#dalytranCardNum()} component of every
 * record streamed or appended through this port is the Primary
 * Account Number (PAN). Per AAP &sect;0.7.2, PAN MUST NOT appear in
 * logs or error messages in plaintext: only the last 4 digits may be
 * visible. Implementations of this port MUST:
 * <ul>
 *   <li>Never log the full 16-character {@code dalytranCardNum} of a
 *       {@link DalyTranRecord} returned by {@link #streamSequential()}
 *       or accepted by
 *       {@link #appendReject(DalyTranRecord, int, String)}. Adapters
 *       that need to emit a record-level diagnostic SHOULD use
 *       {@link DalyTranRecord#toString()} (which already applies PAN
 *       masking via {@link DalyTranRecord#maskedPan()}) or call
 *       {@code DalyTranRecord#maskedPan()} explicitly.</li>
 *   <li>Never include the PAN in exception messages that may cross
 *       the application / operator boundary.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Per AAP &sect;0.6.6, batch drivers may fan out per-record work onto
 * virtual threads where the COBOL processing is serial but the
 * per-record work is independent. For this port:
 * <ul>
 *   <li>{@link #streamSequential()} is read-only and returns a
 *       sequentially-ordered stream; implementations SHOULD permit a
 *       single iteration per stream instance and MAY be unsafe under
 *       concurrent iteration of the same stream instance from
 *       multiple threads &mdash; callers wishing to fan out per-record
 *       work SHOULD partition the work downstream from
 *       {@code forEach}, not by sharing the iterator.</li>
 *   <li>{@link #appendReject(DalyTranRecord, int, String)} mutates
 *       the {@code DALYREJS} sink. Implementations MAY serialise
 *       concurrent calls (typical pattern: a per-instance
 *       {@code synchronized} on the underlying byte channel) but
 *       MUST guarantee that calls from a SINGLE thread are appended
 *       in call order &mdash; this is the property that preserves the
 *       byte-for-byte content of {@code DALYREJS} under
 *       sequential-scan posting in CBTRN02C.</li>
 * </ul>
 *
 * <h2>No framework dependencies</h2>
 * This interface deliberately avoids Spring, Hibernate, JPA, and
 * Lombok imports per AAP &sect;0.1.1 (the user mandate that no
 * framework be introduced that the existing COBOL program does not
 * require). It also avoids {@code java.io.File},
 * {@code java.util.Date}, and {@code java.util.Calendar} per AAP
 * &sect;0.1.1 and &sect;0.6.4. Concrete adapters provide their own
 * dependency-injection wiring via the composition root.
 *
 * <h2>No {@code default} methods</h2>
 * This interface contains no {@code default} method implementations.
 * Every method is abstract so that each adapter (file, JDBC,
 * in-memory test double) provides an explicit implementation. This
 * matches the pure-port design of {@code carddemo-domain} per AAP
 * &sect;0.3.2.
 *
 * @see DalyTranRecord
 * @see com.blitzy.carddemo.domain.record.DalyTranRecord
 * @since 1.0.0
 */
public interface DailyTransactionRepository extends AutoCloseable {

    /**
     * Stream the {@code DALYTRAN} dataset as a lazy, ordered
     * {@link Stream} of {@link DalyTranRecord}s in input file order.
     *
     * <p>Corresponds to the COBOL forward sequential read driven by:
     * <ul>
     *   <li>{@code CBTRN01C} {@code 1000-DALYTRAN-GET-NEXT}
     *       paragraph at {@code app/cbl/CBTRN01C.cbl:L202-L225}:
     *       <pre>{@code
     *       READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
     *       IF  DALYTRAN-STATUS = '00'
     *           MOVE 0 TO APPL-RESULT
     *       ELSE
     *           IF  DALYTRAN-STATUS = '10'
     *               MOVE 16 TO APPL-RESULT
     *           ELSE
     *               MOVE 12 TO APPL-RESULT
     *           END-IF
     *       END-IF
     *       IF  APPL-AOK
     *           CONTINUE
     *       ELSE
     *           IF  APPL-EOF
     *               MOVE 'Y' TO END-OF-DAILY-TRANS-FILE
     *           ELSE
     *               DISPLAY 'ERROR READING DAILY TRANSACTION FILE'
     *               MOVE DALYTRAN-STATUS TO IO-STATUS
     *               PERFORM Z-DISPLAY-IO-STATUS
     *               PERFORM Z-ABEND-PROGRAM
     *           END-IF
     *       END-IF
     *       EXIT.
     *       }</pre></li>
     *   <li>{@code CBTRN02C} {@code 1000-DALYTRAN-GET-NEXT}
     *       paragraph at {@code app/cbl/CBTRN02C.cbl:L345-L369}
     *       (same shape with {@code MOVE 'Y' TO END-OF-FILE}
     *       instead of {@code END-OF-DAILY-TRANS-FILE}).</li>
     * </ul>
     *
     * <h4>End-of-file semantics</h4>
     * The COBOL loop terminates when {@code DALYTRAN-STATUS = '10'}
     * (end-of-file) sets {@code END-OF-FILE = 'Y'}. The Java
     * translation expresses the same condition by having the
     * returned {@link Stream} terminate naturally when the
     * underlying file reader reaches EOF &mdash; callers MUST NOT
     * sentinel-check for an empty record or rely on a terminator
     * value in the stream. The empty-stream case (file present but
     * containing zero records) corresponds to the COBOL condition
     * where the first {@code READ} immediately returns
     * {@code '10'}; implementations MUST treat this as a normal
     * termination, not an error.
     *
     * <h4>I/O error semantics</h4>
     * Adapter-level I/O failures (the COBOL {@code DALYTRAN-STATUS
     * NOT = '00' AND NOT = '10'} branch &mdash; e.g., dataset
     * missing, file-system error, codepage decode failure) MUST be
     * surfaced as an unchecked {@link RuntimeException} subtype
     * (typically thrown by the {@link Stream#forEach} terminal
     * operation when the underlying read fails). This corresponds to
     * the COBOL {@code PERFORM Z-ABEND-PROGRAM} branch which abends
     * the entire batch step. Wrapping into a checked exception is
     * FORBIDDEN per the Folder Rule (no checked exceptions on port
     * interfaces, matching the consistent sibling-port style).
     *
     * <h4>Ordering</h4>
     * Records are returned in <em>physical input-file order</em>
     * &mdash; the order produced by the COBOL sequential
     * {@code READ}. Implementations MUST NOT sort, deduplicate, or
     * reorder records. AAP &sect;0.1.3 is explicit on this point:
     * "virtual threads are NOT a license to reorder records, change
     * sort orders, or break sequencing." If a downstream caller
     * wishes to fan out per-record work onto virtual threads, it
     * MUST preserve the observable ordering of any subsequent
     * append into {@code DALYREJS} (typically by collecting results
     * back into the original input order before calling
     * {@link #appendReject(DalyTranRecord, int, String)}).
     *
     * <h4>Lazy evaluation</h4>
     * The returned stream is LAZY &mdash; records are decoded
     * one-at-a-time as the stream is consumed, NOT eagerly into a
     * collection. This matches the memory profile of the COBOL
     * baseline (which holds a single {@code DALYTRAN-RECORD}
     * working-storage slot regardless of dataset size) and is
     * essential for batches that may process millions of records
     * without blowing the heap.
     *
     * <h4>Resource lifecycle</h4>
     * The returned {@link Stream} extends {@link AutoCloseable}.
     * Callers MUST close the stream (typically via
     * try-with-resources) so the adapter can release the underlying
     * file channel, DB cursor, or pre-allocated buffer:
     * <pre>{@code
     *   try (Stream<DalyTranRecord> daily = repo.streamSequential()) {
     *       daily.forEach(this::process);
     *   }
     * }</pre>
     * Failure to close may leak file handles. The stream MUST be
     * iterated at most once; re-iterating a consumed stream is an
     * adapter-defined {@link IllegalStateException}.
     *
     * @return a lazy, ordered, single-use stream of every
     *         {@link DalyTranRecord} in the underlying
     *         {@code DALYTRAN} dataset, in physical input order.
     *         Never {@code null}; the empty stream represents an
     *         empty {@code DALYTRAN} file (which is a valid COBOL
     *         terminating state, NOT an error).
     */
    Stream<DalyTranRecord> streamSequential();

    /**
     * Append a rejected daily transaction to the {@code DALYREJS}
     * sequential output dataset, decorated with a 4-digit numeric
     * fail-reason code and a 76-character fail-reason description.
     *
     * <p>Corresponds to the COBOL {@code 2500-WRITE-REJECT-REC}
     * paragraph at {@code app/cbl/CBTRN02C.cbl:L446-L465}:
     * <pre>{@code
     * 2500-WRITE-REJECT-REC.
     *     MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
     *     MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
     *     MOVE 8 TO APPL-RESULT
     *     WRITE FD-REJS-RECORD FROM REJECT-RECORD
     *     IF DALYREJS-STATUS = '00'
     *         MOVE 0 TO  APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *     END-IF
     *     IF  APPL-AOK
     *         CONTINUE
     *     ELSE
     *         DISPLAY 'ERROR WRITING TO REJECTS FILE'
     *         MOVE DALYREJS-STATUS  TO IO-STATUS
     *         PERFORM 9910-DISPLAY-IO-STATUS
     *         PERFORM 9999-ABEND-PROGRAM
     *     END-IF
     *     EXIT.
     * }</pre>
     *
     * <h4>Reject-record layout (430 bytes)</h4>
     * The adapter MUST serialise the call as the following byte
     * image, matching the COBOL {@code REJECT-RECORD} layout at
     * {@code app/cbl/CBTRN02C.cbl:L176-L182}:
     * <pre>{@code
     *   bytes   0..349  -- REJECT-TRAN-DATA          (the 350-byte DalyTranRecord)
     *   bytes 350..353  -- WS-VALIDATION-FAIL-REASON (4 ASCII digits, zero-left-padded)
     *   bytes 354..429  -- WS-VALIDATION-FAIL-REASON-DESC (76 ASCII chars, right-space-padded)
     * }</pre>
     * Total: 430 bytes per appended record. The 350-byte
     * {@code REJECT-TRAN-DATA} region is obtained by calling
     * {@link DalyTranRecord} encode logic (the canonical record
     * encode routine in {@code carddemo-domain.record}) on the
     * supplied {@code record} argument, preserving the original
     * {@link DalyTranRecord#filler()} byte-for-byte.
     *
     * <h4>Parameter constraints</h4>
     * <ul>
     *   <li>{@code record} &mdash; the offending daily transaction
     *       record (350 bytes). MUST NOT be {@code null}. Its
     *       byte image is appended verbatim into the
     *       {@code REJECT-TRAN-DATA} region.</li>
     *   <li>{@code validationFailReason} &mdash; the 4-digit
     *       numeric reason code corresponding to the COBOL
     *       {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} field at
     *       {@code app/cbl/CBTRN02C.cbl:L181}. Valid range is
     *       {@code [0, 9999]} inclusive (the full {@code PIC 9(04)}
     *       domain). The COBOL source observed values are
     *       {@code 100} (XREF lookup miss),
     *       {@code 101} (account lookup miss),
     *       {@code 102} (overlimit), and
     *       {@code 103} (post-expiration); see the class-level
     *       "Observed validation reason codes" section above for
     *       provenance. Implementations MUST reject values outside
     *       {@code [0, 9999]} with an
     *       {@link IllegalArgumentException} &mdash; such values
     *       cannot be encoded in {@code PIC 9(04)} without losing
     *       information and would corrupt the byte-for-byte
     *       fidelity of {@code DALYREJS}. Implementations MUST NOT
     *       silently truncate, modulo, or saturate.</li>
     *   <li>{@code validationFailDescription} &mdash; the
     *       fail-reason description text corresponding to the
     *       COBOL {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}
     *       field at {@code app/cbl/CBTRN02C.cbl:L182}. The value
     *       is encoded as exactly 76 ASCII bytes: truncated to the
     *       first 76 characters if longer; right-space-padded with
     *       ASCII space ({@code 0x20}) bytes if shorter &mdash;
     *       matching the COBOL group-move padding semantics. A
     *       {@code null} value is treated as the empty string (76
     *       ASCII spaces). Adapters MAY emit a diagnostic when
     *       truncation occurs; truncation itself is a normal
     *       outcome and MUST NOT be reported as an error.</li>
     * </ul>
     *
     * <h4>Atomicity and ordering</h4>
     * Each {@code appendReject} call is atomic with respect to the
     * single 430-byte record it writes &mdash; matching the COBOL
     * single {@code WRITE} verb. Implementations MUST NOT split a
     * single record across multiple physical writes such that a
     * concurrent reader could observe a partial record. Calls from
     * a single thread MUST be appended in call order; calls from
     * multiple threads MAY be serialised by the implementation but
     * MUST preserve a total order such that the byte-for-byte
     * content of {@code DALYREJS} can be reproduced by re-running
     * the upstream {@link #streamSequential()} loop with the same
     * input.
     *
     * <h4>I/O error semantics</h4>
     * Adapter-level I/O failures (the COBOL
     * {@code DALYREJS-STATUS NOT = '00'} branch at
     * {@code app/cbl/CBTRN02C.cbl:L454-L463} &mdash; e.g., disk
     * full, permission denied, file-system error) MUST be surfaced
     * as an unchecked {@link RuntimeException} subtype. This
     * corresponds to the COBOL {@code PERFORM 9999-ABEND-PROGRAM}
     * branch which abends the entire batch step.
     *
     * <h4>Not surfaced here</h4>
     * <ul>
     *   <li>There is NO random-access write to {@code DALYREJS}
     *       (no {@code REWRITE}, no {@code DELETE}); only
     *       sequential append.</li>
     *   <li>There is NO write to {@code DALYTRAN} from any
     *       translated program &mdash; {@code DALYTRAN} is
     *       produced by an upstream process not part of this
     *       refactor. No {@code save(...)} or {@code append(...)}
     *       method covering {@code DALYTRAN} input writes is
     *       provided.</li>
     * </ul>
     *
     * @param record                      the offending daily
     *                                    transaction record (350
     *                                    bytes when encoded);
     *                                    never {@code null}.
     * @param validationFailReason        the 4-digit numeric
     *                                    fail-reason code; MUST be
     *                                    in the range
     *                                    {@code [0, 9999]}
     *                                    inclusive.
     * @param validationFailDescription   the fail-reason
     *                                    description text;
     *                                    truncated to 76 chars if
     *                                    longer, right-space-padded
     *                                    if shorter; {@code null}
     *                                    is treated as the empty
     *                                    string.
     * @throws NullPointerException     if {@code record} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if {@code validationFailReason}
     *                                  is outside the
     *                                  {@code [0, 9999]}
     *                                  {@code PIC 9(04)} domain.
     * @throws RuntimeException         on any adapter-level I/O
     *                                  failure (corresponds to the
     *                                  COBOL
     *                                  {@code PERFORM 9999-ABEND-PROGRAM}
     *                                  branch).
     */
    void appendReject(DalyTranRecord record,
                      int validationFailReason,
                      String validationFailDescription);

    /**
     * Release any underlying resources held by this repository
     * &mdash; file channels, DB cursors, pre-allocated buffers,
     * the open {@code DALYREJS} output handle, etc.
     *
     * <p>Corresponds to the COBOL {@code CLOSE DALYTRAN-FILE} verb
     * in the {@code 9000-DALYTRAN-CLOSE} paragraph and the
     * {@code CLOSE DALYREJS-FILE} verb in the
     * {@code 9300-DALYREJS-CLOSE} paragraph of CBTRN02C (and the
     * equivalent {@code 9000-DALYTRAN-CLOSE} paragraph of CBTRN01C).
     * In the COBOL baseline these closes are sequenced
     * unconditionally at the end of the main paragraph regardless of
     * whether earlier opens succeeded; the Java translation matches
     * this convention by guaranteeing that {@code close} is
     * idempotent and safe to call after a failed {@code open} on the
     * underlying adapter.
     *
     * <p>Implementations MAY be no-op for in-memory adapters and
     * MUST be idempotent so that callers can defensively close the
     * same repository multiple times without observable
     * side-effects.
     *
     * <p>Declared without {@code throws Exception} to relieve
     * callers of checked-exception boilerplate; concrete adapter
     * exceptions MUST be wrapped in {@link RuntimeException}
     * subtypes per the Folder Rule (no checked exceptions on port
     * interfaces, matching the consistent sibling-port style
     * established by AccountRepository, CardRepository,
     * CardXrefRepository, CustomerRepository,
     * TransactionRepository, etc.).
     */
    @Override
    void close();
}
