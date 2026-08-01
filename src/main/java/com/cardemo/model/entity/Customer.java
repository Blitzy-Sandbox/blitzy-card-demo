/*
 * ******************************************************************
 * Program     : Customer.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Relational replacement for the VSAM KSDS cluster
 *               AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS. Carries the complete
 *               500-byte CUSTOMER-RECORD field contract as a persistent
 *               entity, preserving every source width, every text/numeric
 *               decision and the dash-separated date-of-birth
 *               representation byte-for-byte.
 * Source      : app/cpy/CVCUS01Y.cpy (500 B, key 9) @ 7756d89
 *               app/cpy/CUSTREC.cpy  (500 B, duplicate layout)
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
 * ******************************************************************
 */

package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

/**
 * Customer master record — the relational form of the legacy VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}.
 *
 * <h2>What this component does</h2>
 * <p>
 * It is a pure persistent data holder. It maps the eighteen populated fields of the
 * COBOL {@code 01 CUSTOMER-RECORD} group onto eighteen columns of table
 * {@code customer}, plus one framework-owned optimistic-locking column. It contains
 * no business logic, performs no validation beyond null-rejection of the primary key,
 * emits no log output, opens no connection and calls no collaborator. Every behavioural
 * rule that reads or compares these fields — change detection, case folding, date
 * parsing, area-code lookup — lives in the service layer, deliberately and for the
 * reasons set out under "Behaviour that deliberately lives elsewhere" below.
 * </p>
 *
 * <h2>Provenance</h2>
 * <ul>
 *   <li>Record layout: {@code app/cpy/CVCUS01Y.cpy}, group {@code 01 CUSTOMER-RECORD},
 *       header comment at {@code :L2} reading
 *       "Data-structure for Customer entity (RECLN 500)", fields at {@code :L5-L23}.</li>
 *   <li>Physical geometry: {@code app/catlg/LISTCAT.txt:L630} names the cluster and
 *       {@code :L632} reports {@code KEYLEN 9} with {@code AVGLRECL 500};
 *       {@code :L633} corroborates {@code MAXLRECL 500} and {@code RKP 0}, so the
 *       nine-byte key is the record prefix.</li>
 *   <li>Fixture corroboration: {@code app/data/ASCII/custdata.txt} holds 50 rows of
 *       exactly 500 bytes each (25,050 bytes total), matching the catalogued width.</li>
 *   <li>Traceability anchor: commit {@code 7756d89}.</li>
 * </ul>
 *
 * <h2>One entity serves both copybooks</h2>
 * <p>
 * Two copybooks in the frozen corpus describe this record. They are the same layout.
 * {@code diff -w app/cpy/CVCUS01Y.cpy app/cpy/CUSTREC.cpy} produces exactly two hunks:
 * </p>
 * <ol>
 *   <li>{@code :L19} spells the date-of-birth field {@code CUST-DOB-YYYY-MM-DD} in
 *       {@code CVCUS01Y.cpy} and {@code CUST-DOB-YYYYMMDD} in {@code CUSTREC.cpy}.
 *       Only the <em>name</em> differs — both declare {@code PIC X(10)}.</li>
 *   <li>{@code :L25}, the trailing version comment, differs by one second
 *       (23:16:00 CDT versus 23:15:59 CDT).</li>
 * </ol>
 * <p>
 * The raw {@code diff} runs to 42 lines, every one of which is tab-versus-space
 * indentation. Both groups are {@code 01 CUSTOMER-RECORD}, both total 500 bytes, and
 * the field order and every field width are identical. There is therefore <em>one</em>
 * entity for both copybooks and no second layout type anywhere in this package —
 * duplication that would have to be kept in lockstep forever, for no gain. The
 * {@code CVCUS01Y.cpy} spelling {@code CUST-DOB-YYYY-MM-DD} is adopted as canonical
 * because that is the copybook the programs actually {@code COPY}.
 * </p>
 *
 * <h2>Field contract and the 500-byte proof</h2>
 * <pre>
 * cpy  COBOL field               PIC     Java field                  Column                    SQL type
 * ---  ------------------------  ------  --------------------------  ------------------------  -------------
 * L5   CUST-ID                   9(09)   customerId                  cust_id                   NUMERIC(9) PK
 * L6   CUST-FIRST-NAME           X(25)   firstName                   cust_first_name           CHAR(25)
 * L7   CUST-MIDDLE-NAME          X(25)   middleName                  cust_middle_name          CHAR(25)
 * L8   CUST-LAST-NAME            X(25)   lastName                    cust_last_name            CHAR(25)
 * L9   CUST-ADDR-LINE-1          X(50)   addressLine1                cust_addr_line_1          CHAR(50)
 * L10  CUST-ADDR-LINE-2          X(50)   addressLine2                cust_addr_line_2          CHAR(50)
 * L11  CUST-ADDR-LINE-3          X(50)   addressLine3                cust_addr_line_3          CHAR(50)
 * L12  CUST-ADDR-STATE-CD        X(02)   addressStateCode            cust_addr_state_cd        CHAR(2)
 * L13  CUST-ADDR-COUNTRY-CD      X(03)   addressCountryCode          cust_addr_country_cd      CHAR(3)
 * L14  CUST-ADDR-ZIP             X(10)   addressZip                  cust_addr_zip             CHAR(10)
 * L15  CUST-PHONE-NUM-1          X(15)   phoneNumber1                cust_phone_num_1          CHAR(15)
 * L16  CUST-PHONE-NUM-2          X(15)   phoneNumber2                cust_phone_num_2          CHAR(15)
 * L17  CUST-SSN                  9(09)   ssn                         cust_ssn                  CHAR(9)
 * L18  CUST-GOVT-ISSUED-ID       X(20)   governmentIssuedId          cust_govt_issued_id       CHAR(20)
 * L19  CUST-DOB-YYYY-MM-DD       X(10)   dateOfBirth                 cust_dob_yyyy_mm_dd       CHAR(10)
 * L20  CUST-EFT-ACCOUNT-ID       X(10)   eftAccountId                cust_eft_account_id       CHAR(10)
 * L21  CUST-PRI-CARD-HOLDER-IND  X(01)   primaryCardHolderIndicator  cust_pri_card_holder_ind  CHAR(1)
 * L22  CUST-FICO-CREDIT-SCORE    9(03)   ficoCreditScore             cust_fico_credit_score    CHAR(3)
 * L23  FILLER                    X(168)  (not modelled)              (none)                    (none)
 * --   (no source field)         (none)  version                     version                   BIGINT
 * </pre>
 * <p>
 * Width arithmetic, reproduced so that any future edit can be checked against the
 * catalogued record length rather than against memory:
 * </p>
 * <pre>
 *   9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3
 *     = 332 populated bytes
 *   332 + 168 (FILLER) = 500 bytes, matching AVGLRECL and MAXLRECL at LISTCAT.txt:L632-L633
 * </pre>
 * <p>
 * {@code FILLER X(168)} is deliberately not modelled. It carries no data — it is blank
 * in all 50 fixture rows — and exists only to pad the record to its catalogued 500
 * bytes. Reconstituting the fixed-width image is the batch writers' concern, and they
 * pad from the 168-byte count recorded here.
 * </p>
 * <p>
 * There is no monetary or rate field on this entity, so it declares no
 * {@code BigDecimal} — and, in common with every entity in this package, no IEEE 754
 * binary numeric type of either width anywhere, which the security-audit gate asserts
 * by inspection across the whole tree. {@code ficoCreditScore} is a code rather than an
 * amount and is therefore text; see the Medium finding below.
 * </p>
 *
 * <h2>Findings, classified by severity</h2>
 * <dl>
 *   <dt><strong>Blocker — a {@code String} attribute maps to JDBC {@code VARCHAR} by
 *       default, and schema validation rejects that against a {@code CHAR}
 *       column</strong></dt>
 *   <dd>Found by starting this mapping against a live PostgreSQL 16.10, not by
 *       compiling it: every unit assertion and every data round-trip passed while
 *       application startup could not have succeeded at all. The provider derives the
 *       expected JDBC type from the Java type, so a {@code String} resolves to
 *       {@code VARCHAR}, and {@code columnDefinition} steers only the generated DDL and
 *       not that expectation. PostgreSQL reports a {@code CHAR(n)} column as
 *       {@code bpchar}, which is {@code Types.CHAR}, so validation failed on the first
 *       text column with "wrong column type encountered in column
 *       [cust_addr_country_cd] in table [customer]; found [bpchar (Types#CHAR)], but
 *       expecting [char(3) (Types#VARCHAR)]". Because {@code ddl-auto} is
 *       {@code validate} in every profile, this aborts every boot.
 *       <em>Remediation, already applied:</em> declare
 *       {@code @JdbcTypeCode(SqlTypes.CHAR)} on all seventeen text columns alongside
 *       {@code columnDefinition}. Widening the columns to {@code VARCHAR} would also
 *       have silenced it and was rejected, because that destroys the blank padding on
 *       which fixed-width re-emission depends. A unit assertion now pins the annotation
 *       on all seventeen text columns, and its absence on both numeric columns.</dd>
 *   <dt><strong>High — this record is the personally-identifiable-information
 *       epicentre of the application, so {@code toString} exposes none of it</strong></dt>
 *   <dd>Thirteen of the eighteen fields are personal data: the three name fields, the
 *       three address lines, the postal code, both telephone numbers, the social
 *       security number, the government-issued identifier, the date of birth and the
 *       electronic-funds account identifier. {@code toString} is rendered implicitly by
 *       string concatenation, by collection printing and by most logging frameworks, so
 *       a permissive {@code toString} leaks every one of those fields into the log
 *       stream. The log configuration masks social security numbers, credentials and
 *       hashes, but masking is the backstop; never emitting is the primary defence.
 *       This class therefore restricts {@code toString} to {@code customerId} and
 *       {@code version}, and offers no wider alternative. See the method's own
 *       documentation for the standing prohibition.</dd>
 *   <dt><strong>High — {@code dateOfBirth} is ten-character dash-separated text, and
 *       the update-request snapshot is a different width</strong></dt>
 *   <dd>The live record is {@code PIC X(10)}: row 1 of
 *       {@code app/data/ASCII/custdata.txt} holds {@code 1961-06-08} at bytes 309-318,
 *       and a shape census across all 50 rows finds the dash-separated form and nothing
 *       else. The account-update snapshot, however, is eight characters:
 *       {@code app/cbl/COACTUPC.cbl:L746} declares
 *       {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} with a {@code REDEFINES} into
 *       {@code X(4)}, {@code X(2)}, {@code X(2)} parts, so its components sit at
 *       offsets 1, 5 and 7 while the live record's sit at 1, 6 and 9. The source
 *       compares them component-wise and cross-wise — 1 against 1, 6 against 5, 9
 *       against 7 — at {@code app/cbl/COACTUPC.cbl:L4174-L4179}. This entity keeps the
 *       ten-character dash-separated form. Aligning it to the eight-character snapshot,
 *       or stripping the separators, would silently corrupt the stored representation;
 *       comparing the two whole would report a change on every single request and make
 *       the update endpoint permanently unusable.</dd>
 *   <dt><strong>Medium — two {@code PIC 9(n)} fields are mapped as text, while a third
 *       stays numeric</strong></dt>
 *   <dd>{@code CUST-SSN} and {@code CUST-FICO-CREDIT-SCORE} are declared numeric in the
 *       copybook but carry significant leading zeros in the fixture, so they are
 *       {@code String}. {@code CUST-ID} is also declared numeric and does stay
 *       numeric. The asymmetry is deliberate; both halves are justified on the fields
 *       themselves.</dd>
 * </dl>
 *
 * <h2>Behaviour that deliberately lives elsewhere</h2>
 * <ul>
 *   <li><strong>Case folding.</strong> The change-detection paragraph
 *       {@code 9700-CHECK-CHANGE-IN-REC} at
 *       {@code app/cbl/COACTUPC.cbl:L4109-L4193} folds three different ways in one
 *       predicate. It compares the name fields, all three address lines, the state
 *       code, the country code and the government-issued identifier through
 *       {@code FUNCTION UPPER-CASE} on both sides; it compares the account group
 *       identifier through {@code FUNCTION LOWER-CASE} on both sides; and it compares
 *       the postal code, both telephone numbers, the social security number, the
 *       electronic-funds account identifier, the primary-holder indicator and the
 *       credit score with no case function at all. That three-way asymmetry is the
 *       behaviour, not an oversight, and normalising it in either direction would
 *       change which updates are accepted. It belongs to the service layer.
 *       <strong>This entity performs no case folding whatsoever</strong> — none in the
 *       setters, none in {@code equals}. Were any case operation ever introduced
 *       anywhere, it must pass {@code Locale.ROOT} so that the outcome cannot vary
 *       with the platform default locale.</li>
 *   <li><strong>Date parsing and validation.</strong> Owned by the date validation
 *       service, which uses {@code java.time}. This entity stores the character
 *       representation and neither parses nor validates it.</li>
 *   <li><strong>Telephone and postal-code plausibility.</strong> Owned by the
 *       validation lookup service and its classpath resources.</li>
 *   <li><strong>Business-field change detection.</strong> See the {@code version}
 *       field's documentation — the optimistic-locking column is necessary but not
 *       sufficient.</li>
 * </ul>
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><strong>No JPA associations.</strong> No {@code @ManyToOne}, no
 *       {@code @OneToMany}, no {@code @JoinColumn}, no cascade. The legacy corpus
 *       reaches a customer through explicit keyed lookups — cross-reference, then
 *       account, then customer — and reproducing that as an object graph would
 *       introduce lazy-loading and N+1 query behaviour the source does not have.
 *       Referential integrity is enforced by the schema's foreign keys instead.</li>
 *   <li><strong>No inheritance.</strong> No base entity, no {@code @MappedSuperclass},
 *       no auditing superclass. The record layout is the contract; a shared supertype
 *       would add columns the source does not have.</li>
 *   <li><strong>Not {@code Serializable}.</strong> Java serialisation of the record
 *       most damaging to expose is an avoidable risk, and deserialisation of untrusted
 *       bytes is a well-known attack surface. Should a future requirement force it, a
 *       {@code serialVersionUID} must be declared and instances must never be
 *       deserialised from untrusted input.</li>
 *   <li><strong>No bean-validation constraints.</strong> No {@code @NotBlank}, no
 *       {@code @NotEmpty}, no {@code @Pattern}, no {@code @Min} or {@code @Max}. Every
 *       column is {@code NOT NULL}, but blank-yet-present text is legitimate source
 *       data — an absent middle name or third address line arrives as spaces — and must
 *       load unchanged. Likewise no range check on {@code ficoCreditScore}: the fixture
 *       contains {@code 001}, far outside any real credit-score range, and parity
 *       requires it to load as-is.</li>
 *   <li><strong>Text columns are {@code CHAR(n)}, and the JDBC type code says so
 *       explicitly.</strong> Every text column declares both
 *       {@code columnDefinition = "CHAR(n)"} and
 *       {@code @JdbcTypeCode(SqlTypes.CHAR)}, and <em>both</em> are required. The first
 *       fixes the generated and expected DDL; the second fixes the JDBC type the
 *       provider expects to find. Without the second, the provider infers
 *       {@code VARCHAR} from the Java type alone while the database reports a
 *       {@code CHAR} column, and schema validation fails at startup with "wrong column
 *       type encountered … found [bpchar (Types#CHAR)], but expecting [char(n)
 *       (Types#VARCHAR)]". That was reproduced against PostgreSQL 16.10 and is the
 *       reason the annotation is present; it is not decoration and must not be removed.
 *       {@code CHAR} itself is not negotiable either, because it blank-pads to the full
 *       declared width — verified as physically stored, with
 *       {@code octet_length(cust_first_name) = 25} for an eight-character name — which
 *       is exactly the fixed-width geometry the batch writers must re-emit.
 *       {@code VARCHAR} would silently drop that padding. This is the one place the
 *       entity depends on the provider rather than on the specification alone; the
 *       provider is the one the build already pins, so no dependency is added.</li>
 *   <li><strong>No mutable static state</strong> and no logging from the entity.</li>
 * </ul>
 *
 * <h2>How to build and test</h2>
 * <pre>
 *   mvn -B clean compile        # compiles under -Xlint:all -Werror, release 25
 *   mvn -B clean test           # unit suite, including this entity's mapping assertions
 *   mvn -B clean verify         # adds JaCoCo (80% line floor) and the dependency scan
 * </pre>
 * <p>
 * Unit coverage lives in {@code src/test/java/com/cardemo/unit/model} and asserts the
 * 500-byte width arithmetic, the nine-byte key, that {@code ssn},
 * {@code ficoCreditScore} and {@code dateOfBirth} are {@code String}, and that
 * {@code toString} discloses none of the thirteen personal fields.
 * </p>
 *
 * <h2>Key configuration and defaults</h2>
 * <p>
 * This class reads no configuration of its own. Two settings elsewhere govern how it
 * behaves at runtime. {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in
 * every profile, so the mapping below is checked against the live schema during
 * application-context startup and any divergence in column name, type, length or
 * nullability aborts the boot rather than surfacing later as corrupt data. The schema
 * itself is owned by Flyway, which is enabled in every profile; this entity never
 * creates or alters a table.
 * </p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><em>Startup fails with a schema-validation error naming a
 *       {@code customer} column.</em> The migration and this mapping have diverged.
 *       The field table above is the normative contract; correct the migration to match
 *       it rather than widening the mapping, because the widths are the source record's
 *       and cannot be changed without breaking fixed-width re-emission.</li>
 *   <li><em>A social security number or credit score loses a leading zero.</em>
 *       Something in the pipeline has converted the text to a number. Both fields are
 *       {@code CHAR} end to end by design; find and remove the numeric conversion.</li>
 *   <li><em>Startup fails with "wrong column type … found [bpchar (Types#CHAR)], but
 *       expecting [char(n) (Types#VARCHAR)]".</em> A text field has lost its
 *       {@code @JdbcTypeCode(SqlTypes.CHAR)}. Restore it — do not "fix" the migration to
 *       {@code VARCHAR}, which would destroy the fixed-width blank padding. See the
 *       design decision above.</li>
 *   <li><em>Text read back is padded with trailing spaces.</em> That is
 *       {@code CHAR(n)} behaving correctly and matching the fixed-width source. Trim at
 *       the presentation boundary, never in this entity, or fixed-width re-emission
 *       breaks. Note that PostgreSQL's {@code length()} ignores trailing blanks on a
 *       {@code CHAR} column by definition, so use {@code octet_length()} when checking
 *       the stored width.</li>
 *   <li><em>An update silently overwrites a concurrent change.</em> The optimistic
 *       version guard alone does not reproduce the legacy check; the service layer must
 *       also compare the business-field snapshot. See the {@code version} field.</li>
 *   <li><em>Personal data appears in the logs.</em> {@code toString} is not the
 *       source — it exposes only the identifier and the version. Look for a caller
 *       logging individual getters.</li>
 * </ul>
 *
 * <h2>Information not available at authoring time</h2>
 * <p>
 * <strong>Not available</strong>: {@code src/main/resources/db/migration/V1__create_schema.sql}
 * did not exist when this entity was written, and the {@code db/migration} directory
 * had no children at all. The migration could therefore not be read, and no column
 * name, type or nullability could be confirmed against it. Because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, any
 * divergence between that migration and this mapping fails application-context startup
 * outright, so the divergence cannot go unnoticed — but it also cannot be pre-empted
 * here. <strong>The field table above is consequently the normative column contract,
 * and {@code V1__create_schema.sql} must converge upon it.</strong>
 * </p>
 * <p>
 * What is needed from that migration, exactly: table {@code customer} with
 * {@code cust_id NUMERIC(9) PRIMARY KEY}, {@code cust_first_name CHAR(25) NOT NULL},
 * {@code cust_middle_name CHAR(25) NOT NULL}, {@code cust_last_name CHAR(25) NOT NULL},
 * {@code cust_addr_line_1 CHAR(50) NOT NULL}, {@code cust_addr_line_2 CHAR(50) NOT NULL},
 * {@code cust_addr_line_3 CHAR(50) NOT NULL}, {@code cust_addr_state_cd CHAR(2) NOT NULL},
 * {@code cust_addr_country_cd CHAR(3) NOT NULL}, {@code cust_addr_zip CHAR(10) NOT NULL},
 * {@code cust_phone_num_1 CHAR(15) NOT NULL}, {@code cust_phone_num_2 CHAR(15) NOT NULL},
 * {@code cust_ssn CHAR(9) NOT NULL}, {@code cust_govt_issued_id CHAR(20) NOT NULL},
 * {@code cust_dob_yyyy_mm_dd CHAR(10) NOT NULL}, {@code cust_eft_account_id CHAR(10) NOT NULL},
 * {@code cust_pri_card_holder_ind CHAR(1) NOT NULL},
 * {@code cust_fico_credit_score CHAR(3) NOT NULL} and {@code version BIGINT NOT NULL}.
 * </p>
 * <p>
 * Two scope facts constrain that migration and are recorded here so this entity's
 * expectations are unambiguous. {@code V1} creates exactly eleven tables, with ten
 * foreign keys and five check constraints across them. Spring Batch's {@code BATCH_*}
 * metadata tables come from the framework's own schema script — never from a fourth
 * migration, and never as extra tables inside {@code V1}.
 * </p>
 */
