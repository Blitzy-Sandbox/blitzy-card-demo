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
 * optimistic-locking counter that has no COBOL counterpart. It performs no business logic, no validation,
 * no case folding, no rounding, no arithmetic and no logging; every one of those responsibilities belongs to
 * the service, batch and validation layers that consume this type. The class exists so that the account
 * master data has exactly one shape in Java, derived field-for-field from the frozen legacy corpus.
 *
 * <h2>Provenance</h2>
 * <ul>
 *   <li>Record layout: {@code app/cpy/CVACT01Y.cpy}. The header comment at {@code :L2} reads
 *       {@code *    Data-structure for  account entity (RECLN 300)}, and {@code FILLER PIC X(178)} closes the
 *       layout at {@code :L17}.</li>
 *   <li>Physical specification: {@code app/catlg/LISTCAT.txt:L57} defines the cluster
 *       {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}; {@code :L59} reports {@code KEYLEN 11} and
 *       {@code AVGLRECL 300}; {@code :L60} reports {@code RKP 0} and {@code MAXLRECL 300}, proving the key sits
 *       at byte zero of a fixed-length 300-byte record.</li>
 *   <li>Online consumer: {@code app/cbl/COACTUPC.cbl} (account update, 4,236 lines) reads this record for
 *       update, snapshots it, compares it field by field in {@code 9700-CHECK-CHANGE-IN-REC}
 *       ({@code :L4109}-{@code :L4193}) and rewrites it in {@code 9600-WRITE-PROCESSING}.</li>
 *   <li>Batch consumers: {@code app/cbl/CBTRN02C.cbl} (daily posting) validates and updates the record in
 *       {@code 1500-B-LOOKUP-ACCT} ({@code :L393}) and {@code 2800-UPDATE-ACCOUNT-REC} ({@code :L545});
 *       {@code app/cbl/CBACT04C.cbl} (interest calculation) updates it in {@code 1050-UPDATE-ACCOUNT}
 *       ({@code :L350}); {@code app/cbl/CBACT01C.cbl} reads it sequentially and never writes.</li>
 *   <li>Seed and fixture data: {@code app/data/ASCII/acctdata.txt}, 50 rows of exactly 300 bytes each, which
 *       corroborates {@code REC-TOTAL 50} at {@code app/catlg/LISTCAT.txt:L64}.</li>
 *   <li>Traceability anchor: commit {@code 7756d89}.</li>
 * </ul>
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
 *   <li>Compile: {@code mvn -B clean compile}. The build compiles with {@code -Xlint:all -Werror} and
 *       {@code failOnWarning}, so an unused import or a missing {@code serialVersionUID} is a hard
 *       failure rather than a warning.</li>
 *   <li>Run: this type is not independently runnable. It is a managed persistent type that the application
 *       context loads at boot, so it participates in a run only as part of the Spring Boot application,
 *       started with {@code mvn -B spring-boot:run} or from the packaged executable JAR with
 *       {@code java -jar target/carddemo-1.0.0.jar}. Two preconditions apply at boot and both fail fast: a
 *       reachable PostgreSQL instance carrying the table enumerated below, and the environment-indirected
 *       JWT signing key, which has no committed default. Because {@code ddl-auto} is {@code validate}, any
 *       disagreement between this mapping and the deployed schema aborts context startup outright rather
 *       than degrading later at runtime.</li>
 *   <li>Test: {@code mvn -B clean test}. Unit tests for this type live in
 *       {@code src/test/java/com/cardemo/unit/model} and assert the record geometry, the key length, the five
 *       {@code NUMERIC(12,2)} precisions, that the three date fields are {@code String}, that a negative
 *       {@code currentCycleDebit} round-trips unchanged and that a blank {@code groupId} is accepted.</li>
 *   <li>Verify: {@code mvn -B clean verify} additionally enforces the line-coverage floor and the dependency
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
 * <p>
 * <b>Not available:</b> {@code src/main/resources/db/migration/V1__create_schema.sql} did not exist when this
 * entity was authored, and the migration directory had no planned children, so the mapping below could not be
 * reconciled against real DDL at authoring time. Because {@code spring.jpa.hibernate.ddl-auto} is
 * {@code validate} in every profile, any disagreement between the two fails application-context startup
 * outright rather than producing a warning. <b>This entity's field contract is therefore the normative column
 * contract, and the migration must converge upon it rather than the reverse.</b>
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
 */
