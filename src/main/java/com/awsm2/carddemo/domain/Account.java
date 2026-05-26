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

import com.awsm2.carddemo.util.CobolCodec;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code accounts} table (Flyway
 * migration {@code V001__create_account.sql}). This entity is the
 * Java target for the COBOL {@code ACCOUNT-RECORD} layout defined in
 * {@code app/cpy/CVACT01Y.cpy} (RECLN = 300 bytes), and replaces the
 * mainframe VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}.
 *
 * <h2>Purpose</h2>
 * <p>{@code Account} is the <b>central financial entity</b> of the
 * CardDemo system. Every credit / debit transaction, interest
 * calculation, cycle-credit accumulation, and statement-generation
 * step in the CardDemo application revolves around the row identified
 * by {@code acct_id}. Without a populated {@code accounts} table, the
 * entire signon-to-statement business flow has no subject to act
 * upon. This entity carries the FIVE monetary balance fields, the
 * THREE lifecycle date fields, the account-status discriminator, the
 * billing ZIP code, and the disclosure-group key that drives interest-
 * rate selection.
 *
 * <h2>Consumers (Java services that read from / write to this table)</h2>
 * <ul>
 *   <li>{@code AccountViewService} (COBOL {@code COACTVWC}) &mdash;
 *       random read by {@code acct_id}, joined with {@code customers}
 *       via the {@code card_xref} table to render the account-view
 *       screen ({@code GET /api/accounts/{id}}).</li>
 *   <li>{@code AccountUpdateService} (COBOL {@code COACTUPC}) &mdash;
 *       random read + update under a
 *       {@code @Transactional(rollbackFor = Exception.class)} boundary
 *       with JPA {@link Version} optimistic locking. Replaces the
 *       COBOL before/after image comparison + {@code EXEC CICS
 *       SYNCPOINT ROLLBACK} pattern that is the ONLY explicit multi-
 *       dataset transactional integrity mechanism in the CardDemo
 *       source per AAP &sect;0.1.1.</li>
 *   <li>{@code BillPaymentService} (COBOL {@code COBIL00C}) &mdash;
 *       updates {@link #acctCurrBal} and {@link #acctCurrCycCredit}
 *       inside a {@code @Transactional} boundary that also writes the
 *       offsetting {@code transactions} row and publishes the
 *       {@code account.updated} MSK event.</li>
 *   <li>{@code InterestCalculationService} (COBOL {@code CBACT04C})
 *       &mdash; end-of-cycle batch job that reads every account row,
 *       joins against {@code tran_cat_bal} + {@code disclosure_group}
 *       (with {@code DEFAULT} fallback), computes interest via the
 *       formula {@code (balance * rate) / 1200} with
 *       {@link BigDecimal} {@code scale = 2} and
 *       {@code RoundingMode.HALF_EVEN} per AAP &sect;0.6.1, and
 *       updates {@link #acctCurrBal} /
 *       {@link #acctCurrCycCredit} / {@link #acctCurrCycDebit}.</li>
 *   <li>{@code TransactionPostingService} (COBOL {@code CBTRN01C} /
 *       {@code CBTRN02C} / {@code CBTRN03C}) &mdash; reads
 *       {@link #acctCurrBal} and {@link #acctCreditLimit} during the
 *       4-stage validation cascade (XREF / account / credit limit /
 *       card expiration). Reject codes 100-109 preserved verbatim per
 *       AAP &sect;0.1.1.</li>
 *   <li>{@code AccountFileReaderService} (COBOL {@code CBACT01C})
 *       &mdash; batch sequential scanner that emits every row in the
 *       {@code accounts} table for audit / reporting purposes.</li>
 *   <li>{@code StatementGenerationService} (COBOL {@code CBSTM03A} /
 *       {@code CBSTM03B}) &mdash; reads account balances and cycle
 *       credit/debit totals to print monthly statements; resets
 *       {@link #acctCurrCycCredit} / {@link #acctCurrCycDebit} to
 *       zero at end-of-cycle.</li>
 *   <li>{@code AccountRepository} (Spring Data JPA) &mdash; exposes
 *       {@code findById(Long acctId)} keyed by {@code acct_id};
 *       injected into every service above. ElastiCache Redis cache-
 *       aside is configured for high-frequency balance reads per AAP
 *       &sect;0.6.6 (TTL aligned to transaction frequency,
 *       {@code allkeys-lru} eviction).</li>
 * </ul>
 *
 * <h2>Monetary precision and BigDecimal discipline (AAP &sect;0.6.1)</h2>
 * <p>This entity carries <b>FIVE monetary fields</b>, each declared as
 * {@link BigDecimal} with {@code @Column(precision = 12, scale = 2)}:
 * <ul>
 *   <li>{@link #acctCurrBal} &mdash; current balance
 *       ({@code PIC S9(10)V99})</li>
 *   <li>{@link #acctCreditLimit} &mdash; total credit limit
 *       ({@code PIC S9(10)V99})</li>
 *   <li>{@link #acctCashCreditLimit} &mdash; cash advance sub-limit
 *       ({@code PIC S9(10)V99})</li>
 *   <li>{@link #acctCurrCycCredit} &mdash; cumulative current-cycle
 *       credits ({@code PIC S9(10)V99})</li>
 *   <li>{@link #acctCurrCycDebit} &mdash; cumulative current-cycle
 *       debits ({@code PIC S9(10)V99})</li>
 * </ul>
 *
 * <p>The precision &amp; scale mapping is mandatory and defensive:
 * <ul>
 *   <li>COBOL {@code PIC S9(10)V99} = signed, 10 integer digits + 2
 *       fractional digits = a value range of
 *       -9,999,999,999.99 .. +9,999,999,999.99.</li>
 *   <li>PostgreSQL {@code NUMERIC(12,2)} = 12 total digits of
 *       precision, 2 to the right of the decimal point. Precision
 *       12 = 10 (integer) + 2 (fractional). PostgreSQL {@code NUMERIC}
 *       is arbitrary-precision exact arithmetic; rounding only occurs
 *       at explicit scale changes.</li>
 *   <li>Java {@link BigDecimal} provides arbitrary-precision exact
 *       decimal arithmetic. <b>NEVER use {@code float} or
 *       {@code double}</b> for monetary values &mdash; floating-point
 *       loses precision on decimal arithmetic and breaks parity with
 *       the COBOL source.</li>
 *   <li>Banker's rounding ({@code RoundingMode.HALF_EVEN}) is
 *       enforced at every arithmetic boundary in the service layer
 *       (e.g.,
 *       {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_EVEN)}
 *       in {@code InterestCalculationService}) per AAP &sect;0.6.1.
 *       The divisor {@code 1200} is preserved literally (not
 *       pre-computed) per the Minimal Change Clause.</li>
 *   <li>COBOL {@code ON SIZE ERROR} semantics map to
 *       {@code OnSizeErrorException} thrown by the service layer if a
 *       computed value would exceed the column's {@code precision}
 *       per AAP &sect;0.6.1.</li>
 * </ul>
 *
 * <h2>{@code @Version} optimistic locking (AAP &sect;0.4.1)</h2>
 * <p>The {@link #version} field is annotated {@link Version} and
 * carries the JPA-idiomatic replacement for COBOL's before/after
 * image comparison in {@code COACTUPC.cbl} (CICS {@code READ UPDATE}
 * &rarr; {@code SYNCPOINT} &rarr; {@code REWRITE} with snapshot
 * mismatch detection). On every persistence operation:
 * <ol>
 *   <li>Hibernate auto-increments the {@code version} column.</li>
 *   <li>The {@code UPDATE} statement's {@code WHERE} clause includes
 *       the loaded {@code version} value; if the row's database
 *       {@code version} no longer matches (because another transaction
 *       has committed in the interim), the update affects 0 rows.</li>
 *   <li>Hibernate detects the 0-row update and throws
 *       {@link org.hibernate.StaleObjectStateException} &rarr;
 *       {@link jakarta.persistence.OptimisticLockException}.</li>
 *   <li>The {@code GlobalExceptionHandler} ({@code @RestControllerAdvice})
 *       translates this to a domain
 *       {@code ConcurrentModificationException} and returns HTTP 409
 *       Conflict to the REST caller, preserving the COBOL's "snapshot
 *       mismatch" error semantics.</li>
 * </ol>
 *
 * <h2>COBOL typo correction: {@code ACCT-EXPIRAION-DATE}</h2>
 * <p>The source COBOL copybook contains a spelling typo on
 * {@code app/cpy/CVACT01Y.cpy:L11}: {@code ACCT-EXPIRAION-DATE}
 * (missing the "T" before "ION"). The PostgreSQL column and Java field
 * adopt the corrected spelling {@code acct_expiration_date} /
 * {@link #acctExpirationDate} per AAP &sect;0.4.1 V001 row.
 *
 * <p>This is a deliberate, AAP-mandated spelling correction. It is a
 * <em>name-only</em> change at the Java / PostgreSQL boundary;
 * <em>byte position, semantics, and business logic</em> are
 * unchanged. The COBOL field name is preserved verbatim in the
 * inline traceability comment on the {@link #acctExpirationDate}
 * declaration.
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook:</b> {@code app/cpy/CVACT01Y.cpy} &mdash;
 *       300-byte fixed-width record layout with 12 business fields
 *       plus a 178-byte trailing {@code FILLER PIC X(178)}. The FILLER
 *       has no relational equivalent and is omitted from this entity.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS} &mdash;
 *       {@code KEYS(11,0)}, {@code RECORDSIZE(300,300)},
 *       {@code SHAREOPTIONS(2,3)}, {@code ERASE},
 *       {@code INDEXED} per
 *       {@code app/jcl/ACCTFILE.jcl}:L36-L49 and
 *       {@code app/catlg/LISTCAT.txt}. <em>No alternate index (AIX)
 *       or PATH</em> exists over this cluster &mdash; all COBOL access
 *       is by primary key (random read on {@code ACCT-ID}) or
 *       sequential scan ({@code CBACT01C}); the PostgreSQL B-tree on
 *       the PK satisfies both patterns.</li>
 *   <li><b>JCL allocation/load:</b> {@code app/jcl/ACCTFILE.jcl}
 *       (STEP10 IDCAMS DEFINE CLUSTER, STEP15 IDCAMS REPRO from
 *       {@code ACCTDATA.PS}).</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V001__create_account.sql}.</li>
 *   <li><b>Loading path:</b> Account master data is loaded for bulk-
 *       fact purposes by an AWS Glue Spark job per AAP &sect;0.6.2
 *       (Glue reads ASCII fixtures from S3 and writes to RDS via the
 *       Glue PostgreSQL connection); reference rows for local
 *       testing are loaded by Testcontainers fixtures and integration-
 *       test seed data.</li>
 * </ul>
 *
 * <h2>Schema invariants</h2>
 * <ul>
 *   <li><b>Simple primary key</b> &mdash; a single {@code BIGINT}
 *       column ({@link #acctId}), not a composite key. The 11-digit
 *       unsigned COBOL {@code PIC 9(11)} range fully fits within the
 *       8-byte signed PostgreSQL {@code BIGINT} (range
 *       -2<sup>63</sup>..2<sup>63</sup>-1) and maps directly to
 *       Java {@link Long}. Hibernate's
 *       {@code spring.jpa.hibernate.ddl-auto: validate} verifies the
 *       mapping at startup against the V001 DDL.</li>
 *   <li><b>Optimistic locking</b> &mdash; this entity has a
 *       {@link Version}-annotated {@link #version} field, replacing
 *       the COBOL before/after image comparison in
 *       {@code COACTUPC.cbl} per AAP &sect;0.4.1.</li>
 *   <li><b>No association mappings</b> &mdash; the foreign-key
 *       {@link #acctGroupId} (lookup into
 *       {@code disclosure_group.dis_acct_group_id}) is declared as a
 *       <em>scalar</em> {@code String} column rather than as a JPA
 *       {@code @ManyToOne} association. The composite-key lookup
 *       happens in {@code InterestCalculationService} with
 *       {@code DEFAULT} fallback. This is a deliberate Minimal
 *       Change Clause decision (AAP &sect;0.7.3): associations would
 *       introduce new traversal patterns not present in the COBOL
 *       source.</li>
 *   <li><b>FILLER omitted</b> &mdash; the trailing 178-byte
 *       {@code FILLER PIC X(178)} from the COBOL record has no
 *       relational counterpart and is not declared as a Java field.</li>
 *   <li><b>Anemic domain model</b> &mdash; this class carries only
 *       state (fields, getters, setters, identity contracts). All
 *       business logic lives in the corresponding {@code @Service}
 *       classes per the Layered Architecture pattern (AAP &sect;0.3.3
 *       / &sect;0.7.3). No business validation, no Jakarta
 *       {@code @NotNull} / {@code @DecimalMin} annotations
 *       (validation belongs in DTOs, not entities); no Lombok; no AWS
 *       SDK calls; no JSON / Jackson annotations (entities are
 *       converted to DTOs at the controller boundary).</li>
 * </ul>
 *
 * <h2>JPA mapping discipline</h2>
 * <ul>
 *   <li>{@code @Table(name = "accounts")} matches V001 DDL table name
 *       <em>exactly</em> (plural, snake_case &mdash; the same plural
 *       form used in V003 {@code customers}).</li>
 *   <li>{@code @Column(name = "...")} on every field matches V001
 *       column names <em>exactly</em>; {@code precision} and
 *       {@code scale} on monetary columns match V001 {@code NUMERIC(12,2)}
 *       declarations; {@code nullable} on every column corresponds
 *       1-to-1 with V001's {@code NOT NULL} declarations.</li>
 *   <li>{@code columnDefinition = "CHAR(1)"} on
 *       {@link #acctActiveStatus} preserves the fixed-width
 *       single-character semantics declared in V001 (and matches the
 *       V001 {@code chk_accounts_active_status CHECK} constraint
 *       restricting values to {@code 'Y'} or {@code 'N'}).</li>
 * </ul>
 *
 * <h2>Data quality notes for callers</h2>
 * <ul>
 *   <li>{@link #acctCurrCycCredit} and {@link #acctCurrCycDebit} are
 *       reset to zero at end-of-cycle by {@code StatementGenerationJob}
 *       (COBOL {@code CREASTMT} / {@code CBSTM03A} /
 *       {@code CBSTM03B}). Reading these values mid-cycle returns the
 *       running totals.</li>
 *   <li>{@link #acctGroupId} is the FK into
 *       {@code disclosure_group.dis_acct_group_id} (V007). When
 *       {@code null} (legacy fixture rows or pre-disclosure-group
 *       accounts), {@code InterestCalculationService} applies the
 *       {@code DEFAULT} disclosure group rate per the AAP &sect;0.4.1
 *       fallback rule.</li>
 *   <li>{@link #acctAddrZip} is the billing ZIP code, validated by
 *       {@code ValidationLookupService} (porting
 *       {@code app/cpy/CSLKPCDY.cpy}) at the application layer for
 *       NANPA / state / ZIP-prefix combinations per AAP &sect;0.7.1.</li>
 *   <li>{@link #acctOpenDate} is {@code NOT NULL} (every account has
 *       a known opening date); {@link #acctExpirationDate} is
 *       {@code NOT NULL} (every account has a known expiration date);
 *       {@link #acctReissueDate} is nullable (an account that has
 *       never been reissued has an all-spaces value in the COBOL
 *       source, which maps to PostgreSQL {@code NULL} per the
 *       standard COBOL-to-PostgreSQL all-spaces-&gt;NULL convention).</li>
 *   <li>{@code Customer} deletion does NOT cascade to {@code Account}
 *       per AAP &sect;0.3.1 &mdash; the entities are independent
 *       aggregates linked via the {@code card_xref} table.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.domain.Customer
 * @see com.awsm2.carddemo.domain.Card
 * @see com.awsm2.carddemo.domain.CardCrossReference
 * @see com.awsm2.carddemo.domain.DisclosureGroup
 * @see com.awsm2.carddemo.domain.TransactionCategoryBalance
 */
@Entity
@Table(name = "accounts")
public class Account implements Serializable {

    /**
     * Serializable version identifier. Required by {@link Serializable}
     * to ensure stable serialization semantics across persistence-
     * context boundaries, the ElastiCache Redis second-level cache
     * (per AAP &sect;0.6.6), and any Kafka serialization paths if
     * account snapshots are ever published to MSK topics. Incremented
     * only when the entity's serialized form changes in a backward-
     * incompatible way.
     */
    private static final long serialVersionUID = 1L;

    // -------------------------------------------------------------------------
    // Persistent fields
    //
    // Field declarations correspond one-to-one (in order) to the COBOL
    // ACCOUNT-RECORD layout in app/cpy/CVACT01Y.cpy. The trailing
    // FILLER PIC X(178) is intentionally omitted (no relational
    // equivalent for fixed-width VSAM padding). The terminal `version`
    // field is application-added per AAP §0.4.1 for JPA @Version
    // optimistic locking; it has no COBOL counterpart.
    // -------------------------------------------------------------------------

    /**
     * 11-digit unsigned account identifier &mdash; the primary key.
     *
     * <p>Maps to the COBOL field {@code 05 ACCT-ID PIC 9(11)} in
     * {@code app/cpy/CVACT01Y.cpy} (line 5) and to the V001
     * {@code acct_id BIGINT PRIMARY KEY} column.</p>
     *
     * <p>The PostgreSQL {@code BIGINT} (8-byte signed integer, range
     * -2<sup>63</sup>..2<sup>63</sup>-1) fully contains the COBOL
     * {@code PIC 9(11)} value range (0..99,999,999,999) and maps
     * directly to Java {@link Long}. Referenced as a foreign key by:
     * <ul>
     *   <li>{@code cards.acct_id} (V002 &mdash; account-to-card
     *       relationship)</li>
     *   <li>{@code card_xref.xref_acct_id} (V004 &mdash; 3-way
     *       cross-reference)</li>
     *   <li>{@code tran_cat_bal.acct_id} (V006 &mdash; category
     *       balance accumulator)</li>
     * </ul>
     */
    // COBOL: CVACT01Y.cpy:L5 ACCT-ID PIC 9(11) -- primary key
    @Id
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    /**
     * 1-character account-status flag.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-ACTIVE-STATUS PIC X(01)} in
     * {@code app/cpy/CVACT01Y.cpy} (line 6) and to the V001
     * {@code acct_active_status CHAR(1) NOT NULL} column.</p>
     *
     * <p>COBOL business rule: {@code 'Y'} = active (transactions may
     * be posted), {@code 'N'} = closed / inactive (transactions are
     * rejected at validation stage 2 in
     * {@code TransactionPostingService} per AAP &sect;0.4.1 /
     * {@code CBTRN02C} reject codes 100-109 preservation). A V001
     * {@code chk_accounts_active_status CHECK} constraint restricts
     * stored values to {@code ('Y','N')} as defense-in-depth per AAP
     * &sect;0.7.1 / &sect;0.7.3 &mdash; preventing corrupted values
     * from silently breaking transaction-posting decisions.</p>
     */
    // COBOL: CVACT01Y.cpy:L6 ACCT-ACTIVE-STATUS PIC X(01)
    // -- 'Y' active / 'N' inactive (CHECK constraint enforced at DB)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_active_status", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String acctActiveStatus;

    /**
     * Current account balance, signed 10-digit integer + 2 implied
     * decimal places.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-CURR-BAL PIC S9(10)V99} in
     * {@code app/cpy/CVACT01Y.cpy} (line 7) and to the V001
     * {@code acct_curr_bal NUMERIC(12,2) NOT NULL DEFAULT 0} column.</p>
     *
     * <p>Precision &amp; scale: precision = 10 (digits) + 2 (V99
     * fractional) = 12; scale = 2 per AAP &sect;0.6.1. Value range:
     * -9,999,999,999.99 .. +9,999,999,999.99. Banker's rounding
     * ({@code RoundingMode.HALF_EVEN}) is enforced at every arithmetic
     * boundary in {@code BillPaymentService},
     * {@code InterestCalculationService}, and
     * {@code TransactionPostingService} per AAP &sect;0.6.1.
     * <b>NEVER use {@code float} or {@code double}</b>.</p>
     */
    // COBOL: CVACT01Y.cpy:L7 ACCT-CURR-BAL PIC S9(10)V99 -- current balance
    // BigDecimal precision=12 scale=2 per AAP §0.6.1; RoundingMode.HALF_EVEN applied at service boundary
    @Column(name = "acct_curr_bal", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCurrBal;

    /**
     * Total credit limit, signed 10-digit integer + 2 implied decimal
     * places.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-CREDIT-LIMIT PIC S9(10)V99} in
     * {@code app/cpy/CVACT01Y.cpy} (line 8) and to the V001
     * {@code acct_credit_limit NUMERIC(12,2) NOT NULL DEFAULT 0}
     * column.</p>
     *
     * <p>Read by {@code TransactionPostingService} at validation
     * stage 3 (credit-limit check) &mdash; reject code 102
     * (credit-limit exceeded) preserved verbatim per AAP &sect;0.1.1
     * / {@code CBTRN02C}. The 10-digit precision permits balances up
     * to $9,999,999,999.99 &mdash; a forward-compatible range that
     * matches the COBOL source exactly.</p>
     */
    // COBOL: CVACT01Y.cpy:L8 ACCT-CREDIT-LIMIT PIC S9(10)V99 -- total credit limit
    @Column(name = "acct_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCreditLimit;

    /**
     * Cash-advance credit sub-limit, signed 10-digit integer + 2
     * implied decimal places.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} in
     * {@code app/cpy/CVACT01Y.cpy} (line 9) and to the V001
     * {@code acct_cash_credit_limit NUMERIC(12,2) NOT NULL DEFAULT 0}
     * column.</p>
     *
     * <p>Separate from {@link #acctCreditLimit}; read by
     * {@code TransactionPostingService} when the incoming
     * transaction's transaction-category-code identifies the
     * transaction as a cash advance (via the {@code tran_category}
     * lookup).</p>
     */
    // COBOL: CVACT01Y.cpy:L9 ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 -- cash advance sublimit
    @Column(name = "acct_cash_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCashCreditLimit;

    /**
     * Account-opening date, parsed from the COBOL ISO-8601
     * {@code 'YYYY-MM-DD'} string to native PostgreSQL {@code DATE}.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-OPEN-DATE PIC X(10)} in
     * {@code app/cpy/CVACT01Y.cpy} (line 10) and to the V001
     * {@code acct_open_date DATE NOT NULL} column.</p>
     *
     * <p>Native {@link LocalDate} enables JPA / Hibernate to map the
     * field directly without manual substring parsing. Required for
     * statement age calculation, regulatory reporting, and FICO-
     * vintage analysis. {@code NOT NULL} because every account has a
     * known opening date.</p>
     */
    // COBOL: CVACT01Y.cpy:L10 ACCT-OPEN-DATE PIC X(10)
    // -- account opening date (stored as 'YYYY-MM-DD' in COBOL, DATE in PG)
    @Column(name = "acct_open_date", nullable = false)
    private LocalDate acctOpenDate;

    /**
     * Account expiration date, parsed from the COBOL ISO-8601
     * {@code 'YYYY-MM-DD'} string to native PostgreSQL {@code DATE}.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-EXPIRAION-DATE PIC X(10)} in
     * {@code app/cpy/CVACT01Y.cpy} (line 11 &mdash; <b>note the COBOL
     * spelling typo "EXPIRAION"</b>) and to the V001
     * {@code acct_expiration_date DATE NOT NULL} column. The
     * PostgreSQL column and Java field adopt the corrected spelling
     * {@code expiration} per AAP &sect;0.4.1 V001 row. This is a
     * deliberate, AAP-mandated spelling correction at the
     * Java/PostgreSQL boundary &mdash; semantics, business logic, and
     * byte position are unchanged.</p>
     *
     * <p>Read by {@code TransactionPostingService} at validation
     * stage 4 (card expiration check) &mdash; reject code 103
     * preserved verbatim per AAP &sect;0.1.1 / {@code CBTRN02C}.</p>
     */
    // COBOL: CVACT01Y.cpy:L11 ACCT-EXPIRAION-DATE PIC X(10)
    // -- TYPO! corrected to "expiration" per V001 (per AAP §0.4.1)
    @Column(name = "acct_expiration_date", nullable = false)
    private LocalDate acctExpirationDate;

    /**
     * Card reissue / replacement date, parsed from the COBOL
     * ISO-8601 {@code 'YYYY-MM-DD'} string to native PostgreSQL
     * {@code DATE}.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-REISSUE-DATE PIC X(10)} in
     * {@code app/cpy/CVACT01Y.cpy} (line 12) and to the V001
     * {@code acct_reissue_date DATE} (nullable) column.</p>
     *
     * <p>Nullable because not every account has been reissued; new
     * accounts and never-reissued accounts have an all-spaces value
     * in the COBOL source which maps to PostgreSQL {@code NULL} per
     * the standard COBOL-to-PostgreSQL all-spaces-&gt;NULL
     * convention.</p>
     */
    // COBOL: CVACT01Y.cpy:L12 ACCT-REISSUE-DATE PIC X(10) -- card reissue date
    @Column(name = "acct_reissue_date")
    private LocalDate acctReissueDate;

    /**
     * Cumulative credits posted in the current billing cycle
     * (payments, refunds, reversals), signed 10-digit integer + 2
     * implied decimal places.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-CURR-CYC-CREDIT PIC S9(10)V99} in
     * {@code app/cpy/CVACT01Y.cpy} (line 13) and to the V001
     * {@code acct_curr_cyc_credit NUMERIC(12,2) NOT NULL DEFAULT 0}
     * column.</p>
     *
     * <p>Read + updated by {@code InterestCalculationService}
     * ({@code CBACT04C}) end-of-cycle and by {@code BillPaymentService}
     * ({@code COBIL00C}) when posting a customer payment. Reset to
     * zero at end-of-cycle by {@code StatementGenerationJob}
     * ({@code CREASTMT} / {@code CBSTM03A} / {@code CBSTM03B}).</p>
     */
    // COBOL: CVACT01Y.cpy:L13 ACCT-CURR-CYC-CREDIT PIC S9(10)V99
    // -- current billing-cycle credits (payments, refunds)
    @Column(name = "acct_curr_cyc_credit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCurrCycCredit;

    /**
     * Cumulative debits posted in the current billing cycle
     * (purchases, cash advances, fees, interest), signed 10-digit
     * integer + 2 implied decimal places.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-CURR-CYC-DEBIT PIC S9(10)V99} in
     * {@code app/cpy/CVACT01Y.cpy} (line 14) and to the V001
     * {@code acct_curr_cyc_debit NUMERIC(12,2) NOT NULL DEFAULT 0}
     * column.</p>
     *
     * <p>Read + updated by {@code InterestCalculationService}
     * ({@code CBACT04C}) end-of-cycle and by
     * {@code TransactionPostingService} ({@code CBTRN02C}) when
     * posting a non-payment transaction. Reset to zero at end-of-
     * cycle by {@code StatementGenerationJob}.</p>
     */
    // COBOL: CVACT01Y.cpy:L14 ACCT-CURR-CYC-DEBIT PIC S9(10)V99
    // -- current billing-cycle debits (purchases, fees)
    @Column(name = "acct_curr_cyc_debit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCurrCycDebit;

    /**
     * Account-holder ZIP / ZIP+4 (US) or postal code (international).
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-ADDR-ZIP PIC X(10)} in
     * {@code app/cpy/CVACT01Y.cpy} (line 15) and to the V001
     * {@code acct_addr_zip VARCHAR(10)} (nullable) column.</p>
     *
     * <p>10-character fixed-width in COBOL; stored as
     * {@code VARCHAR(10)} to accommodate 5-digit US ZIP
     * ({@code "78487"}), 5+4 hyphenated US ZIP
     * ({@code "78487-7965"}), and shorter international postal codes
     * without trailing-padding storage. Nullable &mdash; not every
     * account in the COBOL fixture has a non-empty ZIP (some legacy
     * accounts predate ZIP collection). Validated against the
     * {@code CSLKPCDY.cpy} NANPA / ZIP-prefix lookup at the
     * application layer by {@code ValidationLookupService} per AAP
     * &sect;0.7.1.</p>
     */
    // COBOL: CVACT01Y.cpy:L15 ACCT-ADDR-ZIP PIC X(10) -- billing zip code
    @Column(name = "acct_addr_zip", length = 10)
    private String acctAddrZip;

    /**
     * Disclosure-group lookup key into the {@code disclosure_group}
     * table.
     *
     * <p>Maps to the COBOL field
     * {@code 05 ACCT-GROUP-ID PIC X(10)} in
     * {@code app/cpy/CVACT01Y.cpy} (line 16) and to the V001
     * {@code acct_group_id VARCHAR(10)} (nullable) column.</p>
     *
     * <p>10-character fixed-width in COBOL; stored as
     * {@code VARCHAR(10)}. Drives interest-rate selection in
     * {@code InterestCalculationService} ({@code CBACT04C}) &mdash;
     * the lookup is {@code acct_group_id} &rarr;
     * {@code disclosure_group.dis_acct_group_id} with
     * {@code DEFAULT} fallback per AAP &sect;0.4.1
     * ({@code CBACT04C} lookup against {@code DisclosureGroup} with
     * {@code DEFAULT} fallback).</p>
     *
     * <p>Nullable because legacy fixture rows may have an all-spaces
     * value (which maps to {@code NULL}); when {@code null}, the
     * {@code InterestCalculationService} applies the {@code DEFAULT}
     * disclosure group rate. A FOREIGN KEY to {@code disclosure_group}
     * is NOT declared at the database level because that parent table
     * is created in V007 (Flyway enforces strict V001 &rarr; V002
     * &rarr; ... execution order and {@code disclosure_group} does
     * not yet exist at V001 apply time); the referential integrity
     * for this column is enforced by application-layer
     * {@code ValidationLookupService} and by the
     * {@code InterestCalculationService} DEFAULT-fallback contract.</p>
     */
    // COBOL: CVACT01Y.cpy:L16 ACCT-GROUP-ID PIC X(10)
    // -- FK to disclosure_group.dis_acct_group_id; interest-rate lookup
    @Column(name = "acct_group_id", length = 10)
    private String acctGroupId;

    // COBOL: CVACT01Y.cpy:L17 FILLER PIC X(178) -- OMITTED
    // (178 trailing bytes that bring the COBOL record to its declared
    // 300-byte RECORDSIZE. PostgreSQL has no concept of fixed-width
    // records, so the FILLER has no relational equivalent per AAP §0.6.2.)

    /**
     * Application-added {@link Version} column for JPA optimistic
     * locking. <b>No COBOL equivalent.</b>
     *
     * <p>Maps to the V001
     * {@code version BIGINT NOT NULL DEFAULT 0} column &mdash; an
     * application-added column with no COBOL field counterpart.
     * Replaces the COBOL before/after image comparison in
     * {@code COACTUPC.cbl} per AAP &sect;0.4.1 / &sect;0.6.2 /
     * &sect;0.7.1.</p>
     *
     * <p>Hibernate auto-increments this value on every save; a
     * stale-write attempt (where the row's database {@code version}
     * has moved since this entity was loaded) results in an
     * {@code UPDATE} statement that affects zero rows, which
     * Hibernate detects and surfaces as
     * {@link jakarta.persistence.OptimisticLockException}. The
     * {@code GlobalExceptionHandler} ({@code @RestControllerAdvice})
     * translates the JPA exception to a domain
     * {@code ConcurrentModificationException} and returns HTTP 409
     * Conflict to the REST caller.</p>
     *
     * <p>Although a {@link #setVersion(Long)} setter is exposed (for
     * test fixtures and DTO-mapping convenience), <b>application code
     * MUST NOT manually mutate the version</b>: Hibernate manages the
     * value as part of its persistence lifecycle, and manual
     * mutation defeats the optimistic-locking guarantee.</p>
     */
    // JPA @Version per AAP §0.4.1 -- replaces COBOL before/after image
    // comparison in COACTUPC.cbl. Hibernate auto-increments on every
    // update; OptimisticLockException → ConcurrentModificationException → HTTP 409
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Transient holder for the 178-byte COBOL FILLER trailer of the
     * ACCOUNT-RECORD layout (CVACT01Y.cpy). The CardDemo COBOL programs
     * historically initialised this region to SPACES, but the bytes are
     * preserved here verbatim so that the {@link #parse(byte[])} /
     * {@link #format()} round-trip is byte-identical against the
     * canonical {@code app/data/ASCII/acctdata.txt} fixture (AAP &sect;0.2.2).
     *
     * <p>This field is marked {@link Transient @Transient} because it has
     * no SQL representation in the {@code accounts} table (V001 schema);
     * it lives only in memory during fixed-width record marshalling for
     * ETL/migration purposes. Hibernate ignores it during persistence.</p>
     */
    @Transient
    private byte[] cobolFiller;

    // -------------------------------------------------------------------------
    // Constructors
    //
    // Two constructors are exposed:
    //   1) The JPA-required no-arg constructor (used by Hibernate when
    //      hydrating entities from result sets).
    //   2) An all-args constructor covering the TWELVE business fields
    //      (NOT the version field -- JPA manages version internally;
    //      setting version manually would defeat the optimistic-locking
    //      guarantee).
    //
    // No business validation is performed in either constructor;
    // validation is the responsibility of the corresponding DTO at the
    // controller boundary and of the service-layer pre-save checks.
    // -------------------------------------------------------------------------

    /**
     * JPA-required no-argument constructor.
     *
     * <p>Used by Hibernate when hydrating entities from result sets.
     * All fields are left {@code null} or default; the
     * {@link #setAcctId(Long)} and other setters must be invoked
     * before persisting or otherwise the {@code NOT NULL} columns
     * (per V001) will fail at the database layer.</p>
     */
    public Account() {
        // Intentionally empty -- field assignment occurs via setters
        // during JPA hydration or via the all-args constructor below.
    }

    /**
     * All-arguments constructor covering the 12 COBOL business
     * fields. The {@link #version} field is intentionally excluded:
     * JPA manages it on save.
     *
     * @param acctId              the 11-digit account identifier (primary key)
     * @param acctActiveStatus    the 'Y' / 'N' account-status flag
     * @param acctCurrBal         the current balance
     * @param acctCreditLimit     the total credit limit
     * @param acctCashCreditLimit the cash-advance credit sub-limit
     * @param acctOpenDate        the account-opening date
     * @param acctExpirationDate  the account expiration date
     * @param acctReissueDate     the most-recent reissue date (may be {@code null})
     * @param acctCurrCycCredit   the cumulative current-cycle credits
     * @param acctCurrCycDebit    the cumulative current-cycle debits
     * @param acctAddrZip         the billing ZIP code (may be {@code null})
     * @param acctGroupId         the disclosure-group lookup key (may be {@code null})
     */
    public Account(Long acctId,
                   String acctActiveStatus,
                   BigDecimal acctCurrBal,
                   BigDecimal acctCreditLimit,
                   BigDecimal acctCashCreditLimit,
                   LocalDate acctOpenDate,
                   LocalDate acctExpirationDate,
                   LocalDate acctReissueDate,
                   BigDecimal acctCurrCycCredit,
                   BigDecimal acctCurrCycDebit,
                   String acctAddrZip,
                   String acctGroupId) {
        this.acctId = acctId;
        this.acctActiveStatus = acctActiveStatus;
        this.acctCurrBal = acctCurrBal;
        this.acctCreditLimit = acctCreditLimit;
        this.acctCashCreditLimit = acctCashCreditLimit;
        this.acctOpenDate = acctOpenDate;
        this.acctExpirationDate = acctExpirationDate;
        this.acctReissueDate = acctReissueDate;
        this.acctCurrCycCredit = acctCurrCycCredit;
        this.acctCurrCycDebit = acctCurrCycDebit;
        this.acctAddrZip = acctAddrZip;
        this.acctGroupId = acctGroupId;
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    //
    // Provided for every persistent field including version (JPA / Spring
    // Data / Jackson all rely on the JavaBean property contract). Setters
    // for monetary balances are required because services mutate balances
    // during transaction posting, interest calculation, and bill payment.
    // -------------------------------------------------------------------------

    /**
     * Returns the 11-digit account identifier (primary key).
     *
     * @return the {@code acct_id} value, or {@code null} for an
     *         unpersisted instance
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the 11-digit account identifier.
     *
     * @param acctId the new {@code acct_id} value
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the {@code 'Y'} / {@code 'N'} account-status flag.
     *
     * @return the {@code acct_active_status} value
     */
    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    /**
     * Sets the {@code 'Y'} / {@code 'N'} account-status flag. Must
     * satisfy the V001 {@code chk_accounts_active_status} CHECK
     * constraint at persist / merge time.
     *
     * @param acctActiveStatus the new account-status value
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    /**
     * Returns the current account balance.
     *
     * @return the {@code acct_curr_bal} value as a
     *         {@link BigDecimal} with {@code scale = 2}
     */
    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    /**
     * Sets the current account balance. Callers MUST apply
     * {@code RoundingMode.HALF_EVEN} at the arithmetic boundary
     * before invoking this setter per AAP &sect;0.6.1.
     *
     * @param acctCurrBal the new {@code acct_curr_bal} value
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    /**
     * Returns the total credit limit.
     *
     * @return the {@code acct_credit_limit} value as a
     *         {@link BigDecimal} with {@code scale = 2}
     */
    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    /**
     * Sets the total credit limit.
     *
     * @param acctCreditLimit the new {@code acct_credit_limit} value
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    /**
     * Returns the cash-advance credit sub-limit.
     *
     * @return the {@code acct_cash_credit_limit} value as a
     *         {@link BigDecimal} with {@code scale = 2}
     */
    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    /**
     * Sets the cash-advance credit sub-limit.
     *
     * @param acctCashCreditLimit the new
     *                            {@code acct_cash_credit_limit} value
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    /**
     * Returns the account-opening date.
     *
     * @return the {@code acct_open_date} value
     */
    public LocalDate getAcctOpenDate() {
        return acctOpenDate;
    }

    /**
     * Sets the account-opening date.
     *
     * @param acctOpenDate the new {@code acct_open_date} value
     */
    public void setAcctOpenDate(LocalDate acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    /**
     * Returns the account expiration date.
     *
     * <p>Java field name uses the corrected spelling
     * "expiration"; the source COBOL field name
     * {@code ACCT-EXPIRAION-DATE} carries a spelling typo that is
     * deliberately not reproduced in Java per AAP &sect;0.4.1.</p>
     *
     * @return the {@code acct_expiration_date} value
     */
    public LocalDate getAcctExpirationDate() {
        return acctExpirationDate;
    }

    /**
     * Sets the account expiration date.
     *
     * @param acctExpirationDate the new {@code acct_expiration_date}
     *                           value
     */
    public void setAcctExpirationDate(LocalDate acctExpirationDate) {
        this.acctExpirationDate = acctExpirationDate;
    }

    /**
     * Returns the most-recent reissue / card-replacement date, or
     * {@code null} if the account has never been reissued.
     *
     * @return the {@code acct_reissue_date} value, or {@code null}
     */
    public LocalDate getAcctReissueDate() {
        return acctReissueDate;
    }

    /**
     * Sets the most-recent reissue / card-replacement date.
     *
     * @param acctReissueDate the new {@code acct_reissue_date} value
     *                        (may be {@code null})
     */
    public void setAcctReissueDate(LocalDate acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    /**
     * Returns the cumulative current-cycle credits.
     *
     * @return the {@code acct_curr_cyc_credit} value as a
     *         {@link BigDecimal} with {@code scale = 2}
     */
    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    /**
     * Sets the cumulative current-cycle credits.
     *
     * @param acctCurrCycCredit the new {@code acct_curr_cyc_credit}
     *                          value
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    /**
     * Returns the cumulative current-cycle debits.
     *
     * @return the {@code acct_curr_cyc_debit} value as a
     *         {@link BigDecimal} with {@code scale = 2}
     */
    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    /**
     * Sets the cumulative current-cycle debits.
     *
     * @param acctCurrCycDebit the new {@code acct_curr_cyc_debit}
     *                         value
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    /**
     * Returns the billing ZIP code, or {@code null}.
     *
     * @return the {@code acct_addr_zip} value, or {@code null}
     */
    public String getAcctAddrZip() {
        return acctAddrZip;
    }

    /**
     * Sets the billing ZIP code.
     *
     * @param acctAddrZip the new {@code acct_addr_zip} value (may be
     *                    {@code null})
     */
    public void setAcctAddrZip(String acctAddrZip) {
        this.acctAddrZip = acctAddrZip;
    }

    /**
     * Returns the disclosure-group lookup key, or {@code null} to
     * indicate that the {@code DEFAULT} disclosure group should be
     * used by {@code InterestCalculationService}.
     *
     * @return the {@code acct_group_id} value, or {@code null}
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * Sets the disclosure-group lookup key.
     *
     * @param acctGroupId the new {@code acct_group_id} value (may be
     *                    {@code null} to denote DEFAULT)
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    /**
     * Returns the JPA {@link Version} value.
     *
     * <p>This is the optimistic-locking version managed by Hibernate
     * &mdash; auto-incremented on every save, used in the {@code WHERE}
     * clause of every {@code UPDATE} to detect concurrent
     * modification.</p>
     *
     * @return the {@code version} value, or {@code null} for an
     *         unpersisted instance
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the JPA {@link Version} value. <b>Application code MUST
     * NOT manually mutate the version</b>: Hibernate manages this
     * value as part of its persistence lifecycle, and manual mutation
     * defeats the optimistic-locking guarantee.
     *
     * <p>The setter is exposed only for test fixtures and DTO-mapping
     * convenience (e.g., when reconstructing an Account from a
     * payload that carries the version round-trip).</p>
     *
     * @param version the new {@code version} value
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    // -------------------------------------------------------------------------
    // Identity contract (equals + hashCode + toString)
    //
    // Per the standard JPA-entity identity pattern, equals() and hashCode()
    // are derived from the PRIMARY KEY (acctId) only -- not from the
    // monetary balances or version. This ensures that the same logical
    // account row remains equal across persistence operations even as its
    // balances mutate. Collections (HashMap / HashSet) and second-level
    // caches rely on this contract.
    //
    // toString() returns a human-readable representation including the
    // primary key, status flag, monetary balances, lifecycle dates, group
    // ID, and version. Cycle credit/debit and ZIP are intentionally omitted
    // to keep log lines concise; full state is always available via the
    // getters. Balances are shown because they are routinely displayed to
    // the account holder and are not PII / PCI-DSS-sensitive in the same
    // way SSN or CVV would be -- but log redaction of monetary values
    // remains a CloudWatch log-filter concern per AAP §0.6.6.
    // -------------------------------------------------------------------------

    /**
     * Equality is defined as equality of the {@link #acctId} primary
     * key. Two {@code Account} instances with the same non-{@code null}
     * {@code acctId} are equal regardless of any field differences
     * (e.g., uncommitted balance changes vs. database state).
     *
     * @param o the reference object with which to compare
     * @return {@code true} if {@code o} is an {@code Account} with
     *         the same {@code acctId}; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account)) {
            return false;
        }
        Account account = (Account) o;
        return Objects.equals(acctId, account.acctId);
    }

    /**
     * Hash code derived from the {@link #acctId} primary key only.
     * Consistent with the {@link #equals(Object)} contract.
     *
     * @return the hash code for this account
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }

    /**
     * Human-readable representation suitable for log lines and
     * debugger inspection. Shows the primary key, status flag,
     * current balance, credit limit, lifecycle dates, group ID, and
     * version. Cycle credit/debit and ZIP are intentionally omitted
     * to keep log lines concise; full state is always available via
     * the getters.
     *
     * @return a string representation of this account
     */
    @Override
    public String toString() {
        return "Account{"
                + "acctId=" + acctId
                + ", acctActiveStatus='" + acctActiveStatus + '\''
                + ", acctCurrBal=" + acctCurrBal
                + ", acctCreditLimit=" + acctCreditLimit
                + ", acctOpenDate=" + acctOpenDate
                + ", acctExpirationDate=" + acctExpirationDate
                + ", acctGroupId='" + acctGroupId + '\''
                + ", version=" + version
                // Cycle credit/debit and ZIP omitted to keep log lines
                // concise; full state available via the getters.
                + '}';
    }

    // -------------------------------------------------------------------------
    // COBOL fixed-width record marshalling
    //
    // Code Review CP7 FINAL — CRITICAL: parse(byte[]) and format() are
    // required by GoldenOutputDiffTest#acctdata_roundTrip to prove the AAP
    // §0.2.2 byte-identical regulatory-output guarantee against the
    // canonical app/data/ASCII/acctdata.txt fixture.
    //
    // Layout per CVACT01Y.cpy (300 bytes total):
    //   ACCT-ID                 PIC 9(11)      offset 0,   length 11
    //   ACCT-ACTIVE-STATUS      PIC X(01)      offset 11,  length 1
    //   ACCT-CURR-BAL           PIC S9(10)V99  offset 12,  length 12 (zoned)
    //   ACCT-CREDIT-LIMIT       PIC S9(10)V99  offset 24,  length 12 (zoned)
    //   ACCT-CASH-CREDIT-LIMIT  PIC S9(10)V99  offset 36,  length 12 (zoned)
    //   ACCT-OPEN-DATE          PIC X(10)      offset 48,  length 10
    //   ACCT-EXPIRAION-DATE     PIC X(10)      offset 58,  length 10
    //   ACCT-REISSUE-DATE       PIC X(10)      offset 68,  length 10
    //   ACCT-CURR-CYC-CREDIT    PIC S9(10)V99  offset 78,  length 12 (zoned)
    //   ACCT-CURR-CYC-DEBIT     PIC S9(10)V99  offset 90,  length 12 (zoned)
    //   ACCT-ADDR-ZIP           PIC X(10)      offset 102, length 10
    //   ACCT-GROUP-ID           PIC X(10)      offset 112, length 10
    //   FILLER                  PIC X(178)     offset 122, length 178 (SPACES)
    // -------------------------------------------------------------------------

    /** Byte length of one ACCOUNT-RECORD per CVACT01Y.cpy. */
    public static final int COBOL_RECORD_LENGTH = 300;

    /**
     * Parse a single 300-byte COBOL ACCOUNT-RECORD into an {@link Account}.
     *
     * <p>The parser is the inverse of {@link #format()}: given the byte
     * output of {@code format()}, this method reconstructs the equivalent
     * {@link Account} including the transient FILLER trailer. The version
     * field is left {@code null} because COBOL records do not encode JPA
     * optimistic-lock state.</p>
     *
     * @param record exactly 300 bytes per CVACT01Y.cpy
     * @return the parsed {@link Account} (transient — not yet persisted)
     * @throws IllegalArgumentException if {@code record} is not exactly
     *                                  {@value #COBOL_RECORD_LENGTH} bytes
     */
    public static Account parse(byte[] record) {
        if (record == null || record.length != COBOL_RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "ACCOUNT-RECORD must be exactly " + COBOL_RECORD_LENGTH
                            + " bytes per CVACT01Y.cpy; got "
                            + (record == null ? "null" : record.length));
        }
        Account a = new Account();
        a.acctId = CobolCodec.parseLong(record, 0, 11);
        a.acctActiveStatus = CobolCodec.parseText(record, 11, 1);
        a.acctCurrBal = CobolCodec.parseZonedDecimal(record, 12, 12, 2);
        a.acctCreditLimit = CobolCodec.parseZonedDecimal(record, 24, 12, 2);
        a.acctCashCreditLimit = CobolCodec.parseZonedDecimal(record, 36, 12, 2);
        a.acctOpenDate = CobolCodec.parseLocalDate(record, 48, 10);
        a.acctExpirationDate = CobolCodec.parseLocalDate(record, 58, 10);
        a.acctReissueDate = CobolCodec.parseLocalDate(record, 68, 10);
        a.acctCurrCycCredit = CobolCodec.parseZonedDecimal(record, 78, 12, 2);
        a.acctCurrCycDebit = CobolCodec.parseZonedDecimal(record, 90, 12, 2);
        a.acctAddrZip = CobolCodec.parseText(record, 102, 10);
        a.acctGroupId = CobolCodec.parseText(record, 112, 10);
        a.cobolFiller = new byte[178];
        System.arraycopy(record, 122, a.cobolFiller, 0, 178);
        return a;
    }

    /**
     * Format this {@link Account} as a 300-byte COBOL ACCOUNT-RECORD.
     *
     * <p>The output is byte-identical to the input that produced this
     * entity via {@link #parse(byte[])}. If the entity was created by some
     * means other than {@link #parse(byte[])}, the FILLER trailer is
     * filled with 178 ASCII SPACE bytes (the COBOL default).</p>
     *
     * @return exactly 300 bytes per CVACT01Y.cpy
     */
    public byte[] format() {
        byte[] out = new byte[COBOL_RECORD_LENGTH];
        CobolCodec.put(out, 0, CobolCodec.formatLong(acctId == null ? 0L : acctId, 11));
        CobolCodec.put(out, 11, CobolCodec.formatText(acctActiveStatus, 1));
        CobolCodec.put(out, 12, CobolCodec.formatZonedDecimal(acctCurrBal, 12, 2));
        CobolCodec.put(out, 24, CobolCodec.formatZonedDecimal(acctCreditLimit, 12, 2));
        CobolCodec.put(out, 36, CobolCodec.formatZonedDecimal(acctCashCreditLimit, 12, 2));
        CobolCodec.put(out, 48, CobolCodec.formatLocalDate(acctOpenDate));
        CobolCodec.put(out, 58, CobolCodec.formatLocalDate(acctExpirationDate));
        CobolCodec.put(out, 68, CobolCodec.formatLocalDate(acctReissueDate));
        CobolCodec.put(out, 78, CobolCodec.formatZonedDecimal(acctCurrCycCredit, 12, 2));
        CobolCodec.put(out, 90, CobolCodec.formatZonedDecimal(acctCurrCycDebit, 12, 2));
        CobolCodec.put(out, 102, CobolCodec.formatText(acctAddrZip, 10));
        CobolCodec.put(out, 112, CobolCodec.formatText(acctGroupId, 10));
        if (cobolFiller != null && cobolFiller.length == 178) {
            System.arraycopy(cobolFiller, 0, out, 122, 178);
        } else {
            CobolCodec.fillSpaces(out, 122, 178);
        }
        return out;
    }
}
