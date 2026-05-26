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

import com.awsm2.carddemo.domain.DailyTransaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link DailyTransaction} staging entity.
 *
 * <p><strong>Replaces:</strong> Sequential physical-sequential (PS) file
 * {@code AWS.M2.CARDDEMO.DALYTRAN.PS} (consumed by
 * {@code app/cbl/CBTRN01C.cbl} and {@code app/cbl/CBTRN02C.cbl}). In the Java
 * target, incoming daily transactions are staged in the {@code daily_transactions}
 * table (Flyway migration {@code V011__create_daily_transaction.sql}) before
 * being validated, posted, and copied to the canonical {@code transactions}
 * table (V005).</p>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVTRA06Y.cpy}
 * ({@code DALYTRAN-RECORD}, RECLN 350) &mdash; identical record layout to
 * {@code TRAN-RECORD} ({@code CVTRA05Y.cpy}) but with the {@code DALYTRAN-}
 * field-name prefix substituted for {@code TRAN-}. The trailing
 * {@code FILLER PIC X(20)} byte-padding is omitted in the relational model
 * (per AAP &sect;0.4.1: relational layouts have no positional padding).</p>
 *
 * <p><strong>Source consumers (REFERENCE &mdash; never modified):</strong></p>
 * <ul>
 *   <li>{@code app/cbl/CBTRN01C.cbl} &mdash; sequential reader / dump utility
 *       (COBOL {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN
 *       ORGANIZATION IS SEQUENTIAL ACCESS MODE IS SEQUENTIAL}). The COBOL
 *       {@code OPEN INPUT DALYTRAN-FILE} + {@code READ DALYTRAN-FILE NEXT}
 *       loop is replaced in the Java target by Spring Batch
 *       {@code RepositoryItemReader<DailyTransaction>} consuming
 *       {@link #findAll(Pageable)} in chunked fashion.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} &mdash; transaction-posting cascade.
 *       The COBOL paragraph that reads each {@code DALYTRAN-RECORD} and
 *       runs the 4-stage validation cascade (XREF lookup, Account lookup,
 *       Credit-limit check, Card-expiration check &mdash; preserving reject
 *       codes 100&ndash;109) is replaced in the Java target by
 *       {@code DailyTransactionPostingJob} (Spring Batch) +
 *       {@code TransactionPostingService} (per AAP &sect;0.4.1).</li>
 *   <li>{@code app/jcl/POSTTRAN.jcl} &mdash; DD allocation
 *       ({@code DALYTRAN DD DSN=AWS.M2.CARDDEMO.DALYTRAN.PS} on
 *       {@code STEP15 EXEC PGM=CBTRN02C}). Replaced operationally by
 *       AWS Step Functions task state invoking the Spring Batch job via
 *       AWS Batch (per AAP &sect;0.4.1, {@code stepfunctions/eod-batch-pipeline.asl.json}).</li>
 * </ul>
 *
 * <h2>Staging-Table Semantics</h2>
 *
 * <p>This is a <strong>staging</strong> table: rows are loaded
 * <em>unvalidated</em> (by S3-to-RDS Glue jobs or a Spring Batch loader),
 * and the Java {@code TransactionPostingService} iterates them through a
 * 4-stage validation cascade (XREF / Account / Credit limit / Card expiration
 * &mdash; preserving reject codes 100&ndash;109 from {@code CBTRN02C.cbl}).
 * The cascade lives in the service layer, not in this repository
 * (AAP &sect;0.4.1, &sect;0.7.1 layered architecture). On success, validated
 * rows are appended to the canonical {@code transactions} journal (V005);
 * on failure, rejection records are emitted to S3 via {@code S3OutputService}
 * preserving the COBOL reject-code semantics.</p>
 *
 * <p>Notable schema invariants (declared in V011):</p>
 * <ul>
 *   <li><strong>No foreign keys</strong> on {@code dalytran_card_num},
 *       {@code dalytran_type_cd}, or {@code dalytran_cat_cd} &mdash;
 *       intentionally omitted at the DB layer because (a) bulk-load order
 *       is non-deterministic, (b) validation is enforced in the Java service
 *       layer to preserve reject codes 100&ndash;109, (c) malformed/orphan
 *       records must be captured for replay rather than silently dropped by
 *       a constraint violation, and (d) throughput. Validation lives in
 *       {@code TransactionPostingService}, not in this repository.</li>
 *   <li><strong>No {@code @Version} optimistic locking</strong> &mdash;
 *       staging rows are append-only from the ingestion side and consumed
 *       at most once by the posting job; there is no read-modify-write
 *       contention to guard against.</li>
 *   <li><strong>Append-only ingestion</strong> &mdash; rows arrive via
 *       {@link #save(Object)} or {@link #saveAll(Iterable)} and are
 *       cleaned up at end-of-day via {@link #deleteAll()} after successful
 *       promotion to the {@code transactions} journal.</li>
 * </ul>
 *
 * <h2>Indexes</h2>
 *
 * <p>The {@code idx_daily_transactions_card_num} index (declared in
 * {@code V011__create_daily_transaction.sql}) on the
 * {@code dalytran_card_num} column supports the
 * {@link #findByDalytranCardNum(String)} derived query used for per-card
 * validation lookups. Without this index, the batch validator would
 * full-scan {@code daily_transactions} on every join probe, degrading the
 * end-of-day batch SLA. This index mirrors the alternate-index pattern of
 * the source VSAM cluster {@code TRANSACT.VSAM.AIX}
 * (per AAP &sect;0.6.2, AIX/PATH chains become PostgreSQL secondary
 * indexes).</p>
 *
 * <h2>Primary Consumers (Java)</h2>
 *
 * <ul>
 *   <li>{@code DailyTransactionPostingJob} &mdash; Spring Batch job that
 *       reads staging rows via {@link #findAll(Pageable)}, runs the 4-stage
 *       validation cascade, posts validated rows to the {@code transactions}
 *       journal, updates {@code accounts}/{@code tran_cat_bal}, and
 *       publishes {@code transaction.posted} events to Amazon MSK keyed by
 *       account ID. Replacement for the COBOL {@code CBTRN02C.cbl} flow.</li>
 *   <li>{@code TransactionPostingService} &mdash; per-card lookups during
 *       reconciliation and validation diagnostics via
 *       {@link #findByDalytranCardNum(String)}. Replacement for the COBOL
 *       per-card branches in {@code CBTRN01C.cbl} / {@code CBTRN02C.cbl}.</li>
 *   <li>Staging-ingestion loaders (Spring Batch S3-to-RDS reader or AWS Glue
 *       Spark job) populate the table via {@link #saveAll(Iterable)} (per
 *       AAP &sect;0.4.1, {@code infrastructure/terraform/glue.tf}).</li>
 * </ul>
 *
 * <h2>Exception Translation</h2>
 *
 * <p>The {@link Repository @Repository} stereotype annotation enables
 * Spring's {@code PersistenceExceptionTranslationPostProcessor} to convert
 * JPA-specific exceptions (e.g., {@code jakarta.persistence.EntityNotFoundException},
 * {@code jakarta.persistence.OptimisticLockException}) into Spring's
 * unchecked {@code DataAccessException} hierarchy. The
 * {@code GlobalExceptionHandler} (@RestControllerAdvice) further translates
 * these into typed domain exceptions (e.g., {@code RecordNotFoundException},
 * {@code DuplicateRecordException}) and standardized HTTP status responses
 * per AAP &sect;0.7.1 refactor discipline.</p>
 *
 * <h2>Key/Type Contract</h2>
 *
 * <p>The generic type parameter {@code String} matches
 * {@code DailyTransaction.dalytranId} which is a {@code VARCHAR(16)}
 * preserving the COBOL {@code DALYTRAN-ID PIC X(16)} 16-character
 * alphanumeric identifier (the same value space as
 * {@code transactions.tran_id} so that a staged row may be promoted to
 * the journal with the same key).</p>
 *
 * @see com.awsm2.carddemo.domain.DailyTransaction
 *      the JPA entity mapped to the {@code daily_transactions} staging table
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {

    /**
     * Returns all staged daily-transaction rows for a given card number, in
     * unspecified order.
     *
     * <p>Backed by the {@code idx_daily_transactions_card_num} index
     * (declared in {@code V011__create_daily_transaction.sql}). Spring
     * Data JPA derives the SQL from the method name at startup, introspecting
     * the {@code dalytranCardNum} field on {@link DailyTransaction} and
     * generating {@code SELECT * FROM daily_transactions WHERE
     * dalytran_card_num = ?}.</p>
     *
     * <p><strong>Primary use cases:</strong></p>
     * <ul>
     *   <li>{@code TransactionPostingService} per-card validation diagnostics
     *       during the 4-stage cascade (XREF / Account / Credit limit / Card
     *       expiration &mdash; preserving reject codes 100&ndash;109 from
     *       {@code app/cbl/CBTRN02C.cbl}).</li>
     *   <li>End-of-day reconciliation: confirming all staged rows for a
     *       given card were posted (no orphaned rejects) before
     *       {@link #deleteAll()} is invoked to clear the staging table.</li>
     * </ul>
     *
     * <p><strong>Cardinality:</strong> the staging-table card-number column
     * is non-unique &mdash; multiple daily transactions may exist for the
     * same card on a given posting day &mdash; so this method returns
     * {@link List}, not a single entity. Returns an empty list (never
     * {@code null}) when no rows match.</p>
     *
     * <p><strong>PCI-DSS handling (AAP &sect;0.6.6):</strong> the
     * {@code dalytranCardNum} argument is a 16-digit Primary Account Number
     * (PAN). Callers MUST NOT log the raw PAN; the
     * {@link DailyTransaction#toString()} implementation masks all but the
     * last 4 digits, and Logback filters monitor for accidental PAN-like
     * sequences in application logs.</p>
     *
     * @param dalytranCardNum the 16-character card number (PAN) to look up;
     *                        must be non-{@code null}
     * @return all staged transactions for the given card, in unspecified
     *         order; an empty list (never {@code null}) when no rows match
     */
    List<DailyTransaction> findByDalytranCardNum(String dalytranCardNum);
}
