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

import com.awsm2.carddemo.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Customer} entity &mdash; the
 * type-safe data-access port for the master customer profile aggregate in
 * the CardDemo Java target.
 *
 * <p><strong>Replaces VSAM cluster:</strong>
 * {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS} (defined in
 * {@code app/jcl/CUSTFILE.jcl}:L43&ndash;L59 with
 * {@code KEYS(9 0)}, {@code RECORDSIZE(500 500)}, {@code SHAREOPTIONS(2 3)},
 * {@code INDEXED} &mdash; 9-byte primary key {@code CUST-ID} at relative
 * key position 0). The relational target is the {@code customers} table
 * created by Flyway migration {@code V003__create_customer.sql} (per AAP
 * &sect;0.4.1, &sect;0.6.2). The COBOL VSAM {@code READ}/{@code WRITE}/
 * {@code REWRITE}/{@code DELETE} verbs and the CICS {@code STARTBR}/
 * {@code READNEXT}/{@code READPREV} browse loops on
 * {@code DATASET('CUSTDATA')} are replaced by the inherited Spring Data
 * JPA methods documented in the &quot;Inherited Method Inventory&quot;
 * section below; no custom derived queries or {@code @Query} annotations
 * are required because every consumer access pattern is satisfied by
 * primary-key lookup or full-table iteration.</p>
 *
 * <h2>Source copybooks (REFERENCE &mdash; never modified)</h2>
 *
 * <p>The {@code CUSTOMER-RECORD} layout is defined identically in two
 * copybooks, both 500 bytes:</p>
 *
 * <ul>
 *   <li><strong>{@code app/cpy/CVCUS01Y.cpy}</strong> &mdash; the
 *       authoritative layout used throughout this refactor. Contains
 *       18 non-{@code FILLER} fields plus a trailing {@code FILLER
 *       PIC X(168)} (offset 332&ndash;499). The relational target
 *       maps every non-{@code FILLER} field to a column and omits the
 *       trailing padding per AAP &sect;0.4.1 (relational layouts have
 *       no positional padding).</li>
 *   <li><strong>{@code app/cpy/CUSTREC.cpy}</strong> &mdash; identical
 *       500-byte layout to {@code CVCUS01Y}; the only textual
 *       difference is the date-of-birth field name
 *       ({@code CUST-DOB-YYYY-MM-DD} in {@code CVCUS01Y.cpy} vs
 *       {@code CUST-DOB-YYYYMMDD} in {@code CUSTREC.cpy}). The
 *       PostgreSQL schema follows the cleaner {@code CVCUS01Y}
 *       naming convention.</li>
 * </ul>
 *
 * <p>The 18 non-{@code FILLER} fields are: {@code CUST-ID PIC 9(09)},
 * three name fields ({@code CUST-FIRST-NAME}, {@code CUST-MIDDLE-NAME},
 * {@code CUST-LAST-NAME} each {@code PIC X(25)}), three address-line
 * fields ({@code CUST-ADDR-LINE-1}&ndash;{@code 3} each
 * {@code PIC X(50)}), {@code CUST-ADDR-STATE-CD PIC X(02)},
 * {@code CUST-ADDR-COUNTRY-CD PIC X(03)},
 * {@code CUST-ADDR-ZIP PIC X(10)}, two phone numbers
 * ({@code CUST-PHONE-NUM-1}, {@code CUST-PHONE-NUM-2} each
 * {@code PIC X(15)}), {@code CUST-SSN PIC 9(09)},
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)}, the date-of-birth field
 * ({@code PIC X(10)}), {@code CUST-EFT-ACCOUNT-ID PIC X(10)},
 * {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}, and
 * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.</p>
 *
 * <h2>PII / PCI-DSS Handling (AAP &sect;0.6.6, &sect;0.7.1)</h2>
 *
 * <p>The {@code customers} table stores Personally Identifying
 * Information (PII), including (in order of sensitivity):</p>
 * <ul>
 *   <li>{@code cust_ssn} &mdash; Social Security Number
 *       ({@code PIC 9(09)}); the single most sensitive PII column
 *       in the entire CardDemo schema.</li>
 *   <li>{@code cust_govt_issued_id} &mdash; government-issued
 *       identifier (driver's license, passport, state-ID number).</li>
 *   <li>{@code cust_dob_yyyy_mm_dd} &mdash; date of birth.</li>
 *   <li>{@code cust_fico_credit_score} &mdash; FICO credit score.</li>
 *   <li>Full name (first / middle / last), address (three lines + state
 *       + country + ZIP), and two phone numbers.</li>
 * </ul>
 *
 * <p>PCI-DSS / PII protection is enforced in four layers (per AAP
 * &sect;0.6.6 &mdash; Cross-Cutting: Audit, Observability, and
 * PCI-DSS):</p>
 * <ol>
 *   <li><strong>Encryption at rest</strong> &mdash; RDS storage is
 *       encrypted with a customer-managed AWS KMS key (CMK)
 *       provisioned in {@code infrastructure/terraform/kms.tf} and
 *       referenced by {@code infrastructure/terraform/rds.tf}. This
 *       repository is unaware of the cipher; encryption is a
 *       storage-layer concern transparent to JDBC.</li>
 *   <li><strong>Encryption in transit</strong> &mdash; JDBC
 *       connections require TLS 1.2+ via the {@code rds.force_ssl=1}
 *       RDS parameter group setting; the {@code sslmode=require} JDBC
 *       URL parameter is set in {@code application-prod.yml}.</li>
 *   <li><strong>Log masking</strong> &mdash; the {@link Customer}
 *       entity's {@code toString()} method masks {@code custSsn} to
 *       its last 4 digits, masks {@code custGovtIssuedId} to a prefix
 *       marker, and omits phone numbers and full date of birth so
 *       that stray {@code log.info(customer)} calls cannot leak PII
 *       into CloudWatch Logs or OpenSearch.</li>
 *   <li><strong>Continuous monitoring</strong> &mdash; Amazon Macie
 *       continuously scans S3 exports and Glue ETL outputs for
 *       accidental SSN exposure; any leak triggers a Macie finding
 *       and a CloudWatch alarm.</li>
 * </ol>
 *
 * <p><strong>Security-by-design decision &mdash; no PII-searching
 * methods:</strong> this repository deliberately exposes
 * <em>no</em> {@code findBySsn(...)}, no name-based lookups
 * (e.g., {@code findByCustFirstNameAndCustLastName}), no
 * phone-based lookups, and no DOB-based lookups. Such methods
 * would risk leaking sensitive data into SQL query strings, query
 * plans, slow-query logs, RDS Performance Insights captures, and
 * application logs &mdash; all of which are common PCI-DSS audit
 * findings. Any business need to cross-reference a customer by
 * PII attributes (for example, fraud investigation) MUST be
 * implemented in the service layer with explicit auditing
 * ({@code AuditLogService}) and explicit access-control checks.
 * Future contributors MUST NOT add such methods to this repository
 * without an explicit security review and an updated PCI-DSS
 * compliance attestation.</p>
 *
 * <h2>Consumers (REFERENCE COBOL programs)</h2>
 *
 * <p>The Java services that depend on this repository each replace a
 * specific COBOL program preserved under {@code app/cbl/}:</p>
 * <ul>
 *   <li><strong>{@code AccountViewService}</strong> &mdash; replacement
 *       for {@code app/cbl/COACTVWC.cbl}; joins {@code Customer} onto
 *       {@code Account} through {@code CardCrossReference} for the
 *       account-inquiry screen ({@code app/bms/COACTVW.bms}). Uses
 *       {@link #findById(Object)} keyed by the customer ID obtained
 *       from the card cross-reference.</li>
 *   <li><strong>{@code AccountUpdateService}</strong> &mdash;
 *       replacement for {@code app/cbl/COACTUPC.cbl}; updates
 *       customer demographic columns alongside account columns within
 *       a single {@code @Transactional(rollbackFor = Exception.class)}
 *       boundary that maps to the COBOL {@code SYNCPOINT}/
 *       {@code SYNCPOINT ROLLBACK} pair. Uses
 *       {@link #findById(Object)} and {@link #save(Object)}, with
 *       optimistic locking handled by the {@link Customer} entity's
 *       {@code @Version} field (per AAP &sect;0.6.2 &mdash; replaces
 *       COBOL before/after image comparison).</li>
 *   <li><strong>{@code CustomerFileReaderService}</strong> &mdash;
 *       replacement for {@code app/cbl/CBCUS01C.cbl}; performs the
 *       full sequential scan of the customer file (COBOL
 *       {@code SELECT CUSTFILE-FILE ASSIGN TO CUSTFILE ORGANIZATION
 *       IS INDEXED ACCESS MODE IS SEQUENTIAL}). The COBOL
 *       {@code OPEN INPUT CUSTFILE-FILE} + {@code READ CUSTFILE-FILE
 *       NEXT} loop is replaced by Spring Batch
 *       {@code RepositoryItemReader<Customer>} consuming
 *       {@link #findAll()} or {@link #findAll(org.springframework.data.domain.Pageable)}
 *       in chunked fashion.</li>
 *   <li><strong>{@code StatementGenerationService}</strong> &mdash;
 *       replacement for {@code app/cbl/CBSTM03A.CBL} /
 *       {@code app/cbl/CBSTM03B.CBL}; reads the customer name and
 *       mailing address as the statement header. Uses
 *       {@link #findById(Object)}.</li>
 * </ul>
 *
 * <h2>Inherited Method Inventory (from {@link JpaRepository})</h2>
 *
 * <p>All consumer access patterns are satisfied by the methods
 * inherited from {@link JpaRepository} and its super-interfaces
 * ({@code PagingAndSortingRepository}, {@code CrudRepository},
 * {@code QueryByExampleExecutor}). No custom derived queries or
 * {@code @Query} annotations are declared in this interface. The
 * principal inherited methods used by CardDemo consumers are:</p>
 * <ul>
 *   <li>{@code findById(Long)} &mdash; replaces COBOL CICS
 *       {@code READ DATASET('CUSTDATA') RIDFLD(CUST-ID)
 *       INTO(CUSTOMER-RECORD)}; returns {@code Optional<Customer>}
 *       (empty when the row does not exist, replacing COBOL
 *       {@code FILE STATUS 23} NOTFND that the service layer maps
 *       to {@link com.awsm2.carddemo.exception.RecordNotFoundException}
 *       &rarr; HTTP 404).</li>
 *   <li>{@code findAll()} &mdash; full-table iteration used by
 *       {@code CustomerFileReaderService}; equivalent to the
 *       COBOL sequential scan in {@code CBCUS01C.cbl}.</li>
 *   <li>{@code findAll(Pageable)} &mdash; chunked iteration for
 *       Spring Batch readers (page size typically 100&ndash;1000 to
 *       balance memory footprint against round-trip count).</li>
 *   <li>{@code findAll(Sort)} &mdash; full-table iteration with
 *       explicit ordering (e.g., by {@code custId} ascending to
 *       mirror VSAM KSDS key sequence).</li>
 *   <li>{@code save(Customer)} &mdash; replaces COBOL CICS
 *       {@code WRITE DATASET('CUSTDATA') FROM(CUSTOMER-RECORD)} for
 *       inserts and {@code REWRITE DATASET('CUSTDATA') FROM(
 *       CUSTOMER-RECORD)} for updates (Spring Data JPA
 *       transparently distinguishes the two via the entity's
 *       {@code @Id} presence and {@code @Version}).</li>
 *   <li>{@code saveAll(Iterable<Customer>)} &mdash; bulk insert /
 *       update used by Glue ETL bulk-load jobs that populate the
 *       customers table from staged S3 fixtures during cutover.</li>
 *   <li>{@code saveAndFlush(Customer)} &mdash; eager-flush variant
 *       used when downstream logic in the same transaction must
 *       observe the {@code INSERT}/{@code UPDATE} immediately
 *       (e.g., a subsequent {@code findById} in
 *       {@code AccountUpdateService}).</li>
 *   <li>{@code existsById(Long)} &mdash; lightweight existence
 *       check used for referential validation before a foreign-key
 *       insert (for example, validating {@code xref_cust_id}
 *       before inserting a {@code CardCrossReference}).</li>
 *   <li>{@code deleteById(Long)} &mdash; replaces COBOL CICS
 *       {@code DELETE DATASET('CUSTDATA') RIDFLD(CUST-ID)};
 *       supports operational cleanup and (rare) customer record
 *       purges. Note that the CardDemo schema currently retains
 *       customer records indefinitely for regulatory audit
 *       purposes.</li>
 *   <li>{@code delete(Customer)} &mdash; entity-level delete
 *       variant of {@code deleteById}.</li>
 *   <li>{@code deleteAll()} &mdash; bulk delete used exclusively
 *       in integration tests (Testcontainers PostgreSQL); never
 *       invoked in production code paths.</li>
 *   <li>{@code count()} &mdash; total row count; used by
 *       operational dashboards and by Spring Batch step-completion
 *       progress reporting.</li>
 *   <li>{@code getReferenceById(Long)} &mdash; lazy proxy fetch
 *       used when the service layer needs the customer as a
 *       foreign-key reference without materialising the full
 *       record (avoids an unnecessary {@code SELECT} when only
 *       the FK association is required, e.g., when assigning a
 *       customer to a new {@code CardCrossReference}).</li>
 *   <li>{@code flush()} &mdash; forces pending JPA changes to the
 *       JDBC layer; used in batch jobs that need deterministic
 *       checkpoint boundaries.</li>
 * </ul>
 *
 * <h2>Exception Translation (AAP &sect;0.7.1)</h2>
 *
 * <p>The {@link Repository &#64;Repository} annotation enables Spring's
 * persistence-exception-translation post-processor, which transforms
 * raw JPA / Hibernate exceptions into Spring's
 * {@code DataAccessException} hierarchy. The
 * {@code com.awsm2.carddemo.exception.GlobalExceptionHandler}
 * {@code @RestControllerAdvice} then maps those translated exceptions
 * to the CardDemo exception hierarchy and corresponding HTTP status
 * codes:</p>
 * <ul>
 *   <li>JPA {@code OptimisticLockException} &rarr;
 *       {@code ObjectOptimisticLockingFailureException} &rarr;
 *       {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
 *       &rarr; HTTP 409 Conflict (replaces COBOL snapshot-mismatch
 *       handling in {@code COACTUPC.cbl}).</li>
 *   <li>{@code DataIntegrityViolationException} on duplicate primary
 *       key &rarr;
 *       {@link com.awsm2.carddemo.exception.DuplicateRecordException}
 *       &rarr; HTTP 409 Conflict (replaces COBOL
 *       {@code FILE STATUS 22} DUPKEY handling).</li>
 *   <li>{@code EmptyResultDataAccessException} on
 *       {@code findById(...).orElseThrow(...)} &rarr;
 *       {@link com.awsm2.carddemo.exception.RecordNotFoundException}
 *       &rarr; HTTP 404 Not Found (replaces COBOL
 *       {@code FILE STATUS 23} NOTFND handling).</li>
 * </ul>
 *
 * <h2>Refactor Discipline (AAP &sect;0.7.3)</h2>
 *
 * <p>Per the Minimal Change Clause:</p>
 * <ul>
 *   <li>This repository contains <strong>no</strong> business logic;
 *       all behaviour beyond CRUD is implemented in the corresponding
 *       service classes ({@code AccountViewService},
 *       {@code AccountUpdateService},
 *       {@code CustomerFileReaderService},
 *       {@code StatementGenerationService}).</li>
 *   <li>This repository injects <strong>no</strong> AWS SDK clients;
 *       AWS service integrations live exclusively in the
 *       {@code com.awsm2.carddemo.adapter} package per AAP
 *       &sect;0.3.3 (Adapter Pattern).</li>
 *   <li>This repository declares <strong>no</strong> custom methods;
 *       every required access pattern is covered by
 *       {@link JpaRepository}'s inherited methods.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.domain.Customer
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