@Entity
@Table(name = "customer")
public class Customer {

    /**
     * Customer identifier — {@code CUST-ID PIC 9(09)}, {@code app/cpy/CVCUS01Y.cpy:L5}.
     * Bytes 1-9 of the record, and the whole of the nine-byte VSAM key reported at
     * {@code app/catlg/LISTCAT.txt:L632} with {@code RKP 0} placing it at the record
     * start.
     *
     * <p>
     * Mapped numerically as {@code Long} over {@code NUMERIC(9)}, and deliberately so,
     * even though row 1 of {@code app/data/ASCII/custdata.txt} stores it as
     * {@code 000000001}. This is the opposite of the decision taken for {@code ssn} and
     * {@code ficoCreditScore}, and the asymmetry is intentional rather than an
     * oversight a reviewer should "fix": for an identifier the zero padding is purely a
     * fixed-width <em>emission</em> concern, discharged by the batch writers when they
     * rebuild the 500-byte image, and the composite-key types in the sibling
     * {@code key} package already fix account identifiers as {@code Long}. Making this
     * field text instead would fracture that convention and force every key comparison
     * through string handling. For {@code ssn} and {@code ficoCreditScore} no such
     * convention applies and the leading zero is part of the value itself.
     * </p>
     *
     * <p>
     * There is no {@code @GeneratedValue}: the identifier is a natural key supplied by
     * the source record, assigned before the entity is persisted and never altered
     * afterwards. That stability is what makes it safe to use in {@code hashCode}.
     * </p>
     */
    @Id
    @Column(name = "cust_id", nullable = false, columnDefinition = "NUMERIC(9)")
    private Long customerId;

