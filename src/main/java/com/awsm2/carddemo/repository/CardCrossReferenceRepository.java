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
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link CardCrossReference} entity
 * &mdash; the linkage table that connects 16-character cards to their
 * owning customer and account.
 *
 * <p><strong>Replaces VSAM cluster:</strong>
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} (defined in
 * {@code app/jcl/XREFFILE.jcl} with {@code KEYS(16 0)} &mdash; 16-byte
 * primary key {@code XREF-CARD-NUM} at offset 0;
 * {@code RECORDSIZE(50 50)}; {@code INDEXED}) plus its alternate index
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} declared with
 * {@code KEYS(11 25) NONUNIQUEKEY UPGRADE} (the 11-byte
 * {@code XREF-ACCT-ID} alternate key at offset 25, marked
 * non-unique because one account may own multiple cards).</p>
 *
 * <p>The COBOL access patterns are:
 * <ul>
 *   <li>random read by 16-character {@code XREF-CARD-NUM}
 *       ({@code app/cbl/COTRN02C.cbl} &mdash; transaction-add card
 *       validation; {@code app/cbl/CBTRN02C.cbl} &mdash; batch
 *       transaction-posting card lookup);</li>
 *   <li>alternate-key browse by 11-byte {@code XREF-ACCT-ID} via the
 *       NONUNIQUEKEY AIX/PATH chain ({@code app/cbl/COACTVWC.cbl}
 *       &mdash; account view aggregates ACCT + CUST + XREF;
 *       {@code app/cbl/CBACT03C.cbl} &mdash; batch XREF reader).</li>
 * </ul>
 * The PostgreSQL B-tree on the primary key satisfies the random-read
 * pattern; the non-unique secondary index
 * {@code idx_cardxref_acct_id} declared by V004 replaces the
 * VSAM AIX/PATH chain and supports the
 * {@link #findByXrefAcctId(Long)} derived query at O(log n) lookup
 * cost plus O(k) read cost for the {@code k} matching rows.</p>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVACT03Y.cpy}
 * ({@code CARD-XREF-RECORD}, RECLN 50): 16-byte
 * {@code XREF-CARD-NUM PIC X(16)} primary key, 9-byte
 * {@code XREF-CUST-ID PIC 9(09)}, 11-byte
 * {@code XREF-ACCT-ID PIC 9(11)} alternate key, plus a 14-byte trailing
 * {@code FILLER} that has no relational counterpart.</p>
 *
 * <p><strong>Schema:</strong> Flyway migration
 * {@code src/main/resources/db/migration/V004__create_cardxref.sql}
 * creates the {@code card_xref} table. Hibernate
 * {@code spring.jpa.hibernate.ddl-auto: validate} verifies that the
 * {@link CardCrossReference} mapping aligns with the V004 DDL at
 * application startup.</p>
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
 * {@code xrefCardNum} value directly; use the {@code Card.maskCardNumber()}
 * helper for any log line that references the cardholder data. The
 * repository emits no SQL trace logs in production profiles per
 * {@code application.yml} (the {@code org.hibernate.SQL} logger is set
 * to {@code WARN} or higher outside the {@code local} profile).
 * Encryption at rest is delegated to RDS via the customer-managed KMS
 * key; encryption in transit is enforced by the
 * {@code rds.force_ssl=1} parameter.</p>
 *
 * <h2>Consumers (Java services replacing the COBOL programs)</h2>
 * <ul>
 *   <li>{@code AccountViewService} ({@code COACTVWC}) &mdash;
 *       cache-aside read: when the operator requests an account-view
 *       screen with an account ID, the service uses
 *       {@link #findByXrefAcctId(Long)} to enumerate every card that
 *       belongs to that account (NONUNIQUEKEY semantics) before
 *       joining with {@code Account} and {@code Customer}.</li>
 *   <li>{@code TransactionAddService} ({@code COTRN02C}) &mdash;
 *       validates a caller-supplied 16-character card number via
 *       {@link #findById(Object)}; miss surfaces as
 *       {@code RecordNotFoundException} (COBOL {@code FILE STATUS 23}).
 *       The resolved {@code xrefAcctId} is used as the Kafka partition
 *       key for the {@code transaction.posted} MSK event (AAP
 *       &sect;0.6.5).</li>
 *   <li>{@code BillPaymentService} ({@code COBIL00C}) &mdash; same
 *       random-read pattern as {@code TransactionAddService}; surfaces
 *       the owning account ID for MSK partitioning.</li>
 *   <li>{@code TransactionPostingService} ({@code CBTRN02C}) &mdash;
 *       4-stage validation cascade Stage 1 (XREF lookup). A miss yields
 *       reject code {@code 100} ('INVALID CARD NUMBER FOUND') per AAP
 *       &sect;0.4.1; preserved verbatim from the COBOL source.</li>
 *   <li>{@code XrefFileReaderService} ({@code CBACT03C}) &mdash;
 *       sequential batch scan; iterates via
 *       {@link JpaRepository#findAll()} or, for large datasets, a
 *       paged scan.</li>
 *   <li>{@code TransactionReportService} ({@code CBTRN03C}) &mdash;
 *       joins {@code transactions} with {@code card_xref} during
 *       date-windowed report generation to surface account &mdash;
 *       customer linkage on each line.</li>
 * </ul>
 *
 * <h2>Exception translation</h2>
 *
 * <p>The {@link Repository &#64;Repository} stereotype enables Spring's
 * persistence exception translation. {@code GlobalExceptionHandler}
 * then maps:
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
 * framework types.</p>
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
     * {@code xrefAcctId} matches the supplied account ID &mdash; the JPA
     * replacement for the COBOL VSAM
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} NONUNIQUEKEY alternate
     * index browse used by {@code app/cbl/COACTVWC.cbl} (account-view
     * card enumeration) and the batch posting cascade in
     * {@code app/cbl/CBTRN02C.cbl} (transaction validation Stage 1 when
     * the inbound record carries an account ID rather than a card
     * number).
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
     * <p><strong>Cardinality contract:</strong> because the alternate
     * key is declared {@code NONUNIQUEKEY UPGRADE} in the COBOL source,
     * this method MAY return more than one row, exactly one row, or
     * zero rows. Callers MUST NOT assume single-row semantics.</p>
     *
     * <p><strong>Ordering:</strong> the method name does <em>not</em>
     * carry an explicit {@code OrderBy...} clause, so the returned
     * order is unspecified (PostgreSQL may return rows in either index
     * order or heap order depending on the planner choice). Callers
     * that require deterministic ordering MUST sort the result list
     * client-side or use a {@code Sort} parameter.</p>
     *
     * @param acctId the {@code xrefAcctId} alternate-key value (the
     *               11-digit {@code Account.acctId}); never
     *               {@code null}
     * @return the matching cross-reference rows; never {@code null}
     *         (empty list if the account owns no cards)
     */
    List<CardCrossReference> findByXrefAcctId(Long acctId);

    /**
     * Returns the first cross-reference row whose alternate key
     * {@code xrefAcctId} matches the supplied account ID &mdash;
     * convenience helper for callers that need any one card linked to
     * the account (e.g., the {@code BillPaymentService}'s "first card"
     * default-selection logic when the operator has not specified a
     * card explicitly).
     *
     * <p>Spring Data JPA translates this derived method name into:</p>
     * <pre>
     *     SELECT cx FROM CardCrossReference cx
     *      WHERE cx.xrefAcctId = :acctId
     *      ORDER BY cx.xrefCardNum ASC
     *      LIMIT 1
     * </pre>
     *
     * @param acctId the {@code xrefAcctId} alternate-key value; never
     *               {@code null}
     * @return an {@link Optional} containing the first matching row in
     *         ascending {@code xrefCardNum} order, or
     *         {@link Optional#empty()} if the account owns no cards
     */
    Optional<CardCrossReference> findFirstByXrefAcctIdOrderByXrefCardNumAsc(Long acctId);
}
