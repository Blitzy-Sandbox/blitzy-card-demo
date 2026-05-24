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

import com.blitzy.carddemo.domain.record.CustomerRecord;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Domain port (hexagonal-architecture interface) for the CUSTDATA dataset.
 *
 * <p>CUSTDATA is the master customer file. On the mainframe it is a VSAM
 * KSDS with a 9-digit numeric primary key ({@code CUST-ID PIC 9(09)}) and a
 * fixed record length of 500 bytes (copybook {@code CVCUS01Y},
 * {@code app/cpy/CVCUS01Y.cpy}). The COBOL FD splits the 500-byte record
 * into a 9-byte {@code FD-CUST-ID} key and a 491-byte {@code FD-CUST-DATA}
 * payload (see {@code app/cbl/CBCUS01C.cbl:L37-L40}); the corresponding
 * Java {@link CustomerRecord} exposes the eighteen individual fields
 * parsed from the 500-byte buffer (including a 168-byte trailing
 * {@code FILLER} that is preserved verbatim for byte fidelity per
 * AAP &sect;0.1.3).
 *
 * <h2>COBOL consumers</h2>
 * The mainframe code base has three principal consumers of CUSTDATA, each
 * exercising a different access pattern that this port abstracts:
 *
 * <ul>
 *   <li><b>CBCUS01C</b> &mdash; batch sequential dump of the customer master.
 *       Declares the file with {@code ORGANIZATION IS INDEXED
 *       ACCESS MODE IS SEQUENTIAL RECORD KEY IS FD-CUST-ID}
 *       ({@code app/cbl/CBCUS01C.cbl:L29-L33}) and iterates with the
 *       {@code 1000-CUSTFILE-GET-NEXT} paragraph
 *       ({@code app/cbl/CBCUS01C.cbl:L70-L80}). This pattern maps to
 *       {@link #streamSequential()}.</li>
 *   <li><b>COACTVWC</b> &mdash; online account-view transaction. Performs a
 *       random keyed read via
 *       {@code EXEC CICS READ DATASET(LIT-CUSTFILENAME) RIDFLD(...)
 *       INTO(CUSTOMER-RECORD)}
 *       ({@code app/cbl/COACTVWC.cbl:L826-L827}) and evaluates the
 *       response with {@code DFHRESP(NORMAL)} vs {@code DFHRESP(NOTFND)}.
 *       This pattern maps to {@link #findById(long)}.</li>
 *   <li><b>COACTUPC</b> &mdash; online account-update transaction. Reads the
 *       record (random keyed) and later issues
 *       {@code EXEC CICS REWRITE FILE(LIT-CUSTFILENAME)
 *       FROM(CUST-UPDATE-RECORD)}
 *       ({@code app/cbl/COACTUPC.cbl:L4085-L4091}) to commit the change.
 *       This is the second leg of the only {@code SYNCPOINT ROLLBACK}
 *       transaction in the COBOL source (account update is the first
 *       leg). The Java {@code CoActUpC} application class reconstructs
 *       that atomicity with try/finally and compensating writes; this
 *       port deliberately does <em>not</em> model transactional
 *       boundaries &mdash; transactional semantics are the responsibility
 *       of the use-case layer per AAP &sect;0.1.2 and the {@code COACTUPC}
 *       implementation decision logged in {@code MIGRATION_NOTES.md}.
 *       The {@code REWRITE} pattern (and IDCAMS REPRO load via
 *       {@code app/jcl/CUSTFILE.jcl}) map to {@link #save(CustomerRecord)}.</li>
 * </ul>
 *
 * <h2>Adapter implementations</h2>
 * Per AAP &sect;0.3.6 (Hexagonal Architecture), adapter implementations
 * live in:
 * <ul>
 *   <li>{@code carddemo-adapter-file} &mdash; fixed-width file I/O via
 *       {@code java.nio.file} (default, matches the file-based batch
 *       mandate of AAP &sect;0.1.1).</li>
 *   <li>{@code carddemo-adapter-db} &mdash; optional JDBC repositories
 *       (empty by default; created only if a DB2/relational adapter is
 *       required, per AAP &sect;0.2.1).</li>
 * </ul>
 * The composition root in {@code carddemo-app} wires the chosen adapter
 * into the use case at startup via plain constructor injection (no
 * Spring container, per AAP &sect;0.1.1).
 *
 * <h2>Sort order and byte fidelity</h2>
 * Records returned by {@link #streamSequential()} MUST appear in ascending
 * {@code CUST-ID} order &mdash; the same physical order produced by the
 * COBOL KSDS sequential read. Per AAP &sect;0.1.3, reordering is
 * FORBIDDEN because it would change observable batch output. Adapter
 * implementations MUST also preserve the 168-byte trailing {@code FILLER}
 * of {@code CUSTOMER-RECORD} verbatim on every {@link #save(CustomerRecord)}
 * call &mdash; the byte-fidelity invariant of AAP &sect;0.6.5 (every
 * {@code parse(b).encode()} equals {@code b} byte-for-byte) extends through
 * the I/O round-trip.
 *
 * <h2>Error contract</h2>
 * Implementations translate VSAM {@code FILE STATUS} codes (and CICS
 * {@code DFHRESP} values, where applicable) into the typed-exception
 * hierarchy defined by the adapter layer; the port itself imposes only the
 * minimum semantic contract:
 * <ul>
 *   <li>{@link #findById(long)} returns {@link Optional#empty()} for any
 *       absent key (mapping {@code DFHRESP(NOTFND)} /
 *       {@code FILE STATUS = '23'}) and throws an unchecked
 *       {@link RuntimeException} (typically an adapter-defined I/O
 *       exception) for unexpected error responses;</li>
 *   <li>{@link #save(CustomerRecord)} performs an upsert &mdash; it
 *       {@code REWRITE}s an existing key and {@code WRITE}s a new one;</li>
 *   <li>{@link #delete(long)} throws
 *       {@link java.util.NoSuchElementException} when the key is absent
 *       (a stricter contract than COBOL {@code DELETE} which returns
 *       {@code FILE STATUS = '23'} silently; the stricter Java contract
 *       surfaces JCL-time defects earlier).</li>
 * </ul>
 *
 * <h2>Resource lifecycle</h2>
 * The port extends {@link AutoCloseable} so adapters can release backing
 * resources (open file channels, JDBC connections, statement caches)
 * deterministically when the use case completes. This mirrors the
 * COBOL pattern of paired {@code OPEN}/{@code CLOSE} verbs &mdash; e.g., the
 * CBCUS01C paragraphs {@code 0000-CUSTFILE-OPEN} and
 * {@code 9000-CUSTFILE-CLOSE} &mdash; and matches the established
 * convention used by every other port in this package. Callers
 * SHOULD use try-with-resources, or call {@link #close()} from a
 * {@code finally} block in driver code that emulates COBOL's
 * structured {@code 9000-CUSTFILE-CLOSE} call.
 *
 * <h2>Mandated Java 25 idioms</h2>
 * Per AAP &sect;0.7.3 this port uses only finalized Java features &mdash;
 * no preview features, no {@code default} methods (every method on this
 * interface is abstract, including the inherited
 * {@link AutoCloseable#close()} which is re-declared without checked
 * exceptions for ergonomic call sites), no {@code java.io.File} (file
 * I/O uses {@code java.nio.file} in the adapter), no
 * {@code java.util.Date} (date fields are
 * {@link java.time.LocalDate} on {@link CustomerRecord}). The interface
 * carries no Spring, Hibernate, JPA, or Lombok annotations.
 *
 * @see CustomerRecord
 * @see com.blitzy.carddemo.domain.record.CustomerLegacyRecord
 */
public interface CustomerRepository extends AutoCloseable {

    /**
     * Returns the customer record identified by {@code custId}, or
     * {@link Optional#empty()} if no such record exists.
     *
     * <p>Corresponds to the random keyed read pattern
     * {@code EXEC CICS READ DATASET(LIT-CUSTFILENAME) RIDFLD(...)
     * INTO(CUSTOMER-RECORD)} used by COACTVWC
     * ({@code app/cbl/COACTVWC.cbl:L826-L827}) and the equivalent read
     * issued by COACTUPC prior to its {@code REWRITE}
     * ({@code app/cbl/COACTUPC.cbl:L4085-L4091}). It also matches the
     * keyed read form of the {@code 1000-CUSTFILE-GET-NEXT} paragraph
     * in CBCUS01C when the COBOL caller positions the file with
     * {@code START CUSTFILE-FILE KEY = FD-CUST-ID}
     * ({@code app/cbl/CBCUS01C.cbl:L70-L80}).
     *
     * <p>The mapping between COBOL response codes and the Java return
     * value is:
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} / {@code FILE STATUS = '00'} &rarr;
     *       a non-empty {@link Optional} carrying the parsed
     *       {@link CustomerRecord};</li>
     *   <li>{@code DFHRESP(NOTFND)} / {@code FILE STATUS = '23'} /
     *       {@code INVALID KEY} &rarr; {@link Optional#empty()}.</li>
     * </ul>
     * Any other response (e.g., {@code DFHRESP(IOERR)}) is propagated as
     * an unchecked exception by the adapter implementation.
     *
     * @param custId the 9-digit customer identifier
     *               ({@code CUST-ID PIC 9(09)}), in the inclusive range
     *               {@code 0L..999_999_999L}. The
     *               {@link CustomerRecord} canonical constructor
     *               enforces the same range on returned values; callers
     *               passing a value outside this range will observe
     *               {@link Optional#empty()} (no record can match).
     * @return an {@link Optional} containing the matching record if
     *         present, otherwise {@link Optional#empty()}.
     */
    Optional<CustomerRecord> findById(long custId);

    /**
     * Returns a lazy stream of every customer record in the dataset,
     * iterated in ascending {@code CUST-ID} order (the physical order of
     * a KSDS sequential read).
     *
     * <p>Corresponds to the COBOL {@code 1000-CUSTFILE-GET-NEXT} loop in
     * CBCUS01C ({@code app/cbl/CBCUS01C.cbl:L70-L80}), driven by
     * {@code ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL
     * RECORD KEY IS FD-CUST-ID}
     * ({@code app/cbl/CBCUS01C.cbl:L29-L33}). It is the primary entry
     * point for customer-file dumps (JCL {@code READCUST.jcl}) and seed
     * loads (JCL {@code CUSTFILE.jcl}, {@code DEFCUST.jcl}).
     *
     * <h3>Resource management</h3>
     * The returned stream is backed by an open file channel (or database
     * cursor) and therefore <strong>extends {@link AutoCloseable}</strong>
     * (per the {@link Stream} contract). Callers <strong>MUST</strong>
     * use try-with-resources to ensure the underlying handle is released:
     * <pre>{@code
     * try (Stream<CustomerRecord> customers = repo.streamSequential()) {
     *     customers.forEach(this::process);
     * }
     * }</pre>
     * Failing to close the stream leaks an OS file handle.
     *
     * <h3>Ordering invariant</h3>
     * Per AAP &sect;0.1.3, reordering is FORBIDDEN: the JVM batch
     * implementation MUST emit records in the same ascending-CUST-ID
     * order as the COBOL baseline; any virtual-thread fan-out applied
     * downstream of this stream must preserve that order in observable
     * outputs (see AAP &sect;0.6.6, "Virtual threads … only where
     * COBOL was serial but per-record work is independent").
     *
     * @return a closeable, non-{@code null}, ordered {@link Stream} of
     *         every {@link CustomerRecord} in the dataset.
     */
    Stream<CustomerRecord> streamSequential();

    /**
     * Persists the given customer record, overwriting any existing record
     * with the same {@code CUST-ID} (upsert semantics).
     *
     * <p>Corresponds to {@code EXEC CICS REWRITE FILE(LIT-CUSTFILENAME)
     * FROM(CUST-UPDATE-RECORD)} in COACTUPC
     * ({@code app/cbl/COACTUPC.cbl:L4085-L4091}) for updates, and to
     * {@code WRITE CUSTFILE-FILE FROM CUSTOMER-RECORD} (or the IDCAMS
     * {@code REPRO} load step) for inserts. The customer update in
     * COACTUPC is the second leg of the sole {@code SYNCPOINT ROLLBACK}
     * transaction in the COBOL source &mdash; account update is the first
     * leg; the application class {@code CoActUpC} reconstructs that
     * atomicity with try/finally and compensating writes; this port
     * does <em>not</em> model transactional boundaries.
     *
     * <p>This method also covers the JCL seed-load paths:
     * <ul>
     *   <li>{@code app/jcl/CUSTFILE.jcl} &mdash; IDCAMS DELETE/DEFINE
     *       + REPRO from {@code app/data/ASCII/custdata.txt};</li>
     *   <li>{@code app/jcl/DEFCUST.jcl} &mdash; alternate DEFINE step.
     *       NOTE: {@code DEFCUST.jcl} has a delete/define dataset-name
     *       mismatch flagged as a suspected JCL bug in
     *       {@code MIGRATION_NOTES.md} per AAP &sect;0.4.1; that defect
     *       is preserved faithfully by the JCL translation and does not
     *       affect the contract of this port.</li>
     * </ul>
     *
     * <h3>Byte-fidelity contract</h3>
     * Implementations MUST persist the record so that a subsequent
     * {@link #findById(long)} returns a {@link CustomerRecord} whose
     * {@link CustomerRecord#encode()} produces exactly the bytes that
     * were written (round-trip byte equality per AAP &sect;0.6.5). In
     * particular, the 168-byte trailing {@code FILLER} of
     * {@code CUSTOMER-RECORD} MUST be written verbatim &mdash; it MUST
     * NOT be collapsed, normalised, or replaced with spaces.
     *
     * @param customer the customer record to persist; MUST NOT be
     *                 {@code null}. The record's {@code custId} is the
     *                 effective primary key. Implementations MAY throw
     *                 {@link NullPointerException} for a {@code null}
     *                 argument.
     */
    void save(CustomerRecord customer);

    /**
     * Removes the customer record identified by {@code custId}.
     *
     * <p>Provided for IDCAMS {@code DELETE}/{@code DEFINE} operations
     * driven by {@code app/jcl/CUSTFILE.jcl} and {@code app/jcl/DEFCUST.jcl}
     * (the latter with the suspected delete/define mismatch flagged in
     * {@code MIGRATION_NOTES.md} per AAP &sect;0.4.1). No COBOL program
     * issues {@code EXEC CICS DELETE} against CUSTDATA at run time; this
     * method is included for adapter completeness and JCL parity so the
     * file-based adapter can fulfil the {@code DELETE} step of a
     * REPRO-style reload.
     *
     * <p>If no record with the given {@code custId} exists, this method
     * throws {@link java.util.NoSuchElementException}. This is a
     * deliberately stricter contract than the COBOL {@code DELETE} verb
     * (which sets {@code FILE STATUS = '23'} silently) &mdash; the
     * stricter Java contract surfaces upstream JCL or call-site defects
     * earlier and makes the port self-documenting. Callers that need
     * delete-if-exists semantics should guard the call with a prior
     * {@link #findById(long)}.
     *
     * @param custId the 9-digit customer identifier
     *               ({@code CUST-ID PIC 9(09)}), in the inclusive range
     *               {@code 0L..999_999_999L}.
     * @throws java.util.NoSuchElementException if no record with
     *                                {@code custId} exists in the dataset.
     */
    void delete(long custId);

    /**
     * Releases all resources held by this repository (open file channels,
     * JDBC connections, prepared-statement caches, etc.).
     *
     * <p>Mirrors the COBOL {@code 9000-CUSTFILE-CLOSE} paragraph in
     * CBCUS01C ({@code app/cbl/CBCUS01C.cbl}) and the implicit
     * close-on-task-end behaviour of CICS for COACTVWC / COACTUPC.
     * Implementations MAY be a no-op for in-memory test doubles.
     *
     * <p>Overrides {@link AutoCloseable#close()} to remove the
     * declaration of the checked {@link Exception}: file and JDBC
     * adapters wrap their underlying checked exceptions in unchecked
     * adapter-defined exceptions, so callers should never need to catch
     * a checked exception from {@code close()}. This makes the port
     * ergonomic in {@code try-with-resources} blocks in use-case code
     * (mirroring the COBOL {@code 9000-CUSTFILE-CLOSE} call site, which
     * also does not propagate checked errors).
     */
    @Override
    void close();
}