    /**
     * Given name — {@code CUST-FIRST-NAME PIC X(25)}, {@code app/cpy/CVCUS01Y.cpy:L6}.
     * Bytes 10-34. Personal data: excluded from {@code toString}. Compared through
     * {@code FUNCTION UPPER-CASE} by the service layer, never here.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_first_name", nullable = false, length = 25, columnDefinition = "CHAR(25)")
    private String firstName;

    /**
     * Middle name — {@code CUST-MIDDLE-NAME PIC X(25)}, {@code app/cpy/CVCUS01Y.cpy:L7}.
     * Bytes 35-59. Legitimately all spaces when a customer has no middle name, which is
     * why the column is {@code NOT NULL} but carries no blank-rejecting constraint.
     * Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_middle_name", nullable = false, length = 25, columnDefinition = "CHAR(25)")
    private String middleName;

    /**
     * Family name — {@code CUST-LAST-NAME PIC X(25)}, {@code app/cpy/CVCUS01Y.cpy:L8}.
     * Bytes 60-84. Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_last_name", nullable = false, length = 25, columnDefinition = "CHAR(25)")
    private String lastName;

    /**
     * First address line — {@code CUST-ADDR-LINE-1 PIC X(50)},
     * {@code app/cpy/CVCUS01Y.cpy:L9}. Bytes 85-134. Personal data: excluded from
     * {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_line_1", nullable = false, length = 50, columnDefinition = "CHAR(50)")
    private String addressLine1;

    /**
     * Second address line — {@code CUST-ADDR-LINE-2 PIC X(50)},
     * {@code app/cpy/CVCUS01Y.cpy:L10}. Bytes 135-184. Personal data: excluded from
     * {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_line_2", nullable = false, length = 50, columnDefinition = "CHAR(50)")
    private String addressLine2;

    /**
     * Third address line — {@code CUST-ADDR-LINE-3 PIC X(50)},
     * {@code app/cpy/CVCUS01Y.cpy:L11}. Bytes 185-234. Legitimately all spaces when the
     * address needs only two lines. Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_line_3", nullable = false, length = 50, columnDefinition = "CHAR(50)")
    private String addressLine3;

    /**
     * State or province code — {@code CUST-ADDR-STATE-CD PIC X(02)},
     * {@code app/cpy/CVCUS01Y.cpy:L12}. Bytes 235-236; row 1 of the fixture holds
     * {@code NC}. Membership of the recognised state set is checked by the validation
     * lookup service against its classpath resource, not by this entity.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_state_cd", nullable = false, length = 2, columnDefinition = "CHAR(2)")
    private String addressStateCode;

    /**
     * Country code — {@code CUST-ADDR-COUNTRY-CD PIC X(03)},
     * {@code app/cpy/CVCUS01Y.cpy:L13}. Bytes 237-239; row 1 of the fixture holds
     * {@code USA}. Three characters, so it is the alpha-3 form rather than alpha-2.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_country_cd", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String addressCountryCode;

    /**
     * Postal code — {@code CUST-ADDR-ZIP PIC X(10)}, {@code app/cpy/CVCUS01Y.cpy:L14}.
     * Bytes 240-249; row 1 of the fixture holds {@code 12546} followed by five spaces,
     * so the field is text with trailing padding and not a number. The ten-character
     * width accommodates the extended plus-four form. Personal data: excluded from
     * {@code toString}. Compared with no case function by the service layer.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_zip", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String addressZip;

    /**
     * Primary telephone number — {@code CUST-PHONE-NUM-1 PIC X(15)},
     * {@code app/cpy/CVCUS01Y.cpy:L15}. Bytes 250-264.
     *
     * <p>
     * Text, never numeric, and the fixture proves why: row 1 of
     * {@code app/data/ASCII/custdata.txt} holds {@code (908)119-8310} followed by two
     * spaces across those fifteen bytes. Parentheses, a hyphen and trailing padding are
     * part of the stored value; any numeric mapping would destroy the formatting the
     * legacy screens redisplay verbatim.
     * </p>
     *
     * <p>
     * No format constraint is declared here. Area-code plausibility is the validation
     * lookup service's responsibility, backed by its classpath resource. Personal data:
     * excluded from {@code toString}.
     * </p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_phone_num_1", nullable = false, length = 15, columnDefinition = "CHAR(15)")
    private String phoneNumber1;

    /**
     * Secondary telephone number — {@code CUST-PHONE-NUM-2 PIC X(15)},
     * {@code app/cpy/CVCUS01Y.cpy:L16}. Bytes 265-279; row 1 of the fixture holds
     * {@code (373)693-8684} plus two spaces. Text for the same reason as
     * {@code phoneNumber1}. Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_phone_num_2", nullable = false, length = 15, columnDefinition = "CHAR(15)")
    private String phoneNumber2;

    /**
     * Social security number — {@code CUST-SSN PIC 9(09)},
     * {@code app/cpy/CVCUS01Y.cpy:L17}. Bytes 280-288.
     *
     * <p>
     * <strong>Declared numeric in the copybook, mapped as {@code String} over
     * {@code CHAR(9)} on purpose.</strong> A leading-zero census over all 50 rows of
     * {@code app/data/ASCII/custdata.txt} finds <strong>6 rows beginning with
     * {@code 0}</strong>; row 1 is {@code 020973888}. A numeric mapping drops that
     * zero, which both corrupts the value and breaks byte-exact re-emission of the
     * 500-byte record, because nine significant characters would come back as eight.
     * The field is never an arithmetic operand anywhere in the corpus — it is only ever
     * compared for equality, and with no case function, at
     * {@code app/cbl/COACTUPC.cbl:L4171} — so nothing is lost by storing it as text.
     * The same reasoning already governs the card verification code on the
     * {@code Card} entity.
     * </p>
     *
     * <p>
     * The single most sensitive value on the most sensitive record in the application.
     * Excluded from {@code toString}, and masked by the logging configuration as a
     * second line of defence.
     * </p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_ssn", nullable = false, length = 9, columnDefinition = "CHAR(9)")
    private String ssn;

    /**
     * Government-issued identifier — {@code CUST-GOVT-ISSUED-ID PIC X(20)},
     * {@code app/cpy/CVCUS01Y.cpy:L18}. Bytes 289-308; row 1 of the fixture holds
     * {@code 00000000000049368437}, which is zero-padded text and confirms the
     * character mapping. Personal data: excluded from {@code toString}. Compared through
     * {@code FUNCTION UPPER-CASE} by the service layer.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_govt_issued_id", nullable = false, length = 20, columnDefinition = "CHAR(20)")
    private String governmentIssuedId;

    /**
     * Date of birth — {@code CUST-DOB-YYYY-MM-DD PIC X(10)},
     * {@code app/cpy/CVCUS01Y.cpy:L19}. Bytes 309-318.
     *
     * <p>
     * <strong>Ten-character dash-separated text.</strong> Row 1 of
     * {@code app/data/ASCII/custdata.txt} holds {@code 1961-06-08}, and a shape census
     * across all 50 rows finds that form and no other. Stored as {@code String} over
     * {@code CHAR(10)}: deliberately no Java date-time type, no JPA temporal annotation
     * and no date conversion of any kind. A temporal mapping would re-render the value
     * on write and could not guarantee the exact ten characters the fixed-width writers
     * must reproduce.
     * </p>
     *
     * <p>
     * <strong>The update-request snapshot is a different width, and conflating the two
     * breaks the endpoint.</strong> {@code app/cbl/COACTUPC.cbl:L746} declares
     * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} — eight characters, the compact
     * separator-free form — with a {@code REDEFINES} at {@code :L747-L751} splitting it
     * into {@code X(4)}, {@code X(2)}, {@code X(2)}. Its components therefore sit at
     * offsets 1, 5 and 7, whereas this field's sit at 1, 6 and 9. The source compares
     * them cross-wise and component-wise at {@code :L4174-L4179}: offset 1 against
     * offset 1, offset 6 against offset 5, offset 9 against offset 7. Comparing the two
     * representations whole would differ on every request and make the account-update
     * endpoint permanently unusable.
     * </p>
     *
     * <p>
     * Consequently this entity keeps the ten-character dash-separated form unchanged.
     * Do not align it to the eight-character snapshot and do not strip the separators.
     * The component-wise comparison belongs to the service layer; parsing and calendar
     * validation belong to the date validation service. Personal data: excluded from
     * {@code toString}.
     * </p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_dob_yyyy_mm_dd", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String dateOfBirth;

    /**
     * Electronic-funds-transfer account identifier — {@code CUST-EFT-ACCOUNT-ID
     * PIC X(10)}, {@code app/cpy/CVCUS01Y.cpy:L20}. Bytes 319-328; row 1 of the fixture
     * holds {@code 0053581756}, zero-padded text. Personal financial data: excluded
     * from {@code toString}. Compared with no case function by the service layer.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_eft_account_id", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String eftAccountId;

    /**
     * Primary-card-holder indicator — {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)},
     * {@code app/cpy/CVCUS01Y.cpy:L21}. Byte 329; row 1 of the fixture holds {@code Y}.
     * A single character rather than a boolean, because the source field is one byte of
     * character data and the batch writers must re-emit exactly whatever byte was
     * stored — including any value other than {@code Y} or {@code N}. Compared with no
     * case function by the service layer.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_pri_card_holder_ind", nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private String primaryCardHolderIndicator;

    /**
     * FICO credit score — {@code CUST-FICO-CREDIT-SCORE PIC 9(03)},
     * {@code app/cpy/CVCUS01Y.cpy:L22}. Bytes 330-332.
     *
     * <p>
     * <strong>Declared numeric in the copybook, mapped as {@code String} over
     * {@code CHAR(3)} on purpose</strong> — the same decision as {@code ssn} and for
     * the same evidence. A leading-zero census over all 50 rows of
     * {@code app/data/ASCII/custdata.txt} finds <strong>7 rows beginning with
     * {@code 0}</strong>, with the distinct set {@code 001}, {@code 044}, {@code 051},
     * {@code 053}, {@code 054}, {@code 058} and {@code 078}. A numeric mapping drops
     * those zeros and breaks the three-character re-emission. The score is a code here,
     * never an arithmetic operand: it is only ever compared for equality, with no case
     * function, at {@code app/cbl/COACTUPC.cbl:L4188}. It is emphatically not a monetary
     * amount and therefore not a {@code BigDecimal}.
     * </p>
     *
     * <p>
     * <strong>No range validation is declared, deliberately.</strong> {@code 001} is far
     * outside any real credit-score band and is a legacy fixture quirk. Parity is the
     * contract: the value loads exactly as it appears in the source, so there is no
     * {@code @Min}, no {@code @Max} and no pattern constraint. Rejecting it here would
     * make 50-row fixture loads fail on data the legacy system accepts.
     * </p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_fico_credit_score", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String ficoCreditScore;

    /**
     * Optimistic-locking version counter. Has <strong>no counterpart in the source
     * record</strong> — the 500-byte layout is fully accounted for by the eighteen data
     * fields plus {@code FILLER X(168)} — and is introduced because the relational
     * target has no equivalent of the legacy read-for-update lock.
     *
     * <p>
     * <strong>Necessary but not sufficient.</strong> This counter detects that
     * <em>some</em> concurrent write has occurred. The legacy account-update program
     * detects something stricter: that <em>specific business field values</em> differ
     * from the ones the user was shown. The two are not equivalent — a concurrent write
     * that set a field and then restored its original value passes the legacy check but
     * fails a version check. Reproducing the source therefore requires two layers, and
     * this is only the store-level one.
     * </p>
     *
     * <p>
     * The business-level layer is an explicit field-by-field comparison against a
     * snapshot captured when the screen was first populated, implemented in the service
     * layer from {@code app/cbl/COACTUPC.cbl:L4109-L4193} and fed by the
     * {@code oldDetails} group that {@code AccountUpdateRequest} in
     * {@code com.cardemo.model.dto} carries. Because the target is stateless the
     * snapshot cannot live on the server between requests, which is precisely why the
     * request body carries it. Neither layer substitutes for the other.
     * </p>
     *
     * <p>
     * Assigned and incremented by the persistence provider; a caller normally leaves it
     * alone. It is excluded from the all-columns constructor for that reason, and the
     * setter exists only so a detached instance can be reconstituted with its known
     * version.
     * </p>
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "BIGINT")
    private Long version;

    /**
     * No-argument constructor required by the JPA specification so that the persistence
     * provider can instantiate the entity during hydration.
     *
     * <p>
     * Deliberately {@code protected} rather than {@code public}: the provider and
     * subclasses need it, application code does not, and leaving it public would invite
     * the creation of instances with every field null — including the primary key —
     * which the {@code NOT NULL} schema would then reject at flush time with a far less
     * obvious error than the all-columns constructor produces at the point of the
     * mistake. It cannot be made {@code private}, because the provider must reach it.
     * </p>
     *
     * <p>
     * Leaves every field null. Callers other than the provider should use the
     * all-columns constructor.
     * </p>
     */
    protected Customer() {
        // Intentionally empty. Field population is the persistence provider's
        // responsibility during hydration; see the all-columns constructor for the
        // application-facing entry point.
    }

