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
package com.awsm2.carddemo.repository;

import com.awsm2.carddemo.domain.CardCrossReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link CardCrossReference} entity
 * &mdash; the linkage table that connects 16-character cards to their
 * owning customer and account.
 *
 * <p>This repository is the canonical example of a COBOL VSAM
 * KSDS-plus-AIX/PATH access-layer migration in the entire CardDemo
 * refactor: a single 50-byte VSAM cluster with both a primary key and a
 * NONUNIQUEKEY alternate index that is translated into a PostgreSQL
 * table with a primary-key column and a non-unique secondary B-tree
 * index, exposed through Spring Data JPA primary-key methods plus a
 * derived-query method on the alternate-index column (AAP &sect;0.6.2).</p>
 *
 * <h2>Replaces VSAM clusters</h2>
 * <ul>
 *   <li>{@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} ({@code KEYS(16 0)},
 *       {@code RECORDSIZE(50 50)}, {@code INDEXED}, defined in
 *       {@code app/jcl/XREFFILE.jcl}:L39-L52) &mdash; 16-byte primary
 *       key {@code XREF-CARD-NUM} at offset 0; replaced by the JPA
 *       {@code @Id} on {@code xref_card_num VARCHAR(16) PRIMARY KEY}
 *       declared in {@code V004__create_cardxref.sql}. Primary-key
 *       access is provided by the inherited
 *       {@link JpaRepository#findById(Object)} method.</li>
 *   <li>{@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} ({@code KEYS(11 25)},
 *       {@code NONUNIQUEKEY}, {@code UPGRADE}, defined in
 *       {@code app/jcl/XREFFILE.jcl}:L72-L82) &mdash; alternate index
 *       on {@code XREF-ACCT-ID} at offset 25, with associated
 *       {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH} indirection
 *       (CICS symbolic name {@code CXACAIX}); replaced by the
 *       PostgreSQL non-unique B-tree index {@code idx_cardxref_acct_id}
 *       declared in {@code V004__create_cardxref.sql} plus the derived
 *       query {@link #findByXrefAcctId(Long) findByXrefAcctId(...)}.</li>
 * </ul>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVACT03Y.cpy}
 * ({@code CARD-XREF-RECORD}, RECLN 50): 16-byte
 * {@code XREF-CARD-NUM PIC X(16)} primary key, 9-byte
 * {@code XREF-CUST-ID PIC 9(09)}, 11-byte
 * {@code XREF-ACCT-ID PIC 9(11)} alternate key, plus a 14-byte trailing
 * {@code FILLER} that has no relational counterpart.</p>
 *
 * <p><strong>Source JCL:</strong> {@code app/jcl/XREFFILE.jcl} &mdash;
 * IDCAMS {@code DELETE CLUSTER} (STEP05), {@code DEFINE CLUSTER}
 * (STEP10), {@code REPRO} from {@code CARDXREF.PS} (STEP15),
 * {@code DEFINE ALTERNATEINDEX} (STEP20), {@code DEFINE PATH} (STEP25),
 * and {@code BLDINDEX} (STEP30). The Java target replaces this entire
 * provisioning chain with the {@code V004__create_cardxref.sql} Flyway
 * migration plus the seed-load workflow defined in
 * {@code src/main/resources/stepfunctions/file-provisioning.asl.json}.</p>
 *
 * <h2>AIX-to-Index Migration (AAP &sect;0.6.2)</h2>
 *
 * <p>The VSAM alternate index allowed COBOL programs to look up
 * cross-reference records by account ID via the PATH name
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH} (referenced under the
 * CICS symbolic name {@code CXACAIX}). The Java target preserves this
 * access pattern by:</p>
 * <ol>
 *   <li>Declaring a non-unique B-tree index
 *       {@code idx_cardxref_acct_id} on
 *       {@code card_xref(xref_acct_id)} in
 *       {@code V004__create_cardxref.sql}. The non-unique declaration
 *       mirrors the VSAM {@code NONUNIQUEKEY} semantics exactly: one
 *       account may own zero, one, or many cross-reference rows.</li>
 *   <li>Exposing the {@link #findByXrefAcctId(Long)} derived query,
 *       which Spring Data JPA translates into the JPQL
 *       {@code SELECT cx FROM CardCrossReference cx WHERE
 *       cx.xrefAcctId = :acctId}. The PostgreSQL planner satisfies
 *       this query via the {@code idx_cardxref_acct_id} index (visible
 *       as {@code Bitmap Index Scan on idx_cardxref_acct_id} in
 *       {@code EXPLAIN ANALYZE} output).</li>
 * </ol>
 *
 * <p>The PATH name {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH} has
 * no Java equivalent &mdash; it was a CICS dataset-name indirection
 * that is unnecessary in the JPA layer because Spring Data JPA queries
 * reference the entity field, not a physical dataset name.</p>
 *
 * <h2>No {@code @Version} (AAP &sect;0.4.1)</h2>
 *
 * <p>This linkage table does <em>not</em> carry a JPA {@code @Version}
 * column &mdash; cross-reference rows are write-once-at-issuance and
 * read-many; there is no read-modify-write contention path. The COBOL
 * source does not perform any {@code REWRITE} on this cluster outside
 * the one-time {@code XREFFILE.jcl} provisioning load, so optimistic
 * locking is unnecessary. The AAP explicitly limits {@code @Version} to
 * the {@code Account} and {@code Card} entities (which carry the
 * before/after image comparison semantics from {@code COACTUPC.cbl} and
 * {@code COCRDUPC.cbl}).</p>
 *
 * <h2>PCI-DSS Cardholder Data Scope (AAP &sect;0.6.6)</h2>
 *
 * <p>This repository operates over the PAN (Primary Account Number) as
 * its primary key. Callers <strong>MUST NOT</strong> log the
 * {@code xrefCardNum} value directly; use the
 * {@code CardCrossReference.toString()} helper or the
 * {@code Card.maskCardNumber()} pattern for any log line that
 * references the cardholder data. The repository emits no SQL trace
 * logs in production profiles per {@code application.yml} (the
 * {@code org.hibernate.SQL} logger is set to {@code WARN} or higher
 * outside the {@code local} profile). Encryption at rest is delegated
 * to RDS via the customer-managed KMS key; encryption in transit is
 * enforced by the {@code rds.force_ssl=1} parameter.</p>
 *
 * <h2>Consumers (Java services replacing the COBOL programs)</h2>
 * <ul>
 *   <li>{@code AccountViewService} (replacement for
 *       {@code app/cbl/COACTVWC.cbl}) &mdash; enumerates the cards
 *       linked to an account via {@link #findByXrefAcctId(Long)} to
 *       resolve the owning customer and to populate the
 *       {@code COACTVW.bms} associated-cards sub-panel. The COBOL
 *       source uses {@code STARTBR}/{@code READNEXT} against
 *       {@code CXACAIX}; the Java target issues a single SELECT and
 *       sorts/picks client-side because the caller (not the
 *       repository) owns the cardinality decision.</li>
 *   <li>{@code TransactionAddService} (replacement for
 *       {@code app/cbl/COTRN02C.cbl}) &mdash; validates a
 *       caller-supplied 16-character card number via
 *       {@link #findById(Object)}; a miss surfaces as a
 *       {@code RecordNotFoundException} (COBOL {@code FILE STATUS 23}).
 *       The resolved {@code xrefAcctId} is used as the Kafka partition
 *       key for the {@code transaction.posted} MSK event (AAP
 *       &sect;0.6.5).</li>
 *   <li>{@code BillPaymentService} (replacement for
 *       {@code app/cbl/COBIL00C.cbl}) &mdash; calls
 *       {@link #findByXrefAcctId(Long)} during the bill-payment flow
 *       to surface the owning card and partition the
 *       {@code account.updated} MSK event by account ID.</li>
 *   <li>{@code TransactionPostingService} (replacement for
 *       {@code app/cbl/CBTRN02C.cbl}) &mdash; Stage 1 of the 4-stage
 *       validation cascade (XREF lookup). A miss yields reject code
 *       {@code 100} ("INVALID CARD NUMBER FOUND") per AAP &sect;0.4.1;
 *       reject codes 100-109 are preserved verbatim from the COBOL
 *       source.</li>
 *   <li>{@code XrefFileReaderService} (replacement for
 *       {@code app/cbl/CBACT03C.cbl}) &mdash; sequential batch scan;
 *       iterates via {@link JpaRepository#findAll()} or, for large
 *       datasets, a paged scan via
 *       {@link JpaRepository#findAll(org.springframework.data.domain.Pageable)}.</li>
 *   <li>{@code TransactionReportService} (replacement for
 *       {@code app/cbl/CBTRN03C.cbl}) &mdash; joins
 *       {@code transactions} with {@code card_xref} during
 *       date-windowed report generation to surface
 *       account-to-customer linkage on each line.</li>
 *   <li>{@code InterestCalculationService} (replacement for
 *       {@code app/cbl/CBACT04C.cbl}) &mdash; resolves the cards
 *       associated with an account via {@link #findByXrefAcctId(Long)}
 *       during the per-account interest computation.</li>
 *   <li>{@code StatementGenerationService} (replacement for
 *       {@code app/cbl/CBSTM03A.CBL} / {@code CBSTM03B.CBL}) &mdash;
 *       resolves the cards associated with an account via
 *       {@link #findByXrefAcctId(Long)} during statement assembly.</li>
 * </ul>
 *
 * <h2>Exception translation (AAP &sect;0.7.1)</h2>
 *
 * <p>The {@link Repository &#64;Repository} stereotype enables Spring's
 * persistence exception translation. {@code GlobalExceptionHandler}
 * then maps the translated exceptions to HTTP responses per REST
 * conventions:</p>
 * <ul>
 *   <li>{@code EmptyResultDataAccessException} &rarr;
 *       {@code RecordNotFoundException} &rarr; HTTP 404 (COBOL FILE
 *       STATUS 23 NOTFND, reject code 100 in batch);</li>
 *   <li>{@code DataIntegrityViolationException} on duplicate insert
 *       &rarr; {@code DuplicateRecordException} &rarr; HTTP 409 (COBOL
 *       FILE STATUS 22 DUPKEY).</li>
 * </ul>
 *
 * <h2>No-AWS-SDK isolation (AAP &sect;0.7.1)</h2>
 *
 * <p>This repository contains no AWS SDK client wiring. All AWS service
 * integrations are isolated in the {@code com.awsm2.carddemo.adapter}
 * package. The repository is pure Spring Data JPA and depends only on
 * the {@link CardCrossReference} domain entity and standard Spring
 * framework types &mdash; no business logic, no event publishing, no
 * external I/O. This isolation is a deliberate AAP requirement:
 * "never inline AWS SDK calls in business logic" (AAP &sect;0.7.1).</p>
 *
 * @see com.awsm2.carddemo.domain.CardCrossReference
 *      the JPA entity mapped to the {@code card_xref} table
 * @see com.awsm2.carddemo.repository.CardRepository
 *      the card repository used in concert with this cross-reference
 *      for cardholder data lookups
 * @see com.awsm2.carddemo.repository.AccountRepository
 *      the account repository typically chained after a
 *      {@link #findByXrefAcctId(Long)} lookup
 */
@Repository
public interface CardCrossReferenceRepository
        extends JpaRepository<CardCrossReference, String> {

    /**
     * Returns every cross-reference row whose alternate key
     * {@code xrefAcctId} matches the supplied account ID &mdash; the
     * JPA replacement for the COBOL VSAM
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} ({@code KEYS(11 25)},
     * {@code NONUNIQUEKEY}, {@code UPGRADE}) alternate-index browse
     * used by {@code app/cbl/COACTVWC.cbl} (account-view card
     * enumeration), {@code app/cbl/COTRN02C.cbl} (transaction-add card
     * validation), {@code app/cbl/CBTRN02C.cbl} (batch posting
     * validation Stage 1), and the report / interest / statement
     * generators that need to enumerate every card on an account.
     *
     * <p><strong>Replaces VSAM AIX:</strong>
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} ({@code KEYS(11 25)},
     * {@code NONUNIQUEKEY}). Backed by the database index
     * {@code idx_cardxref_acct_id} (declared in
     * {@code V004__create_cardxref.sql}).</p>
     *
     * <p>Spring Data JPA translates this derived method name into the
     * JPQL query:</p>
     * <pre>
     *     SELECT cx FROM CardCrossReference cx
     *      WHERE cx.xrefAcctId = :acctId
     * </pre>
     * <p>which, executed against PostgreSQL, traverses the
     * {@code idx_cardxref_acct_id} non-unique secondary index declared
     * by V004 to find the matching rows. The returned {@link List} is
     * effectively unbounded in the COBOL source (one account may own
     * many cards), but in practice the cardinality is &lt; 10 per
     * account in the seed-fixture data.</p>
     *
     * <p><strong>Cardinality contract (NONUNIQUEKEY semantics):</strong>
     * because the alternate key is declared {@code NONUNIQUEKEY UPGRADE}
     * in the COBOL source, this method MAY return more than one row,
     * exactly one row, or zero rows. Callers MUST NOT assume
     * single-row semantics. The caller &mdash; typically
     * {@code AccountViewService}, {@code TransactionAddService},
     * {@code TransactionPostingService}, {@code BillPaymentService},
     * {@code InterestCalculationService},
     * {@code StatementGenerationService}, or
     * {@code TransactionReportService} &mdash; decides how to handle
     * 0, 1, or many results (e.g., reject when empty, pick the first
     * by ascending card number when "any" suffices, iterate all when
     * enumerating every card on the account).</p>
     *
     * <p><strong>Ordering:</strong> the method name does <em>not</em>
     * carry an explicit {@code OrderBy...} clause, so the returned
     * order is unspecified (PostgreSQL may return rows in either index
     * order or heap order depending on the planner choice). Callers
     * that require deterministic ordering MUST sort the result list
     * client-side (e.g., using a {@code Comparator} on
     * {@code xrefCardNum}) or supply a {@code Sort} parameter via a
     * dedicated overload &mdash; this base method intentionally avoids
     * baking an ordering assumption into the repository contract.</p>
     *
     * @param xrefAcctId the {@code xrefAcctId} alternate-key value
     *                   (the 11-digit {@code Account.acctId} from
     *                   {@code app/cpy/CVACT03Y.cpy XREF-ACCT-ID
     *                   PIC 9(11)}); never {@code null}
     * @return all cross-reference rows for the given account; never
     *         {@code null}, possibly empty (NONUNIQUEKEY semantics
     *         preserved from the COBOL VSAM source)
     */
    List<CardCrossReference> findByXrefAcctId(Long xrefAcctId);
}
