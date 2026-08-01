/*
 * ****************************************************************
 * Program     : Account.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Account master record - replaces VSAM KSDS cluster
 *               AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS.
 * Source      : app/cpy/CVACT01Y.cpy (300 B, key 11) @ 7756d89
 * ****************************************************************
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
 * language governing permissions and limitations under the License
 * ****************************************************************
 */
package com.cardemo.model.entity;

import java.math.BigDecimal;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Account master record: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}.
 *
 * <h2>What it does</h2>
 * <p>
 * This is a pure data holder. It carries the twelve populated fields of the COBOL record layout
 * {@code app/cpy/CVACT01Y.cpy} ({@code 01 ACCOUNT-RECORD}, fields at {@code :L5}-{@code :L16}) plus one
 * optimistic-locking counter that has no COBOL counterpart. It performs no business logic, no case folding,
 * no rounding, no arithmetic and no logging; every one of those responsibilities belongs to the service,
 * batch and validation layers that consume this type. The class exists so that the account master data has
 * exactly one shape in Java, derived field-for-field from the frozen legacy corpus.
 * <p>
 * The one thing it does enforce is its own shape. Construction and every mutator refuse a value the 300-byte
 * record layout cannot have produced - a {@code null}, a character field wider than its picture clause, or a
 * numeric outside the domain its picture clause declares - and say so through
 * {@link IllegalArgumentException} naming the offending property. That is a structural check, not a business
 * rule: no credit limit is compared against a balance, no date is parsed as a calendar date, and no group
 * identifier is looked up against the disclosure-group table, because each of those is a judgement the
 * service and batch layers own.
 *
 * <p>Three mappings are deliberate and easy to "correct" by mistake. {@code expiraionDate} keeps the
 * copybook's misspelling, because the field contract fixes the name. The three date fields are
 * {@code CHAR(10)} text, because the posting and update paths compare them as strings and by substring.
 * {@code currentCycleDebit} legitimately holds negative values, which is why the over-limit expression
 * subtracts rather than adds it.
 *
 * <h2>Record geometry: the 300-byte proof</h2>
 * <p>
 * The twelve mapped fields account for 122 bytes and {@code FILLER} accounts for the remaining 178:
 * <pre>
 *   11 + 1 + (5 &times; 12) + 10 + 10 + 10 + 10 + 10 + 178 = 300
 *   |    |     |            |    |    |    |    |     |
 *   |    |     |            |    |    |    |    |     +-- FILLER            X(178)
 *   |    |     |            |    |    |    |    +-------- ACCT-GROUP-ID     X(10)
 *   |    |     |            |    |    |    +------------- ACCT-ADDR-ZIP     X(10)
 *   |    |     |            |    |    +------------------ ACCT-REISSUE-DATE X(10)
 *   |    |     |            |    +----------------------- ACCT-EXPIRAION-DATE X(10)
 *   |    |     |            +---------------------------- ACCT-OPEN-DATE    X(10)
 *   |    |     +----------------------------------------- the five S9(10)V99 money fields, 12 bytes each
 *   |    +----------------------------------------------- ACCT-ACTIVE-STATUS X(01)
 *   +---------------------------------------------------- ACCT-ID            9(11)
 * </pre>
 * <p>
 * Verified against {@code app/data/ASCII/acctdata.txt} row 1, whose length is exactly 300 bytes:
 * {@code ACCT-OPEN-DATE} occupies bytes 49-58 and holds {@code 2014-11-20}, {@code ACCT-EXPIRAION-DATE}
 * occupies bytes 59-68 and holds {@code 2025-05-20}, {@code ACCT-REISSUE-DATE} occupies bytes 69-78 and holds
 * {@code 2025-05-20}, and {@code ACCT-GROUP-ID} occupies bytes 113-122 and holds ten spaces.
 * <p>
 * <b>{@code FILLER} is deliberately not modelled.</b> Its 178 bytes exist solely to pad the VSAM record out to
 * the catalogued 300 bytes; the field carries no data, is never referenced by any program in the corpus, and a
 * column for it would be a fabrication. The byte count is recorded here because the fixed-width readers and
 * writers that emit this record need it, but it is not part of the relational shape.
 *
 * <h2>Field contract</h2>
 * <p>
 * Thirteen mapped fields: the twelve data columns transcribed from the copybook, plus {@code version}.
 * <pre>
 *   #   COBOL field (CVACT01Y.cpy)      PIC          Java field           Column
 *   --  ------------------------------  -----------  -------------------  ----------------------------
 *    1  ACCT-ID                 :L5     9(11)        accountId            acct_id  NUMERIC(11)  (id)
 *    2  ACCT-ACTIVE-STATUS      :L6     X(01)        activeStatus         acct_active_status CHAR(1)
 *    3  ACCT-CURR-BAL           :L7     S9(10)V99    currentBalance       acct_curr_bal NUMERIC(12,2)
 *    4  ACCT-CREDIT-LIMIT       :L8     S9(10)V99    creditLimit          acct_credit_limit NUMERIC(12,2)
 *    5  ACCT-CASH-CREDIT-LIMIT  :L9     S9(10)V99    cashCreditLimit      acct_cash_credit_limit NUMERIC(12,2)
 *    6  ACCT-OPEN-DATE          :L10    X(10)        openDate             acct_open_date CHAR(10)
 *    7  ACCT-EXPIRAION-DATE     :L11    X(10)        expiraionDate        acct_expiraion_date CHAR(10)
 *    8  ACCT-REISSUE-DATE       :L12    X(10)        reissueDate          acct_reissue_date CHAR(10)
 *    9  ACCT-CURR-CYC-CREDIT    :L13    S9(10)V99    currentCycleCredit   acct_curr_cyc_credit NUMERIC(12,2)
 *   10  ACCT-CURR-CYC-DEBIT     :L14    S9(10)V99    currentCycleDebit    acct_curr_cyc_debit NUMERIC(12,2)
 *   11  ACCT-ADDR-ZIP           :L15    X(10)        addressZip           acct_addr_zip CHAR(10)
 *   12  ACCT-GROUP-ID           :L16    X(10)        groupId              acct_group_id CHAR(10)
 *    -  FILLER                  :L17    X(178)       (not modelled)       (no column)
 *    +  (no COBOL counterpart)          -            version              version BIGINT  (optimistic lock)
 * </pre>
 * <p>
 * Exactly five columns carry {@code NUMERIC(12,2)}, one for each {@code PIC S9(10)V99} money field. Every
 * column is {@code NOT NULL}, matching the schema migration's not-null-on-every-column rule. There is no
 * thirteenth data column.
 *
 * <h2>Findings, classified by severity</h2>
 * <dl>
 *   <dt><b>High &mdash; the field name {@code expiraionDate} is misspelled on purpose.</b></dt>
 *   <dd>{@code app/cpy/CVACT01Y.cpy:L11} declares {@code ACCT-EXPIRAION-DATE}, which is missing the
 *       {@code T} of {@code EXPIRATION}. A repository-wide search finds twelve references to the misspelled
 *       name across {@code app/cbl} and {@code app/cpy} and <em>zero</em> references to any correctly spelled
 *       variant, so the misspelling is not a typo in one place but the only name the contract has. Silently
 *       renaming it to {@code expirationDate} would break paragraph-level traceability and diverge from the
 *       column name the schema migration must create. Remediation: none required; the spelling is retained
 *       deliberately and is documented on the field itself.</dd>
 *   <dt><b>High &mdash; the three date fields are text, not dates.</b></dt>
 *   <dd>{@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE} and {@code ACCT-REISSUE-DATE} are all
 *       {@code PIC X(10)} and are mapped to {@code CHAR(10)} {@code String}. The corpus manipulates them as
 *       character data and never as dates: {@code app/cbl/COACTUPC.cbl:L4127}-{@code :L4137} compares each one
 *       as three separate substrings, {@code (1:4)} for the year, {@code (6:2)} for the month and
 *       {@code (9:2)} for the day, and {@code app/cbl/CBTRN02C.cbl:L414} compares the expiry field as a string
 *       against the first ten characters of a timestamp. The offsets 1, 6 and 9 prove the stored form is
 *       {@code yyyy-MM-dd} with dash separators, which the fixture confirms. Remediation: parsing and calendar
 *       validation belong to the date validation service; this class keeps the column textual so that blank
 *       and legacy-invalid values load and round-trip byte-exactly instead of failing at the boundary.</dd>
 *   <dt><b>Medium &mdash; {@code currentCycleDebit} legitimately holds negative values.</b></dt>
 *   <dd>See the field documentation. No normalisation of any kind is applied or permitted.</dd>
 *   <dt><b>Medium &mdash; {@code groupId} may be blank but is never null.</b></dt>
 *   <dd>See the field documentation. No constraint that rejects blank input is applied.</dd>
 *   <dt><b>Medium &mdash; {@code version} is necessary but not sufficient for concurrency control.</b></dt>
 *   <dd>See the field documentation. The business-level change detection lives in the service layer.</dd>
 * </dl>
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>No associations.</b> This entity declares no {@code @ManyToOne}, no {@code @OneToMany}, no
 *       {@code @JoinColumn} and no cascade. The legacy programs reach related records through explicit keyed
 *       lookup chains (cross-reference, then account, then customer) rather than by navigation, so modelling
 *       associations would add a traversal path the source does not have. It also removes any possibility of
 *       an N+1 select. Referential integrity is enforced by the schema's foreign keys.</li>
 *   <li><b>No inheritance.</b> There is no base entity, no {@code @MappedSuperclass} and no auditing
 *       superclass. The record is flat in COBOL and is flat here.</li>
 *   <li><b>No {@code Serializable}.</b> Implementing it would widen the deserialization surface for no benefit
 *       and would oblige a {@code serialVersionUID}, whose absence the zero-warning compile treats as
 *       fatal.</li>
 *   <li><b>No static mutable state.</b> There are no counters and no caches. In particular the interest job's
 *       run-sequential identifier suffix, declared as working storage at {@code app/cbl/CBACT04C.cbl:L173},
 *       is job state and must never be hosted on an entity.</li>
 *   <li><b>Explicit members throughout.</b> No annotation processor generates any part of this class, so what
 *       is written here is exactly what compiles.</li>
 *   <li><b>Decimal arithmetic only.</b> Every monetary field is a {@link BigDecimal}. No binary IEEE-754
 *       primitive appears anywhere in this file &mdash; not as a field, not as a parameter and not as a return
 *       type. Binary radix types cannot represent the two-decimal money values of the source exactly, so they
 *       are prohibited across the whole migration and the security-audit gate asserts their absence by
 *       inspection. Where a caller must round it uses {@code RoundingMode.HALF_EVEN}; where a caller must
 *       compare monetary values it uses {@code compareTo}, never {@code equals}, because
 *       {@link BigDecimal#equals(Object)} is scale-sensitive and reports {@code 2.0} and {@code 2.00} as
 *       different values.</li>
 * </ul>
 *
 * <h2>How to build, run and test</h2>
 * <ul>
 *   <li>Compile: {@code ./mvnw -B clean compile}. The build compiles with {@code -Xlint:all -Werror} and
 *       {@code failOnWarning}, so a missing {@code serialVersionUID}, a raw type or a deprecated call is a
 *       hard failure rather than a warning. An unused import is not - {@code javac} 25.0.3 publishes no
 *       lint key for one - so that prohibition is enforced by review.</li>
 *   <li>Run: this type is not independently runnable. It is a managed persistent type that the application
 *       context loads at boot, so it participates in a run only as part of the Spring Boot application,
 *       started with {@code ./mvnw -B spring-boot:run} or from the packaged executable JAR with
 *       {@code java -jar target/carddemo-1.0.0.jar}. <strong>Neither is possible at this commit, measured
 *       1 August 2026:</strong> no {@code @SpringBootApplication} entry point and no
 *       {@code application*.yml} profile exists, so no context can refresh and no executable JAR can be
 *       produced. Two preconditions apply at boot once it can, and both fail fast: a
 *       reachable PostgreSQL instance carrying the table enumerated below, and the environment-indirected
 *       JWT signing key, which has no committed default. Because {@code ddl-auto} is {@code validate}, any
 *       disagreement between this mapping and the deployed schema aborts context startup outright rather
 *       than degrading later at runtime.</li>
 *   <li>Test: {@code ./mvnw -B clean test}. Unit tests for this type belong in
 *       {@code src/test/java/com/cardemo/unit/model} and are to assert the record geometry, the key length, the
 *       five {@code NUMERIC(12,2)} precisions, that the three date fields are {@code String}, that a negative
 *       {@code currentCycleDebit} round-trips unchanged and that a blank {@code groupId} is accepted.
 *       <strong>Not available, measured 1 August 2026:</strong> no {@code AccountTest} exists, so that list is
 *       the coverage owed, not coverage that runs.</li>
 *   <li>Verify: {@code ./mvnw -B clean verify} additionally enforces the line-coverage floor and the dependency
 *       vulnerability gate.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 *   <li>{@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile. This entity never
 *       generates schema; it is checked against the schema the migration created.</li>
 *   <li>The table name is {@code account} and every column name is stated explicitly, so no implicit naming
 *       strategy can rename anything.</li>
 *   <li>The six text columns declare {@code CHAR} <em>twice over</em>: through {@code columnDefinition} and
 *       through an explicit JDBC type code of {@code CHAR}. Both are required, which is worth spelling out
 *       because it is counter-intuitive. PostgreSQL reports a {@code CHAR(n)} column to JDBC under the type
 *       name {@code bpchar} with the type code {@code Types.CHAR}, whereas a {@code String} field maps to
 *       {@code Types.VARCHAR} by default. The schema validator accepts a column when either the type codes
 *       match or the declared type name is a prefix match, and {@code columnDefinition} alone satisfies
 *       neither: it changes the declared type string without changing the type code. Only the explicit JDBC
 *       type code makes the codes agree. This was verified by validating deliberately weakened variants
 *       against a real {@code CHAR(10)} column: see the troubleshooting section for the exact rejections.
 *       Fixing the physical type at {@code CHAR} also keeps the space-padded fixed-width values of the legacy
 *       record round-tripping byte-exactly, which is what preserves a ten-space {@code acct_group_id} as ten
 *       spaces rather than as an empty string.</li>
 *   <li>{@code acct_id} declares {@code NUMERIC(11)} through {@code columnDefinition}, and here
 *       {@code columnDefinition} alone <em>is</em> sufficient: the declared type string
 *       {@code numeric(11)} is a prefix match against the {@code numeric} type name PostgreSQL reports, so
 *       the validator's second branch succeeds without an explicit type code. Without
 *       {@code columnDefinition} the default mapping for {@code Long} is {@code BIGINT}, which matches
 *       neither the type code nor the type name of the eleven-digit numeric key the catalogue specifies.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><b>Application context fails to start with a schema-validation error naming a wrong column type.</b>
 *       The migration and this mapping disagree. Compare the offending column against the field contract
 *       above; the contract is normative. Do not weaken {@code ddl-auto} to make the error go away. The three
 *       rejections below were reproduced deliberately against a real {@code CHAR(10)} column and a real
 *       {@code NUMERIC(11)} column, so they are the messages to expect if the annotations on this class are
 *       ever "simplified":
 *       <pre>
 *   wrong column type ... found [bpchar (Types#CHAR)], but expecting [char(10) (Types#VARCHAR)]
 *       -- cause: the explicit CHAR JDBC type code was removed, leaving columnDefinition alone.
 *
 *   wrong column type ... found [bpchar (Types#CHAR)], but expecting [varchar(10) (Types#VARCHAR)]
 *       -- cause: both the type code and columnDefinition were removed from a text column.
 *
 *   wrong column type ... found [numeric (Types#NUMERIC)], but expecting [bigint (Types#BIGINT)]
 *       -- cause: columnDefinition was removed from acct_id, so Long fell back to BIGINT.
 *       </pre>
 *       In every case the fix is to restore the annotation, not to alter the column.</li>
 *   <li><b>A not-null violation on insert.</b> Every column is {@code NOT NULL}, so a caller that leaves a
 *       field unset gets a constraint violation. Blank is the correct representation of "no value" for the
 *       text columns, and {@code BigDecimal.ZERO} for the money columns; {@code null} is not.</li>
 *   <li><b>An optimistic-lock failure on update.</b> Another writer changed the row. Note that this is a
 *       weaker guarantee than the legacy program's, which detected which business fields changed; see the
 *       {@code version} field documentation.</li>
 *   <li><b>Money values that look like they lost a digit.</b> The money columns hold twelve digits with two
 *       decimals. A value wider than that is out of contract at the boundary, not here.</li>
 * </ul>
 *
 * <h2>Missing information disclosure</h2>
 *
 * <p><strong>Schema reconciliation, measured 1 August 2026.</strong>
 * {@code src/main/resources/db/migration/V1__create_schema.sql} is <strong>present</strong>,
 * declaring 11 tables, 10 named foreign keys, 5 CHECK constraints and 4 {@code version} columns.
 * It declares {@code CREATE TABLE account} with 13 columns whose names are identical, as a
 * set, to the 13 {@code @Column(name = ...)} declarations below, verified by direct comparison.
 * The mapping is therefore reconciled against real DDL rather than asserted in its absence.
 * Because {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every planned profile, any
 * disagreement between the two would fail application-context startup outright rather than producing a
 * warning.
 * What remains <strong>not available</strong> is {@code V2__create_indexes.sql},
 * {@code V3__seed_data.sql} and all four {@code application*.yml} profiles, so the
 * {@code spring.jpa.hibernate.ddl-auto: validate} behaviour cited here is the mandated configuration
 * rather than an observed one.</p>
 * <p>
 * What is needed is table {@code account} with these columns and no others:
 * <pre>
 *   acct_id                 NUMERIC(11)   PRIMARY KEY
 *   acct_active_status      CHAR(1)       NOT NULL
 *   acct_curr_bal           NUMERIC(12,2) NOT NULL
 *   acct_credit_limit       NUMERIC(12,2) NOT NULL
 *   acct_cash_credit_limit  NUMERIC(12,2) NOT NULL
 *   acct_open_date          CHAR(10)      NOT NULL
 *   acct_expiraion_date     CHAR(10)      NOT NULL
 *   acct_reissue_date       CHAR(10)      NOT NULL
 *   acct_curr_cyc_credit    NUMERIC(12,2) NOT NULL
 *   acct_curr_cyc_debit     NUMERIC(12,2) NOT NULL
 *   acct_addr_zip           CHAR(10)      NOT NULL
 *   acct_group_id           CHAR(10)      NOT NULL
 *   version                 BIGINT        NOT NULL
 * </pre>
 * <p>
 * For the avoidance of doubt about the surrounding migration: it creates exactly eleven tables with ten
 * foreign keys and five check constraints. The Spring Batch metadata tables come from the framework's own
 * schema script and are neither a fourth migration nor extra tables in the first one.
 * <p>
 * <b>JSON serialisation barrier.</b> This class is structurally unserialisable by Jackson, enforced by the
 * two class-level annotations below rather than by reviewer discipline. {@link JsonIgnoreType} removes any
 * property whose declared type is this class from an enclosing object's JSON, and {@link JsonAutoDetect}
 * with every visibility set to {@code NONE} switches off bean introspection entirely, so no getter, no
 * setter, no field and no creator is discoverable. An entity is a bean with public accessors, so without
 * the barrier the default behaviour of returning this type from a controller, or holding a field of it on a
 * response object, is to publish every column it carries - here the balance, both credit limits, both cycle
 * totals, the three dates and the postal code. With the barrier in place Jackson finds no properties and its
 * default {@code FAIL_ON_EMPTY_BEANS} setting turns that mistake into a loud failure at the first request
 * instead of a silent disclosure. Nothing legitimate is lost: outbound representations are built by
 * {@code com.cardemo.model.dto.AccountDto} and inbound JSON targets
 * {@code com.cardemo.model.dto.AccountUpdateRequest}, and persistence is unaffected because Hibernate reads
 * and writes the annotated fields reflectively and never consults Jackson visibility.
 */
@Entity
@Table(name = "account")
@JsonIgnoreType
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class Account {

    /**
     * Inclusive lower bound of {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}.
     * <p>
     * The picture clause carries no {@code S}, so the field is unsigned and zero is its floor. Zero itself is
     * accepted rather than treated as a sentinel, because eleven display digits can hold it and nothing in the
     * corpus reserves it.
     */
    private static final long MIN_ACCOUNT_ID = 0L;

    /**
     * Inclusive upper bound of {@code ACCT-ID PIC 9(11)}: the largest value eleven unsigned display digits can
     * hold, which is also the {@code KEYLEN 11} reported at {@code app/catlg/LISTCAT.txt:L59}.
     */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    /** Declared width of {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6}. */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /** Declared width of {@code ACCT-OPEN-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L10}. */
    private static final int OPEN_DATE_WIDTH = 10;

    /** Declared width of {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L11}. */
    private static final int EXPIRAION_DATE_WIDTH = 10;

    /** Declared width of {@code ACCT-REISSUE-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L12}. */
    private static final int REISSUE_DATE_WIDTH = 10;

    /** Declared width of {@code ACCT-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L15}. */
    private static final int ADDRESS_ZIP_WIDTH = 10;

    /** Declared width of {@code ACCT-GROUP-ID PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L16}. */
    private static final int GROUP_ID_WIDTH = 10;

    /**
     * Integer digit count of the five money fields, every one of which is declared
     * {@code S9(10)V99}: {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:L7},
     * {@code ACCT-CREDIT-LIMIT} at {@code :L8}, {@code ACCT-CASH-CREDIT-LIMIT} at {@code :L9},
     * {@code ACCT-CURR-CYC-CREDIT} at {@code :L13} and {@code ACCT-CURR-CYC-DEBIT} at {@code :L14}.
     */
    private static final int MONEY_INTEGER_DIGITS = 10;

    /** Decimal positions declared by the {@code V99} of every money field: exactly two. */
    private static final int MONEY_SCALE = 2;

    /**
     * Total precision of a money column, {@code NUMERIC(12,2)}: the ten integer digits plus the two decimal
     * positions the picture clause declares.
     */
    private static final int MONEY_PRECISION = MONEY_INTEGER_DIGITS + MONEY_SCALE;

    /** Inclusive upper bound of a money field: the largest magnitude {@code S9(10)V99} can represent. */
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999.99");

    /**
     * Inclusive lower bound of a money field. The picture clause carries an {@code S}, so the full negative
     * range is representable and legitimate: a negative balance is an over-payment, and a negative cycle debit
     * is what the over-limit expression subtracts.
     */
    private static final BigDecimal MIN_MONEY = MAX_MONEY.negate();

    /**
     * Account identifier: the primary key, from {@code ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5}.
     * <p>
     * Eleven unsigned digits occupying bytes 1-11 of the record, which is exactly the
     * {@code KEYLEN 11} reported at {@code app/catlg/LISTCAT.txt:L59} and, with
     * {@code RKP 0} at {@code :L60}, confirms the key is the leading field. Eleven digits exceed the range of
     * {@code int}, so the Java type is {@code Long}; the column is {@code NUMERIC(11)} rather than
     * {@code BIGINT} so that the declared width matches the catalogued key width instead of merely
     * accommodating it.
     * <p>
     * The value is assigned by the caller, never generated: there is no identity column and no sequence,
     * because the legacy key is supplied by the cross-reference record and by the account-creation path. This
     * is why no generation strategy is declared.
     */
    @Id
    @Column(name = "acct_id", nullable = false, columnDefinition = "NUMERIC(11)")
    private Long accountId;

    /**
     * Active status flag, from {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_active_status", nullable = false, length = ACTIVE_STATUS_WIDTH, columnDefinition = "CHAR(1)")
    private String activeStatus;

    /**
     * Current balance, from {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}.
     */
    @Column(name = "acct_curr_bal", nullable = false, precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal currentBalance;

    /**
     * Credit limit, from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L8}.
     */
    @Column(name = "acct_credit_limit", nullable = false, precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal creditLimit;

    /**
     * Cash credit limit, from {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L9}.
     */
    @Column(name = "acct_cash_credit_limit", nullable = false, precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal cashCreditLimit;

    /**
     * Account open date as text, from {@code ACCT-OPEN-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L10}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_open_date", nullable = false, length = OPEN_DATE_WIDTH, columnDefinition = "CHAR(10)")
    private String openDate;

    /**
     * Account expiry date as text, from {@code ACCT-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L11}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_expiraion_date", nullable = false, length = EXPIRAION_DATE_WIDTH,
            columnDefinition = "CHAR(10)")
    private String expiraionDate;

    /**
     * Card reissue date as text, from {@code ACCT-REISSUE-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L12}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_reissue_date", nullable = false, length = REISSUE_DATE_WIDTH, columnDefinition = "CHAR(10)")
    private String reissueDate;

    /**
     * Current cycle credit accumulator, from {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L13}.
     */
    @Column(name = "acct_curr_cyc_credit", nullable = false, precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal currentCycleCredit;

    /**
     * Current cycle debit accumulator, from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    @Column(name = "acct_curr_cyc_debit", nullable = false, precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal currentCycleDebit;

    /**
     * Account address postal code, from {@code ACCT-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L15}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_addr_zip", nullable = false, length = ADDRESS_ZIP_WIDTH, columnDefinition = "CHAR(10)")
    private String addressZip;

    /**
     * Disclosure group identifier, from {@code ACCT-GROUP-ID PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L16}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_group_id", nullable = false, length = GROUP_ID_WIDTH, columnDefinition = "CHAR(10)")
    private String groupId;

    /**
     * Optimistic-locking version counter. This column has no COBOL counterpart and is additive.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * No-argument constructor required by the Jakarta Persistence specification, which obliges every entity to
     * declare a public or protected constructor taking no arguments so that the provider can instantiate the
     * class when materialising a row.
     */
    protected Account() {
        // Intentionally empty: field population is performed by the persistence provider.
    }

    /**
     * Creates a fully populated account record from the twelve data columns of the source layout.
     *
     * @param accountId eleven-digit account key, {@code ACCT-ID PIC 9(11)}.
     * @param activeStatus one-character active flag, {@code ACCT-ACTIVE-STATUS PIC X(01)}
     * @param currentBalance current balance, {@code ACCT-CURR-BAL PIC S9(10)V99}.
     * @param creditLimit credit limit, {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}
     * @param cashCreditLimit cash credit limit, {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}
     * @param openDate open date as {@code yyyy-MM-dd} text, {@code ACCT-OPEN-DATE PIC X(10)}
     * @param expiraionDate expiry date as {@code yyyy-MM-dd} text, {@code ACCT-EXPIRAION-DATE PIC X(10)}.
     * @param reissueDate reissue date as {@code yyyy-MM-dd} text, {@code ACCT-REISSUE-DATE PIC X(10)}
     * @param currentCycleCredit cycle credit accumulator, {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}
     * @param currentCycleDebit cycle debit accumulator, {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}.
     * @param addressZip postal code, {@code ACCT-ADDR-ZIP PIC X(10)}
     * @param groupId disclosure group identifier, {@code ACCT-GROUP-ID PIC X(10)}.
     * @throws IllegalArgumentException if {@code accountId} is {@code null}
     */
    public Account(
            Long accountId,
            String activeStatus,
            BigDecimal currentBalance,
            BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            String openDate,
            String expiraionDate,
            String reissueDate,
            BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit,
            String addressZip,
            String groupId) {
        // Direct field assignment through private static guards, never setter invocation: calling an
        // overridable method from a constructor of a non-final class would leak a partially constructed
        // reference, which the zero-warning compile rejects outright as this-escape. The class cannot be
        // final because the persistence provider subclasses it to build lazy-loading proxies, so the guards
        // are static to sidestep the hazard entirely rather than merely to avoid it by convention.
        this.accountId = requireAccountId(accountId);
        this.activeStatus = requireWidth(activeStatus, "activeStatus",
                "ACCT-ACTIVE-STATUS PIC X(01)", ACTIVE_STATUS_WIDTH);
        this.currentBalance = requireMoney(currentBalance, "currentBalance",
                "ACCT-CURR-BAL PIC S9(10)V99");
        this.creditLimit = requireMoney(creditLimit, "creditLimit",
                "ACCT-CREDIT-LIMIT PIC S9(10)V99");
        this.cashCreditLimit = requireMoney(cashCreditLimit, "cashCreditLimit",
                "ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99");
        this.openDate = requireWidth(openDate, "openDate",
                "ACCT-OPEN-DATE PIC X(10)", OPEN_DATE_WIDTH);
        this.expiraionDate = requireWidth(expiraionDate, "expiraionDate",
                "ACCT-EXPIRAION-DATE PIC X(10)", EXPIRAION_DATE_WIDTH);
        this.reissueDate = requireWidth(reissueDate, "reissueDate",
                "ACCT-REISSUE-DATE PIC X(10)", REISSUE_DATE_WIDTH);
        this.currentCycleCredit = requireMoney(currentCycleCredit, "currentCycleCredit",
                "ACCT-CURR-CYC-CREDIT PIC S9(10)V99");
        this.currentCycleDebit = requireMoney(currentCycleDebit, "currentCycleDebit",
                "ACCT-CURR-CYC-DEBIT PIC S9(10)V99");
        this.addressZip = requireWidth(addressZip, "addressZip",
                "ACCT-ADDR-ZIP PIC X(10)", ADDRESS_ZIP_WIDTH);
        this.groupId = requireWidth(groupId, "groupId",
                "ACCT-GROUP-ID PIC X(10)", GROUP_ID_WIDTH);
    }

    /**
     * Returns the eleven-digit account key, from {@code ACCT-ID PIC 9(11)} ({@code app/cpy/CVACT01Y.cpy:L5}).
     *
     * @return the account identifier, or {@code null} on an instance the provider has not yet populated
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Sets the eleven-digit account key, from {@code ACCT-ID PIC 9(11)} ({@code app/cpy/CVACT01Y.cpy:L5}).
     *
     * @param accountId the account identifier to set; must not be {@code null} and must lie between 0
     *                  and 99999999999 inclusive
     * @throws IllegalArgumentException if {@code accountId} is {@code null}, negative, or greater than
     *                                  99999999999
     */
    public void setAccountId(Long accountId) {
        this.accountId = requireAccountId(accountId);
    }

    /**
     * Returns the one-character active flag, from {@code ACCT-ACTIVE-STATUS PIC X(01)}
     * ({@code app/cpy/CVACT01Y.cpy:L6}).
     *
     * @return the active status character as stored, without interpretation
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the one-character active flag, from {@code ACCT-ACTIVE-STATUS PIC X(01)}
     * ({@code app/cpy/CVACT01Y.cpy:L6}).
     *
     * @param activeStatus the active status character to set; must not be {@code null} and must be at
     *                     most one character
     * @throws IllegalArgumentException if {@code activeStatus} is {@code null} or longer than one characters
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = requireWidth(activeStatus, "activeStatus",
                "ACCT-ACTIVE-STATUS PIC X(01)", ACTIVE_STATUS_WIDTH);
    }

    /**
     * Returns the current balance, from {@code ACCT-CURR-BAL PIC S9(10)V99} ({@code app/cpy/CVACT01Y.cpy:L7}).
     *
     * @return the current balance with two-decimal scale.
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Sets the current balance, from {@code ACCT-CURR-BAL PIC S9(10)V99} ({@code app/cpy/CVACT01Y.cpy:L7}).
     *
     * @param currentBalance the balance to set; must not be {@code null}, may be negative, and must
     *                       carry at most two decimal digits
     * @throws IllegalArgumentException if {@code currentBalance} is {@code null}, carries more than two decimal
     *                                  digits, or falls outside the range {@code S9(10)V99} can hold
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = requireMoney(currentBalance, "currentBalance", "ACCT-CURR-BAL PIC S9(10)V99");
    }

    /**
     * Returns the credit limit, from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} ({@code app/cpy/CVACT01Y.cpy:L8}).
     *
     * @return the credit limit with two-decimal scale
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Sets the credit limit, from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} ({@code app/cpy/CVACT01Y.cpy:L8}).
     *
     * @param creditLimit the credit limit to set; must not be {@code null} and must carry at most two
     *                    decimal digits
     * @throws IllegalArgumentException if {@code creditLimit} is {@code null}, carries more than two decimal
     *                                  digits, or falls outside the range {@code S9(10)V99} can hold
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = requireMoney(creditLimit, "creditLimit", "ACCT-CREDIT-LIMIT PIC S9(10)V99");
    }

    /**
     * Returns the cash credit limit, from {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L9}).
     *
     * @return the cash credit limit with two-decimal scale
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * Sets the cash credit limit, from {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L9}).
     *
     * @param cashCreditLimit the cash credit limit to set; must not be {@code null} and must carry at
     *                        most two decimal digits
     * @throws IllegalArgumentException if {@code cashCreditLimit} is {@code null}, carries more than two decimal
     *                                  digits, or falls outside the range {@code S9(10)V99} can hold
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = requireMoney(cashCreditLimit, "cashCreditLimit",
                "ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99");
    }

    /**
     * Returns the account open date as ten-character text, from {@code ACCT-OPEN-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L10}).
     *
     * @return the open date in {@code yyyy-MM-dd} form as stored.
     */
    public String getOpenDate() {
        return openDate;
    }

    /**
     * Sets the account open date as ten-character text, from {@code ACCT-OPEN-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L10}).
     *
     * @param openDate the open date text to set; must not be {@code null} and must be at most ten
     *                 characters
     * @throws IllegalArgumentException if {@code openDate} is {@code null} or longer than ten characters
     */
    public void setOpenDate(String openDate) {
        this.openDate = requireWidth(openDate, "openDate", "ACCT-OPEN-DATE PIC X(10)", OPEN_DATE_WIDTH);
    }

    /**
     * Returns the account expiry date as ten-character text, from {@code ACCT-EXPIRAION-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L11}).
     *
     * @return the expiry date in {@code yyyy-MM-dd} form as stored
     */
    public String getExpiraionDate() {
        return expiraionDate;
    }

    /**
     * Sets the account expiry date as ten-character text, from {@code ACCT-EXPIRAION-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L11}).
     *
     * @param expiraionDate the expiry date text to set; must not be {@code null} and must be at most
     *                      ten characters
     * @throws IllegalArgumentException if {@code expiraionDate} is {@code null} or longer than ten characters
     */
    public void setExpiraionDate(String expiraionDate) {
        this.expiraionDate = requireWidth(expiraionDate, "expiraionDate",
                "ACCT-EXPIRAION-DATE PIC X(10)", EXPIRAION_DATE_WIDTH);
    }

    /**
     * Returns the card reissue date as ten-character text, from {@code ACCT-REISSUE-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L12}).
     *
     * @return the reissue date in {@code yyyy-MM-dd} form as stored
     */
    public String getReissueDate() {
        return reissueDate;
    }

    /**
     * Sets the card reissue date as ten-character text, from {@code ACCT-REISSUE-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L12}).
     *
     * @param reissueDate the reissue date text to set; must not be {@code null} and must be at most ten
     *                    characters
     * @throws IllegalArgumentException if {@code reissueDate} is {@code null} or longer than ten characters
     */
    public void setReissueDate(String reissueDate) {
        this.reissueDate = requireWidth(reissueDate, "reissueDate",
                "ACCT-REISSUE-DATE PIC X(10)", REISSUE_DATE_WIDTH);
    }

    /**
     * Returns the current cycle credit accumulator, from {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L13}).
     *
     * @return the cycle credit accumulator with two-decimal scale
     */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * Sets the current cycle credit accumulator, from {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L13}).
     *
     * @param currentCycleCredit the cycle credit accumulator value to set; must not be {@code null} and
     *                           must carry at most two decimal digits
     * @throws IllegalArgumentException if {@code currentCycleCredit} is {@code null}, carries more than two decimal
     *                                  digits, or falls outside the range {@code S9(10)V99} can hold
     */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = requireMoney(currentCycleCredit, "currentCycleCredit",
                "ACCT-CURR-CYC-CREDIT PIC S9(10)V99");
    }

    /**
     * Returns the current cycle debit accumulator, from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L14}).
     *
     * @return the cycle debit accumulator with two-decimal scale, signed and unnormalised
     */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * Sets the current cycle debit accumulator, from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L14}).
     *
     * @param currentCycleDebit the cycle debit accumulator value to set.
     */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = requireMoney(currentCycleDebit, "currentCycleDebit",
                "ACCT-CURR-CYC-DEBIT PIC S9(10)V99");
    }

    /**
     * Returns the account postal code, from {@code ACCT-ADDR-ZIP PIC X(10)} ({@code app/cpy/CVACT01Y.cpy:L15}).
     *
     * @return the postal code as stored.
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * Sets the account postal code, from {@code ACCT-ADDR-ZIP PIC X(10)} ({@code app/cpy/CVACT01Y.cpy:L15}).
     *
     * @param addressZip the postal code to set; must not be {@code null} and must be at most ten
     *                   characters
     * @throws IllegalArgumentException if {@code addressZip} is {@code null} or longer than ten characters
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = requireWidth(addressZip, "addressZip",
                "ACCT-ADDR-ZIP PIC X(10)", ADDRESS_ZIP_WIDTH);
    }

    /**
     * Returns the disclosure group identifier, from {@code ACCT-GROUP-ID PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L16}).
     *
     * @return the group identifier as stored.
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the disclosure group identifier, from {@code ACCT-GROUP-ID PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L16}).
     *
     * @param groupId the group identifier to set.
     */
    public void setGroupId(String groupId) {
        this.groupId = requireWidth(groupId, "groupId", "ACCT-GROUP-ID PIC X(10)", GROUP_ID_WIDTH);
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * @return the version counter, or {@code null} on an instance that has never been persisted
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter.
     *
     * @param version the version counter to set
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares two accounts by primary key alone.
     *
     * @param other the object to compare with, may be {@code null}
     * @return {@code true} if {@code other} is an account with a non-null key equal to this one's
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Account account)) {
            return false;
        }
        return accountId != null && accountId.equals(account.getAccountId());
    }

    /**
     * Returns a hash code derived from the primary key alone, consistent with {@link #equals(Object)}.
     *
     * @return the hash code of the account identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId);
    }

    /**
     * Returns a deliberately minimal diagnostic representation: the account identifier, which correlates a
     * log line to a row, and the optimistic lock version, which distinguishes two readings of that row.
     * Nothing else.
     * <p>
     * <b>Every financial and date-bearing column is omitted, and that omission is the whole point.</b> An
     * earlier form of this method rendered the current balance, both credit limits, both current-cycle
     * totals, the three text dates, the group identifier and the active status. Each of those is customer
     * financial data, and {@code toString} is invoked implicitly - by string concatenation, by a logging
     * placeholder, by an exception message, by a debugger and by an APM agent capturing local variables - so
     * anything rendered here is data that reaches a log aggregator as a matter of routine, on paths no
     * reviewer sees. Withholding the values at source is a strictly stronger control than relying on a
     * masking rule in {@code logback-spring.xml} to recognise them downstream, because masking must know the
     * shape of what it is looking for and an unlabelled decimal has no distinguishing shape.
     * <p>
     * The postal code remains omitted for the reason it always was: it is address-adjacent personal data.
     * <p>
     * What is retained is the minimum that makes a log line useful. The account identifier is a surrogate
     * key with no personal or financial content of its own, and it is what an operator needs in order to
     * find the row; the version counter is what tells them whether they are looking at the same state twice.
     * An operator who needs a balance should read the row, where the access is authorised and audited,
     * rather than recover it from a log.
     * <p>
     * The format is intended for humans and for log correlation only. It is not a serialisation format, it is
     * not parsed anywhere, and it is not the fixed-width 300-byte representation: emitting that record image is
     * the job of the fixed-width writers, which apply the byte offsets and the zoned-decimal sign encoding.
     *
     * @return a single-line rendering of the account identifier and version, never containing a monetary
     *         amount, a date or personal data
     */
    @Override
    public String toString() {
        return "Account{accountId=" + accountId + ", version=" + version + '}';
    }

    /**
     * Validates a candidate character value against the width of the COBOL field it comes from and returns it
     * unchanged.
     * <p>
     * Rejects {@code null}, because every character column of this table is {@code NOT NULL} and because a
     * {@code PIC X(n)} field always holds its declared width, and rejects any value longer than the picture
     * clause declares, because a 300-byte record cannot contain one. Everything the picture clause admits is
     * accepted, including a value of only spaces - {@code ACCT-GROUP-ID} is legitimately all spaces in the
     * seed data - and any character content whatever. Nothing is trimmed, padded or case folded.
     * <p>
     * The failure message reports the received length and never the value: {@code ACCT-ADDR-ZIP} is
     * address-adjacent personal data that {@link #toString()} deliberately omits, and a validation message is
     * exactly the kind of string that reaches a log.
     * <p>
     * Declared {@code private static} so that the constructor can call it without invoking an overridable
     * method, which would publish a partially initialised instance; the JPA specification forbids a final
     * entity, so the hazard is real and {@code -Xlint:all -Werror} reports it as {@code this-escape}.
     *
     * @param value      the candidate value, possibly {@code null}
     * @param property   the Java property name, used in the failure message
     * @param cobolField the originating COBOL field name and picture clause
     * @param width      the declared width of that field in characters
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null} or longer than {@code width}
     */
    private static String requireWidth(String value, String property, String cobolField, int width) {
        if (value == null) {
            throw new IllegalArgumentException(property + " (" + cobolField
                    + ") must not be null: it maps to a NOT NULL CHAR(" + width
                    + ") column of table account");
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(property + " (" + cobolField + ") must be at most " + width
                    + " characters but was " + value.length());
        }
        return value;
    }

    /**
     * Validates the primary key against {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} and
     * returns it unchanged.
     * <p>
     * Rejects {@code null}, because a row without its primary key cannot be persisted and failing here names
     * the offending property instead of surfacing an opaque constraint violation from the driver much later,
     * and rejects any value outside 0 through 99999999999 inclusive, which is what eleven unsigned display
     * digits can hold and what the catalogued {@code KEYLEN 11} allocates. Zero is accepted and is not a
     * sentinel. Declared {@code private static} for the reason given on
     * {@link #requireWidth(String, String, String, int)}.
     *
     * @param value the candidate account identifier, possibly {@code null}
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}, negative, or greater than 99999999999
     */
    private static Long requireAccountId(Long value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "accountId (ACCT-ID PIC 9(11)) must not be null: it is the primary key of table account, "
                            + "derived from app/cpy/CVACT01Y.cpy:L5");
        }
        if (value.longValue() < MIN_ACCOUNT_ID || value.longValue() > MAX_ACCOUNT_ID) {
            throw new IllegalArgumentException("accountId (ACCT-ID PIC 9(11)) must be between "
                    + MIN_ACCOUNT_ID + " and " + MAX_ACCOUNT_ID + " inclusive but was " + value);
        }
        return value;
    }

    /**
     * Validates a candidate money value against the domain {@code S9(10)V99} can represent and returns it
     * unchanged.
     * <p>
     * Three things are checked and nothing else. {@code null} is rejected, because all five money columns are
     * {@code NOT NULL} and a packed decimal always holds a value. A scale greater than two is rejected,
     * because {@code V99} declares exactly two decimal positions and PostgreSQL rounds a
     * {@code NUMERIC(12,2)} insert half away from zero rather than refusing it - a silent alteration, and by a
     * rounding mode that is not the {@code RoundingMode.HALF_EVEN} the interest and posting paths use. A
     * magnitude outside -9999999999.99 through 9999999999.99 is rejected, because twelve zoned-decimal bytes
     * cannot hold more.
     * <p>
     * <b>What is deliberately not checked:</b> the sign, because every one of the five picture clauses carries
     * an {@code S}, a negative balance is an over-payment, and
     * {@code app/cbl/CBTRN02C.cbl:L547-L552} adds a negative amount to the cycle debit accumulator, which is
     * precisely why the over-limit expression at {@code app/cbl/CBTRN02C.cbl:L393-L422} subtracts that term;
     * zero, which is the opening value of every accumulator and the value the interest path writes back at end
     * of cycle; and a scale smaller than two, because {@code 0} and {@code 0.00} denote the same amount. <b>No
     * absolute value, no clamping, no rescaling and no rounding is applied anywhere in this class.</b>
     * <p>
     * Declared {@code private static} for the reason given on
     * {@link #requireWidth(String, String, String, int)}.
     *
     * @param value      the candidate amount, possibly {@code null}
     * @param property   the Java property name, used in the failure message
     * @param cobolField the originating COBOL field name and picture clause
     * @return {@code value}, unchanged and unrescaled
     * @throws IllegalArgumentException if {@code value} is {@code null}, carries more than two decimal digits,
     *                                  or falls outside the representable range
     */
    private static BigDecimal requireMoney(BigDecimal value, String property, String cobolField) {
        if (value == null) {
            throw new IllegalArgumentException(property + " (" + cobolField
                    + ") must not be null: it maps to a NOT NULL NUMERIC(" + MONEY_PRECISION + ","
                    + MONEY_SCALE + ") column of table account");
        }
        if (value.scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(property + " (" + cobolField + ") must carry at most "
                    + MONEY_SCALE + " decimal digits but had a scale of " + value.scale()
                    + "; rescale it explicitly with RoundingMode.HALF_EVEN rather than letting the NUMERIC("
                    + MONEY_PRECISION + "," + MONEY_SCALE + ") column round it half away from zero");
        }
        if (value.compareTo(MIN_MONEY) < 0 || value.compareTo(MAX_MONEY) > 0) {
            throw new IllegalArgumentException(property + " (" + cobolField + ") must be between "
                    + MIN_MONEY.toPlainString() + " and " + MAX_MONEY.toPlainString()
                    + " inclusive, which is what " + MONEY_INTEGER_DIGITS
                    + " signed integer digits can hold, but was " + value.toPlainString());
        }
        return value;
    }
}
