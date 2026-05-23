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
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA {@link Entity} mapped to the {@code cards} table (Flyway migration
 * {@code V002__create_card.sql}). This entity is the Java target for the
 * COBOL {@code CARD-RECORD} layout defined in
 * {@code app/cpy/CVACT02Y.cpy} (RECLN = 150 bytes), and replaces the
 * mainframe VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} plus its alternate index
 * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX}
 * ({@code KEYS(11 16) NONUNIQUEKEY UPGRADE}).
 *
 * <h2>Purpose</h2>
 * <p>Represents an individual credit / debit card associated with an
 * account. Every {@code Card} row is linked to exactly one
 * {@link Account} row via the {@link #cardAcctId} foreign key, but a
 * single account may have multiple cards (the COBOL VSAM AIX is
 * {@code NONUNIQUEKEY}). The card row carries the 16-character primary
 * card number (PAN), the 3-digit CVV, the embossed name, the expiration
 * date, and the {@code 'Y'} / {@code 'N'} active-status flag &mdash;
 * exactly the seven fields from the COBOL copybook minus the trailing
 * {@code FILLER PIC X(59)} (omitted per AAP &sect;0.6.2 because
 * PostgreSQL has no concept of fixed-width record padding).
 *
 * <h2>Consumers (Java services that read from / write to this table)</h2>
 * <ul>
 *   <li>{@code CardListService} (COBOL {@code COCRDLIC}) &mdash;
 *       paginated browse by {@link #cardAcctId} via the
 *       {@code findByCardAcctId(Long acctId, Pageable pageable)}
 *       derived query method on {@code CardRepository}. The
 *       {@code Pageable} size of {@code 7} matches the
 *       {@code COCRDLI.bms} 7-row-per-page card-list screen. Uses the
 *       PostgreSQL secondary index {@code idx_cards_acct_id} (V002)
 *       which replaces the COBOL {@code CARDDATA.VSAM.AIX} alternate
 *       index for efficient by-account lookup.</li>
 *   <li>{@code CardDetailService} (COBOL {@code COCRDSLC}) &mdash;
 *       random read by {@link #cardNum} (the 16-character primary
 *       key) via {@code CardRepository.findById(String cardNum)};
 *       the entity is loaded into the {@code COCRDSL.bms}
 *       card-detail screen ({@code GET /api/cards/{cardNumber}}).</li>
 *   <li>{@code CardUpdateService} (COBOL {@code COCRDUPC}) &mdash;
 *       random read + update under a
 *       {@code @Transactional(rollbackFor = Exception.class)} boundary
 *       with JPA {@link Version} optimistic locking. Replaces the
 *       COBOL before/after image comparison + {@code EXEC CICS READ
 *       UPDATE} / {@code REWRITE} pattern per AAP &sect;0.4.1 /
 *       &sect;0.6.2 / &sect;0.7.1.</li>
 *   <li>{@code TransactionPostingService} (COBOL {@code CBTRN01C} /
 *       {@code CBTRN02C} / {@code CBTRN03C}) &mdash; reads
 *       {@link #cardExpirationDate} and {@link #cardActiveStatus}
 *       during the 4-stage validation cascade (XREF / account /
 *       credit-limit / card-expiration); reject codes 100-109
 *       preserved verbatim per AAP &sect;0.1.1.</li>
 *   <li>{@code TransactionAddService} (COBOL {@code COTRN02C})
 *       &mdash; looks up the card row when authorizing a new
 *       transaction; also joins via {@code card_xref} to resolve the
 *       owning account.</li>
 *   <li>{@code CardFileReaderService} (COBOL {@code CBACT02C})
 *       &mdash; batch sequential scanner that emits every row in the
 *       {@code cards} table for audit / reporting purposes.</li>
 *   <li>{@code StatementGenerationService} (COBOL {@code CBSTM03A} /
 *       {@code CBSTM03B}) &mdash; prints {@link #cardEmbossedName}
 *       and {@link #cardNum} (always masked) onto monthly
 *       statements.</li>
 *   <li>{@code CardRepository} (Spring Data JPA) &mdash; exposes
 *       {@code findById(String cardNum)} keyed by {@link #cardNum}
 *       and the paged
 *       {@code findByCardAcctId(Long acctId, Pageable pageable)}
 *       derived query; injected into every service above.</li>
 * </ul>
 *
 * <h2>{@code @Version} optimistic locking (AAP &sect;0.4.1)</h2>
 * <p>The {@link #version} field is annotated {@link Version} and
 * carries the JPA-idiomatic replacement for COBOL's before/after
 * image comparison in {@code COCRDUPC.cbl} (CICS {@code READ UPDATE}
 * &rarr; {@code REWRITE} with snapshot mismatch detection). On every
 * persistence operation:
 * <ol>
 *   <li>Hibernate auto-increments the {@code version} column.</li>
 *   <li>The {@code UPDATE} statement's {@code WHERE} clause includes
 *       the loaded {@code version} value; if the row's database
 *       {@code version} no longer matches (because another transaction
 *       has committed in the interim), the update affects 0 rows.</li>
 *   <li>Hibernate detects the 0-row update and throws
 *       {@link org.hibernate.StaleObjectStateException} &rarr;
 *       {@link jakarta.persistence.OptimisticLockException}.</li>
 *   <li>The {@code GlobalExceptionHandler}
 *       ({@code @RestControllerAdvice}) translates this to a domain
 *       {@code ConcurrentModificationException} and returns HTTP 409
 *       Conflict to the REST caller, preserving the COBOL's
 *       "snapshot mismatch" error semantics.</li>
 * </ol>
 *
 * <h2>COBOL typo correction: {@code CARD-EXPIRAION-DATE}</h2>
 * <p>The source COBOL copybook contains a spelling typo on
 * {@code app/cpy/CVACT02Y.cpy:L9}: {@code CARD-EXPIRAION-DATE}
 * (missing the "T" before "ION"). The PostgreSQL column and Java
 * field adopt the corrected spelling {@code card_expiration_date} /
 * {@link #cardExpirationDate} per AAP &sect;0.4.1 V002 row &mdash;
 * the same pattern applied to {@code ACCT-EXPIRAION-DATE} in V001 /
 * {@link Account}.
 *
 * <p>This is a deliberate, AAP-mandated spelling correction. It is a
 * <em>name-only</em> change at the Java / PostgreSQL boundary;
 * <em>byte position, semantics, and business logic</em> are
 * unchanged. The COBOL field name is preserved verbatim in the
 * inline traceability comment on the {@link #cardExpirationDate}
 * declaration.
 *
 * <h2>PCI-DSS scope (AAP &sect;0.6.6)</h2>
 * <p>This entity holds two PCI-DSS-classified data elements:
 * <ul>
 *   <li><b>{@link #cardNum}</b> &mdash; "cardholder data" (CHD) per
 *       PCI-DSS v4.0 Requirement 3.4. The full 16-digit PAN MUST be
 *       masked in application logs (the
 *       {@link #toString()} method on this class shows only the last
 *       4 digits; the CloudWatch log filter regex enforces masking
 *       on any PAN-like sequence that leaks through other code
 *       paths). Encryption at rest is delegated to RDS via the
 *       customer KMS CMK (configured in
 *       {@code infrastructure/terraform/rds.tf}); encryption in
 *       transit via the {@code rds.force_ssl=1} parameter.</li>
 *   <li><b>{@link #cardCvvCd}</b> &mdash; "sensitive authentication
 *       data" (SAD) per PCI-DSS v4.0 Requirement 3.2. SAD <b>MUST
 *       NOT be persisted post-authorization</b> in a real payment-
 *       card environment. CardDemo persists it ONLY because the
 *       COBOL source persists it ({@code CVACT02Y.cpy:L7}) and the
 *       Minimal Change Clause (AAP &sect;0.7.3) forbids removing
 *       fields that exist in the COBOL source. The
 *       {@link #toString()} method on this class <b>intentionally
 *       omits the CVV</b>; application code MUST NOT log it under
 *       any circumstance and MUST NOT return it in any API response
 *       outside of card-update flows that explicitly require it.</li>
 * </ul>
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL copybook:</b> {@code app/cpy/CVACT02Y.cpy}
 *       &mdash; 150-byte fixed-width record layout with 6 business
 *       fields plus a 59-byte trailing {@code FILLER PIC X(59)}.
 *       The FILLER has no relational equivalent and is omitted from
 *       this entity.</li>
 *   <li><b>VSAM cluster:</b>
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} &mdash;
 *       {@code KEYS(16,0)}, {@code RECORDSIZE(150,150)},
 *       {@code SHAREOPTIONS(2,3)}, {@code ERASE},
 *       {@code INDEXED} per {@code app/jcl/CARDFILE.jcl}:L50-L63
 *       and {@code app/catlg/LISTCAT.txt} L164-L222.</li>
 *   <li><b>VSAM alternate index:</b>
 *       {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} &mdash;
 *       {@code KEYS(11,16) NONUNIQUEKEY UPGRADE} per
 *       {@code app/jcl/CARDFILE.jcl}:L83-L92. Replaced by the
 *       PostgreSQL secondary index {@code idx_cards_acct_id} on
 *       {@link #cardAcctId} (V002).</li>
 *   <li><b>JCL allocation/load:</b> {@code app/jcl/CARDFILE.jcl}
 *       (STEP10 IDCAMS DEFINE CLUSTER, STEP15 IDCAMS REPRO from
 *       {@code CARDDATA.PS}, STEP20 IDCAMS DEFINE ALTERNATEINDEX,
 *       STEP30 IDCAMS BLDINDEX).</li>
 *   <li><b>Flyway DDL:</b>
 *       {@code src/main/resources/db/migration/V002__create_card.sql}.</li>
 *   <li><b>Loading path:</b> Card master data is loaded for bulk-
 *       fact purposes by an AWS Glue Spark job per AAP &sect;0.6.2
 *       (Glue reads ASCII fixtures from S3 and writes to RDS via
 *       the Glue PostgreSQL connection); reference rows for local
 *       testing are loaded by Testcontainers fixtures and
 *       integration-test seed data.</li>
 * </ul>
 *
 * <h2>Schema invariants</h2>
 * <ul>
 *   <li><b>Simple primary key</b> &mdash; a single
 *       {@code VARCHAR(16)} column ({@link #cardNum}), not a
 *       composite key. The 16-character COBOL {@code PIC X(16)} value
 *       is stored as-is (NOT cast to {@code BIGINT}) because card
 *       numbers are conventionally treated as opaque identifier
 *       strings, leading-zero preservation is required for byte-
 *       identical parallel-run output diff per AAP &sect;0.7.2, and
 *       Luhn-check / BIN-range parsing operates on the string form.
 *       Maps directly to Java {@link String}. Hibernate's
 *       {@code spring.jpa.hibernate.ddl-auto: validate} verifies the
 *       mapping at startup against the V002 DDL.</li>
 *   <li><b>Optimistic locking</b> &mdash; this entity has a
 *       {@link Version}-annotated {@link #version} field, replacing
 *       the COBOL before/after image comparison in
 *       {@code COCRDUPC.cbl} per AAP &sect;0.4.1.</li>
 *   <li><b>Scalar foreign key, no association mapping</b> &mdash;
 *       the {@link #cardAcctId} foreign-key column is declared as a
 *       <em>scalar</em> {@code Long} field rather than as a JPA
 *       {@code @ManyToOne Account} association. The referential
 *       integrity is enforced at the database level (V002
 *       {@code fk_cards_acct FOREIGN KEY (card_acct_id) REFERENCES
 *       accounts(acct_id) ON DELETE NO ACTION}); services that need
 *       the full {@code Account} entity perform an explicit
 *       {@code accountRepository.findById(card.getCardAcctId())}
 *       lookup. This is a deliberate Minimal Change Clause decision
 *       (AAP &sect;0.7.3): associations would introduce new
 *       traversal patterns (lazy initialization exceptions, eager
 *       JOINs, cascade behavior) not present in the COBOL
 *       source.</li>
 *   <li><b>FILLER omitted</b> &mdash; the trailing 59-byte
 *       {@code FILLER PIC X(59)} from the COBOL record has no
 *       relational counterpart and is not declared as a Java
 *       field.</li>
 *   <li><b>Anemic domain model</b> &mdash; this class carries only
 *       state (fields, getters, setters, identity contracts). All
 *       business logic lives in the corresponding {@code @Service}
 *       classes per the Layered Architecture pattern (AAP
 *       &sect;0.3.3 / &sect;0.7.3). No business validation, no
 *       Jakarta {@code @NotNull} / {@code @Size} annotations
 *       (validation belongs in DTOs, not entities); no Lombok; no
 *       AWS SDK calls; no JSON / Jackson annotations (entities are
 *       converted to DTOs at the controller boundary).</li>
 * </ul>
 *
 * <h2>JPA mapping discipline</h2>
 * <ul>
 *   <li>{@code @Table(name = "cards")} matches V002 DDL table name
 *       <em>exactly</em> (plural, snake_case &mdash; the same plural
 *       form used in V001 {@code accounts} and V003
 *       {@code customers}).</li>
 *   <li>{@code @Column(name = "...")} on every field matches V002
 *       column names <em>exactly</em>; {@code length} and
 *       {@code precision} attributes match V002's
 *       {@code VARCHAR(...)} / {@code NUMERIC(...)} declarations;
 *       {@code nullable} on every column corresponds 1-to-1 with
 *       V002's {@code NOT NULL} declarations.</li>
 *   <li>{@code columnDefinition = "CHAR(1)"} on
 *       {@link #cardActiveStatus} preserves the fixed-width
 *       single-character semantics declared in V002 (and matches
 *       the V002 {@code chk_cards_active_status CHECK} constraint
 *       restricting values to {@code 'Y'} or {@code 'N'}).</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.domain.Account
 * @see com.awsm2.carddemo.domain.CardCrossReference
 * @see com.awsm2.carddemo.domain.Transaction
 */
@Entity
@Table(name = "cards")
public class Card implements Serializable {

    /**
     * Serializable version identifier. Required by {@link Serializable}
     * to ensure stable serialization semantics across persistence-
     * context boundaries, the ElastiCache Redis caching layer
     * (per AAP &sect;0.6.6), and any Kafka serialization paths if card
     * snapshots are ever published to MSK topics. Incremented only
     * when the entity's serialized form changes in a backward-
     * incompatible way.
     */
    private static final long serialVersionUID = 1L;

    // -------------------------------------------------------------------------
    // Persistent fields
    //
    // Field declarations correspond one-to-one (in order) to the COBOL
    // CARD-RECORD layout in app/cpy/CVACT02Y.cpy. The trailing
    // FILLER PIC X(59) is intentionally omitted (no relational
    // equivalent for fixed-width VSAM padding). The terminal `version`
    // field is application-added per AAP §0.4.1 for JPA @Version
    // optimistic locking; it has no COBOL counterpart.
    // -------------------------------------------------------------------------

    /**
     * 16-character card-number string &mdash; the primary key.
     *
     * <p>Maps to the COBOL field {@code 05 CARD-NUM PIC X(16)} in
     * {@code app/cpy/CVACT02Y.cpy} (line 5) and to the V002
     * {@code card_num VARCHAR(16) PRIMARY KEY} column.</p>
     *
     * <p>Stored as {@link String} (NOT {@link Long} / {@link Integer})
     * because card numbers are conventionally treated as opaque
     * identifier strings: leading-zero preservation is mandatory for
     * byte-identical parallel-run output diffing per AAP &sect;0.7.2;
     * Luhn-check and BIN-range parsing operate on the string form;
     * and the COBOL source declares the field as {@code PIC X(16)}
     * (alphanumeric), not as {@code PIC 9(16)} (numeric).</p>
     *
     * <p><b>PCI-DSS classification:</b> "cardholder data" (CHD) per
     * PCI-DSS v4.0 Requirement 3.4. MUST be masked in all application
     * logs by the CloudWatch log filter regex that detects PAN-like
     * sequences (AAP &sect;0.6.6); {@link #toString()} on this class
     * shows only the last 4 digits.</p>
     */
    // COBOL: CVACT02Y.cpy:L5 CARD-NUM PIC X(16) -- PK; PCI-sensitive (masked in toString)
    @Id
    @Column(name = "card_num", nullable = false, length = 16)
    private String cardNum;

    /**
     * 11-digit unsigned numeric foreign key into the
     * {@code accounts} table.
     *
     * <p>Maps to the COBOL field {@code 05 CARD-ACCT-ID PIC 9(11)} in
     * {@code app/cpy/CVACT02Y.cpy} (line 6) and to the V002
     * {@code card_acct_id BIGINT NOT NULL} column.</p>
     *
     * <p>The PostgreSQL {@code BIGINT} (8-byte signed integer, range
     * -2<sup>63</sup>..2<sup>63</sup>-1) fully contains the COBOL
     * {@code PIC 9(11)} value range (0..99,999,999,999) and maps
     * directly to Java {@link Long}. {@code BIGINT} is required
     * (rather than {@code NUMERIC(11)}) so the FK to
     * {@code accounts.acct_id} (also {@code BIGINT}) is valid: a
     * PostgreSQL FK requires the referencing column type to be binary-
     * coercible to the referenced column type, and
     * {@code NUMERIC} / {@code BIGINT} are not directly compatible.</p>
     *
     * <p>This column is the target of the V002 secondary index
     * {@code idx_cards_acct_id} which replaces the COBOL VSAM
     * {@code CARDDATA.VSAM.AIX KEYS(11 16) NONUNIQUEKEY UPGRADE}
     * alternate index and supports the
     * {@code findByCardAcctId(Long acctId, Pageable pageable)}
     * derived query on {@code CardRepository}. The index is
     * <em>non-unique</em> because one account may have multiple
     * cards.</p>
     *
     * <p>Declared as a scalar {@code Long} (not a {@code @ManyToOne
     * Account} association) per the Minimal Change Clause: services
     * that need the full {@link Account} entity invoke
     * {@code accountRepository.findById(cardAcctId)} explicitly.</p>
     */
    // COBOL: CVACT02Y.cpy:L6 CARD-ACCT-ID PIC 9(11)
    // -- FK to accounts.acct_id; indexed by idx_cards_acct_id (replaces CARDDATA.VSAM.AIX)
    @Column(name = "card_acct_id", nullable = false)
    private Long cardAcctId;

    /**
     * 3-digit Card Verification Value (CVV2 / CVC2 / CID).
     *
     * <p>Maps to the COBOL field {@code 05 CARD-CVV-CD PIC 9(03)} in
     * {@code app/cpy/CVACT02Y.cpy} (line 7) and to the V002
     * {@code card_cvv_cd NUMERIC(3) NOT NULL} column.</p>
     *
     * <p>Stored as {@link Integer} (NOT {@link String}) because the
     * COBOL source declares the field as {@code PIC 9(03)} (numeric),
     * not as {@code PIC X(03)} (alphanumeric). Leading-zero
     * preservation is native to PostgreSQL {@code NUMERIC} &mdash;
     * numeric values are stored without any string-representation
     * padding, and the {@code precision = 3} attribute enforces the
     * 3-digit maximum.</p>
     *
     * <p><b>*** PCI-DSS CRITICAL (AAP &sect;0.6.6) ***</b>
     * The CVV is "sensitive authentication data" (SAD) per PCI-DSS
     * v4.0 Requirement 3.2 and <b>MUST NOT be persisted post-
     * authorization</b> in a real payment-card environment.
     * CardDemo persists it ONLY because the COBOL source persists
     * it ({@code CVACT02Y.cpy:L7}) and the Minimal Change Clause
     * (AAP &sect;0.7.3) forbids removing fields that exist in the
     * COBOL source. The application <b>MUST NOT log this field</b>
     * under any circumstance and <b>MUST NOT return it in any API
     * response</b> outside of card-update flows that explicitly
     * require it. The {@link #toString()} method on this class
     * <b>intentionally omits the CVV entirely</b>. Production
     * hardening (out of scope for this migration) would replace
     * this column with column-level pgcrypto encryption, HSM-managed
     * key derivation, or removal of the column entirely.</p>
     */
    // COBOL: CVACT02Y.cpy:L7 CARD-CVV-CD PIC 9(03)
    // -- PCI-sensitive (NEVER include in toString or logs; encrypted at rest via RDS KMS CMK).
    // columnDefinition = "NUMERIC(3)" aligns Hibernate's schema
    // validator with V002's numeric(3) column type. Without this
    // explicit columnDefinition, Hibernate maps Integer to SQL INTEGER
    // by default, causing "wrong column type encountered in column
    // [card_cvv_cd] in table [cards]; found [numeric (Types#NUMERIC)],
    // but expecting [integer (Types#INTEGER)]" at ddl-auto: validate.
    // Same pattern as TransactionCategory.tranCatCd (V009 NUMERIC(4)
    // PK mapped via Integer + columnDefinition = "NUMERIC(4)").
    @Column(name = "card_cvv_cd", nullable = false, precision = 3,
            columnDefinition = "NUMERIC(3)")
    private Integer cardCvvCd;

    /**
     * 50-character cardholder name as embossed / printed on the
     * physical card.
     *
     * <p>Maps to the COBOL field
     * {@code 05 CARD-EMBOSSED-NAME PIC X(50)} in
     * {@code app/cpy/CVACT02Y.cpy} (line 8) and to the V002
     * {@code card_embossed_name VARCHAR(50) NOT NULL} column.</p>
     *
     * <p>Fixed 50-character width in COBOL (typically space-padded
     * right); stored as {@code VARCHAR(50)} to allow trimming on
     * read without loss of information. Read by
     * {@code StatementGenerationService} when printing monthly
     * statements and by {@code CardDetailService} when populating
     * the {@code COCRDSL.bms} card-detail screen. {@code NOT NULL}
     * because every card has an embossed name (in the COBOL source
     * the field is always populated, even if with spaces).</p>
     */
    // COBOL: CVACT02Y.cpy:L8 CARD-EMBOSSED-NAME PIC X(50) -- cardholder name
    @Column(name = "card_embossed_name", nullable = false, length = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date, parsed from the COBOL ISO-8601
     * {@code 'YYYY-MM-DD'} string to native PostgreSQL {@code DATE}.
     *
     * <p>Maps to the COBOL field
     * {@code 05 CARD-EXPIRAION-DATE PIC X(10)} in
     * {@code app/cpy/CVACT02Y.cpy} (line 9 &mdash; <b>note the
     * COBOL spelling typo "EXPIRAION"</b>) and to the V002
     * {@code card_expiration_date DATE NOT NULL} column. The
     * PostgreSQL column and Java field adopt the corrected
     * spelling {@code expiration} per AAP &sect;0.4.1 V002 row
     * (the same pattern used for {@code ACCT-EXPIRAION-DATE} in
     * V001 / {@link Account}). This is a deliberate, AAP-mandated
     * spelling correction at the Java / PostgreSQL boundary
     * &mdash; semantics, business logic, and byte position are
     * unchanged.</p>
     *
     * <p>Native {@link LocalDate} enables JPA / Hibernate to map
     * the field directly without manual substring parsing.
     * {@code java.time.LocalDate} replaces the COBOL LE
     * {@code CEEDAYS}-based date arithmetic per AAP &sect;0.4.1.</p>
     *
     * <p>Read by {@code TransactionPostingService} at validation
     * stage 4 (card-expiration check) &mdash; reject code 103
     * (card expired) preserved verbatim per AAP &sect;0.1.1 /
     * {@code CBTRN02C}.</p>
     */
    // COBOL: CVACT02Y.cpy:L9 CARD-EXPIRAION-DATE PIC X(10)
    // -- TYPO! corrected to "expiration" per V002 (per AAP §0.4.1)
    @Column(name = "card_expiration_date", nullable = false)
    private LocalDate cardExpirationDate;

    /**
     * 1-character card-status flag.
     *
     * <p>Maps to the COBOL field
     * {@code 05 CARD-ACTIVE-STATUS PIC X(01)} in
     * {@code app/cpy/CVACT02Y.cpy} (line 10) and to the V002
     * {@code card_active_status CHAR(1) NOT NULL} column.</p>
     *
     * <p>COBOL business rule: {@code 'Y'} = active (transactions may
     * be posted), {@code 'N'} = inactive / blocked / lost / stolen
     * (transactions are rejected at validation stage 2 in
     * {@code TransactionPostingService} per AAP &sect;0.4.1 /
     * {@code CBTRN02C} reject codes 100-109 preservation). A V002
     * {@code chk_cards_active_status CHECK} constraint restricts
     * stored values to {@code ('Y','N')} as defense-in-depth per
     * AAP &sect;0.7.1 / &sect;0.7.3 &mdash; preventing corrupted
     * values from silently breaking transaction-posting
     * decisions.</p>
     */
    // COBOL: CVACT02Y.cpy:L10 CARD-ACTIVE-STATUS PIC X(01)
    // -- 'Y' active / 'N' inactive (CHECK constraint enforced at DB)
    // @JdbcTypeCode(SqlTypes.CHAR) is required so Hibernate maps this
    // String to PostgreSQL CHAR(1) (bpchar) at runtime rather than the
    // default VARCHAR -- without it, spring.jpa.hibernate.ddl-auto:
    // validate fails with "wrong column type encountered in column
    // [card_active_status] in table [cards]; found [bpchar (Types#CHAR)],
    // but expecting [char(1) (Types#VARCHAR)]". Identical pattern is
    // used in Account.acctActiveStatus, TransactionType.tranType,
    // Customer.custUsState/custCountry/custUsCountry, and
    // UserSecurity.secUsrType / .secUsrFiller per the project's
    // established CHAR-column convention.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_active_status", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String cardActiveStatus;

    // COBOL: CVACT02Y.cpy:L11 FILLER PIC X(59) -- OMITTED
    // (59 trailing bytes that bring the COBOL record to its declared
    // 150-byte RECORDSIZE. PostgreSQL has no concept of fixed-width
    // records, so the FILLER has no relational equivalent per
    // AAP §0.6.2. Total COBOL record length: 16 + 11 + 3 + 50 + 10
    // + 1 + 59 = 150 bytes.)

    /**
     * Application-added {@link Version} column for JPA optimistic
     * locking. <b>No COBOL equivalent.</b>
     *
     * <p>Maps to the V002
     * {@code version BIGINT NOT NULL DEFAULT 0} column &mdash; an
     * application-added column with no COBOL field counterpart.
     * Replaces the COBOL before/after image comparison in
     * {@code COCRDUPC.cbl} (CICS {@code READ UPDATE} &rarr;
     * {@code REWRITE} with snapshot mismatch detection) per AAP
     * &sect;0.4.1 / &sect;0.6.2 / &sect;0.7.1.</p>
     *
     * <p>Hibernate auto-increments this value on every save; a
     * stale-write attempt (where the row's database {@code version}
     * has moved since this entity was loaded) results in an
     * {@code UPDATE} statement that affects zero rows, which
     * Hibernate detects and surfaces as
     * {@link jakarta.persistence.OptimisticLockException}. The
     * {@code GlobalExceptionHandler}
     * ({@code @RestControllerAdvice}) translates the JPA exception
     * to a domain {@code ConcurrentModificationException} and
     * returns HTTP 409 Conflict to the REST caller.</p>
     *
     * <p>Although a {@link #setVersion(Long)} setter is exposed
     * (for test fixtures and DTO-mapping convenience),
     * <b>application code MUST NOT manually mutate the version</b>:
     * Hibernate manages the value as part of its persistence
     * lifecycle, and manual mutation defeats the optimistic-
     * locking guarantee.</p>
     */
    // JPA @Version per AAP §0.4.1 -- replaces COBOL before/after image
    // comparison in COCRDUPC.cbl. Hibernate auto-increments on every
    // update; OptimisticLockException → ConcurrentModificationException → HTTP 409
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // -------------------------------------------------------------------------
    // Constructors
    //
    // Two constructors are exposed:
    //   1) The JPA-required no-arg constructor (used by Hibernate when
    //      hydrating entities from result sets).
    //   2) An all-args constructor covering the SIX business fields
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
     * {@link #setCardNum(String)} and other setters must be invoked
     * before persisting or otherwise the {@code NOT NULL} columns
     * (per V002) will fail at the database layer.</p>
     */
    public Card() {
        // Intentionally empty -- field assignment occurs via setters
        // during JPA hydration or via the all-args constructor below.
    }

    /**
     * All-arguments constructor covering the 6 COBOL business
     * fields. The {@link #version} field is intentionally excluded:
     * JPA manages it on save.
     *
     * @param cardNum            the 16-character card-number string (primary key)
     * @param cardAcctId         the 11-digit account identifier (foreign key)
     * @param cardCvvCd          the 3-digit Card Verification Value
     * @param cardEmbossedName   the cardholder name embossed on the card
     * @param cardExpirationDate the card expiration date
     * @param cardActiveStatus   the {@code 'Y'} / {@code 'N'} active-status flag
     */
    public Card(String cardNum,
                Long cardAcctId,
                Integer cardCvvCd,
                String cardEmbossedName,
                LocalDate cardExpirationDate,
                String cardActiveStatus) {
        this.cardNum = cardNum;
        this.cardAcctId = cardAcctId;
        this.cardCvvCd = cardCvvCd;
        this.cardEmbossedName = cardEmbossedName;
        this.cardExpirationDate = cardExpirationDate;
        this.cardActiveStatus = cardActiveStatus;
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    //
    // Provided for every persistent field including version (JPA / Spring
    // Data / Jackson all rely on the JavaBean property contract). Setters
    // for card_active_status and card_expiration_date are required because
    // CardUpdateService mutates these during card maintenance flows.
    // -------------------------------------------------------------------------

    /**
     * Returns the 16-character card-number string (primary key).
     *
     * <p><b>PCI-DSS:</b> callers receiving this value MUST mask it
     * before passing to any log, audit, or external system per AAP
     * &sect;0.6.6. Use {@link #toString()} for log-safe
     * representation.</p>
     *
     * @return the {@code card_num} value, or {@code null} for an
     *         unpersisted instance
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the 16-character card-number string.
     *
     * @param cardNum the new {@code card_num} value
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the 11-digit account identifier (foreign key into
     * {@code accounts.acct_id}).
     *
     * @return the {@code card_acct_id} value
     */
    public Long getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Sets the 11-digit account identifier.
     *
     * @param cardAcctId the new {@code card_acct_id} value
     */
    public void setCardAcctId(Long cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the 3-digit CVV.
     *
     * <p><b>*** PCI-DSS CRITICAL ***</b> &mdash; callers receiving
     * this value MUST NOT log it, store it in any audit trail, or
     * return it in any API response outside of card-update flows
     * that explicitly require it. The CVV is "sensitive
     * authentication data" per PCI-DSS v4.0 Requirement 3.2.</p>
     *
     * @return the {@code card_cvv_cd} value
     */
    public Integer getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * Sets the 3-digit CVV.
     *
     * @param cardCvvCd the new {@code card_cvv_cd} value
     */
    public void setCardCvvCd(Integer cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * Returns the 50-character embossed cardholder name.
     *
     * @return the {@code card_embossed_name} value
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * Sets the embossed cardholder name.
     *
     * @param cardEmbossedName the new {@code card_embossed_name}
     *                         value
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * Returns the card expiration date.
     *
     * <p>Java field name uses the corrected spelling "expiration";
     * the source COBOL field name {@code CARD-EXPIRAION-DATE}
     * carries a spelling typo that is deliberately not reproduced
     * in Java per AAP &sect;0.4.1.</p>
     *
     * @return the {@code card_expiration_date} value
     */
    public LocalDate getCardExpirationDate() {
        return cardExpirationDate;
    }

    /**
     * Sets the card expiration date.
     *
     * @param cardExpirationDate the new {@code card_expiration_date}
     *                           value
     */
    public void setCardExpirationDate(LocalDate cardExpirationDate) {
        this.cardExpirationDate = cardExpirationDate;
    }

    /**
     * Returns the {@code 'Y'} / {@code 'N'} card-status flag.
     *
     * @return the {@code card_active_status} value
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * Sets the {@code 'Y'} / {@code 'N'} card-status flag. Must
     * satisfy the V002 {@code chk_cards_active_status} CHECK
     * constraint at persist / merge time.
     *
     * @param cardActiveStatus the new card-status value
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    /**
     * Returns the JPA {@link Version} value.
     *
     * <p>This is the optimistic-locking version managed by
     * Hibernate &mdash; auto-incremented on every save, used in
     * the {@code WHERE} clause of every {@code UPDATE} to detect
     * concurrent modification.</p>
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
     * value as part of its persistence lifecycle, and manual
     * mutation defeats the optimistic-locking guarantee.
     *
     * <p>The setter is exposed only for test fixtures and DTO-
     * mapping convenience (e.g., when reconstructing a Card from a
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
    // are derived from the PRIMARY KEY (cardNum) only -- not from the CVV,
    // embossed name, expiration date, or version. This ensures that the
    // same logical card row remains equal across persistence operations
    // even as its mutable fields change (e.g., active-status updates,
    // expiration-date renewal). Collections (HashMap / HashSet) and
    // second-level caches rely on this contract.
    //
    // toString() returns a PCI-DSS-safe human-readable representation:
    //   - cardNum is MASKED (last 4 digits visible, leading 12 replaced
    //     by asterisks);
    //   - cardCvvCd is INTENTIONALLY OMITTED (sensitive authentication
    //     data per PCI-DSS v4.0 Requirement 3.2 -- MUST NOT be logged
    //     under any circumstance);
    //   - all other fields are shown as-is.
    // -------------------------------------------------------------------------

    /**
     * Equality is defined as equality of the {@link #cardNum}
     * primary key. Two {@code Card} instances with the same
     * non-{@code null} {@code cardNum} are equal regardless of any
     * field differences (e.g., uncommitted status / expiration
     * changes vs. database state).
     *
     * @param o the reference object with which to compare
     * @return {@code true} if {@code o} is a {@code Card} with the
     *         same {@code cardNum}; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Card)) {
            return false;
        }
        Card card = (Card) o;
        return Objects.equals(cardNum, card.cardNum);
    }

    /**
     * Hash code derived from the {@link #cardNum} primary key only.
     * Consistent with the {@link #equals(Object)} contract.
     *
     * @return the hash code for this card
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }

    /**
     * PCI-DSS-safe human-readable representation suitable for log
     * lines and debugger inspection.
     *
     * <p>Per AAP &sect;0.6.6:
     * <ul>
     *   <li>{@link #cardNum} is <b>MASKED</b> &mdash; only the last 4
     *       digits are visible; the leading 12 characters are
     *       replaced by asterisks (
     *       {@code "************1234"}).</li>
     *   <li>{@link #cardCvvCd} is <b>INTENTIONALLY OMITTED</b>
     *       &mdash; the CVV is "sensitive authentication data" per
     *       PCI-DSS v4.0 Requirement 3.2 and MUST NOT be logged
     *       under any circumstance.</li>
     *   <li>All other fields are shown as-is.</li>
     * </ul>
     *
     * @return a PCI-DSS-safe string representation of this card
     */
    @Override
    public String toString() {
        return "Card{"
                + "cardNum='" + maskCardNumber(cardNum) + '\''
                + ", cardAcctId=" + cardAcctId
                + ", cardEmbossedName='" + cardEmbossedName + '\''
                + ", cardExpirationDate=" + cardExpirationDate
                + ", cardActiveStatus='" + cardActiveStatus + '\''
                + ", version=" + version
                // NOTE: cardCvvCd is intentionally omitted per PCI-DSS
                // v4.0 Requirement 3.2 (AAP §0.6.6). Sensitive
                // authentication data MUST NOT appear in logs.
                + '}';
    }

    /**
     * Masks a 16-character card number for PCI-DSS-safe logging.
     *
     * <p>Returns a string of the form
     * {@code "************XXXX"} where {@code XXXX} are the last 4
     * characters of the input. For {@code null} or short inputs
     * (length &lt; 4), returns {@code "****"} as a defensive
     * fallback &mdash; never returning the raw value.</p>
     *
     * <p>The PAN-masking pattern matches PCI-DSS v4.0 Requirement
     * 3.4.1 (a maximum of the first 6 and last 4 digits may be
     * displayed; CardDemo's policy is to display only the last 4
     * to be conservative).</p>
     *
     * @param card the 16-character card number (may be {@code null})
     * @return a masked representation with only the last 4 digits
     *         visible, or {@code "****"} for {@code null} / short
     *         inputs
     */
    private static String maskCardNumber(String card) {
        if (card == null || card.length() < 4) {
            return "****";
        }
        return "************" + card.substring(card.length() - 4);
    }
}
