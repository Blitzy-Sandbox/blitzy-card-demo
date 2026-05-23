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

import com.awsm2.carddemo.domain.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link Card} entity &mdash; the
 * cardholder-card aggregate that powers the CardDemo card-detail, card-list,
 * and card-update flows.
 *
 * <p><strong>Replaces VSAM cluster:</strong>
 * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} (defined in
 * {@code app/jcl/CARDFILE.jcl} with {@code KEYS(16 0)} &mdash; 16-byte
 * primary key {@code CARD-NUM} at offset 0; {@code RECORDSIZE(150 150)};
 * {@code INDEXED}). The COBOL access patterns are:
 * <ul>
 *   <li>random read by {@code CARD-NUM} ({@code app/cbl/COCRDSLC.cbl}
 *       &mdash; card detail screen);</li>
 *   <li>random read + {@code REWRITE} ({@code app/cbl/COCRDUPC.cbl}
 *       &mdash; card update with before/after image comparison);</li>
 *   <li>sequential scan ({@code app/cbl/CBACT02C.cbl} &mdash; batch
 *       card-file reader);</li>
 *   <li>{@code STARTBR}/{@code READNEXT} paged browse for the
 *       {@code COCRDLI.bms} 7-row-per-page list screen
 *       ({@code app/cbl/COCRDLIC.cbl}).</li>
 * </ul>
 * The PostgreSQL B-tree on the primary key satisfies the first three
 * patterns; the paged browse is provided by the inherited
 * {@link JpaRepository#findAll(Pageable)} as well as the
 * derived query {@link #findByCardAcctIdOrderByCardNumAsc(Long, Pageable)}
 * for the dominant per-account paging access pattern.</p>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVACT02Y.cpy}
 * ({@code CARD-RECORD}, RECLN 150; 6 business fields plus trailing
 * {@code FILLER}).</p>
 *
 * <p><strong>Schema:</strong> Flyway migration
 * {@code src/main/resources/db/migration/V002__create_card.sql} creates the
 * {@code cards} table. Hibernate
 * {@code spring.jpa.hibernate.ddl-auto: validate} verifies that the
 * {@link Card} mapping aligns with the V002 DDL at application startup.</p>
 *
 * <h2>PCI-DSS Cardholder Data Scope (AAP &sect;0.6.6)</h2>
 *
 * <p>This repository operates over the PAN (Primary Account Number) primary
 * key. All callers <strong>MUST NOT</strong> log the {@code cardNum} value
 * directly; instead, the {@link Card#maskCardNumber()} helper produces the
 * PCI-DSS v4.0 Requirement 3.4.1 compliant
 * {@code ****-****-****-1234} format which is safe for logging. The
 * repository itself emits no SQL trace logs in production profiles per
 * {@code application.yml} (the {@code org.hibernate.SQL} logger is set to
 * {@code WARN} or higher outside the {@code local} profile). Encryption at
 * rest is delegated to RDS via the customer-managed KMS key configured in
 * {@code infrastructure/terraform/rds.tf}; encryption in transit is enforced
 * by the {@code rds.force_ssl=1} parameter.</p>
 *
 * <h2>Optimistic Locking (AAP &sect;0.6.2 / &sect;0.7.1)</h2>
 *
 * <p>The {@link Card} entity carries a JPA
 * {@link jakarta.persistence.Version &#64;Version} field
 * ({@code version BIGINT}) per AAP &sect;0.4.1 (Card is one of two entities
 * with optimistic locking; the other is Account). On
 * {@link JpaRepository#save(Object) save(...)}, Hibernate:
 * <ol>
 *   <li>Auto-increments the {@code version} column on the in-memory entity.</li>
 *   <li>Issues an {@code UPDATE} whose {@code WHERE} clause includes the
 *       previously-loaded {@code version} value.</li>
 *   <li>If another transaction has committed in the interim, the
 *       {@code WHERE} clause matches 0 rows; Hibernate throws
 *       {@link org.hibernate.StaleObjectStateException} which Spring's
 *       persistence exception translation wraps as
 *       {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
 *       around {@link jakarta.persistence.OptimisticLockException}.</li>
 *   <li>The {@code GlobalExceptionHandler}
 *       ({@code @RestControllerAdvice}) maps this to the domain
 *       {@code ConcurrentModificationException} and returns HTTP 409 Conflict
 *       to the REST caller, preserving the COBOL "snapshot mismatch" error
 *       semantics from {@code app/cbl/COCRDUPC.cbl}.</li>
 * </ol>
 *
 * <p>This {@code @Version} mechanism is the JPA-idiomatic replacement for
 * the COBOL before/after image comparison in {@code app/cbl/COCRDUPC.cbl}
 * (CICS {@code READ UPDATE} &rarr; {@code SYNCPOINT} &rarr;
 * {@code REWRITE}, with manual snapshot comparison). Future developers MUST
 * NOT attempt to "re-implement" optimistic locking via application-layer
 * snapshot checks &mdash; that is exactly what {@code @Version} replaces.</p>
 *
 * <h2>Consumers (Java services replacing the COBOL programs)</h2>
 * <ul>
 *   <li>{@code CardDetailService} ({@code COCRDSLC}) &mdash; random read
 *       by 16-character {@code cardNum} via {@link #findById(Object)};
 *       miss surfaces as {@code RecordNotFoundException}
 *       (COBOL {@code FILE STATUS 23}).</li>
 *   <li>{@code CardListService} ({@code COCRDLIC}) &mdash; paginated
 *       browse for the {@code COCRDLI.bms} 7-row-per-page list, via
 *       {@link #findByCardAcctIdOrderByCardNumAsc(Long, Pageable)} when
 *       filtering by account, or {@link #findAll(Pageable)} when the
 *       caller requests an unfiltered global list.</li>
 *   <li>{@code CardUpdateService} ({@code COCRDUPC}) &mdash; random read +
 *       {@code @Version}-protected save; preserves the COBOL
 *       {@code EXEC CICS READ UPDATE ... REWRITE} contract via JPA
 *       optimistic locking.</li>
 *   <li>{@code TransactionPostingService} ({@code CBTRN02C}) &mdash; card-
 *       expiration check during the 4-stage validation cascade; reads via
 *       {@link #findById(Object)} and inspects
 *       {@link Card#getCardExpirationDate()} (reject code 103 if
 *       expired).</li>
 *   <li>{@code CardFileReaderService} ({@code CBACT02C}) &mdash; sequential
 *       batch scan via {@link JpaRepository#findAll()} or a paged
 *       {@link #findAll(Pageable)} (avoid loading 1M+ rows into memory).</li>
 * </ul>
 *
 * <h2>Exception translation</h2>
 *
 * <p>The {@link Repository &#64;Repository} stereotype enables Spring's
 * {@code PersistenceExceptionTranslationPostProcessor} to convert JPA-
 * specific exceptions into Spring's unchecked {@code DataAccessException}
 * hierarchy. {@code GlobalExceptionHandler} then translates them into the
 * typed CardDemo domain-exception hierarchy:
 * <ul>
 *   <li>{@code RecordNotFoundException} &rarr; HTTP 404 (COBOL FILE STATUS
 *       23 NOTFND);</li>
 *   <li>{@code DuplicateRecordException} &rarr; HTTP 409 (COBOL FILE STATUS
 *       22 DUPKEY);</li>
 *   <li>{@code ConcurrentModificationException} &rarr; HTTP 409 (JPA
 *       optimistic-lock conflict from {@code @Version}).</li>
 * </ul>
 *
 * <h2>No-AWS-SDK isolation (AAP &sect;0.7.1)</h2>
 *
 * <p>This repository contains no AWS SDK client wiring. All AWS service
 * integrations (Secrets Manager for the JDBC password, KMS for
 * encryption-at-rest, CloudWatch for metrics) are isolated in the
 * {@code com.awsm2.carddemo.adapter} package. The repository is pure
 * Spring Data JPA and depends only on the {@link Card} domain entity and
 * standard Spring framework types.</p>
 *
 * @see com.awsm2.carddemo.domain.Card
 *      the JPA entity mapped to the {@code cards} table
 * @see com.awsm2.carddemo.repository.CardCrossReferenceRepository
 *      the cross-reference repository used to traverse from card &rarr;
 *      account &rarr; customer for cardholder lookups
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Paged, primary-key-ordered scan of all cards owned by a specific
     * account &mdash; the JPA replacement for the CICS
     * {@code STARTBR}/{@code READNEXT} keyed browse loop in
     * {@code app/cbl/COCRDLIC.cbl} when the operator has supplied an
     * account filter on the {@code COCRDLI.bms} screen.
     *
     * <p>Spring Data JPA translates this derived method name into the
     * JPQL query:</p>
     * <pre>
     *     SELECT c FROM Card c
     *      WHERE c.cardAcctId = :acctId
     *      ORDER BY c.cardNum ASC
     * </pre>
     * <p>which, executed against PostgreSQL, uses the
     * {@code idx_cards_card_acct_id} secondary index declared by V002 to
     * locate the matching rows, then returns up to
     * {@code pageable.getPageSize()} rows.</p>
     *
     * <p><strong>Ordering invariant:</strong> the method name ends in
     * {@code OrderByCardNumAsc}, which forces Spring Data to emit
     * {@code ORDER BY c.cardNum ASC} regardless of any {@code Sort}
     * embedded in the {@link Pageable}. This matches the deterministic
     * ascending-key traversal semantics of the CICS browse loop and
     * supports the {@code COCRDLI.bms} list pagination footer
     * ("Page n of m"); page size of 7 is the COCRDLI.bms display default
     * per the {@code CARD-ROW OCCURS 7 TIMES} working-storage array in
     * {@code COCRDLIC.cbl}.</p>
     *
     * @param acctId   the owning {@code Account.acctId} (11-digit
     *                 unsigned) to filter against; never {@code null}
     * @param pageable Spring Data paging request supplying
     *                 {@code pageNumber} and {@code pageSize}; never
     *                 {@code null}
     * @return a {@link Page} envelope containing the matching cards on
     *         the current page plus total row and page counts; never
     *         {@code null} (empty page if no cards exist for the
     *         account)
     */
    Page<Card> findByCardAcctIdOrderByCardNumAsc(Long acctId, Pageable pageable);

    /**
     * Unfiltered eager list of all cards owned by a specific account
     * &mdash; used by services that need every card for an account in
     * one read (e.g., for issuing per-card statement listings).
     *
     * <p>Spring Data JPA translates this into:</p>
     * <pre>
     *     SELECT c FROM Card c
     *      WHERE c.cardAcctId = :acctId
     * </pre>
     *
     * <p>Use {@link #findByCardAcctIdOrderByCardNumAsc(Long, Pageable)}
     * for paginated UI flows; use this method only when the full card
     * list per account is required and the row count is known to be
     * bounded.</p>
     *
     * @param acctId the owning {@code Account.acctId} (11-digit
     *               unsigned) to filter against; never {@code null}
     * @return the matching cards in unspecified order; never
     *         {@code null} (empty list if no cards exist)
     */
    List<Card> findByCardAcctId(Long acctId);
}
