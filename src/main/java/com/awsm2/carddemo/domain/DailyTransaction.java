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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code daily_transactions} staging table
 * (Flyway migration {@code V011__create_daily_transaction.sql}). This entity
 * is the Java target for the COBOL {@code DALYTRAN-RECORD} layout defined in
 * {@code app/cpy/CVTRA06Y.cpy} (RECLN = 350 bytes), and replaces the
 * mainframe physical-sequential (PS) dataset
 * {@code AWS.M2.CARDDEMO.DALYTRAN.PS}.
 *
 * <h2>Staging-table semantics</h2>
 * <p>{@code daily_transactions} is the <em>inbox</em> of unprocessed
 * incoming transactions. It is populated by either:
 * <ol>
 *   <li>A Spring Batch {@code ItemReader} (S3 &rarr; RDS) during the
 *       parallel-run period using {@code app/data/ASCII/dailytran.txt}
 *       (300 fixed-width 350-byte golden fixture records), or</li>
 *   <li>AWS Glue Spark jobs ingesting upstream feeds from S3
 *       (per AAP &sect;0.4.1, {@code infrastructure/terraform/glue.tf}).</li>
 * </ol>
 *
 * <p>{@code DailyTransactionPostingJob} (the Spring Batch replacement
 * for COBOL {@code CBTRN02C}) consumes records from this table, runs
 * the 4-stage validation cascade (per AAP &sect;0.4.1), and on
 * success writes to the {@code transactions} journal
 * (V005) plus updates {@code accounts} balances and the
 * {@code tran_cat_bal} ledger, while publishing
 * {@code transaction.posted} events to Amazon MSK keyed by account ID.
 * On failure, a rejection record is emitted to S3 via
 * {@code S3OutputService} preserving COBOL reject codes 100&ndash;109.</p>
 *
 * <h2>4-stage validation cascade (consumed by Java {@code TransactionPostingService})</h2>
 * <ol>
 *   <li><b>XREF lookup</b> &mdash; reject code 100 (NOTFND on
 *       {@code card_xref}; COBOL {@code FILE-STATUS} '23' against
 *       {@code XREFFILE}).</li>
 *   <li><b>Account lookup</b> &mdash; reject code 101 (NOTFND on
 *       {@code accounts}; COBOL {@code FILE-STATUS} '23' against
 *       {@code ACCTFILE}).</li>
 *   <li><b>Credit-limit check</b> &mdash; reject code 102 (post-debit
 *       balance would exceed {@code acct_credit_limit}).</li>
 *   <li><b>Card-expiration check</b> &mdash; reject code 103
 *       (current date &gt; {@code card_exp_date}).</li>
 * </ol>
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook:</b> {@code app/cpy/CVTRA06Y.cpy}
 *       &mdash; 350-byte fixed-width record layout
 *       (byte-identical to {@code CVTRA05Y.cpy} {@code TRAN-RECORD} with
 *       field-name prefix {@code DALYTRAN-} substituted for
 *       {@code TRAN-}). Trailing {@code FILLER PIC X(20)} is omitted
 *       (relational layouts have no positional padding).</li>
 *   <li><b>COBOL consumers:</b>
 *       {@code app/cbl/CBTRN01C.cbl} (sequential reader / dump utility)
 *       and {@code app/cbl/CBTRN02C.cbl}
 *       (transaction-posting cascade) &mdash; replaced respectively
 *       by Java {@code TransactionPostingService} and
 *       {@code DailyTransactionPostingJob}.</li>
 *   <li><b>JCL DD allocation:</b> {@code app/jcl/POSTTRAN.jcl}
 *       (STEP15 EXEC PGM=CBTRN02C; DALYTRAN DD
 *       DSN=AWS.M2.CARDDEMO.DALYTRAN.PS).</li>
 *   <li><b>Golden fixture:</b> {@code app/data/ASCII/dailytran.txt}
 *       (300 records used for parallel-run validation).</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V011__create_daily_transaction.sql}.</li>
 * </ul>
 *
 * <h2>Schema invariants</h2>
 * <ul>
 *   <li><b>No foreign keys</b> &mdash; intentionally omitted at the DB
 *       layer because (a) bulk-load order is non-deterministic, (b)
 *       validation is enforced in the Java service layer preserving
 *       reject codes 100&ndash;109, (c) malformed/orphan records must
 *       be captured for replay rather than silently dropped by a
 *       constraint violation, (d) throughput. The main
 *       {@code transactions} table (V005) DOES enforce FKs because by
 *       the time a row reaches it, the Java cascade has proven the
 *       parents exist.</li>
 *   <li><b>No optimistic locking</b> &mdash; no {@code @Version}
 *       column is present. Staging rows are append-only from the
 *       ingestion side and consumed at most once by the posting job;
 *       there is no read-modify-write contention to guard against.</li>
 *   <li><b>Secondary index</b> &mdash;
 *       {@code idx_daily_transactions_card_num} on
 *       {@code dalytran_card_num} speeds up the validation join probe
 *       against {@code cards}/{@code card_xref} (replaces the VSAM AIX
 *       pattern of {@code TRANSACT.VSAM.AIX} referenced in
 *       {@code app/catlg/LISTCAT.txt}).</li>
 * </ul>
 *
 * <h2>Monetary arithmetic discipline (AAP &sect;0.6.1)</h2>
 * <p>{@link #dalytranAmt} is a {@link java.math.BigDecimal} with
 * precision 11 and scale 2 &mdash; matching the COBOL
 * {@code PIC S9(09)V99} declaration and the PostgreSQL
 * {@code NUMERIC(11, 2)} column type. <b>Never</b> use
 * {@code double} or {@code float} for monetary fields in this codebase.
 * All arithmetic operations performed on {@code dalytranAmt} in
 * downstream Java code MUST use the explicit form
 * {@code a.multiply(b).setScale(2, RoundingMode.HALF_EVEN)} (banker's
 * rounding) to match COBOL {@code PIC 9} decimal-arithmetic semantics
 * exactly, and MUST guard against {@code ON SIZE ERROR} overflows by
 * throwing {@code OnSizeErrorException} when a result would exceed the
 * configured precision.</p>
 *
 * <h2>PCI-DSS handling (AAP &sect;0.6.6)</h2>
 * <p>{@link #dalytranCardNum} contains a 16-digit Primary Account
 * Number (PAN). Database storage is encrypted at rest via the RDS
 * KMS customer-managed key (CMK) and protected in transit via
 * TLS 1.2+. No plaintext PAN is permitted in application logs;
 * {@link #toString()} below masks all but the last 4 digits, and
 * application Logback filters monitor for accidental PAN-like
 * sequences in log output.</p>
 *
 * <h2>JPA mapping discipline</h2>
 * <ul>
 *   <li>{@code @Table(name = "daily_transactions")} matches V011 DDL
 *       table name <em>exactly</em> (Hibernate
 *       {@code spring.jpa.hibernate.ddl-auto: validate} verifies this
 *       at startup).</li>
 *   <li>All {@code @Column(name = ...)} attributes match V011 column
 *       names (snake_case, {@code dalytran_*} prefix).</li>
 *   <li>{@code nullable}, {@code length}, {@code precision},
 *       {@code scale} attributes correspond 1-to-1 with the V011 DDL.</li>
 *   <li>{@code equals}/{@code hashCode} are based on the primary key
 *       ({@link #dalytranId}) only &mdash; the recommended JPA
 *       contract that remains stable across the entity lifecycle
 *       (transient &rarr; managed &rarr; detached).</li>
 *   <li>Implements {@link Serializable} (a serialVersionUID is
 *       declared) to support Hibernate L2 cache serialization,
 *       Spring Session distribution, and any cross-JVM transfer in
 *       Spring Batch chunk processing.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.domain.Transaction the journal counterpart
 *      (the {@code transactions} table; post-validated transactions
 *      flow from {@code daily_transactions} to {@code transactions})
 * @see com.awsm2.carddemo.repository.DailyTransactionRepository for
 *      Spring Data JPA access patterns
 */
@Entity
@Table(name = "daily_transactions")
public class DailyTransaction implements Serializable {

    /**
     * Serial-version UID for this JPA entity. Kept stable across
     * minor field additions so that Hibernate L2 cache entries and
     * Spring Session data survive non-breaking entity evolution.
     * Bump this value if (and only if) the wire/cache layout changes
     * incompatibly (e.g., a field removed, renamed, or its type
     * changed in a way that breaks Java {@link Serializable} round-trip).
     */
    private static final long serialVersionUID = 1L;

    // -------------------------------------------------------------------------
    // Field declarations
    //
    // Order matches the COBOL copybook (CVTRA06Y.cpy) line order so that the
    // Java source remains visually parallel to the source-of-truth COBOL
    // record layout. This eases code review for COBOL SMEs during the
    // parallel-run validation window.
    // -------------------------------------------------------------------------

    // COBOL: CVTRA06Y.cpy:L5 DALYTRAN-ID PIC X(16) - primary key
    // Maps to V011 column: dalytran_id VARCHAR(16) NOT NULL (pk_daily_transactions)
    /**
     * 16-character alphanumeric transaction identifier. Primary key.
     * Shares the same value space as
     * {@code transactions.tran_id} so that a daily-transaction row can
     * be promoted into the {@code transactions} journal with the same
     * key. {@code @Id} is on the field (per JPA best practice for
     * single-PK string keys); no {@code @GeneratedValue} because the
     * identifier originates upstream (mainframe feed or merchant
     * system) and must be preserved verbatim.
     */
    @Id
    @Column(name = "dalytran_id", nullable = false, length = 16)
    private String dalytranId;

    // COBOL: CVTRA06Y.cpy:L6 DALYTRAN-TYPE-CD PIC X(02) - 2-char tran-type code
    // Maps to V011 column: dalytran_type_cd CHAR(2) NOT NULL
    /**
     * 2-character transaction-type code (e.g., {@code "01"} purchase,
     * {@code "02"} return, {@code "03"} payment). Logically references
     * {@code tran_type.tran_type} (V008/V013) but no FK constraint is
     * enforced at the DB layer per the staging-table policy. Always
     * exactly 2 characters; leading zeros are significant
     * ({@code columnDefinition = "CHAR(2)"} preserves the fixed
     * width on PostgreSQL).
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_type_cd", nullable = false, length = 2, columnDefinition = "CHAR(2)")
    private String dalytranTypeCd;

    // COBOL: CVTRA06Y.cpy:L7 DALYTRAN-CAT-CD PIC 9(04) - tran-category code
    // Maps to V011 column: dalytran_cat_cd NUMERIC(4) NOT NULL
    /**
     * Transaction-category code (1..9999). Joined with
     * {@link #dalytranTypeCd} as the composite logical key into
     * {@code tran_category} (V009/V014). Stored as
     * {@code NUMERIC(4)} on PostgreSQL and mapped to a Java
     * {@code Integer} (PIC 9(04) fits in int but Integer permits
     * null-safe semantics during partial DTO &rarr; entity mapping).
     */
    @Column(name = "dalytran_cat_cd", nullable = false, precision = 4)
    private Integer dalytranCatCd;

    // COBOL: CVTRA06Y.cpy:L8 DALYTRAN-SOURCE PIC X(10) - origination source
    // Maps to V011 column: dalytran_source VARCHAR(10) (nullable)
    /**
     * Origination source label (e.g., {@code "POS TERM"},
     * {@code "OPERATOR"}, {@code "ONLINE"}). Free-form 10-character
     * alphanumeric per the upstream fixture format. Nullable because
     * historical records may have unspecified provenance.
     */
    @Column(name = "dalytran_source", length = 10)
    private String dalytranSource;

    // COBOL: CVTRA06Y.cpy:L9 DALYTRAN-DESC PIC X(100) - free-text description
    // Maps to V011 column: dalytran_desc VARCHAR(100) (nullable)
    /**
     * Human-readable transaction description. May include merchant
     * name and free-text supplied by the upstream feed. Nullable
     * because some payment types (e.g., automated interest postings)
     * may omit the description.
     */
    @Column(name = "dalytran_desc", length = 100)
    private String dalytranDesc;

    // COBOL: CVTRA06Y.cpy:L10 DALYTRAN-AMT PIC S9(09)V99 - signed monetary amount
    // Maps to V011 column: dalytran_amt NUMERIC(11,2) NOT NULL
    // Per AAP §0.6.1: BigDecimal precision=11 scale=2 with RoundingMode.HALF_EVEN
    /**
     * Signed monetary amount &mdash; 9 integer digits + 2 fractional
     * digits, signed (range approximately
     * &plusmn;999&nbsp;999&nbsp;999.99). Stored as
     * {@code NUMERIC(11, 2)} so Java {@link BigDecimal} arithmetic
     * with {@code scale = 2} and
     * {@code RoundingMode.HALF_EVEN} matches COBOL
     * {@code PIC 9} decimal-arithmetic semantics exactly. Any
     * arithmetic that would exceed this precision MUST be guarded by
     * the Java equivalent of COBOL {@code ON SIZE ERROR} &mdash;
     * {@code OnSizeErrorException} (see AAP &sect;0.6.1, &sect;0.7.1).
     * <b>NEVER</b> assign a {@code double} or {@code float} value to
     * this field; do all computation in {@link BigDecimal} from end
     * to end.
     */
    @Column(name = "dalytran_amt", nullable = false, precision = 11, scale = 2)
    private BigDecimal dalytranAmt;

    // COBOL: CVTRA06Y.cpy:L11 DALYTRAN-MERCHANT-ID PIC 9(09) - unsigned merchant id
    // Maps to V011 column: dalytran_merchant_id NUMERIC(9) (nullable)
    /**
     * 9-digit unsigned merchant identifier (PIC 9(09) is unsigned in
     * COBOL, so no sign-reserve digit is required). Mapped to
     * {@link Long} because Java {@code int} cannot store the full
     * unsigned 9-digit range without sign-bit reinterpretation. No
     * merchant master table is in scope; merchant attributes are
     * denormalized onto each transaction row (mirrors the COBOL
     * flat-file layout).
     */
    @Column(name = "dalytran_merchant_id", precision = 9)
    private Long dalytranMerchantId;

    // COBOL: CVTRA06Y.cpy:L12 DALYTRAN-MERCHANT-NAME PIC X(50) - merchant name
    // Maps to V011 column: dalytran_merchant_name VARCHAR(50) (nullable)
    /**
     * Merchant display name (up to 50 characters). Stored as
     * {@code VARCHAR(50)} without trailing space padding (relational
     * idiomatic storage).
     */
    @Column(name = "dalytran_merchant_name", length = 50)
    private String dalytranMerchantName;

    // COBOL: CVTRA06Y.cpy:L13 DALYTRAN-MERCHANT-CITY PIC X(50) - merchant city
    // Maps to V011 column: dalytran_merchant_city VARCHAR(50) (nullable)
    /**
     * Merchant city (up to 50 characters).
     */
    @Column(name = "dalytran_merchant_city", length = 50)
    private String dalytranMerchantCity;

    // COBOL: CVTRA06Y.cpy:L14 DALYTRAN-MERCHANT-ZIP PIC X(10) - merchant ZIP/postal
    // Maps to V011 column: dalytran_merchant_zip VARCHAR(10) (nullable)
    /**
     * Merchant ZIP/postal code (up to 10 characters). Accommodates
     * 5-digit US ZIP, 5+4 hyphenated US ZIP (e.g.,
     * {@code "78487-7965"}), and international postal formats.
     */
    @Column(name = "dalytran_merchant_zip", length = 10)
    private String dalytranMerchantZip;

    // COBOL: CVTRA06Y.cpy:L15 DALYTRAN-CARD-NUM PIC X(16) - card PAN (16 digits)
    // Maps to V011 column: dalytran_card_num VARCHAR(16) NOT NULL (indexed)
    /**
     * 16-digit card Primary Account Number (PAN). Stored as
     * {@code VARCHAR(16)} to preserve leading zeros and any
     * non-numeric formatting variations. Indexed at the DB layer via
     * {@code idx_daily_transactions_card_num} (V011) to support the
     * batch validation join against {@code card_xref}/{@code cards}.
     * Logically references {@code cards.card_num} (V002) but no FK
     * constraint is enforced at the DB layer per the staging-table
     * policy.
     *
     * <p><b>PCI-DSS:</b> Database storage is encrypted at rest via
     * the RDS KMS CMK and in transit via TLS 1.2+. The
     * {@link #toString()} implementation masks all but the last 4
     * digits to prevent inadvertent PAN exposure in application
     * logs.</p>
     */
    @Column(name = "dalytran_card_num", nullable = false, length = 16)
    private String dalytranCardNum;

    // COBOL: CVTRA06Y.cpy:L16 DALYTRAN-ORIG-TS PIC X(26) - origination timestamp
    // Maps to V011 column: dalytran_orig_ts TIMESTAMP(6) NOT NULL
    /**
     * Origination timestamp &mdash; when the transaction was created
     * by the upstream system. The COBOL X(26) text format is
     * {@code "YYYY-MM-DD HH:MM:SS.ffffff"} (microsecond precision),
     * stored as PostgreSQL {@code TIMESTAMP(6)} and mapped to Java
     * {@link LocalDateTime}. Per AAP &sect;0.5.2,
     * {@code java.time} replaces LE
     * {@code CEEDAYS}-based date handling.
     * Parsing/formatting of the COBOL X(26) text is performed by
     * {@code DateValidationService} (Spring Batch
     * {@code ItemProcessor} during ingestion).
     */
    @Column(name = "dalytran_orig_ts", nullable = false)
    private LocalDateTime dalytranOrigTs;

    // COBOL: CVTRA06Y.cpy:L17 DALYTRAN-PROC-TS PIC X(26) - processing timestamp
    // Maps to V011 column: dalytran_proc_ts TIMESTAMP(6) NOT NULL
    /**
     * Processing timestamp &mdash; when the transaction entered the
     * posting pipeline. Stored as PostgreSQL {@code TIMESTAMP(6)}
     * (microsecond precision). The {@code (card_num, proc_ts)} tuple
     * is the natural ordering key for per-card transaction streams
     * (mirrors the alternate-index pattern of {@code TRANSACT.VSAM.AIX}
     * referenced in {@code app/catlg/LISTCAT.txt}).
     */
    @Column(name = "dalytran_proc_ts", nullable = false)
    private LocalDateTime dalytranProcTs;

    // COBOL: CVTRA06Y.cpy:L18 FILLER PIC X(20) - trailing VSAM/PS padding
    // OMITTED per AAP §0.4.1: relational layouts have no positional padding.

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
    public DailyTransaction() {
        // Intentionally empty; JPA instantiates the entity via this
        // constructor and then sets each managed field via the
        // appropriate setter or by reflective field assignment.
    }

    /**
     * All-arguments constructor accepting every non-{@code FILLER}
     * column in COBOL copybook order (matches the {@code CVTRA06Y.cpy}
     * record layout). Provided for ergonomic construction in tests,
     * Spring Batch {@code ItemProcessor} mapping logic, and any
     * Spring Batch reader that converts a 350-byte fixed-width
     * record into a managed entity.
     *
     * @param dalytranId            primary key &mdash; 16-char tran ID
     *                              (COBOL {@code DALYTRAN-ID})
     * @param dalytranTypeCd        2-char transaction-type code
     *                              (COBOL {@code DALYTRAN-TYPE-CD})
     * @param dalytranCatCd         transaction-category code 1..9999
     *                              (COBOL {@code DALYTRAN-CAT-CD})
     * @param dalytranSource        origination source label
     *                              (COBOL {@code DALYTRAN-SOURCE})
     * @param dalytranDesc          free-text description
     *                              (COBOL {@code DALYTRAN-DESC})
     * @param dalytranAmt           signed monetary amount with
     *                              {@code scale = 2}
     *                              (COBOL {@code DALYTRAN-AMT})
     * @param dalytranMerchantId    9-digit unsigned merchant identifier
     *                              (COBOL {@code DALYTRAN-MERCHANT-ID})
     * @param dalytranMerchantName  merchant display name
     *                              (COBOL {@code DALYTRAN-MERCHANT-NAME})
     * @param dalytranMerchantCity  merchant city
     *                              (COBOL {@code DALYTRAN-MERCHANT-CITY})
     * @param dalytranMerchantZip   merchant ZIP/postal code
     *                              (COBOL {@code DALYTRAN-MERCHANT-ZIP})
     * @param dalytranCardNum       16-digit card PAN
     *                              (COBOL {@code DALYTRAN-CARD-NUM})
     * @param dalytranOrigTs        origination timestamp
     *                              (COBOL {@code DALYTRAN-ORIG-TS})
     * @param dalytranProcTs        processing timestamp
     *                              (COBOL {@code DALYTRAN-PROC-TS})
     */
    public DailyTransaction(
            String dalytranId,
            String dalytranTypeCd,
            Integer dalytranCatCd,
            String dalytranSource,
            String dalytranDesc,
            BigDecimal dalytranAmt,
            Long dalytranMerchantId,
            String dalytranMerchantName,
            String dalytranMerchantCity,
            String dalytranMerchantZip,
            String dalytranCardNum,
            LocalDateTime dalytranOrigTs,
            LocalDateTime dalytranProcTs) {
        this.dalytranId = dalytranId;
        this.dalytranTypeCd = dalytranTypeCd;
        this.dalytranCatCd = dalytranCatCd;
        this.dalytranSource = dalytranSource;
        this.dalytranDesc = dalytranDesc;
        this.dalytranAmt = dalytranAmt;
        this.dalytranMerchantId = dalytranMerchantId;
        this.dalytranMerchantName = dalytranMerchantName;
        this.dalytranMerchantCity = dalytranMerchantCity;
        this.dalytranMerchantZip = dalytranMerchantZip;
        this.dalytranCardNum = dalytranCardNum;
        this.dalytranOrigTs = dalytranOrigTs;
        this.dalytranProcTs = dalytranProcTs;
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    //
    // Explicit JavaBeans accessors per AAP §0.7.1 ("NO Lombok"). The order
    // matches the field-declaration order, which itself matches the COBOL
    // copybook layout for ease of cross-reference.
    // -------------------------------------------------------------------------

    /**
     * @return the 16-char primary-key transaction identifier
     *         (COBOL {@code DALYTRAN-ID})
     */
    public String getDalytranId() {
        return dalytranId;
    }

    /**
     * @param dalytranId the 16-char primary-key transaction identifier
     */
    public void setDalytranId(String dalytranId) {
        this.dalytranId = dalytranId;
    }

    /**
     * @return the 2-char transaction-type code
     *         (COBOL {@code DALYTRAN-TYPE-CD})
     */
    public String getDalytranTypeCd() {
        return dalytranTypeCd;
    }

    /**
     * @param dalytranTypeCd the 2-char transaction-type code
     */
    public void setDalytranTypeCd(String dalytranTypeCd) {
        this.dalytranTypeCd = dalytranTypeCd;
    }

    /**
     * @return the transaction-category code (1..9999)
     *         (COBOL {@code DALYTRAN-CAT-CD})
     */
    public Integer getDalytranCatCd() {
        return dalytranCatCd;
    }

    /**
     * @param dalytranCatCd the transaction-category code (1..9999)
     */
    public void setDalytranCatCd(Integer dalytranCatCd) {
        this.dalytranCatCd = dalytranCatCd;
    }

    /**
     * @return the origination source label
     *         (COBOL {@code DALYTRAN-SOURCE})
     */
    public String getDalytranSource() {
        return dalytranSource;
    }

    /**
     * @param dalytranSource the origination source label
     */
    public void setDalytranSource(String dalytranSource) {
        this.dalytranSource = dalytranSource;
    }

    /**
     * @return the human-readable transaction description
     *         (COBOL {@code DALYTRAN-DESC})
     */
    public String getDalytranDesc() {
        return dalytranDesc;
    }

    /**
     * @param dalytranDesc the human-readable transaction description
     */
    public void setDalytranDesc(String dalytranDesc) {
        this.dalytranDesc = dalytranDesc;
    }

    /**
     * @return the signed monetary amount with {@code scale = 2}
     *         (COBOL {@code DALYTRAN-AMT})
     */
    public BigDecimal getDalytranAmt() {
        return dalytranAmt;
    }

    /**
     * Sets the signed monetary amount. Callers MUST pass a
     * {@link BigDecimal} value that conforms to the
     * {@code precision = 11, scale = 2} constraint of the
     * underlying column; otherwise an
     * {@code OnSizeErrorException} (the Java equivalent of COBOL
     * {@code ON SIZE ERROR}) should be raised by the calling
     * service prior to invoking this setter. See AAP &sect;0.6.1 for
     * the full arithmetic-discipline rules.
     *
     * @param dalytranAmt the signed monetary amount
     */
    public void setDalytranAmt(BigDecimal dalytranAmt) {
        this.dalytranAmt = dalytranAmt;
    }

    /**
     * @return the 9-digit unsigned merchant identifier
     *         (COBOL {@code DALYTRAN-MERCHANT-ID})
     */
    public Long getDalytranMerchantId() {
        return dalytranMerchantId;
    }

    /**
     * @param dalytranMerchantId the 9-digit unsigned merchant identifier
     */
    public void setDalytranMerchantId(Long dalytranMerchantId) {
        this.dalytranMerchantId = dalytranMerchantId;
    }

    /**
     * @return the merchant display name
     *         (COBOL {@code DALYTRAN-MERCHANT-NAME})
     */
    public String getDalytranMerchantName() {
        return dalytranMerchantName;
    }

    /**
     * @param dalytranMerchantName the merchant display name
     */
    public void setDalytranMerchantName(String dalytranMerchantName) {
        this.dalytranMerchantName = dalytranMerchantName;
    }

    /**
     * @return the merchant city
     *         (COBOL {@code DALYTRAN-MERCHANT-CITY})
     */
    public String getDalytranMerchantCity() {
        return dalytranMerchantCity;
    }

    /**
     * @param dalytranMerchantCity the merchant city
     */
    public void setDalytranMerchantCity(String dalytranMerchantCity) {
        this.dalytranMerchantCity = dalytranMerchantCity;
    }

    /**
     * @return the merchant ZIP/postal code
     *         (COBOL {@code DALYTRAN-MERCHANT-ZIP})
     */
    public String getDalytranMerchantZip() {
        return dalytranMerchantZip;
    }

    /**
     * @param dalytranMerchantZip the merchant ZIP/postal code
     */
    public void setDalytranMerchantZip(String dalytranMerchantZip) {
        this.dalytranMerchantZip = dalytranMerchantZip;
    }

    /**
     * @return the 16-digit card PAN (PCI-DSS protected;
     *         {@link #toString()} masks all but the last 4 digits)
     *         (COBOL {@code DALYTRAN-CARD-NUM})
     */
    public String getDalytranCardNum() {
        return dalytranCardNum;
    }

    /**
     * Sets the 16-digit card PAN. Callers MUST treat the value as
     * PCI-DSS-restricted information: never write it to plaintext
     * logs, never embed it in URL paths, and never include it in any
     * exception message that may be persisted.
     *
     * @param dalytranCardNum the 16-digit card PAN
     */
    public void setDalytranCardNum(String dalytranCardNum) {
        this.dalytranCardNum = dalytranCardNum;
    }

    /**
     * @return the origination timestamp with microsecond precision
     *         (COBOL {@code DALYTRAN-ORIG-TS})
     */
    public LocalDateTime getDalytranOrigTs() {
        return dalytranOrigTs;
    }

    /**
     * @param dalytranOrigTs the origination timestamp with
     *                       microsecond precision
     */
    public void setDalytranOrigTs(LocalDateTime dalytranOrigTs) {
        this.dalytranOrigTs = dalytranOrigTs;
    }

    /**
     * @return the processing timestamp with microsecond precision
     *         (COBOL {@code DALYTRAN-PROC-TS})
     */
    public LocalDateTime getDalytranProcTs() {
        return dalytranProcTs;
    }

    /**
     * @param dalytranProcTs the processing timestamp with
     *                       microsecond precision
     */
    public void setDalytranProcTs(LocalDateTime dalytranProcTs) {
        this.dalytranProcTs = dalytranProcTs;
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
    // (AAP §0.6.6) and elides large textual fields (description) to keep
    // log output compact during high-throughput batch runs.
    // -------------------------------------------------------------------------

    /**
     * Equality is defined on the primary key ({@link #dalytranId}) only,
     * matching the standard JPA entity contract. Two
     * {@code DailyTransaction} instances are equal iff they have the
     * same {@code dalytranId} value (both {@code null} is treated as
     * equal &mdash; common during transient-state comparisons within a
     * test fixture but should not occur in persisted state).
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
        if (!(o instanceof DailyTransaction)) {
            return false;
        }
        DailyTransaction that = (DailyTransaction) o;
        return Objects.equals(dalytranId, that.dalytranId);
    }

    /**
     * Hash code derived from {@link #dalytranId} only, consistent
     * with the {@link #equals(Object)} contract above. Safe for use
     * as a hash-set or hash-map key once {@code dalytranId} has been
     * assigned (which is required before
     * {@code EntityManager.persist} by virtue of the
     * {@code nullable = false} primary-key constraint).
     *
     * @return the hash-code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(dalytranId);
    }

    /**
     * String representation suitable for log statements during the
     * batch posting pipeline. Includes the primary identifying
     * fields (id, type, category, amount, masked PAN, processing
     * timestamp) but deliberately omits the long-form description,
     * merchant details, and origination timestamp to keep log lines
     * compact at high throughput. The card-number PAN is masked
     * per PCI-DSS &mdash; only the last 4 digits are visible. See
     * AAP &sect;0.6.6 for full PCI-DSS rules.
     *
     * @return a non-sensitive string representation of this entity
     */
    @Override
    public String toString() {
        return "DailyTransaction{"
                + "dalytranId='" + dalytranId + '\''
                + ", dalytranTypeCd='" + dalytranTypeCd + '\''
                + ", dalytranCatCd=" + dalytranCatCd
                + ", dalytranAmt=" + dalytranAmt
                + ", dalytranCardNum='" + maskCardNumber(dalytranCardNum) + '\''
                + ", dalytranProcTs=" + dalytranProcTs
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
