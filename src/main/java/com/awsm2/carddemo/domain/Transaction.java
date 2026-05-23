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
package com.awsm2.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Hibernate-specific imports — REQUIRED for runtime correctness when
// Hibernate's spring.jpa.hibernate.ddl-auto: validate compares the
// PostgreSQL CHAR(2) column type against this entity's String tranTypeCd
// mapping. Jakarta Persistence's @Column has no JDBC-type override
// (columnDefinition only affects DDL export, NOT validation), so
// @JdbcTypeCode(SqlTypes.CHAR) is the established codebase convention
// (mirrors src/main/java/com/awsm2/carddemo/domain/Card.java
// cardActiveStatus + DailyTransaction.dalytranTypeCd) for binding a
// Java String to a PostgreSQL CHAR (bpchar) column. Without this,
// validation fails with "wrong column type encountered in column
// [tran_type_cd] ... found [bpchar (Types#CHAR)], but expecting
// [char(2) (Types#VARCHAR)]". The Spring Boot Data JPA starter
// already brings Hibernate ORM transitively via the carddemo pom.xml,
// so no additional Maven dependency is needed for these imports.
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code transactions} table (Flyway
 * migration {@code V005__create_transaction.sql}). This entity is the
 * Java target for the COBOL {@code TRAN-RECORD} layout defined in
 * {@code app/cpy/CVTRA05Y.cpy} (RECLN = 350 bytes), and replaces the
 * mainframe VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} plus its alternate index
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX}
 * ({@code KEYS(26 304) NONUNIQUEKEY UPGRADE}) declared in
 * {@code app/jcl/TRANFILE.jcl}.
 *
 * <h2>Purpose</h2>
 * <p>This entity is the source-of-truth <em>journal</em> of every
 * posted financial transaction in the CardDemo system &mdash; the
 * central FACT table that powers every reporting, statement-
 * generation, interest-calculation, and audit flow downstream of the
 * daily transaction posting cascade. It captures the 13 business
 * fields of the COBOL {@code TRAN-RECORD} layout (the trailing 20-byte
 * {@code FILLER} is omitted per AAP &sect;0.6.2 because PostgreSQL has
 * no concept of fixed-width record padding).</p>
 *
 * <h2>Consumers (Java services that read from / write to this table)</h2>
 * <ul>
 *   <li>{@code TransactionAddService} (COBOL {@code COTRN02C})
 *       &mdash; online insertion of a single new transaction via
 *       {@code POST /api/transactions}. Uses JPA sequence ID
 *       generation as the replacement for the COBOL browse-to-end
 *       pattern; publishes {@code transaction.posted} to MSK
 *       partitioned by account ID per AAP &sect;0.6.5.</li>
 *   <li>{@code TransactionListService} (COBOL {@code COTRN00C})
 *       &mdash; paginated browse of recent transactions for a card or
 *       account; the {@code COTRN00.bms} 10-row-per-page list screen.
 *       Uses the composite secondary index
 *       {@code idx_transactions_card_proc_ts} (declared in V005) for
 *       efficient by-card time-windowed scans.</li>
 *   <li>{@code TransactionDetailService} (COBOL {@code COTRN01C})
 *       &mdash; random read by {@link #tranId} (the 16-character
 *       primary key) to populate the {@code COTRN01.bms}
 *       transaction-detail screen.</li>
 *   <li>{@code BillPaymentService} (COBOL {@code COBIL00C}) &mdash;
 *       inserts the offsetting transaction row when an account holder
 *       makes a bill payment; the
 *       {@code @Transactional(rollbackFor = Exception.class)} boundary
 *       simultaneously decrements {@code acct_curr_bal} (V001) and
 *       increments {@code transactions}. Publishes
 *       {@code account.updated} to MSK per AAP &sect;0.4.1.</li>
 *   <li>{@code TransactionPostingService} (COBOL {@code CBTRN01C} /
 *       {@code CBTRN02C} / {@code CBTRN03C}) &mdash; batch posting
 *       pipeline that reads from {@code daily_transactions} (V011),
 *       runs the 4-stage validation cascade (XREF lookup / Account
 *       lookup / Credit-limit check / Card-expiration check; reject
 *       codes 100-109 preserved verbatim per AAP &sect;0.4.1), and
 *       inserts the accepted transactions here. The composite index on
 *       {@code (tran_card_num, tran_proc_ts)} supports the per-card
 *       chronological scan used by the batch reconciliation pass.</li>
 *   <li>{@code InterestCalculationService} (COBOL {@code CBACT04C})
 *       &mdash; end-of-cycle batch job that emits interest-charge
 *       transactions via the COBOL formula
 *       {@code (tran_cat_bal * dis_int_rate) / 1200} preserved
 *       verbatim per AAP &sect;0.6.1 (the divisor 1200 is kept as
 *       {@code BigDecimal.valueOf(1200)} and is NOT algebraically
 *       simplified); results are inserted as new {@code transactions}
 *       records.</li>
 *   <li>{@code StatementGenerationService} (COBOL
 *       {@code CBSTM03A} / {@code CBSTM03B}) &mdash; reads
 *       transactions filtered by card and statement cycle window when
 *       printing monthly statements; the composite index supports the
 *       date-range scan.</li>
 *   <li>{@code TransactionReportService} (COBOL {@code CBTRN03C}
 *       report variant) &mdash; date-windowed report generation
 *       joining transactions with {@code tran_type} (V008),
 *       {@code tran_category} (V009), and {@code card_xref} (V004);
 *       output written to S3 via {@code S3OutputService} per AAP
 *       &sect;0.4.1.</li>
 *   <li>{@code TransactionRepository} (Spring Data JPA) &mdash;
 *       exposes {@code findById(String tranId)}, paged
 *       {@code findAll(Pageable)}, and derived queries on
 *       {@code (tran_card_num, tran_proc_ts)} that leverage the
 *       composite index; injected into every service above.</li>
 * </ul>
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook:</b> {@code app/cpy/CVTRA05Y.cpy} &mdash;
 *       350-byte fixed-width record layout (13 business fields +
 *       20-byte trailing FILLER; verified L4-L18). Total record
 *       length: 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 +
 *       26 + 26 + 20 = 350 bytes, matching the
 *       {@code LISTCAT MAXLRECL=350 / AVGLRECL=350}.</li>
 *   <li><b>JCL DD allocation:</b> {@code app/jcl/TRANFILE.jcl}
 *       (IDCAMS DEFINE CLUSTER L49-L62: KEYS(16 0), RECORDSIZE(350
 *       350), CYLINDERS(1 5), SHAREOPTIONS(2 3), ERASE, INDEXED; plus
 *       DEFINE ALTERNATEINDEX L79-L92 KEYS(26 304) NONUNIQUEKEY
 *       UPGRADE).</li>
 *   <li><b>VSAM catalog snapshot:</b> {@code app/catlg/LISTCAT.txt}
 *       (confirms KEYLEN=16, RKP=0, MAXLRECL=350, AVGLRECL=350,
 *       INDEXED, SHROPTNS(2,3); also confirms the AIX association
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX}).</li>
 *   <li><b>COBOL consumers:</b> {@code COTRN00C}, {@code COTRN01C},
 *       {@code COTRN02C}, {@code COBIL00C}, {@code CBTRN01C},
 *       {@code CBTRN02C}, {@code CBTRN03C}, {@code CBACT04C},
 *       {@code CBSTM03A}, {@code CBSTM03B} (per file header
 *       documentation in {@code V005__create_transaction.sql}).</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V005__create_transaction.sql}.</li>
 * </ul>
 *
 * <h2>Schema invariants</h2>
 * <ul>
 *   <li><b>Append-only audit trail</b> &mdash; the
 *       {@code transactions} journal is APPEND-ONLY in both the COBOL
 *       source and the Java target. No paragraph in {@code CBTRN02C},
 *       {@code COTRN02C}, {@code COBIL00C}, {@code CBACT04C}, or any
 *       other program updates an existing {@code TRANSACT.VSAM.KSDS}
 *       record in place. Reversals are recorded as NEW offsetting
 *       rows with their own fresh {@code tran_id}, NOT as in-place
 *       mutations of the original row &mdash; preserving the audit-
 *       trail semantic per AAP &sect;0.7.2.</li>
 *   <li><b>No optimistic locking</b> &mdash; no {@code @Version}
 *       column is present, in deliberate contrast to V001
 *       {@code accounts} and V002 {@code cards}. Without UPDATEs
 *       there is no read-modify-write race, so there is no
 *       optimistic-lock contention to detect. This is the SAME
 *       RATIONALE used for V011 {@code daily_transactions}.</li>
 *   <li><b>Foreign key</b> &mdash; {@link #tranCardNum} references
 *       {@code cards(card_num)} (V002) with ON DELETE NO ACTION;
 *       declared at the DB layer in V005 only. Per the Minimal Change
 *       Clause (AAP &sect;0.7.3) the entity uses a SCALAR
 *       {@link String} {@code tranCardNum} field rather than a
 *       {@code @ManyToOne} association. Service-layer code performs
 *       explicit lookups via
 *       {@code cardRepository.findById(tranCardNum)}.</li>
 *   <li><b>Composite secondary index</b> &mdash;
 *       {@code idx_transactions_card_proc_ts} on
 *       {@code (tran_card_num, tran_proc_ts)} replaces the COBOL
 *       VSAM alternate index {@code TRANSACT.VSAM.AIX KEYS(26 304)
 *       NONUNIQUEKEY UPGRADE} (which keyed on {@code TRAN-PROC-TS}
 *       alone). Per AAP &sect;0.6.2 the composite form provides
 *       BETTER coverage for the dominant per-card chronological
 *       query patterns.</li>
 * </ul>
 *
 * <h2>Monetary arithmetic discipline (AAP &sect;0.6.1)</h2>
 * <p>{@link #tranAmt} is a {@link java.math.BigDecimal} with
 * precision 11 and scale 2 &mdash; matching the COBOL
 * {@code PIC S9(09)V99} declaration and the PostgreSQL
 * {@code NUMERIC(11, 2)} column type. <b>Never</b> use
 * {@code double} or {@code float} for monetary fields in this
 * codebase. All arithmetic operations performed on {@code tranAmt}
 * in downstream Java code MUST use the explicit form
 * {@code a.multiply(b).setScale(2, RoundingMode.HALF_EVEN)} (banker's
 * rounding) to match COBOL {@code PIC 9} decimal-arithmetic semantics
 * exactly, and MUST guard against {@code ON SIZE ERROR} overflows by
 * throwing {@code OnSizeErrorException} when a result would exceed
 * the configured precision (per AAP &sect;0.6.1, &sect;0.7.1). The
 * entity itself does NOT enforce rounding &mdash; that is a service-
 * layer concern.</p>
 *
 * <h2>PCI-DSS handling (AAP &sect;0.6.6)</h2>
 * <p>{@link #tranCardNum} contains a 16-digit Primary Account Number
 * (PAN), classified as <em>cardholder data</em> per PCI-DSS scope.
 * Database storage is encrypted at rest via the RDS KMS customer-
 * managed key (CMK; configured in
 * {@code infrastructure/terraform/rds.tf}) and in transit via TLS
 * 1.2+ ({@code rds.force_ssl=1} parameter). No plaintext PAN is
 * permitted in application logs; {@link #toString()} below masks all
 * but the last 4 digits, and CloudWatch log filters monitor for
 * accidental PAN-like sequences in log output.</p>
 *
 * <h2>JPA mapping discipline</h2>
 * <ul>
 *   <li>{@code @Table(name = "transactions")} matches the V005 DDL
 *       table name <em>exactly</em> (Hibernate
 *       {@code spring.jpa.hibernate.ddl-auto: validate} verifies this
 *       at startup; Flyway is the sole schema owner).</li>
 *   <li>All {@code @Column(name = ...)} attributes match V005 column
 *       names (snake_case, {@code tran_*} prefix).</li>
 *   <li>{@code nullable}, {@code length}, {@code precision},
 *       {@code scale}, {@code columnDefinition} attributes correspond
 *       1-to-1 with the V005 DDL.</li>
 *   <li>{@code equals}/{@code hashCode} are based on the primary key
 *       ({@link #tranId}) only &mdash; the recommended JPA contract
 *       that remains stable across the entity lifecycle
 *       (transient &rarr; managed &rarr; detached).</li>
 *   <li>Implements {@link Serializable} (a {@code serialVersionUID}
 *       is declared) to support Hibernate L2 cache serialization,
 *       ElastiCache Redis cache-aside (per AAP &sect;0.6.6),
 *       Spring Session distribution, and any cross-JVM transfer in
 *       Spring Batch chunk processing or Kafka serialization paths
 *       (per AAP &sect;0.6.5 if transaction snapshots are published
 *       to MSK topics {@code transaction.posted},
 *       {@code account.updated}).</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.domain.DailyTransaction the staging
 *      counterpart (the {@code daily_transactions} table; post-
 *      validated transactions flow from {@code daily_transactions}
 *      to this {@code transactions} table)
 * @see com.awsm2.carddemo.repository.TransactionRepository for
 *      Spring Data JPA access patterns
 */
@Entity
@Table(name = "transactions")
public class Transaction implements Serializable {

    /**
     * Serial-version UID for this JPA entity. Kept stable across
     * minor field additions so that Hibernate L2 cache entries,
     * ElastiCache Redis cached snapshots, Spring Session data, and
     * Kafka-serialized payloads survive non-breaking entity
     * evolution. Bump this value if (and only if) the wire/cache
     * layout changes incompatibly (e.g., a field removed, renamed,
     * or its type changed in a way that breaks Java
     * {@link Serializable} round-trip).
     */
    private static final long serialVersionUID = 1L;

    // -------------------------------------------------------------------------
    // Field declarations
    //
    // Order matches the COBOL copybook (CVTRA05Y.cpy) line order so that the
    // Java source remains visually parallel to the source-of-truth COBOL
    // record layout. This eases code review for COBOL SMEs during the
    // parallel-run validation window per AAP §0.7.2.
    // -------------------------------------------------------------------------

    // COBOL: CVTRA05Y.cpy:L5 TRAN-ID PIC X(16) - primary key (VSAM RKP=0 KEYLEN=16)
    // Maps to V005 column: tran_id VARCHAR(16) NOT NULL (pk_transactions)
    /**
     * 16-character alphanumeric transaction identifier. Primary key.
     *
     * <p>Stored as {@code VARCHAR(16)} (NOT {@code BIGINT}) because:
     * <ol>
     *   <li>Transaction identifiers are opaque alphanumeric tokens
     *       in the COBOL source (some flows use
     *       {@code "yyyymmddNNNNNNNN"}, others pure sequence) and
     *       must round-trip byte-identical for parallel-run output
     *       diff per AAP &sect;0.7.2;</li>
     *   <li>Leading-zero preservation is REQUIRED &mdash; the ID
     *       {@code "0000000000000001"} must NOT collapse to a numeric
     *       {@code "1"};</li>
     *   <li>The Java target {@code @Id} field is declared as
     *       {@link String} to match the COBOL {@code PIC X(16)}
     *       alphanumeric type.</li>
     * </ol>
     *
     * <p>{@code @Id} is on the field (per JPA best practice for
     * single-PK string keys); no {@code @GeneratedValue} because the
     * identifier is application-generated &mdash; in the Java target
     * via JPA sequence + zero-padded format (replacing the COBOL
     * {@code COTRN02C} browse-to-end + increment pattern), and in
     * existing data carries the verbatim value loaded from the
     * mainframe via the parallel-run period.</p>
     */
    @Id
    @Column(name = "tran_id", nullable = false, length = 16)
    private String tranId;

    // COBOL: CVTRA05Y.cpy:L6 TRAN-TYPE-CD PIC X(02) - 2-char tran-type code
    // Maps to V005 column: tran_type_cd CHAR(2) NOT NULL
    /**
     * 2-character transaction-type code (e.g., {@code "01"} Purchase,
     * {@code "02"} Payment, {@code "03"} Credit, {@code "04"}
     * Authorization, {@code "05"} Refund, {@code "06"} Reversal,
     * {@code "07"} Adjustment per the V013 fixture loaded from
     * {@code app/data/ASCII/trantype.txt}).
     *
     * <p>Logically references {@code tran_type.tran_type} (V008) but
     * <b>no FK constraint is enforced at the DB layer</b> because V008
     * is created after V005 in the Flyway sequence (per AAP
     * &sect;0.4.1 and the V005 file-header rationale). Application-
     * layer code in {@code TransactionPostingService} and
     * {@code TransactionAddService} enforces the lookup at write time
     * via the 4-stage validation cascade.</p>
     *
     * <p>{@code columnDefinition = "CHAR(2)"} preserves the COBOL
     * fixed-width 2-character semantic on PostgreSQL &mdash; leading
     * zeros are significant ({@code "01"} is NOT {@code "1"}) and the
     * value is always exactly 2 characters. Matches the CHAR(2) type
     * used by V006 {@code tran_cat_bal.trancat_type_cd}, V008
     * {@code tran_type.tran_type}, and V009
     * {@code tran_category.tran_type_cd}, so application-layer JOINs
     * on {@code tran_type_cd} use existing PostgreSQL B-tree indexes
     * without an implicit type cast.</p>
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.CHAR)} is REQUIRED so Hibernate
     * binds this Java {@code String} to PostgreSQL {@code CHAR(2)}
     * (a.k.a. {@code bpchar}) at runtime rather than the default
     * {@code VARCHAR}. Without this annotation,
     * {@code spring.jpa.hibernate.ddl-auto: validate} fails at
     * application startup with "wrong column type encountered in
     * column [tran_type_cd] in table [transactions]; found [bpchar
     * (Types#CHAR)], but expecting [char(2) (Types#VARCHAR)]". This
     * mirrors the established codebase pattern in
     * {@link com.awsm2.carddemo.domain.Card#cardActiveStatus}.
     * {@code @Column(columnDefinition)} alone is insufficient because
     * it only influences DDL export &mdash; schema validation compares
     * the runtime JDBC type binding, which is governed by
     * {@code @JdbcTypeCode}.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type_cd", nullable = false, length = 2, columnDefinition = "CHAR(2)")
    private String tranTypeCd;

    // COBOL: CVTRA05Y.cpy:L7 TRAN-CAT-CD PIC 9(04) - tran-category code
    // Maps to V005 column: tran_cat_cd NUMERIC(4) NOT NULL
    /**
     * 4-digit unsigned numeric transaction-category code (range
     * {@code 0001..9999}). 18 valid categories per the V014 fixture
     * loaded from {@code app/data/ASCII/trancatg.txt} (5 Purchase
     * categories, 3 Payment categories, 3 Credit categories, 3
     * Authorization categories, 1 Refund category, 2 Reversal
     * categories, 1 Adjustment category).
     *
     * <p>Joined with {@link #tranTypeCd} as the composite logical key
     * into {@code tran_category} (V009) and {@code tran_cat_bal}
     * (V006). Stored as {@code NUMERIC(4)} on PostgreSQL and mapped
     * to a Java {@link Integer} (the COBOL {@code PIC 9(04)} fits in
     * a primitive {@code int} but {@code Integer} permits null-safe
     * semantics during partial DTO &rarr; entity mapping and
     * Optional/null-safe lookups).</p>
     *
     * <p>As with {@link #tranTypeCd}, NO FK is declared because V009
     * is created after V005; the application enforces the lookup at
     * write time via {@code TransactionPostingService} and
     * {@code TransactionAddService}.</p>
     */
    @Column(name = "tran_cat_cd", nullable = false, precision = 4, columnDefinition = "NUMERIC(4)")
    private Integer tranCatCd;

    // COBOL: CVTRA05Y.cpy:L8 TRAN-SOURCE PIC X(10) - origination source
    // Maps to V005 column: tran_source VARCHAR(10) (nullable)
    /**
     * 10-character free-form origination-source indicator
     * (e.g., {@code "POS TERM  "}, {@code "OPERATOR  "},
     * {@code "ONLINE    "}, {@code "INTEREST  "},
     * {@code "PAYMENT   "}). Denormalized tag used for downstream
     * reporting and audit categorization, not a strict enumeration
     * in the COBOL source. Optional in the COBOL record (may be
     * space-filled for system-generated rows), so the column is
     * <b>NULLABLE</b> in PostgreSQL &mdash; consistent with V011
     * {@code daily_transactions.dalytran_source} which shares the
     * same byte position and value space.
     */
    @Column(name = "tran_source", length = 10)
    private String tranSource;

    // COBOL: CVTRA05Y.cpy:L9 TRAN-DESC PIC X(100) - free-text description
    // Maps to V005 column: tran_desc VARCHAR(100) (nullable)
    /**
     * 100-character free-form human-readable transaction
     * description. May include merchant name, free text supplied by
     * the upstream feed, or a system-generated description for
     * interest charges and adjustments. The COBOL source treats this
     * as a display-only field with no parsing rules.
     *
     * <p><b>NULLABLE</b> because the COBOL record may carry an
     * all-space value (which the Java {@code ItemReader} /
     * {@code ItemProcessor} TRIMs before insert; trimmed empty-
     * string is then either stored as empty string or NULL depending
     * on the importer policy &mdash; both representations are
     * preserved without information loss). Consistent with V011
     * {@code daily_transactions.dalytran_desc}.</p>
     */
    @Column(name = "tran_desc", length = 100)
    private String tranDesc;

    // COBOL: CVTRA05Y.cpy:L10 TRAN-AMT PIC S9(09)V99 - signed monetary amount
    // Maps to V005 column: tran_amt NUMERIC(11,2) NOT NULL
    // Per AAP §0.6.1: BigDecimal precision=11 scale=2 with RoundingMode.HALF_EVEN
    /**
     * Signed 9-digit integer with 2 implied decimal places (range
     * {@code -999,999,999.99..+999,999,999.99}). The <b>CORE MONETARY
     * FIELD</b> of this table. Stored as {@code NUMERIC(11,2)} where
     * precision = 9 (digits) + 2 (V99 fractional) = 11 and scale = 2,
     * per AAP &sect;0.6.1.
     *
     * <p><b>MONETARY FIELD &mdash; BIGDECIMAL ARITHMETIC RULES (AAP
     * &sect;0.6.1):</b>
     * <ul>
     *   <li>Banker's rounding
     *       ({@code RoundingMode.HALF_EVEN}) is enforced at every
     *       arithmetic boundary in the Java service layer
     *       ({@code TransactionPostingService},
     *       {@code TransactionAddService},
     *       {@code BillPaymentService},
     *       {@code InterestCalculationService}).</li>
     *   <li><b>NEVER</b> use {@code DOUBLE PRECISION}, {@code REAL},
     *       {@code FLOAT}, or any Java {@code double} / {@code float}
     *       intermediate for monetary computation &mdash; those
     *       types lose precision on decimal arithmetic and break
     *       parity with COBOL.</li>
     *   <li>{@code NUMERIC} is arbitrary-precision exact arithmetic;
     *       rounding only occurs at explicit scale changes
     *       ({@code setScale} / {@code multiply} + {@code divide}
     *       chains).</li>
     * </ul>
     *
     * <p>Interest-charge transactions emitted by
     * {@code InterestCalculationService} (COBOL {@code CBACT04C})
     * carry an amount computed via the COBOL formula
     * {@code interest = (tran_cat_bal * dis_int_rate) / 1200}
     * preserved verbatim per AAP &sect;0.6.1 &mdash; the divisor
     * {@code 1200} is kept as {@code BigDecimal.valueOf(1200)} and
     * is NOT algebraically simplified. The Java implementation:</p>
     * <pre>
     *   balance.multiply(rate)
     *          .divide(BigDecimal.valueOf(1200),
     *                  2, RoundingMode.HALF_EVEN)
     * </pre>
     *
     * <p><b>ON SIZE ERROR semantics:</b> if a transaction amount
     * would exceed the {@code NUMERIC(11,2)} range
     * ({@code |value| > 999,999,999.99}) the Java
     * {@code TransactionPostingService} /
     * {@code TransactionAddService} throws
     * {@code OnSizeErrorException} per AAP &sect;0.6.1 (translated to
     * HTTP 422 Unprocessable Entity by {@code GlobalExceptionHandler}
     * for online flows, or recorded as a reject for batch flows
     * &mdash; the COBOL {@code CBTRN02C} reject code semantics
     * {@code 100-109} are preserved per AAP &sect;0.4.1).</p>
     *
     * <p><b>NOT NULL</b> because a transaction without an amount is
     * meaningless &mdash; every COBOL row carries a populated
     * {@code TRAN-AMT}.</p>
     */
    @Column(name = "tran_amt", nullable = false, precision = 11, scale = 2)
    private BigDecimal tranAmt;

    // COBOL: CVTRA05Y.cpy:L11 TRAN-MERCHANT-ID PIC 9(09) - unsigned merchant id
    // Maps to V005 column: tran_merchant_id NUMERIC(9) (nullable)
    /**
     * 9-digit unsigned merchant identifier (range
     * {@code 000000000..999999999}). Logically links to an upstream
     * merchant master, but NO merchant table is in scope for this
     * refactor &mdash; merchant attributes are denormalized onto
     * each transaction row (mirrors the COBOL flat-file structure).
     *
     * <p><b>NULLABLE</b> because the COBOL record may carry an
     * all-zero or all-space merchant ID for system-generated rows
     * (interest charges, adjustments, internal transfers) where no
     * real merchant exists.</p>
     *
     * <p>Mapped to {@link Long} because Java {@code int} can store
     * the full unsigned 9-digit range without sign-bit
     * reinterpretation, but {@link Long} provides additional safety
     * for any future widening and aligns with the
     * {@code daily_transactions.dalytran_merchant_id} mapping of V011
     * that shares the same byte position and value space.</p>
     */
    @Column(name = "tran_merchant_id", precision = 9, columnDefinition = "NUMERIC(9)")
    private Long tranMerchantId;

    // COBOL: CVTRA05Y.cpy:L12 TRAN-MERCHANT-NAME PIC X(50) - merchant name
    // Maps to V005 column: tran_merchant_name VARCHAR(50) (nullable)
    /**
     * Merchant display name (up to 50 characters &mdash; e.g.,
     * {@code "AMAZON.COM"}, {@code "STARBUCKS"},
     * {@code "SHELL OIL"}). <b>NULLABLE</b> for system-generated
     * rows.
     *
     * <p>Stored as {@code VARCHAR(50)} which allows trimming on read
     * without information loss. Read by
     * {@code StatementGenerationService} when printing monthly
     * statements (line-item description) and by
     * {@code TransactionDetailService} when populating the
     * {@code COTRN01.bms} transaction-detail screen.</p>
     */
    @Column(name = "tran_merchant_name", length = 50)
    private String tranMerchantName;

    // COBOL: CVTRA05Y.cpy:L13 TRAN-MERCHANT-CITY PIC X(50) - merchant city
    // Maps to V005 column: tran_merchant_city VARCHAR(50) (nullable)
    /**
     * Merchant city (up to 50 characters). <b>NULLABLE</b> for
     * system-generated rows or for online merchants where a physical
     * city is not meaningful.
     */
    @Column(name = "tran_merchant_city", length = 50)
    private String tranMerchantCity;

    // COBOL: CVTRA05Y.cpy:L14 TRAN-MERCHANT-ZIP PIC X(10) - merchant ZIP/postal
    // Maps to V005 column: tran_merchant_zip VARCHAR(10) (nullable)
    /**
     * Merchant ZIP / postal code (up to 10 characters).
     * {@code VARCHAR(10)} accommodates 5-digit US ZIP, 5+4
     * hyphenated US ZIP (e.g., {@code "78487-7965"}), and
     * international postal formats per the source fixture.
     * <b>NULLABLE</b> for system-generated rows.
     */
    @Column(name = "tran_merchant_zip", length = 10)
    private String tranMerchantZip;

    // COBOL: CVTRA05Y.cpy:L15 TRAN-CARD-NUM PIC X(16) - card PAN (16 digits)
    // Maps to V005 column: tran_card_num VARCHAR(16) NOT NULL (FK to cards.card_num, indexed)
    /**
     * 16-character card-number string (Primary Account Number, PAN)
     * identifying the card on which this transaction was posted.
     * Foreign key to {@code cards(card_num)} (V002) with ON DELETE
     * NO ACTION (the conservative choice that prevents silent
     * cascading deletion of audit-trail rows; explicit programmatic
     * deletion is required, mirroring the COBOL VSAM semantic).
     *
     * <p>Stored as {@code VARCHAR(16)} (NOT {@code BIGINT}) for the
     * same reasons as {@code cards.card_num}: opaque identifier
     * string, leading-zero preservation, Luhn-check / BIN-range
     * parsing operates on the string form, and parallel-run byte-
     * identical output diff per AAP &sect;0.7.2.</p>
     *
     * <p>This column is the <b>LEADING</b> field of the composite
     * secondary index {@code idx_transactions_card_proc_ts} declared
     * in V005. The composite index supports the dominant query
     * patterns:</p>
     * <ul>
     *   <li>per-card transaction list with timestamp ordering
     *       ({@code TransactionListService} for the
     *       {@code COTRN00.bms} 10-row-per-page screen);</li>
     *   <li>date-windowed report by card
     *       ({@code TransactionReportService} for
     *       {@code CBTRN03C}); and</li>
     *   <li>statement-generation chronological scan per card
     *       ({@code StatementGenerationService} for
     *       {@code CBSTM03A} / {@code CBSTM03B}).</li>
     * </ul>
     *
     * <p><b>PCI-DSS CRITICAL (AAP &sect;0.6.6):</b> this column
     * contains <em>cardholder data</em> per PCI-DSS scope. The
     * application MUST mask {@code tran_card_num} in logs by the
     * CloudWatch log filter regex that detects PAN-like sequences.
     * Encryption at rest is delegated to RDS via the customer KMS
     * CMK (configured in {@code infrastructure/terraform/rds.tf});
     * encryption in transit via {@code rds.force_ssl=1} parameter.
     * The {@link #toString()} implementation below masks all but the
     * last 4 digits to prevent inadvertent PAN exposure in
     * application logs.</p>
     *
     * <p><b>MSK PARTITION KEY (AAP &sect;0.6.5):</b> when publishing
     * the {@code transaction.posted} event after a successful
     * {@code INSERT}, {@code KafkaEventPublisher} may use
     * {@code tran_card_num} as the partition key to guarantee per-
     * card ordering across all consumers (or the account-id partition
     * key for account-level ordering as documented in AAP
     * &sect;0.6.5).</p>
     */
    @Column(name = "tran_card_num", nullable = false, length = 16)
    private String tranCardNum;

    // COBOL: CVTRA05Y.cpy:L16 TRAN-ORIG-TS PIC X(26) - origination timestamp
    // Maps to V005 column: tran_orig_ts TIMESTAMP(6) NOT NULL
    /**
     * Origination timestamp &mdash; when the transaction was created
     * by the upstream system (e.g., when the cardholder swiped the
     * card at a POS terminal, or when an online transaction was
     * authorized). The COBOL {@code PIC X(26)} text format is
     * {@code "YYYY-MM-DD HH:MM:SS.mmmmmm"} (microsecond precision;
     * verified against {@code app/data/ASCII/dailytran.txt} fixture
     * records, e.g., {@code "2022-06-10 19:27:53.000000"}).
     *
     * <p>Stored as PostgreSQL {@code TIMESTAMP(6)} for microsecond
     * precision matching the COBOL {@code .mmmmmm} fractional
     * suffix exactly, and mapped to Java {@link LocalDateTime}.
     * Per AAP &sect;0.5.2, {@code java.time} replaces LE
     * {@code CEEDAYS}-based date handling from the COBOL source.
     * Parsing / formatting of the COBOL {@code X(26)} text is
     * performed by {@code DateValidationService} (Spring Batch
     * {@code ItemProcessor} during ingestion).</p>
     *
     * <p>May differ from {@link #tranProcTs} by minutes (online
     * flows), hours (overnight settlement), or days (batch ingest
     * of offline POS terminal data).</p>
     *
     * <p><b>NOT NULL</b> because every COBOL row carries a populated
     * {@code TRAN-ORIG-TS} &mdash; the COBOL fixed-width record is
     * always fully populated; an all-space value would fail the
     * Java {@code DateValidationService} parse and would have been
     * rejected by the COBOL data-entry layer.</p>
     */
    @Column(name = "tran_orig_ts", nullable = false)
    private LocalDateTime tranOrigTs;

    // COBOL: CVTRA05Y.cpy:L17 TRAN-PROC-TS PIC X(26) - processing timestamp
    // Maps to V005 column: tran_proc_ts TIMESTAMP(6) NOT NULL (indexed)
    /**
     * Processing timestamp &mdash; when the transaction was processed
     * by the posting pipeline. For online flows ({@code COTRN02C},
     * {@code COBIL00C}) this is the timestamp of the REST request
     * acceptance; for batch flows ({@code CBTRN02C}) this is the
     * timestamp of the end-of-day posting job. Stored as PostgreSQL
     * {@code TIMESTAMP(6)} for microsecond precision matching the
     * COBOL {@code .mmmmmm} fractional suffix exactly, and mapped to
     * Java {@link LocalDateTime}.
     *
     * <p><b>VSAM AIX KEY EQUIVALENT (AAP &sect;0.6.2):</b> in the
     * COBOL source, this column at byte offset 304 of the 350-byte
     * record was the search key of the VSAM alternate index
     * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} with
     * {@code KEYS(26 304)} per
     * {@code app/jcl/TRANFILE.jcl}. The PostgreSQL composite index
     * {@code idx_transactions_card_proc_ts} on
     * {@code (tran_card_num, tran_proc_ts)} supersedes the VSAM AIX
     * with BETTER query coverage (the leading {@link #tranCardNum}
     * prefix supports the dominant per-card chronological access
     * patterns). This column is the <b>TRAILING</b> column of that
     * composite index.</p>
     *
     * <p><b>NOT NULL</b> because every successfully-posted
     * transaction has a processing timestamp (the COBOL
     * {@code CBTRN02C} posting paragraph always sets
     * {@code TRAN-PROC-TS} before the {@code WRITE}).</p>
     */
    @Column(name = "tran_proc_ts", nullable = false)
    private LocalDateTime tranProcTs;

    // COBOL: CVTRA05Y.cpy:L18 FILLER PIC X(20) - trailing VSAM record padding
    // OMITTED per AAP §0.6.2: relational layouts have no positional padding.
    // Total COBOL record length: 16+2+4+10+100+11+9+50+50+10+16+26+26+20 = 350 bytes.

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * No-argument constructor required by the JPA specification
     * (Hibernate uses reflection to instantiate detached entities
     * during result-set hydration). Application code SHOULD prefer
     * the all-args constructor or the explicit setter sequence for
     * clarity.
     */
    public Transaction() {
        // Intentionally empty; JPA instantiates the entity via this
        // constructor and then sets each managed field via the
        // appropriate setter or by reflective field assignment.
    }

    /**
     * All-arguments constructor accepting every non-{@code FILLER}
     * column in COBOL copybook order (matches the
     * {@code CVTRA05Y.cpy} record layout). Provided for ergonomic
     * construction in tests, Spring Batch {@code ItemProcessor}
     * mapping logic, and any path that promotes a
     * {@link com.awsm2.carddemo.domain.DailyTransaction} staging row
     * (after the 4-stage validation cascade) into a journal row.
     *
     * @param tranId            primary key &mdash; 16-char tran ID
     *                          (COBOL {@code TRAN-ID})
     * @param tranTypeCd        2-char transaction-type code
     *                          (COBOL {@code TRAN-TYPE-CD})
     * @param tranCatCd         transaction-category code 1..9999
     *                          (COBOL {@code TRAN-CAT-CD})
     * @param tranSource        origination source label
     *                          (COBOL {@code TRAN-SOURCE})
     * @param tranDesc          free-text description
     *                          (COBOL {@code TRAN-DESC})
     * @param tranAmt           signed monetary amount with
     *                          {@code scale = 2}
     *                          (COBOL {@code TRAN-AMT};
     *                          {@code BigDecimal}, never
     *                          {@code double} / {@code float})
     * @param tranMerchantId    9-digit unsigned merchant identifier
     *                          (COBOL {@code TRAN-MERCHANT-ID})
     * @param tranMerchantName  merchant display name
     *                          (COBOL {@code TRAN-MERCHANT-NAME})
     * @param tranMerchantCity  merchant city
     *                          (COBOL {@code TRAN-MERCHANT-CITY})
     * @param tranMerchantZip   merchant ZIP / postal code
     *                          (COBOL {@code TRAN-MERCHANT-ZIP})
     * @param tranCardNum       16-character card PAN; PCI-DSS
     *                          cardholder data &mdash; never log
     *                          in plaintext
     *                          (COBOL {@code TRAN-CARD-NUM})
     * @param tranOrigTs        origination timestamp with
     *                          microsecond precision
     *                          (COBOL {@code TRAN-ORIG-TS})
     * @param tranProcTs        processing timestamp with
     *                          microsecond precision
     *                          (COBOL {@code TRAN-PROC-TS})
     */
    public Transaction(
            String tranId,
            String tranTypeCd,
            Integer tranCatCd,
            String tranSource,
            String tranDesc,
            BigDecimal tranAmt,
            Long tranMerchantId,
            String tranMerchantName,
            String tranMerchantCity,
            String tranMerchantZip,
            String tranCardNum,
            LocalDateTime tranOrigTs,
            LocalDateTime tranProcTs) {
        this.tranId = tranId;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranSource = tranSource;
        this.tranDesc = tranDesc;
        this.tranAmt = tranAmt;
        this.tranMerchantId = tranMerchantId;
        this.tranMerchantName = tranMerchantName;
        this.tranMerchantCity = tranMerchantCity;
        this.tranMerchantZip = tranMerchantZip;
        this.tranCardNum = tranCardNum;
        this.tranOrigTs = tranOrigTs;
        this.tranProcTs = tranProcTs;
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    //
    // Explicit JavaBeans accessors per AAP §0.7.1 ("NO Lombok"). The order
    // matches the field-declaration order, which itself matches the COBOL
    // copybook layout for ease of cross-reference. Each accessor has a
    // minimal JavaDoc citing the COBOL field name for traceability.
    // -------------------------------------------------------------------------

    /**
     * @return the 16-character primary-key transaction identifier
     *         (COBOL {@code TRAN-ID})
     */
    public String getTranId() {
        return tranId;
    }

    /**
     * @param tranId the 16-character primary-key transaction identifier
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * @return the 2-char transaction-type code
     *         (COBOL {@code TRAN-TYPE-CD})
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * @param tranTypeCd the 2-char transaction-type code
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * @return the transaction-category code (1..9999)
     *         (COBOL {@code TRAN-CAT-CD})
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * @param tranCatCd the transaction-category code (1..9999)
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * @return the origination source label
     *         (COBOL {@code TRAN-SOURCE})
     */
    public String getTranSource() {
        return tranSource;
    }

    /**
     * @param tranSource the origination source label
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * @return the human-readable transaction description
     *         (COBOL {@code TRAN-DESC})
     */
    public String getTranDesc() {
        return tranDesc;
    }

    /**
     * @param tranDesc the human-readable transaction description
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * @return the signed monetary amount with {@code scale = 2}
     *         (COBOL {@code TRAN-AMT}). Returned as a
     *         {@link BigDecimal} &mdash; never converted to
     *         {@code double} or {@code float} for monetary
     *         computation (AAP &sect;0.6.1).
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * Sets the signed monetary amount. Callers MUST pass a
     * {@link BigDecimal} value that conforms to the
     * {@code precision = 11, scale = 2} constraint of the underlying
     * column; otherwise an {@code OnSizeErrorException} (the Java
     * equivalent of COBOL {@code ON SIZE ERROR}) should be raised by
     * the calling service prior to invoking this setter. The entity
     * itself does NOT enforce rounding &mdash; rounding is a service-
     * layer concern using {@code RoundingMode.HALF_EVEN}. See AAP
     * &sect;0.6.1 for the full arithmetic-discipline rules.
     *
     * @param tranAmt the signed monetary amount
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * @return the 9-digit unsigned merchant identifier
     *         (COBOL {@code TRAN-MERCHANT-ID})
     */
    public Long getTranMerchantId() {
        return tranMerchantId;
    }

    /**
     * @param tranMerchantId the 9-digit unsigned merchant identifier
     */
    public void setTranMerchantId(Long tranMerchantId) {
        this.tranMerchantId = tranMerchantId;
    }

    /**
     * @return the merchant display name
     *         (COBOL {@code TRAN-MERCHANT-NAME})
     */
    public String getTranMerchantName() {
        return tranMerchantName;
    }

    /**
     * @param tranMerchantName the merchant display name
     */
    public void setTranMerchantName(String tranMerchantName) {
        this.tranMerchantName = tranMerchantName;
    }

    /**
     * @return the merchant city
     *         (COBOL {@code TRAN-MERCHANT-CITY})
     */
    public String getTranMerchantCity() {
        return tranMerchantCity;
    }

    /**
     * @param tranMerchantCity the merchant city
     */
    public void setTranMerchantCity(String tranMerchantCity) {
        this.tranMerchantCity = tranMerchantCity;
    }

    /**
     * @return the merchant ZIP / postal code
     *         (COBOL {@code TRAN-MERCHANT-ZIP})
     */
    public String getTranMerchantZip() {
        return tranMerchantZip;
    }

    /**
     * @param tranMerchantZip the merchant ZIP / postal code
     */
    public void setTranMerchantZip(String tranMerchantZip) {
        this.tranMerchantZip = tranMerchantZip;
    }

    /**
     * @return the 16-character card PAN (PCI-DSS cardholder data;
     *         {@link #toString()} masks all but the last 4 digits)
     *         (COBOL {@code TRAN-CARD-NUM})
     */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /**
     * Sets the 16-character card PAN. Callers MUST treat the value
     * as PCI-DSS-restricted information: never write it to plaintext
     * logs, never embed it in URL paths, and never include it in any
     * exception message that may be persisted. See AAP &sect;0.6.6
     * for the full PCI-DSS handling rules.
     *
     * @param tranCardNum the 16-character card PAN
     */
    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    /**
     * @return the origination timestamp with microsecond precision
     *         (COBOL {@code TRAN-ORIG-TS})
     */
    public LocalDateTime getTranOrigTs() {
        return tranOrigTs;
    }

    /**
     * @param tranOrigTs the origination timestamp with microsecond
     *                   precision
     */
    public void setTranOrigTs(LocalDateTime tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    /**
     * @return the processing timestamp with microsecond precision
     *         &mdash; the trailing column of the composite secondary
     *         index {@code idx_transactions_card_proc_ts} (replaces
     *         the COBOL {@code TRANSACT.VSAM.AIX})
     *         (COBOL {@code TRAN-PROC-TS})
     */
    public LocalDateTime getTranProcTs() {
        return tranProcTs;
    }

    /**
     * @param tranProcTs the processing timestamp with microsecond
     *                   precision
     */
    public void setTranProcTs(LocalDateTime tranProcTs) {
        this.tranProcTs = tranProcTs;
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    //
    // equals/hashCode follow the JPA recommended contract: based on the
    // primary-key field only. This contract is STABLE across the entity
    // lifecycle states (transient, managed, detached, removed) and avoids
    // the pathological hashCode-changes-on-flush problem that comes from
    // including auto-generated identifiers or version columns.
    //
    // toString is non-sensitive: masks the card-number PAN per PCI-DSS
    // (AAP §0.6.6) and elides large textual fields (description, merchant
    // detail, origination timestamp) to keep log output compact during
    // high-throughput batch runs.
    // -------------------------------------------------------------------------

    /**
     * Equality is defined on the primary key ({@link #tranId}) only,
     * matching the standard JPA entity contract. Two
     * {@code Transaction} instances are equal iff they have the same
     * {@code tranId} value (both {@code null} is treated as equal
     * &mdash; common during transient-state comparisons within a
     * test fixture but should not occur in persisted state since the
     * primary key is {@code NOT NULL}).
     *
     * @param o the reference object with which to compare
     * @return {@code true} if this object is the same as the
     *         {@code o} argument; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transaction)) {
            return false;
        }
        Transaction that = (Transaction) o;
        return Objects.equals(tranId, that.tranId);
    }

    /**
     * Hash code derived from {@link #tranId} only, consistent with
     * the {@link #equals(Object)} contract above. Safe for use as a
     * hash-set or hash-map key once {@code tranId} has been assigned
     * (which is required before {@code EntityManager.persist} by
     * virtue of the {@code nullable = false} primary-key constraint).
     *
     * @return the hash-code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }

    /**
     * String representation suitable for log statements during the
     * online and batch transaction-posting pipelines. Includes the
     * primary identifying fields (id, type, category, amount, masked
     * PAN, processing timestamp) but deliberately omits the long-
     * form description, merchant details, and origination timestamp
     * to keep log lines compact at high throughput. The card-number
     * PAN is masked per PCI-DSS &mdash; only the last 4 digits are
     * visible. See AAP &sect;0.6.6 for full PCI-DSS rules.
     *
     * @return a non-sensitive string representation of this entity
     */
    @Override
    public String toString() {
        return "Transaction{"
                + "tranId='" + tranId + '\''
                + ", tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd=" + tranCatCd
                + ", tranAmt=" + tranAmt
                + ", tranCardNum='" + maskCardNumber(tranCardNum) + '\''
                + ", tranProcTs=" + tranProcTs
                + '}';
    }

    /**
     * Masks a 16-digit PAN so only the last 4 digits remain visible.
     * Used by {@link #toString()} to enforce the PCI-DSS rule that no
     * plaintext PAN may appear in application logs (AAP &sect;0.6.6).
     *
     * <p>Behavior:
     * <ul>
     *   <li>{@code null} input &rarr; returns the literal
     *       {@code "****"} (no leakage of length information).</li>
     *   <li>Input shorter than 4 characters &rarr; returns
     *       {@code "****"} (cannot safely reveal a "last 4" from a
     *       short string).</li>
     *   <li>Otherwise &rarr; returns 12 asterisks followed by the
     *       last 4 characters of the input. Format matches typical
     *       cardholder receipt formatting.</li>
     * </ul>
     *
     * @param card the card-number string to mask
     * @return the masked string suitable for log output
     */
    private static String maskCardNumber(String card) {
        if (card == null || card.length() < 4) {
            return "****";
        }
        return "************" + card.substring(card.length() - 4);
    }
}
