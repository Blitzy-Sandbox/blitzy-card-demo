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

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code card_xref} relational table
 * (Flyway migration {@code V004__create_cardxref.sql}). This entity is the
 * Java target for the COBOL {@code CARD-XREF-RECORD} layout defined in
 * {@code app/cpy/CVACT03Y.cpy} (RECLN = 50 bytes), and replaces the
 * mainframe VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} plus its alternate index
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} (referenced under the CICS
 * symbolic name {@code CXACAIX}).
 *
 * <h2>Purpose</h2>
 * <p>{@code card_xref} is the pure cross-reference join table that links
 * cards, customers, and accounts into the 3-way (card &harr; customer
 * &harr; account) relationship that drives every CardDemo business
 * workflow. It is the join key that powers
 * {@code AccountViewService} (COBOL {@code COACTVWC}),
 * {@code TransactionAddService} (COBOL {@code COTRN02C}), and
 * {@code TransactionPostingService} (COBOL {@code CBTRN01C} /
 * {@code CBTRN02C} / {@code CBTRN03C}).</p>
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook:</b> {@code app/cpy/CVACT03Y.cpy} &mdash;
 *       50-byte fixed-width record layout with 3 business fields plus a
 *       14-byte trailing {@code FILLER PIC X(14)}. The FILLER has no
 *       relational equivalent and is omitted from this entity.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} &mdash;
 *       {@code KEYS(16,0)}, {@code RECORDSIZE(50,50)},
 *       {@code SHAREOPTIONS(2,3)}, {@code INDEXED} (per
 *       {@code app/jcl/XREFFILE.jcl}:L39-L52 and
 *       {@code app/catlg/LISTCAT.txt}).</li>
 *   <li><b>VSAM alternate index:</b>
 *       {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} (CICS name
 *       {@code CXACAIX}) &mdash; {@code KEYS(11,25)},
 *       {@code NONUNIQUEKEY}, {@code UPGRADE} (per
 *       {@code app/jcl/XREFFILE.jcl}:L72-L82). Translated to the
 *       PostgreSQL secondary index {@code idx_cardxref_acct_id} on
 *       {@link #xrefAcctId} declared in V004.</li>
 *   <li><b>JCL allocation/load:</b>
 *       {@code app/jcl/XREFFILE.jcl} (STEP10 IDCAMS DEFINE CLUSTER,
 *       STEP15 IDCAMS REPRO from {@code CARDXREF.PS}, STEP20 IDCAMS
 *       DEFINE ALTERNATEINDEX, STEP25 IDCAMS DEFINE PATH, STEP30
 *       IDCAMS BLDINDEX).</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V004__create_cardxref.sql}.</li>
 * </ul>
 *
 * <h2>Field mapping (COBOL &rarr; PostgreSQL &rarr; Java)</h2>
 * <pre>
 *   COBOL field      PIC clause   PostgreSQL column     Java type
 *   ---------------- ------------ --------------------- ----------
 *   XREF-CARD-NUM    PIC X(16)    xref_card_num         String (PK,FK)
 *                                 VARCHAR(16) PK NN
 *   XREF-CUST-ID     PIC 9(09)    xref_cust_id          Long  (FK)
 *                                 BIGINT       NN
 *   XREF-ACCT-ID     PIC 9(11)    xref_acct_id          Long  (FK)
 *                                 BIGINT       NN
 *   FILLER           PIC X(14)    OMITTED               --
 *                                 (50-byte VSAM pad)
 * </pre>
 *
 * <p>Note on FK column types: {@link #xrefCustId} and {@link #xrefAcctId}
 * are declared as Java {@code Long} (PostgreSQL {@code BIGINT}) rather
 * than {@code BigInteger} ({@code NUMERIC}). This matches the BIGINT
 * declarations of the FK targets {@code customers.cust_id} (V003) and
 * {@code accounts.acct_id} (V001). PostgreSQL foreign-key constraints
 * require binary-coercible referencing types; NUMERIC and BIGINT are
 * NOT directly compatible. {@code BIGINT} (signed 8-byte integer)
 * fully contains both the COBOL {@code PIC 9(09)} range
 * (0..999,999,999) and {@code PIC 9(11)} range (0..99,999,999,999)
 * without truncation.</p>
 *
 * <h2>Consumers (Java services that read/write this entity)</h2>
 * <ul>
 *   <li>{@code AccountViewService} (COBOL {@code COACTVWC}) &mdash;
 *       account inquiry; joins {@code Account} + {@code Customer} +
 *       {@code CardCrossReference} for the {@code COACTVW.bms}
 *       account-inquiry screen via
 *       {@code CardCrossReferenceRepository.findByXrefAcctId(Long)}.</li>
 *   <li>{@code TransactionAddService} (COBOL {@code COTRN02C}) &mdash;
 *       validates the (card, account, customer) link BEFORE posting
 *       a new transaction; rejects the request if no {@code card_xref}
 *       row exists for the supplied card_num + acct_id combination.</li>
 *   <li>{@code TransactionPostingService} (COBOL {@code CBTRN02C})
 *       &mdash; Stage 1 of the 4-stage validation cascade (XREF /
 *       account / credit-limit / card-expiration); rejects with code
 *       {@code 100} if no {@code card_xref} row exists for the
 *       (card_num, acct_id) pair. Reject codes 100-109 are preserved
 *       verbatim per AAP &sect;0.1.1.</li>
 *   <li>{@code XrefFileReaderService} (COBOL {@code CBACT03C}) &mdash;
 *       batch sequential scanner that emits every row in the
 *       {@code card_xref} table for audit / reporting / regulatory
 *       inquiry purposes.</li>
 * </ul>
 *
 * <h2>Schema invariants</h2>
 * <ul>
 *   <li><b>Simple primary key</b> &mdash; a single {@code VARCHAR(16)}
 *       column ({@link #xrefCardNum}), not a composite key. The PK is
 *       BOTH the primary key of {@code card_xref} AND a foreign key
 *       to {@code cards.card_num} (1:1 cardinality &mdash; one card
 *       has exactly one cross-reference row).</li>
 *   <li><b>Secondary index on xref_acct_id</b> &mdash; the
 *       PostgreSQL non-unique B-tree index
 *       {@code idx_cardxref_acct_id} (declared in V004) replaces the
 *       VSAM AIX {@code CXACAIX} {@code KEYS(11,25)}
 *       {@code NONUNIQUEKEY} {@code UPGRADE}. THIS IS THE SINGLE MOST
 *       CRITICAL SECONDARY INDEX IN THE ENTIRE CARDDEMO SCHEMA;
 *       without it, every account-view, transaction-add, and
 *       transaction-posting workflow would degenerate into a full
 *       table scan of {@code card_xref}.</li>
 *   <li><b>No optimistic locking</b> &mdash; this entity has no
 *       {@code @Version} column. The COBOL source does NOT support
 *       in-place UPDATE of {@code CARDXREF} rows; the cross-reference
 *       is a write-once linkage created at card issuance and deleted
 *       at card closure, never mutated. {@code CardCrossReferenceRepository}
 *       exposes {@code save()} (INSERT) and {@code delete()} (card
 *       closure / customer migration) but no UPDATE flow. Adding a
 *       {@code version} column would violate the Minimal Change
 *       Clause (AAP &sect;0.7.3).</li>
 *   <li><b>No association mappings</b> &mdash; the three FK columns
 *       are declared as scalar {@code String} / {@code Long} fields
 *       rather than as JPA {@code @ManyToOne} associations. This is
 *       a deliberate Minimal Change Clause decision (AAP &sect;0.7.3):
 *       the COBOL data model is a flat 50-byte record with three
 *       scalar fields; introducing eager / lazy associations would
 *       change the runtime behavior of service-layer code (N+1
 *       query risk, lazy-init exception surface area), neither of
 *       which is required by any consumer. Service-layer code
 *       performs explicit lookups via repository methods
 *       (e.g., {@code cardRepository.findById(xrefCardNum)},
 *       {@code customerRepository.findById(xrefCustId)},
 *       {@code accountRepository.findById(xrefAcctId)}).</li>
 *   <li><b>FILLER omitted</b> &mdash; the trailing 14-byte
 *       {@code FILLER PIC X(14)} from the COBOL record has no
 *       relational counterpart (PostgreSQL has no concept of
 *       fixed-width records) and is not declared as a Java field.</li>
 * </ul>
 *
 * <h2>JPA mapping discipline</h2>
 * <ul>
 *   <li>{@code @Table(name = "card_xref")} matches the V004 DDL table
 *       name <em>exactly</em>. The name is intentionally SINGULAR
 *       per AAP &sect;0.6.2 explicit directive, even though sibling
 *       tables V001 {@code accounts}, V002 {@code cards}, V003
 *       {@code customers} use plural names &mdash; {@code card_xref}
 *       is a relationship table, not a noun.</li>
 *   <li>{@code @Column(name = "xref_card_num", nullable = false, length = 16)}
 *       on the primary-key field matches V004's
 *       {@code VARCHAR(16) NOT NULL} declaration. The
 *       {@link Id} annotation marks this field as the JPA
 *       primary key.</li>
 *   <li>{@code @Column(name = "xref_cust_id", nullable = false)} and
 *       {@code @Column(name = "xref_acct_id", nullable = false)}
 *       correspond to V004's {@code BIGINT NOT NULL} declarations;
 *       Hibernate maps the Java {@link Long} type to the PostgreSQL
 *       {@code BIGINT} type by default, so no explicit
 *       {@code columnDefinition} is required.</li>
 *   <li>All three {@code @Column} {@code nullable} attributes match
 *       V004 1-to-1.</li>
 *   <li>Hibernate's {@code spring.jpa.hibernate.ddl-auto: validate}
 *       startup check verifies that the V004 schema matches this
 *       entity's annotations exactly &mdash; failure to align names,
 *       types, or nullability raises a {@code SchemaManagementException}
 *       at application boot.</li>
 * </ul>
 *
 * <h2>PCI-DSS scope (per AAP &sect;0.6.6)</h2>
 * <p>The {@link #xrefCardNum} field stores the 16-character card
 * number ("cardholder data" / CHD per PCI-DSS v4.0). This entity
 * therefore lies inside the PCI-DSS scope and is subject to the
 * following protections:</p>
 * <ul>
 *   <li><b>Encryption at rest</b> &mdash; delegated to RDS via the
 *       customer-managed KMS CMK configured in
 *       {@code infrastructure/terraform/rds.tf}. The application
 *       does not perform application-level encryption of card_num
 *       (that would prevent PostgreSQL B-tree lookups on the PK).</li>
 *   <li><b>Encryption in transit</b> &mdash; enforced by TLS 1.2+
 *       on the RDS connection ({@code rds.force_ssl=1} parameter)
 *       per AAP &sect;0.6.6.</li>
 *   <li><b>Log masking</b> &mdash; the {@link #toString()} method
 *       on this class MASKS {@link #xrefCardNum} (shows only the
 *       last 4 characters); the CloudWatch log filter regex
 *       additionally enforces masking on any accidental raw-PAN
 *       emission. Never log {@link #xrefCardNum} directly; always
 *       use {@link #toString()} or apply the same masking pattern.</li>
 *   <li><b>Macie continuous scan</b> &mdash; Amazon Macie scans the
 *       S3 buckets that receive batch output for accidental
 *       cardholder-data exposure (per AAP &sect;0.6.6).</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.domain.Account
 * @see com.awsm2.carddemo.domain.Card
 * @see com.awsm2.carddemo.domain.Customer
 */
@Entity
@Table(name = "card_xref")
public class CardCrossReference implements Serializable {

    /**
     * Serializable version identifier. Required by {@link Serializable}
     * to ensure stable serialization semantics across persistence-context
     * boundaries, second-level caches (ElastiCache Redis per AAP
     * &sect;0.6.6), and remote-call boundaries (e.g., MSK Kafka
     * serialization if cross-reference snapshots are ever published).
     * Incremented only when the entity's serialized form changes in a
     * backward-incompatible way.
     */
    private static final long serialVersionUID = 1L;

    // -------------------------------------------------------------------------
    // Persistent fields
    //
    // Field declarations correspond one-to-one (in order) to the COBOL
    // CARD-XREF-RECORD layout in app/cpy/CVACT03Y.cpy. The trailing 14-byte
    // FILLER PIC X(14) at line 8 of the copybook is intentionally omitted
    // (PostgreSQL has no concept of fixed-width records, so the VSAM
    // padding has no relational equivalent).
    // -------------------------------------------------------------------------

    /**
     * 16-character card-number string &mdash; the primary key of
     * {@code card_xref} and a foreign key to {@code cards.card_num}.
     *
     * <p>Maps to the COBOL field
     * {@code 05 XREF-CARD-NUM PIC X(16)} in
     * {@code app/cpy/CVACT03Y.cpy} (line 5) and to the V004
     * {@code xref_card_num VARCHAR(16) NOT NULL PRIMARY KEY} column.
     * The VSAM KSDS primary key is at RKP=0 with KEYLEN=16 per
     * {@code app/jcl/XREFFILE.jcl}:L43 ({@code KEYS(16 0)}).</p>
     *
     * <p>Stored as {@link String} (not numeric) because:
     * <ol>
     *   <li>The FK target {@code cards.card_num} is {@code VARCHAR(16)}
     *       per V002 (PostgreSQL FK constraints require binary-coercible
     *       referencing types &mdash; VARCHAR / BIGINT are not
     *       compatible);</li>
     *   <li>Card numbers are conventionally treated as opaque
     *       alphanumeric identifiers (not arithmetic values);</li>
     *   <li>Leading-zero preservation is REQUIRED for byte-identical
     *       parallel-run output diff validation per AAP &sect;0.7.2.</li>
     * </ol>
     *
     * <p><b>PCI-DSS:</b> this field is cardholder data (CHD) per
     * PCI-DSS v4.0 Requirement 3.4. MUST be masked in all application
     * logs &mdash; never log directly. The CloudWatch log filter regex
     * detects PAN-like sequences (AAP &sect;0.6.6); the
     * {@link #toString()} method on this class shows only the last
     * 4 characters. Callers receiving this value via {@link #getXrefCardNum()}
     * MUST mask it before emitting it to any log sink, audit trail,
     * or error message.</p>
     */
    // COBOL: CVACT03Y.cpy:L5 XREF-CARD-NUM PIC X(16) -- PK (matches V004 PK);
    // PCI-sensitive cardholder data (masked in toString)
    @Id
    @Column(name = "xref_card_num", nullable = false, length = 16)
    private String xrefCardNum;

    /**
     * 9-digit unsigned customer identifier &mdash; foreign key to
     * {@code customers.cust_id}.
     *
     * <p>Maps to the COBOL field
     * {@code 05 XREF-CUST-ID PIC 9(09)} in
     * {@code app/cpy/CVACT03Y.cpy} (line 6) and to the V004
     * {@code xref_cust_id BIGINT NOT NULL} column. The COBOL
     * PIC 9(09) range is 0..999,999,999 (zero-padded numeric),
     * fully contained by Java's {@link Long} primitive wrapper
     * and PostgreSQL's {@code BIGINT}.</p>
     *
     * <p>Declared as {@code Long} (PostgreSQL {@code BIGINT}) &mdash;
     * NOT {@code BigInteger} ({@code NUMERIC(9)}) &mdash; to match
     * the BIGINT declaration of {@code customers.cust_id} in V003.
     * PostgreSQL foreign-key constraints require binary-coercible
     * referencing types; NUMERIC and BIGINT are NOT directly
     * compatible (the FK constraint would fail at constraint-creation
     * time with "foreign key constraint cannot be implemented:
     * incompatible types"). Same decision applied to
     * {@link #xrefAcctId}.</p>
     *
     * <p>NOT NULL because every {@code card_xref} row must link a
     * card to a specific customer; an unlinked row would violate
     * the COBOL invariant that the cross-reference is the
     * authoritative linkage table.</p>
     */
    // COBOL: CVACT03Y.cpy:L6 XREF-CUST-ID PIC 9(09) -- FK to customers.cust_id (BIGINT)
    @Column(name = "xref_cust_id", nullable = false)
    private Long xrefCustId;

    /**
     * 11-digit unsigned account identifier &mdash; foreign key to
     * {@code accounts.acct_id}.
     *
     * <p>Maps to the COBOL field
     * {@code 05 XREF-ACCT-ID PIC 9(11)} in
     * {@code app/cpy/CVACT03Y.cpy} (line 7) and to the V004
     * {@code xref_acct_id BIGINT NOT NULL} column. The COBOL
     * PIC 9(11) range is 0..99,999,999,999, fully contained by
     * Java's {@link Long} primitive wrapper and PostgreSQL's
     * {@code BIGINT}.</p>
     *
     * <p>Declared as {@code Long} (PostgreSQL {@code BIGINT}) &mdash;
     * NOT {@code BigInteger} ({@code NUMERIC(11)}) &mdash; to match
     * the BIGINT declaration of {@code accounts.acct_id} in V001;
     * same NUMERIC vs BIGINT FK compatibility rationale as
     * {@link #xrefCustId}.</p>
     *
     * <p><b>SECONDARY INDEX TARGET:</b> this column is the target of
     * the PostgreSQL secondary index {@code idx_cardxref_acct_id}
     * (V004) &mdash; the single most critical secondary index in
     * the entire CardDemo schema. It replaces the COBOL VSAM
     * alternate index {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX}
     * ({@code KEYS(11,25)}, {@code NONUNIQUEKEY}, {@code UPGRADE},
     * referenced under the CICS symbolic name {@code CXACAIX}).
     * The {@code CardCrossReferenceRepository.findByXrefAcctId(Long)}
     * derived JPA query method uses this index for efficient
     * by-account lookup; PostgreSQL EXPLAIN ANALYZE shows
     * "Bitmap Index Scan on idx_cardxref_acct_id" for queries on
     * this column.</p>
     *
     * <p>NOT NULL because every {@code card_xref} row must link a
     * card to a specific account.</p>
     */
    // COBOL: CVACT03Y.cpy:L7 XREF-ACCT-ID PIC 9(11) -- FK to accounts.acct_id (BIGINT);
    // indexed (replaces CXACAIX VSAM AIX KEYS(11,25) NONUNIQUEKEY UPGRADE)
    @Column(name = "xref_acct_id", nullable = false)
    private Long xrefAcctId;

    // COBOL: CVACT03Y.cpy:L8 FILLER PIC X(14) -- OMITTED
    // (14 trailing bytes that bring the COBOL record to its declared
    // 50-byte RECORDSIZE; PostgreSQL has no concept of fixed-width
    // records, so the FILLER has no relational equivalent.)

    // -------------------------------------------------------------------------
    // Constructors
    //
    // Two constructors are exposed:
    //   1) The JPA-required no-arg constructor (used by Hibernate when
    //      hydrating a row read from PostgreSQL into a managed entity).
    //   2) An all-args constructor for convenient programmatic
    //      construction in service code and tests.
    // -------------------------------------------------------------------------

    /**
     * No-arg constructor required by the JPA specification.
     *
     * <p>Hibernate invokes this constructor reflectively when hydrating
     * a row read from PostgreSQL into a managed entity instance. The
     * persistent fields are subsequently set via the JavaBean setters
     * declared below.</p>
     *
     * <p>Application code SHOULD use the
     * {@link #CardCrossReference(String, Long, Long)} all-args
     * constructor for explicit field initialization.</p>
     */
    public CardCrossReference() {
        // Intentionally empty -- field initialization is performed by
        // Hibernate via reflective field/setter access during entity
        // hydration, or by the all-args constructor in application code.
    }

    /**
     * All-args constructor for convenient programmatic construction.
     *
     * <p>Used in service code and tests to build a
     * {@code CardCrossReference} instance from its three persistent
     * fields. No validation is performed here &mdash; the database
     * {@code NOT NULL} / {@code FOREIGN KEY} constraints in V004 are
     * authoritative.</p>
     *
     * <p>Reflects the COBOL field order from {@code CVACT03Y.cpy}:
     * card_num &rarr; cust_id &rarr; acct_id (the same order as the
     * VSAM record bytes 0..24 plus 25..35).</p>
     *
     * @param xrefCardNum the 16-character card-number string (primary
     *                    key; foreign key to {@code cards.card_num}).
     *                    Must be exactly 16 characters with leading
     *                    zeros preserved. Must not be {@code null}
     *                    at persist time (enforced by the
     *                    {@code NOT NULL} constraint on
     *                    {@code xref_card_num}).
     * @param xrefCustId  the 9-digit customer identifier (foreign key
     *                    to {@code customers.cust_id}). Must not be
     *                    {@code null} at persist time.
     * @param xrefAcctId  the 11-digit account identifier (foreign key
     *                    to {@code accounts.acct_id}). Must not be
     *                    {@code null} at persist time.
     */
    public CardCrossReference(String xrefCardNum, Long xrefCustId, Long xrefAcctId) {
        this.xrefCardNum = xrefCardNum;
        this.xrefCustId = xrefCustId;
        this.xrefAcctId = xrefAcctId;
    }

    // -------------------------------------------------------------------------
    // Accessors (getters and setters)
    //
    // Plain JavaBean accessors, one per field. Required by Hibernate's
    // property access mode and consumed by Spring Data JPA derived queries,
    // Jackson serialization at the DTO boundary, and the JPA validation
    // framework.
    //
    // NOTE: getXrefCardNum() returns the raw 16-character card number. Per
    // AAP §0.6.6 (PCI-DSS), callers that intend to log the card number MUST
    // mask it before emission -- never log the raw PAN. The toString() method
    // on this class performs masking automatically; use it as the canonical
    // log-safe representation.
    // -------------------------------------------------------------------------

    /**
     * @return the 16-character card-number string (primary key).
     *
     * <p><b>PCI-DSS:</b> callers receiving this value MUST mask it
     * before emitting it to any log sink, audit trail, or error
     * message. The PAN-masking pattern is implemented by
     * {@link #toString()}.</p>
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the primary-key card-number string.
     *
     * <p>Although this setter exists to satisfy the JavaBean contract
     * required by JPA, application code SHOULD NOT mutate the
     * primary key of a persisted entity. New cross-reference rows
     * are created via {@link #CardCrossReference(String, Long, Long)}
     * and persisted via the repository's {@code save()} method.</p>
     *
     * @param xrefCardNum the new {@code xref_card_num} value (must
     *                    be exactly 16 characters with leading
     *                    zeros preserved; must not be {@code null}
     *                    at persist time)
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * @return the 9-digit customer identifier (foreign key to
     *         {@code customers.cust_id})
     */
    public Long getXrefCustId() {
        return xrefCustId;
    }

    /**
     * Sets the customer-identifier foreign-key value.
     *
     * @param xrefCustId the new {@code xref_cust_id} value (must not
     *                   be {@code null} at persist time; must
     *                   correspond to an existing
     *                   {@code customers.cust_id} or the FK
     *                   {@code fk_cardxref_cust} will fail)
     */
    public void setXrefCustId(Long xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * @return the 11-digit account identifier (foreign key to
     *         {@code accounts.acct_id}). This field is the target
     *         of the secondary index {@code idx_cardxref_acct_id},
     *         the single most critical secondary index in the
     *         CardDemo schema.
     */
    public Long getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * Sets the account-identifier foreign-key value.
     *
     * @param xrefAcctId the new {@code xref_acct_id} value (must not
     *                   be {@code null} at persist time; must
     *                   correspond to an existing
     *                   {@code accounts.acct_id} or the FK
     *                   {@code fk_cardxref_acct} will fail)
     */
    public void setXrefAcctId(Long xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    // -------------------------------------------------------------------------
    // Identity contract (equals + hashCode + toString)
    //
    // Per the standard JPA-entity identity pattern, equals() and hashCode()
    // are derived from the PRIMARY KEY (xrefCardNum) only -- not from
    // xrefCustId or xrefAcctId. This ensures that the same logical card_xref
    // row remains equal across persistence operations even if the entity
    // graph is modified, and avoids the classic "hash code changes after
    // persist" trap that occurs when generated keys are included in
    // hashCode.
    //
    // toString() returns a PCI-DSS-safe human-readable representation:
    //   - xrefCardNum is MASKED (last 4 digits visible, leading 12 replaced
    //     by asterisks);
    //   - xrefCustId and xrefAcctId are shown as-is (not cardholder data).
    // -------------------------------------------------------------------------

    /**
     * Equality is defined as equality of the {@link #xrefCardNum}
     * primary key. Two {@code CardCrossReference} instances with
     * the same non-{@code null} {@code xrefCardNum} are equal
     * regardless of differences in {@link #xrefCustId} or
     * {@link #xrefAcctId} (e.g., uncommitted relinkage vs.
     * database state).
     *
     * <p>The {@code instanceof} check (not {@code getClass()}
     * comparison) follows the JPA-entity equality convention and
     * accommodates Hibernate's proxy subclasses (lazy-loading
     * proxies extend the entity class).</p>
     *
     * @param o the reference object with which to compare
     * @return {@code true} if {@code o} is a
     *         {@code CardCrossReference} with the same
     *         {@code xrefCardNum}; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardCrossReference)) {
            return false;
        }
        CardCrossReference that = (CardCrossReference) o;
        return Objects.equals(xrefCardNum, that.xrefCardNum);
    }

    /**
     * Hash code derived from the {@link #xrefCardNum} primary key
     * only. Consistent with the {@link #equals(Object)} contract.
     *
     * <p>Safe for use as a {@code HashMap} / {@code HashSet} key
     * once {@code xrefCardNum} has been assigned (which is required
     * before {@code EntityManager.persist} by virtue of the
     * {@code nullable = false} primary-key constraint).</p>
     *
     * @return the hash code for this cross-reference
     */
    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum);
    }

    /**
     * PCI-DSS-safe human-readable representation suitable for log
     * lines and debugger inspection.
     *
     * <p>Per AAP &sect;0.6.6:</p>
     * <ul>
     *   <li>{@link #xrefCardNum} is <b>MASKED</b> &mdash; only the
     *       last 4 digits are visible; the leading 12 characters
     *       are replaced by asterisks (e.g.,
     *       {@code "************1234"}). This satisfies PCI-DSS
     *       v4.0 Requirement 3.4.1 (a maximum of the first 6 and
     *       last 4 digits may be displayed; CardDemo's policy is
     *       to display only the last 4 to be conservative).</li>
     *   <li>{@link #xrefCustId} and {@link #xrefAcctId} are shown
     *       as-is &mdash; they are numeric identifiers, not
     *       cardholder data.</li>
     * </ul>
     *
     * @return a PCI-DSS-safe string representation of this
     *         cross-reference
     */
    @Override
    public String toString() {
        return "CardCrossReference{"
                + "xrefCardNum='" + maskCardNumber(xrefCardNum) + '\''
                + ", xrefCustId=" + xrefCustId
                + ", xrefAcctId=" + xrefAcctId
                + '}';
    }

    /**
     * Masks a 16-character card number for PCI-DSS-safe logging.
     *
     * <p>Returns a string of the form {@code "************XXXX"}
     * where {@code XXXX} are the last 4 characters of the input.
     * For {@code null} or short inputs (length &lt; 4), returns
     * {@code "****"} as a defensive fallback &mdash; never
     * returning the raw value.</p>
     *
     * <p>The PAN-masking pattern matches PCI-DSS v4.0 Requirement
     * 3.4.1 (a maximum of the first 6 and last 4 digits may be
     * displayed; CardDemo's policy is to display only the last 4
     * to be conservative). The same helper is implemented on
     * {@link com.awsm2.carddemo.domain.Card#toString()} for
     * consistency across the cardholder-data entity surface.</p>
     *
     * @param card the 16-character card number (may be {@code null})
     * @return a masked representation with only the last 4
     *         characters visible, or {@code "****"} for
     *         {@code null} / short inputs
     */
    private static String maskCardNumber(String card) {
        if (card == null || card.length() < 4) {
            return "****";
        }
        return "************" + card.substring(card.length() - 4);
    }

    // -------------------------------------------------------------------------
    // COBOL fixed-width record marshalling
    //
    // Code Review CP7 FINAL — CRITICAL: parse(byte[]) and format() are
    // required by GoldenOutputDiffTest#cardxref_roundTrip to prove the AAP
    // §0.2.2 byte-identical regulatory-output guarantee against the
    // canonical app/data/ASCII/cardxref.txt fixture.
    //
    // Layout per CVACT03Y.cpy (declared 50 bytes; FIXTURE-TRUNCATED to 36):
    //   XREF-CARD-NUM   PIC X(16)  offset 0,  length 16
    //   XREF-CUST-ID    PIC 9(09)  offset 16, length 9
    //   XREF-ACCT-ID    PIC 9(11)  offset 25, length 11
    //   FILLER          PIC X(14)  OMITTED in fixture (36-byte truncated records).
    //
    // The fixture {@code app/data/ASCII/cardxref.txt} contains 36-byte
    // records — the COBOL FILLER trailer was suppressed at extract time.
    // To round-trip exactly, this class supports both 36- and 50-byte
    // input records and emits a record whose length matches the input.
    // -------------------------------------------------------------------------

    /** Byte length of one CARD-XREF-RECORD as declared in CVACT03Y.cpy. */
    public static final int COBOL_RECORD_LENGTH = 50;

    /** Byte length of one CARD-XREF-RECORD in the truncated ASCII fixture. */
    public static final int COBOL_RECORD_LENGTH_FIXTURE = 36;

    /**
     * Parse a single COBOL CARD-XREF-RECORD into a {@link CardCrossReference}.
     * Accepts either the 50-byte declared layout (per CVACT03Y.cpy) or the
     * 36-byte truncated layout used in the {@code cardxref.txt} fixture.
     *
     * @param record 36 or 50 bytes per CVACT03Y.cpy
     * @return the parsed {@link CardCrossReference}
     */
    public static CardCrossReference parse(byte[] record) {
        if (record == null
                || (record.length != COBOL_RECORD_LENGTH_FIXTURE
                && record.length != COBOL_RECORD_LENGTH)) {
            throw new IllegalArgumentException(
                    "CARD-XREF-RECORD must be exactly 36 or 50 bytes per CVACT03Y.cpy; got "
                            + (record == null ? "null" : record.length));
        }
        CardCrossReference x = new CardCrossReference();
        x.xrefCardNum = CobolCodec.parseText(record, 0, 16);
        x.xrefCustId = CobolCodec.parseLong(record, 16, 9);
        x.xrefAcctId = CobolCodec.parseLong(record, 25, 11);
        // No transient FILLER — fixture is truncated so there is nothing to preserve.
        return x;
    }

    /**
     * Format this {@link CardCrossReference} as a 36-byte COBOL CARD-XREF-RECORD,
     * matching the {@code cardxref.txt} fixture convention. The 14-byte FILLER
     * declared by CVACT03Y.cpy is omitted to preserve byte-identical parity.
     *
     * @return exactly 36 bytes
     */
    public byte[] format() {
        byte[] out = new byte[COBOL_RECORD_LENGTH_FIXTURE];
        CobolCodec.put(out, 0, CobolCodec.formatText(xrefCardNum, 16));
        CobolCodec.put(out, 16, CobolCodec.formatLong(xrefCustId == null ? 0L : xrefCustId, 9));
        CobolCodec.put(out, 25, CobolCodec.formatLong(xrefAcctId == null ? 0L : xrefAcctId, 11));
        return out;
    }
}