    /**
     * Creates a fully populated customer from the complete {@code CUST-*} field contract
     * of {@code app/cpy/CVCUS01Y.cpy:L5-L22}.
     *
     * <p>
     * The parameter list is the source record's field order, so a caller reading the
     * copybook top to bottom supplies the arguments in the order they appear there.
     * Every parameter maps to a {@code NOT NULL} column and every field is assigned
     * directly rather than through its setter, both to keep the constructor free of any
     * call to an overridable method and so that the object is fully formed before any
     * other code can observe it.
     * </p>
     *
     * <p>
     * {@code version} is not a parameter. It has no counterpart in the source record and
     * is assigned by the persistence provider; supplying it at construction would let a
     * caller fabricate a version and defeat the optimistic-locking guard. Use
     * {@code setVersion} only when reconstituting a detached instance whose version is
     * genuinely known.
     * </p>
     *
     * <p>
     * No trimming, no padding, no case folding and no reformatting is applied to any
     * argument. Values are stored exactly as supplied, because the fixed-width writers
     * must be able to re-emit the original 500-byte image and the service layer's
     * comparisons depend on the stored representation.
     * </p>
     *
     * @param customerId                 {@code CUST-ID PIC 9(09)}; must not be null
     * @param firstName                  {@code CUST-FIRST-NAME PIC X(25)}
     * @param middleName                 {@code CUST-MIDDLE-NAME PIC X(25)}; may be blank
     * @param lastName                   {@code CUST-LAST-NAME PIC X(25)}
     * @param addressLine1               {@code CUST-ADDR-LINE-1 PIC X(50)}
     * @param addressLine2               {@code CUST-ADDR-LINE-2 PIC X(50)}
     * @param addressLine3               {@code CUST-ADDR-LINE-3 PIC X(50)}; may be blank
     * @param addressStateCode           {@code CUST-ADDR-STATE-CD PIC X(02)}
     * @param addressCountryCode         {@code CUST-ADDR-COUNTRY-CD PIC X(03)}
     * @param addressZip                 {@code CUST-ADDR-ZIP PIC X(10)}
     * @param phoneNumber1               {@code CUST-PHONE-NUM-1 PIC X(15)}; formatted text
     * @param phoneNumber2               {@code CUST-PHONE-NUM-2 PIC X(15)}; formatted text
     * @param ssn                        {@code CUST-SSN PIC 9(09)}; text, leading zeros significant
     * @param governmentIssuedId         {@code CUST-GOVT-ISSUED-ID PIC X(20)}
     * @param dateOfBirth                {@code CUST-DOB-YYYY-MM-DD PIC X(10)}; dash-separated
     * @param eftAccountId               {@code CUST-EFT-ACCOUNT-ID PIC X(10)}
     * @param primaryCardHolderIndicator {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}
     * @param ficoCreditScore            {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}; text, not ranged
     * @throws IllegalArgumentException if {@code customerId} is null, since it is the
     *                                  nine-byte VSAM key and the table's primary key,
     *                                  and a null there cannot be a valid record
     */
    public Customer(Long customerId,
                    String firstName,
                    String middleName,
                    String lastName,
                    String addressLine1,
                    String addressLine2,
                    String addressLine3,
                    String addressStateCode,
                    String addressCountryCode,
                    String addressZip,
                    String phoneNumber1,
                    String phoneNumber2,
                    String ssn,
                    String governmentIssuedId,
                    String dateOfBirth,
                    String eftAccountId,
                    String primaryCardHolderIndicator,
                    String ficoCreditScore) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId (CUST-ID) must not be null");
        }
        this.customerId = customerId;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.addressLine3 = addressLine3;
        this.addressStateCode = addressStateCode;
        this.addressCountryCode = addressCountryCode;
        this.addressZip = addressZip;
        this.phoneNumber1 = phoneNumber1;
        this.phoneNumber2 = phoneNumber2;
        this.ssn = ssn;
        this.governmentIssuedId = governmentIssuedId;
        this.dateOfBirth = dateOfBirth;
        this.eftAccountId = eftAccountId;
        this.primaryCardHolderIndicator = primaryCardHolderIndicator;
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Returns the customer identifier — {@code CUST-ID PIC 9(09)}.
     *
     * @return the nine-digit identifier, never null on a persisted instance
     */
    public Long getCustomerId() {
        return customerId;
    }

    /**
     * Sets the customer identifier — {@code CUST-ID PIC 9(09)}.
     *
     * <p>
     * Reassigning the primary key of an already-persisted entity is not a supported
     * operation and the persistence provider will not translate it into an update of the
     * key column. The setter exists so that the identifier can be supplied when building
     * an instance outside the all-columns constructor, for example while mapping an
     * inbound fixed-width record field by field.
     * </p>
     *
     * @param customerId the nine-digit identifier; must not be null
     * @throws IllegalArgumentException if {@code customerId} is null
     */
    public void setCustomerId(Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId (CUST-ID) must not be null");
        }
        this.customerId = customerId;
    }

    /**
     * Returns the given name — {@code CUST-FIRST-NAME PIC X(25)}.
     *
     * @return the given name, space-padded to 25 characters as stored
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the given name — {@code CUST-FIRST-NAME PIC X(25)}. Stored verbatim: no
     * trimming, no padding and no case folding is applied.
     *
     * @param firstName the given name
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the middle name — {@code CUST-MIDDLE-NAME PIC X(25)}.
     *
     * @return the middle name, which is legitimately all spaces when the customer has
     *         none
     */
    public String getMiddleName() {
        return middleName;
    }

    /**
     * Sets the middle name — {@code CUST-MIDDLE-NAME PIC X(25)}. Stored verbatim; a blank
     * value is valid source data and is accepted unchanged.
     *
     * @param middleName the middle name
     */
    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    /**
     * Returns the family name — {@code CUST-LAST-NAME PIC X(25)}.
     *
     * @return the family name, space-padded to 25 characters as stored
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the family name — {@code CUST-LAST-NAME PIC X(25)}. Stored verbatim.
     *
     * @param lastName the family name
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the first address line — {@code CUST-ADDR-LINE-1 PIC X(50)}.
     *
     * @return the first address line, space-padded to 50 characters as stored
     */
    public String getAddressLine1() {
        return addressLine1;
    }

    /**
     * Sets the first address line — {@code CUST-ADDR-LINE-1 PIC X(50)}. Stored verbatim.
     *
     * @param addressLine1 the first address line
     */
    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    /**
     * Returns the second address line — {@code CUST-ADDR-LINE-2 PIC X(50)}.
     *
     * @return the second address line, space-padded to 50 characters as stored
     */
    public String getAddressLine2() {
        return addressLine2;
    }

    /**
     * Sets the second address line — {@code CUST-ADDR-LINE-2 PIC X(50)}. Stored verbatim.
     *
     * @param addressLine2 the second address line
     */
    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    /**
     * Returns the third address line — {@code CUST-ADDR-LINE-3 PIC X(50)}.
     *
     * @return the third address line, which is legitimately all spaces for a two-line
     *         address
     */
    public String getAddressLine3() {
        return addressLine3;
    }

    /**
     * Sets the third address line — {@code CUST-ADDR-LINE-3 PIC X(50)}. Stored verbatim;
     * a blank value is valid source data.
     *
     * @param addressLine3 the third address line
     */
    public void setAddressLine3(String addressLine3) {
        this.addressLine3 = addressLine3;
    }

    /**
     * Returns the state or province code — {@code CUST-ADDR-STATE-CD PIC X(02)}.
     *
     * @return the two-character state code, for example {@code NC}
     */
    public String getAddressStateCode() {
        return addressStateCode;
    }

    /**
     * Sets the state or province code — {@code CUST-ADDR-STATE-CD PIC X(02)}. Stored
     * verbatim; membership of the recognised state set is not checked here.
     *
     * @param addressStateCode the two-character state code
     */
    public void setAddressStateCode(String addressStateCode) {
        this.addressStateCode = addressStateCode;
    }

    /**
     * Returns the country code — {@code CUST-ADDR-COUNTRY-CD PIC X(03)}.
     *
     * @return the three-character country code, for example {@code USA}
     */
    public String getAddressCountryCode() {
        return addressCountryCode;
    }

    /**
     * Sets the country code — {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. Stored verbatim.
     *
     * @param addressCountryCode the three-character country code
     */
    public void setAddressCountryCode(String addressCountryCode) {
        this.addressCountryCode = addressCountryCode;
    }

    /**
     * Returns the postal code — {@code CUST-ADDR-ZIP PIC X(10)}.
     *
     * @return the postal code as stored, space-padded to 10 characters
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * Sets the postal code — {@code CUST-ADDR-ZIP PIC X(10)}. Stored verbatim; the
     * ten-character width accommodates the extended plus-four form.
     *
     * @param addressZip the postal code
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    /**
     * Returns the primary telephone number — {@code CUST-PHONE-NUM-1 PIC X(15)}.
     *
     * @return the formatted telephone number as stored, for example
     *         {@code (908)119-8310} with trailing spaces
     */
    public String getPhoneNumber1() {
        return phoneNumber1;
    }

    /**
     * Sets the primary telephone number — {@code CUST-PHONE-NUM-1 PIC X(15)}. Stored
     * verbatim, punctuation and padding included; no format check is applied here.
     *
     * @param phoneNumber1 the formatted telephone number
     */
    public void setPhoneNumber1(String phoneNumber1) {
        this.phoneNumber1 = phoneNumber1;
    }

    /**
     * Returns the secondary telephone number — {@code CUST-PHONE-NUM-2 PIC X(15)}.
     *
     * @return the formatted telephone number as stored
     */
    public String getPhoneNumber2() {
        return phoneNumber2;
    }

    /**
     * Sets the secondary telephone number — {@code CUST-PHONE-NUM-2 PIC X(15)}. Stored
     * verbatim; no format check is applied here.
     *
     * @param phoneNumber2 the formatted telephone number
     */
    public void setPhoneNumber2(String phoneNumber2) {
        this.phoneNumber2 = phoneNumber2;
    }

    /**
     * Returns the social security number — {@code CUST-SSN PIC 9(09)}.
     *
     * <p>
     * Text rather than a number so that significant leading zeros survive: 6 of the 50
     * fixture rows begin with {@code 0}. Callers must not convert the result to a numeric
     * type, and must not place it in a log message; the class documentation explains why.
     * </p>
     *
     * @return the nine-character social security number, leading zeros intact
     */
    public String getSsn() {
        return ssn;
    }

    /**
     * Sets the social security number — {@code CUST-SSN PIC 9(09)}. Stored verbatim as
     * nine characters; do not strip leading zeros and do not pass a numeric conversion.
     *
     * @param ssn the nine-character social security number
     */
    public void setSsn(String ssn) {
        this.ssn = ssn;
    }

    /**
     * Returns the government-issued identifier — {@code CUST-GOVT-ISSUED-ID PIC X(20)}.
     *
     * @return the identifier as stored, zero-padded to 20 characters in the fixture data
     */
    public String getGovernmentIssuedId() {
        return governmentIssuedId;
    }

    /**
     * Sets the government-issued identifier — {@code CUST-GOVT-ISSUED-ID PIC X(20)}.
     * Stored verbatim; no case folding is applied here.
     *
     * @param governmentIssuedId the identifier
     */
    public void setGovernmentIssuedId(String governmentIssuedId) {
        this.governmentIssuedId = governmentIssuedId;
    }

    /**
     * Returns the date of birth — {@code CUST-DOB-YYYY-MM-DD PIC X(10)}.
     *
     * <p>
     * Ten characters in dash-separated {@code yyyy-MM-dd} form, for example
     * {@code 1961-06-08}. Note that the account-update snapshot uses the compact
     * eight-character form, so the two must be compared component-wise rather than
     * whole; the field's own documentation gives the offsets and the source citation.
     * </p>
     *
     * @return the ten-character dash-separated date of birth
     */
    public String getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * Sets the date of birth — {@code CUST-DOB-YYYY-MM-DD PIC X(10)}. Stored verbatim in
     * the ten-character dash-separated form; the separators must not be stripped and the
     * value must not be reformatted to the eight-character snapshot form. Calendar
     * validity is the date validation service's responsibility, not this setter's.
     *
     * @param dateOfBirth the ten-character dash-separated date of birth
     */
    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    /**
     * Returns the electronic-funds-transfer account identifier —
     * {@code CUST-EFT-ACCOUNT-ID PIC X(10)}.
     *
     * @return the identifier as stored, zero-padded to 10 characters in the fixture data
     */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /**
     * Sets the electronic-funds-transfer account identifier —
     * {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. Stored verbatim; leading zeros are
     * significant.
     *
     * @param eftAccountId the identifier
     */
    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    /**
     * Returns the primary-card-holder indicator —
     * {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}.
     *
     * @return the single stored character, {@code Y} in the first fixture row
     */
    public String getPrimaryCardHolderIndicator() {
        return primaryCardHolderIndicator;
    }

    /**
     * Sets the primary-card-holder indicator —
     * {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. Stored verbatim as a single character
     * and not coerced to a boolean, so that a value other than {@code Y} or {@code N} is
     * preserved for byte-exact re-emission.
     *
     * @param primaryCardHolderIndicator the single-character indicator
     */
    public void setPrimaryCardHolderIndicator(String primaryCardHolderIndicator) {
        this.primaryCardHolderIndicator = primaryCardHolderIndicator;
    }

    /**
     * Returns the FICO credit score — {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
     *
     * <p>
     * Text rather than a number so that significant leading zeros survive: 7 of the 50
     * fixture rows begin with {@code 0}. Values outside any plausible credit-score band,
     * such as the fixture's {@code 001}, are returned unchanged because parity is the
     * contract.
     * </p>
     *
     * @return the three-character credit score, leading zeros intact
     */
    public String getFicoCreditScore() {
        return ficoCreditScore;
    }

    /**
     * Sets the FICO credit score — {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}. Stored
     * verbatim as three characters, with no range check and no numeric conversion.
     *
     * @param ficoCreditScore the three-character credit score
     */
    public void setFicoCreditScore(String ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * @return the current version, or null before the entity has been persisted
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter.
     *
     * <p>
     * Normally left to the persistence provider, which assigns the initial value on
     * persist and increments it on each update. Set it explicitly only when
     * reconstituting a detached instance whose version is genuinely known — for example
     * when rebuilding an entity from a request payload that carried it. Fabricating a
     * version defeats the concurrency guard.
     * </p>
     *
     * @param version the version counter
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares two customers by identifier alone — {@code CUST-ID}, the nine-byte VSAM
     * key reported at {@code app/catlg/LISTCAT.txt:L632} and the table's primary key.
     *
     * <p>
     * Identity rather than value equality is the right contract here, and the choice is
     * deliberate on three counts. The identifier is a natural key taken from the source
     * record and assigned before the entity is persisted, so it never changes and equality
     * stays consistent across the transient, managed, detached and removed states. The
     * eighteen data fields are all mutable, so folding them in would let an entity's
     * equality change while it sits in a hash-based collection. And the source itself
     * treats the key as identity: its reads are keyed lookups on {@code CUST-ID}.
     * </p>
     *
     * <p>
     * Two instances that both have a null identifier are equal only if they are the same
     * object. Without that guard every unsaved instance would collide with every other,
     * which would quietly corrupt any set or map they were placed in before persisting.
     * </p>
     *
     * <p>
     * Pattern matching is used in preference to a {@code getClass()} comparison so that a
     * lazily-initialised provider proxy, which is a generated subclass, still compares
     * equal to the instance it stands for. No case folding is applied — see the class
     * documentation for why that asymmetry belongs to the service layer.
     * </p>
     *
     * @param other the object to compare with, possibly null
     * @return true if {@code other} is a customer with the same non-null identifier, or
     *         is this very object
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Customer that)) {
            return false;
        }
        return customerId != null && customerId.equals(that.customerId);
    }

    /**
     * Returns a hash code derived from the identifier alone, consistent with
     * {@link #equals(Object)}.
     *
     * <p>
     * Safe to cache in a hash-based collection because the identifier is a natural key
     * assigned before the entity is persisted and never changed afterwards, so the hash
     * cannot shift underneath the collection. A transient instance with a null identifier
     * hashes to zero, which is correct but means such instances all land in one bucket —
     * an accepted and negligible cost, since entities are keyed long before they are
     * collected in bulk.
     * </p>
     *
     * @return the identifier's hash code, or zero when the identifier is null
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(customerId);
    }

    /**
     * Returns a diagnostic string containing <strong>only</strong> the customer
     * identifier and the optimistic-locking version.
     *
     * <p>
     * <strong>This restriction is a security control, not a formatting preference, and it
     * must not be widened.</strong> Thirteen of the eighteen mapped fields are personal
     * data: {@code firstName}, {@code middleName}, {@code lastName}, {@code addressLine1},
     * {@code addressLine2}, {@code addressLine3}, {@code addressZip},
     * {@code phoneNumber1}, {@code phoneNumber2}, {@code ssn},
     * {@code governmentIssuedId}, {@code dateOfBirth} and {@code eftAccountId}. None of
     * them appears below, and none of them may be added.
     * </p>
     *
     * <p>
     * The reason is that {@code toString} is invoked implicitly and in places a developer
     * is not thinking about it — string concatenation, collection and map printing, most
     * logging frameworks' argument rendering, exception messages built from an entity, and
     * debugger and profiler output. A permissive implementation therefore does not leak
     * occasionally; it leaks the entire customer master file into the log stream the first
     * time anything prints an entity. The logging configuration masks social security
     * numbers, credentials and hashes, but that is the backstop. Not emitting the data is
     * the primary defence, and it is the only one that also covers the address, the
     * telephone numbers, the date of birth and the funds-transfer identifier.
     * </p>
     *
     * <p>
     * For the same reason this class offers no {@code toDebugString}, no
     * {@code toFullString} and no verbose-mode flag. An escape hatch would be used, and
     * would reintroduce exactly the exposure this method exists to prevent. Code that
     * genuinely needs a field calls its getter, at which point the decision to disclose is
     * explicit, local and reviewable.
     * </p>
     *
     * <p>
     * The identifier and the version are safe to disclose: the identifier is a
     * non-sensitive surrogate that appears in the request path already, and the version is
     * an internal counter. Together they are what a concurrency or persistence
     * investigation actually needs.
     * </p>
     *
     * @return a string of the form {@code Customer[customerId=1, version=0]}, carrying no
     *         personal data
     */
    @Override
    public String toString() {
        return "Customer[customerId=" + customerId + ", version=" + version + "]";
    }
}