@Entity
@Table(name = "account")
public class Account {

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
     * Active status flag, from {@code ACCT-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT01Y.cpy:L6}.
     * <p>
     * A single character at byte 12. The seed fixture {@code app/data/ASCII/acctdata.txt} carries {@code Y}
     * in this position on row 1. The field is kept as a one-character {@code String} rather than promoted to
     * an enumeration because the copybook declares no condition names for it, so the set of accepted values
     * is not stated anywhere in the corpus and inventing one would narrow the contract.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_active_status", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String activeStatus;

    /**
     * Current balance, from {@code ACCT-CURR-BAL PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L7}.
     * <p>
     * Ten integer digits and two decimals, signed, occupying bytes 13-24 of the record; the column is
     * therefore {@code NUMERIC(12,2)}. Row 1 of the fixture holds <code>00000001940&#123;</code>, where the
     * trailing overpunch character <code>&#123;</code> denotes a positive zero digit, so the value decodes to
     * {@code +194.00}.
     * <p>
     * Both batch writers mutate this field by addition and never by assignment:
     * {@code app/cbl/CBTRN02C.cbl:L547} adds the transaction amount to it, and
     * {@code app/cbl/CBACT04C.cbl:L352} adds the accumulated interest to it. The value may legitimately be
     * negative, and the bill-payment path drives it to exactly zero.
     */
    @Column(name = "acct_curr_bal", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentBalance;

    /**
     * Credit limit, from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L8}.
     * <p>
     * Bytes 25-36, mapped to {@code NUMERIC(12,2)}. Row 1 of the fixture decodes to {@code +2020.00}.
     * <p>
     * This is the left-hand side of the over-limit test at {@code app/cbl/CBTRN02C.cbl:L407}, which reads
     * {@code IF ACCT-CREDIT-LIMIT &gt;= WS-TEMP-BAL} and assigns reject code 102 otherwise. The temporary
     * balance it is compared against is computed at {@code :L403}-{@code :L405} as the current cycle credit
     * minus the current cycle debit plus the transaction amount. That expression must be transcribed in
     * exactly that shape wherever it is reproduced, because the debit term is an accumulator that holds
     * negative values.
     */
    @Column(name = "acct_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /**
     * Cash credit limit, from {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L9}.
     * <p>
     * Bytes 37-48, mapped to {@code NUMERIC(12,2)}. Row 1 of the fixture decodes to {@code +1020.00}. The
     * field is carried and compared but is not an input to the over-limit test, which uses the general credit
     * limit; it is one of the twelve account predicates the update path snapshots and compares at
     * {@code app/cbl/COACTUPC.cbl:L4121}.
     */
    @Column(name = "acct_cash_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    /**
     * Account open date as text, from {@code ACCT-OPEN-DATE PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L10}.
     * <p>
     * Ten characters at bytes 49-58, holding {@code 2014-11-20} on row 1 of the fixture, so the stored form is
     * {@code yyyy-MM-dd} with dash separators.
     * <p>
     * <b>Severity High: this is character data and must stay character data.</b> The update path compares it
     * as three substrings rather than as one value: {@code app/cbl/COACTUPC.cbl:L4127}-{@code :L4129} compares
     * {@code ACCT-OPEN-DATE(1:4)}, {@code (6:2)} and {@code (9:2)} against discrete snapshot fields, and the
     * whole-string alternative is present in the source but commented out at {@code :L3831}, which shows the
     * component-wise treatment is deliberate rather than incidental. Mapping this column to a date type would
     * impose calendar validation the source does not perform, would reject the blank and legacy-invalid values
     * the record can legitimately contain, and would not round-trip byte-exactly. Parsing and calendar
     * validation belong to the date validation service.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_open_date", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String openDate;

    /**
     * Account expiry date as text, from {@code ACCT-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L11}.
     * <p>
     * <b>Severity High: the misspelling is intentional and is part of the field contract.</b> The copybook
     * declares {@code ACCT-EXPIRAION-DATE}, without the {@code T} of {@code EXPIRATION}. That name is used
     * throughout the corpus: {@code app/cbl/CBTRN02C.cbl:L414} reads
     * {@code IF ACCT-EXPIRAION-DATE &gt;= DALYTRAN-ORIG-TS (1:10)},
     * {@code app/cbl/COACTUPC.cbl:L4131}-{@code :L4133} compares its {@code (1:4)}, {@code (6:2)} and
     * {@code (9:2)} substrings, and {@code :L3837}-{@code :L3839} captures the same three substrings into the
     * snapshot. A repository-wide search finds twelve references to the misspelled name and none to any
     * correctly spelled variant, so the misspelling is the only name that exists. The Java field is therefore
     * {@code expiraionDate} and the column is {@code acct_expiraion_date}. Correcting the spelling would
     * break paragraph-level traceability and would diverge from the column the migration creates.
     * <p>
     * Like the other two dates this is {@code CHAR(10)} text holding {@code yyyy-MM-dd}; row 1 of the fixture
     * holds {@code 2025-05-20} at bytes 59-68. The expiry test at {@code app/cbl/CBTRN02C.cbl:L414} is a
     * string comparison against the first ten characters of the transaction's <em>originating</em> timestamp,
     * not its processing timestamp, and it runs unguarded immediately after the over-limit test, so when both
     * conditions fail reject code 103 overwrites 102. That control flow belongs to the posting processor; it
     * is described here only because it is what fixes this field's type as text.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_expiraion_date", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String expiraionDate;

    /**
     * Card reissue date as text, from {@code ACCT-REISSUE-DATE PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L12}.
     * <p>
     * Ten characters at bytes 69-78, holding {@code 2025-05-20} on row 1 of the fixture. Compared as three
     * substrings at {@code app/cbl/COACTUPC.cbl:L4135}-{@code :L4137} and snapshotted the same way at
     * {@code :L3843}-{@code :L3845}. Text for the same reasons given on {@code openDate}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_reissue_date", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String reissueDate;

    /**
     * Current cycle credit accumulator, from {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L13}.
     * <p>
     * Bytes 79-90, mapped to {@code NUMERIC(12,2)}. This is an accumulator, not a snapshot: the posting path
     * adds the transaction amount to it at {@code app/cbl/CBTRN02C.cbl:L549} whenever the amount is
     * non-negative, and the interest path zeroes it at {@code app/cbl/CBACT04C.cbl:L353} at the end of a
     * cycle. It is the first term of the over-limit expression at {@code app/cbl/CBTRN02C.cbl:L403}.
     */
    @Column(name = "acct_curr_cyc_credit", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleCredit;

    /**
     * Current cycle debit accumulator, from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     * <p>
     * <b>Severity Medium: this accumulator legitimately holds negative values and must never be
     * normalised.</b> The column is signed, which {@code NUMERIC(12,2)} is by default in PostgreSQL, so no
     * additional declaration is required to permit it.
     * <p>
     * The sign behaviour is explicit in the source. {@code app/cbl/CBTRN02C.cbl:L548}-{@code :L552} reads
     * {@code IF DALYTRAN-AMT &gt;= 0} then adds the amount to the credit accumulator, and in the
     * {@code ELSE} branch at {@code :L551} adds the <em>same signed amount</em> to this debit accumulator. No
     * absolute value is taken. A negative amount is therefore accumulated as a negative number, which is
     * precisely why the over-limit expression at {@code :L403}-{@code :L405} <em>subtracts</em> this term:
     * subtracting a negative accumulator increases the temporary balance, which is the intended arithmetic.
     * <p>
     * The behaviour is genuinely exercised rather than theoretical. The fixture
     * {@code app/data/ASCII/dailytran.txt} carries 300 amount values of which 250 are positive and 50 are
     * negative, and both the <code>&#123;</code> and <code>&#125;</code> zoned-decimal overpunch signs occur
     * in it.
     * Consequently this class applies no {@code abs}, no {@code negate}, no clamping to zero and no
     * non-negativity constraint of any kind, and no consumer may add one. Rewriting the over-limit expression
     * into an algebraically equivalent form is equally forbidden, because equivalence over unsigned values is
     * not equivalence over these.
     * <p>
     * Like the credit accumulator it is zeroed at end of cycle, at {@code app/cbl/CBACT04C.cbl:L354}.
     */
    @Column(name = "acct_curr_cyc_debit", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleDebit;

    /**
     * Account address postal code, from {@code ACCT-ADDR-ZIP PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L15}.
     * <p>
     * Ten characters at bytes 103-112, holding {@code A000000000} on row 1 of the fixture, which shows the
     * field is not restricted to digits. No program in the corpus reads or writes it: the only occurrence of
     * the name anywhere is the copybook declaration itself. It is mapped because it occupies ten bytes of the
     * 300-byte record and must round-trip, not because any behaviour depends on it.
     * <p>
     * This field is deliberately excluded from {@link #toString()}. Postal data is address-adjacent personal
     * information, {@code toString} output reaches logs by default, and the logging configuration masks
     * personal data; omitting the value at source is stronger than relying on a downstream mask.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_addr_zip", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String addressZip;

    /**
     * Disclosure group identifier, from {@code ACCT-GROUP-ID PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L16}.
     * <p>
     * <b>Severity Medium: this field may be blank but is never null.</b> Row 1 of
     * {@code app/data/ASCII/acctdata.txt} holds ten spaces at bytes 113-122. Combined with the
     * not-null-on-every-column rule, the contract is a blank-but-present {@code CHAR(10)}. This class
     * therefore applies no constraint that would reject blank input and never trims a blank value to
     * {@code null}; either would make the seed data unloadable.
     * <p>
     * The blank is load-bearing rather than accidental. A blank group identifier matches no real disclosure
     * group, which is exactly why the interest calculation's fallback to the literal default group exists and
     * is reached; the fixture {@code app/data/ASCII/discgrp.txt} carries seventeen default-group rows for that
     * purpose.
     * <p>
     * Note for consumers: {@code app/cbl/COACTUPC.cbl:L4139}-{@code :L4140} compares this field through
     * {@code FUNCTION LOWER-CASE} on both sides, a deliberate asymmetry against the upper-casing applied to
     * the customer text fields in the same paragraph. That comparison is service-layer logic and is not
     * performed here: this entity applies no case folding at all. Any case operation added anywhere must pass
     * an explicit {@code Locale.ROOT} so the result cannot vary with the platform default locale.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acct_group_id", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String groupId;

    /**
     * Optimistic-locking version counter. This column has no COBOL counterpart and is additive.
     * <p>
     * <b>Severity Medium: this is the store-level guard only, and it is necessary but not sufficient.</b> The
     * counter detects <em>that</em> the row changed since it was read. The legacy account-update path detects
     * something strictly stronger: <em>which</em> business fields changed, and in what representation.
     * {@code app/cbl/COACTUPC.cbl:L4109}-{@code :L4193}, the paragraph
     * {@code 9700-CHECK-CHANGE-IN-REC}, evaluates twelve account predicates beginning with the active-status
     * comparison against a snapshot captured when the screen was first displayed, and abandons the write if
     * any one of them differs.
     * <p>
     * The two guarantees are not interchangeable. A concurrent write that set a field and then restored its
     * original value passes the legacy check and fails a version check; conversely a version check cannot
     * report which field the user's view went stale on, which the legacy screen reported. Because the target
     * is stateless there is no server-side place to keep the snapshot between requests, so it travels in the
     * request body: {@code AccountUpdateRequest} carries both an old-details and a new-details group, and the
     * field-by-field comparison runs in the account update service. Both layers are required and neither
     * substitutes for the other.
     * <p>
     * The field is provider-managed. It is deliberately absent from the all-columns constructor so that a
     * freshly constructed instance is unambiguously new; the accessor pair exists for detached-instance merge
     * and for test fixtures.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;


    /**
     * No-argument constructor required by the Jakarta Persistence specification, which obliges every entity to
     * declare a public or protected constructor taking no arguments so that the provider can instantiate the
     * class when materialising a row.
     * <p>
     * It is {@code protected} rather than {@code public} deliberately: the persistence provider and any
     * subclass proxy can reach it, while application code cannot use it to create a half-built instance that
     * would then fail the not-null constraint on every column. Application code uses the all-columns
     * constructor instead. Every field is left {@code null} here; populating them is the provider's job.
     */
    protected Account() {
        // Intentionally empty: field population is performed by the persistence provider.
    }

    /**
     * Creates a fully populated account record from the twelve data columns of the source layout.
     * <p>
     * Parameters are declared in COBOL field order, so the argument list reads in the same sequence as
     * {@code app/cpy/CVACT01Y.cpy:L5}-{@code :L16}. This keeps a call site directly comparable with the record
     * layout and with a fixed-width reader that walks the 300-byte image left to right.
     * <p>
     * The optimistic-locking {@code version} counter is deliberately not a parameter: it is provider-managed,
     * and an instance built here is unambiguously new. Use {@link #setVersion(Long)} only when reconstituting a
     * detached instance for merge, or in a test fixture.
     * <p>
     * <b>Side effects:</b> none. The constructor assigns fields directly and calls no overridable method, so
     * no partially constructed reference escapes.
     * <p>
     * <b>Error modes:</b> {@link IllegalArgumentException} if {@code accountId} is {@code null}, because a row
     * without its primary key cannot be persisted and failing here names the offending field instead of
     * surfacing an opaque constraint violation from the driver much later. No other argument is checked: every
     * remaining column is {@code NOT NULL} in the schema and is validated there, and adding checks here would
     * duplicate that authority. In particular blank text and negative money values are accepted, because both
     * are legitimate in the source data.
     *
     * @param accountId          eleven-digit account key, {@code ACCT-ID PIC 9(11)}; must not be {@code null}
     * @param activeStatus       one-character active flag, {@code ACCT-ACTIVE-STATUS PIC X(01)}
     * @param currentBalance     current balance, {@code ACCT-CURR-BAL PIC S9(10)V99}; may be negative
     * @param creditLimit        credit limit, {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}
     * @param cashCreditLimit    cash credit limit, {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}
     * @param openDate           open date as {@code yyyy-MM-dd} text, {@code ACCT-OPEN-DATE PIC X(10)}
     * @param expiraionDate      expiry date as {@code yyyy-MM-dd} text,
     *                           {@code ACCT-EXPIRAION-DATE PIC X(10)}; the spelling is the source's
     * @param reissueDate        reissue date as {@code yyyy-MM-dd} text, {@code ACCT-REISSUE-DATE PIC X(10)}
     * @param currentCycleCredit cycle credit accumulator, {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}
     * @param currentCycleDebit  cycle debit accumulator, {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}; may be
     *                           negative and is never normalised
     * @param addressZip         postal code, {@code ACCT-ADDR-ZIP PIC X(10)}
     * @param groupId            disclosure group identifier, {@code ACCT-GROUP-ID PIC X(10)}; may be blank
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
        if (accountId == null) {
            throw new IllegalArgumentException(
                    "accountId must not be null: it is the primary key of table account, derived from "
                            + "ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5");
        }
        // Direct field assignment, never setter invocation: calling an overridable method from a constructor
        // of a non-final class would leak a partially constructed reference, which the zero-warning compile
        // rejects outright. The class cannot be final because the persistence provider subclasses it to build
        // lazy-loading proxies.
        this.accountId = accountId;
        this.activeStatus = activeStatus;
        this.currentBalance = currentBalance;
        this.creditLimit = creditLimit;
        this.cashCreditLimit = cashCreditLimit;
        this.openDate = openDate;
        this.expiraionDate = expiraionDate;
        this.reissueDate = reissueDate;
        this.currentCycleCredit = currentCycleCredit;
        this.currentCycleDebit = currentCycleDebit;
        this.addressZip = addressZip;
        this.groupId = groupId;
    }


    /**
     * Returns the eleven-digit account key, from {@code ACCT-ID PIC 9(11)}
     * ({@code app/cpy/CVACT01Y.cpy:L5}).
     *
     * @return the account identifier, or {@code null} on an instance the provider has not yet populated
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Sets the eleven-digit account key, from {@code ACCT-ID PIC 9(11)}
     * ({@code app/cpy/CVACT01Y.cpy:L5}).
     * <p>
     * Changing the key of an already persistent instance is not a supported operation: the primary key is
     * supplied by the cross-reference record and by the account-creation path, never reassigned. This setter
     * exists so that the provider and fixture code can populate the field.
     *
     * @param accountId the account identifier to set
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
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
     * <p>
     * The value is stored exactly as supplied. No case folding and no membership check is applied, because the
     * copybook declares no condition names for this field and therefore states no accepted value set.
     *
     * @param activeStatus the active status character to set
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the current balance, from {@code ACCT-CURR-BAL PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L7}).
     *
     * @return the current balance with two-decimal scale; may be negative
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Sets the current balance, from {@code ACCT-CURR-BAL PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L7}).
     * <p>
     * Stored verbatim. Callers that compute a new balance perform the arithmetic themselves and are
     * responsible for using {@code RoundingMode.HALF_EVEN} if the result needs rounding to the column's
     * two-decimal scale.
     *
     * @param currentBalance the balance to set; may be negative
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    /**
     * Returns the credit limit, from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L8}).
     *
     * @return the credit limit with two-decimal scale
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Sets the credit limit, from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L8}).
     *
     * @param creditLimit the credit limit to set
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
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
     * @param cashCreditLimit the cash credit limit to set
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * Returns the account open date as ten-character text, from {@code ACCT-OPEN-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L10}).
     *
     * @return the open date in {@code yyyy-MM-dd} form as stored; never parsed or normalised here
     */
    public String getOpenDate() {
        return openDate;
    }

    /**
     * Sets the account open date as ten-character text, from {@code ACCT-OPEN-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L10}).
     * <p>
     * No calendar validation is performed. The source compares this field by year, month and day substrings at
     * offsets 1, 6 and 9 rather than as a date, and blank or legacy-invalid values must remain loadable, so
     * validating here would reject data the system of record accepts.
     *
     * @param openDate the open date text to set
     */
    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    /**
     * Returns the account expiry date as ten-character text, from {@code ACCT-EXPIRAION-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L11}).
     * <p>
     * The accessor name preserves the copybook's misspelling of {@code EXPIRATION}, which is the only spelling
     * used anywhere in the corpus.
     *
     * @return the expiry date in {@code yyyy-MM-dd} form as stored
     */
    public String getExpiraionDate() {
        return expiraionDate;
    }

    /**
     * Sets the account expiry date as ten-character text, from {@code ACCT-EXPIRAION-DATE PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L11}).
     * <p>
     * The accessor name preserves the copybook's misspelling of {@code EXPIRATION}. No calendar validation is
     * performed; the posting path compares this value as a string against the first ten characters of a
     * transaction's originating timestamp.
     *
     * @param expiraionDate the expiry date text to set
     */
    public void setExpiraionDate(String expiraionDate) {
        this.expiraionDate = expiraionDate;
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
     * <p>
     * No calendar validation is performed, for the reasons given on {@link #setOpenDate(String)}.
     *
     * @param reissueDate the reissue date text to set
     */
    public void setReissueDate(String reissueDate) {
        this.reissueDate = reissueDate;
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
     * <p>
     * The posting path accumulates into this field and the interest path zeroes it at end of cycle; both
     * compute the new value and set it here. Nothing is accumulated by this setter.
     *
     * @param currentCycleCredit the cycle credit accumulator value to set
     */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit;
    }

    /**
     * Returns the current cycle debit accumulator, from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L14}).
     * <p>
     * The returned value <b>may be negative</b> and is returned exactly as stored. Callers must not take its
     * absolute value: the over-limit expression subtracts this term precisely because it can be negative.
     *
     * @return the cycle debit accumulator with two-decimal scale, signed and unnormalised
     */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * Sets the current cycle debit accumulator, from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}
     * ({@code app/cpy/CVACT01Y.cpy:L14}).
     * <p>
     * The value is stored exactly as supplied, sign included. A negative amount posted to the debit
     * accumulator is correct and intended behaviour, so this setter applies no absolute value, no clamping to
     * zero and no sign correction of any kind.
     *
     * @param currentCycleDebit the cycle debit accumulator value to set; may be negative
     */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit;
    }

    /**
     * Returns the account postal code, from {@code ACCT-ADDR-ZIP PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L15}).
     * <p>
     * This value is address-adjacent personal data and is deliberately absent from {@link #toString()}. A
     * caller that logs it must mask it.
     *
     * @return the postal code as stored; not restricted to digits
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * Sets the account postal code, from {@code ACCT-ADDR-ZIP PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L15}).
     *
     * @param addressZip the postal code to set
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    /**
     * Returns the disclosure group identifier, from {@code ACCT-GROUP-ID PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L16}).
     * <p>
     * The returned value <b>may be blank</b>, which is a legitimate state present in the seed data, and is
     * returned without trimming or case folding. Consumers that match it against a disclosure group must
     * apply the fallback to the default group, exactly as the interest calculation does.
     *
     * @return the group identifier as stored; may be all spaces, never {@code null} once loaded
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the disclosure group identifier, from {@code ACCT-GROUP-ID PIC X(10)}
     * ({@code app/cpy/CVACT01Y.cpy:L16}).
     * <p>
     * Blank input is accepted and stored as given. This setter never trims a blank value to {@code null} and
     * never folds case, because both would corrupt a value the seed data legitimately contains.
     *
     * @param groupId the group identifier to set; may be blank
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
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
     * <p>
     * The counter is provider-managed and application code normally leaves it alone. Set it only when
     * reconstituting a detached instance whose version was carried outside the persistence context, or in a
     * test fixture. Supplying a stale value causes the next flush to fail with an optimistic-lock error, which
     * is the intended protection rather than a defect.
     *
     * @param version the version counter to set
     */
    public void setVersion(Long version) {
        this.version = version;
    }


    /**
     * Compares two accounts by primary key alone.
     * <p>
     * Identity for a persistent record is its key, so {@code accountId} is the only field considered. Two
     * instances carrying the same eleven-digit key denote the same account no matter how their mutable
     * balances, dates or accumulators currently differ, which is what makes the result stable across a
     * transaction that updates those balances. An instance whose key is still {@code null} is equal only to
     * itself, so unsaved instances never collide in a collection.
     * <p>
     * The test uses {@code instanceof} rather than an exact class comparison on purpose: the persistence
     * provider materialises lazy references as a generated subclass, and an exact class comparison would
     * report a proxy and its own target as different objects.
     * <p>
     * <b>No monetary field participates, and none may be added.</b> {@link BigDecimal#equals(Object)} is
     * scale-sensitive and reports {@code 2.0} and {@code 2.00} as unequal, so any value comparison of the
     * money fields anywhere in the codebase must use {@link BigDecimal#compareTo(BigDecimal)} instead. The
     * rule is stated here, on the one method a reader checks first, so that it is discoverable even though
     * this implementation is key-based.
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
     * Returns a hash code derived from the primary key alone, consistent with
     * {@link #equals(Object)}.
     * <p>
     * Only {@code accountId} contributes, so the hash of an instance does not change when a balance, a date or
     * an accumulator is updated. That stability is the point: a mutable-field hash would let an instance go
     * missing from a hash-based collection the moment a transaction touched it. Instances whose key is still
     * {@code null} all share one bucket, which is acceptable because unsaved instances are few and are unequal
     * to one another.
     *
     * @return the hash code of the account identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId);
    }

    /**
     * Returns a diagnostic representation of this account.
     * <p>
     * <b>{@code addressZip} is deliberately omitted.</b> A postal code is address-adjacent personal data,
     * {@code toString} output reaches log files as a matter of routine, and omitting the value at source is a
     * stronger control than relying on a downstream masking rule to catch it. Every other field is included:
     * the identifiers, the three text dates and the group identifier are not personal data, and the monetary
     * amounts are account figures rather than personal identifiers, so they are safe to render and are the
     * fields an operator actually needs when reading a log line.
     * <p>
     * The format is intended for humans and for log correlation only. It is not a serialisation format, it is
     * not parsed anywhere, and it is not the fixed-width 300-byte representation: emitting that record image is
     * the job of the fixed-width writers, which apply the byte offsets and the zoned-decimal sign encoding.
     *
     * @return a single-line description of this account, excluding the postal code
     */
    @Override
    public String toString() {
        return "Account{"
                + "accountId=" + accountId
                + ", activeStatus='" + activeStatus + '\''
                + ", currentBalance=" + currentBalance
                + ", creditLimit=" + creditLimit
                + ", cashCreditLimit=" + cashCreditLimit
                + ", openDate='" + openDate + '\''
                + ", expiraionDate='" + expiraionDate + '\''
                + ", reissueDate='" + reissueDate + '\''
                + ", currentCycleCredit=" + currentCycleCredit
                + ", currentCycleDebit=" + currentCycleDebit
                + ", groupId='" + groupId + '\''
                + ", version=" + version
                + '}';
    }
}

