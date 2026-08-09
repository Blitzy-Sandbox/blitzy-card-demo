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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

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
 * no business logic, emits no log output, opens no connection and calls no collaborator.
 * Every behavioural rule that reads or compares these fields — change detection, case
 * folding, date parsing, area-code lookup — lives in the service layer, deliberately and
 * for the reasons set out under "Behaviour that deliberately lives elsewhere" below.
 * </p>
 *
 * <p>
 * The one thing it does enforce is its own shape. Construction and every mutator refuse a
 * value the 500-byte record layout cannot have produced: a {@code null}, a character field
 * wider than its picture clause, or an identifier outside the nine unsigned digits
 * {@code CUST-ID PIC 9(09)} declares. Failure is reported with
 * {@link IllegalArgumentException} naming the offending property and its COBOL field. That
 * is a structural check rather than a business rule — no blank is rejected, no date is
 * parsed, no code is looked up and no credit score is ranged — so nothing the legacy
 * system accepts becomes unloadable.
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
 *   </ol>
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
 * amount and is therefore text; see the numeric-declaration note below.
 * </p>
 *
 * <h2>Mapping decisions that must not be undone</h2>
 * <dl>
 *   <dt><strong>A {@code String} attribute maps to JDBC {@code VARCHAR} by
 *       default, and schema validation rejects that against a {@code CHAR}
 *       column</strong></dt>
 *   <dd>This is a startup concern rather than a compile-time one: unit assertions and
 *       data round-trips all pass while application startup cannot succeed at all. The
 *       provider derives the expected JDBC type from the Java type, so a {@code String}
 *       resolves to {@code VARCHAR}, and {@code columnDefinition} steers only the
 *       generated DDL and not that expectation. PostgreSQL reports a {@code CHAR(n)}
 *       column as {@code bpchar}, which is {@code Types.CHAR}, so validation fails on the
 *       first text column with "wrong column type encountered in column
 *       [cust_addr_country_cd] in table [customer]; found [bpchar (Types#CHAR)], but
 *       expecting [char(3) (Types#VARCHAR)]". Because {@code ddl-auto} is
 *       {@code validate} in every profile, that aborts every boot. The fix is to declare
 *       {@code @JdbcTypeCode(SqlTypes.CHAR)} on all seventeen text columns alongside
 *       {@code columnDefinition}. Widening the columns to {@code VARCHAR} would also
 *       silence it and is rejected, because that destroys the blank padding on
 *       which fixed-width re-emission depends. A unit assertion pins the annotation
 *       on all seventeen text columns, and its absence on both numeric columns.</dd>
 *   <dt><strong>This record is the personally-identifiable-information
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
 *   <dt><strong>{@code dateOfBirth} is ten-character dash-separated text, and
 *       the update-request snapshot is a different width</strong></dt>
 *   <dd>The live record is {@code PIC X(10)}: every row of
 *       {@code app/data/ASCII/custdata.txt} holds a dash-separated date at bytes 309-318,
 *       and a shape census across all 50 rows finds that form and nothing
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
 *   <dt><strong>Two {@code PIC 9(n)} fields are mapped as text, while a third
 *       stays numeric</strong></dt>
 *   <dd>{@code CUST-SSN} and {@code CUST-FICO-CREDIT-SCORE} are declared numeric in the
 *       copybook but carry significant leading zeros in the fixture, so they are
 *       {@code String}. {@code CUST-ID} is also declared numeric and does stay
 *       numeric. The asymmetry is deliberate; both halves are justified on the fields
 *       themselves.</dd>
 *   </dl>
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
 *   </ul>
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
 *       requires it to load as-is. The nullability and width checks the constructor and
 *       setters do apply are not bean validation and are not a substitute for it: they run
 *       unconditionally, without a {@code Validator}, and constrain only what the record
 *       layout itself fixes.</li>
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
 *   ./mvnw -B clean compile     # compiles under -Xlint:all -Werror, release 25
 *   ./mvnw -B clean test        # unit suite, including this entity's mapping assertions
 *   ./mvnw -B clean verify      # adds JaCoCo (80% line floor) and the dependency scan
 * </pre>
 * <p>
 * Unit coverage belongs in {@code src/test/java/com/cardemo/unit/model} and is to
 * assert the 500-byte width arithmetic, the nine-byte key, that {@code ssn},
 * {@code ficoCreditScore} and {@code dateOfBirth} are {@code String}, and that
 * {@code toString} discloses none of the thirteen personal fields.
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
 *   </ul>
 *
 * <h2>The schema this mapping requires</h2>
 *
 * <p>The mapping is reconciled against real DDL rather than asserted.
 * {@code src/main/resources/db/migration/V1__create_schema.sql} declares 11 tables, 10 named
 * foreign keys, 5 CHECK constraints and 4 {@code version} columns, and among them
 * {@code CREATE TABLE customer} with 19 columns whose names are identical, as a
 * set, to the 19 {@code @Column(name = ...)} declarations below.
 * Because {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, any
 * divergence between that migration and this mapping fails application-context startup
 * outright rather than going unnoticed.
 * </p>
 * What that migration declares for this table, and what this mapping asserts, is table
 * {@code customer} with
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
 *
 * <p>
 * Two scope facts constrain that migration and are recorded here so this entity's
 * expectations are unambiguous. {@code V1} creates exactly eleven tables, with ten
 * foreign keys and five check constraints across them. Spring Batch's {@code BATCH_*}
 * metadata tables come from the framework's own schema script — never from a fourth
 * migration, and never as extra tables inside {@code V1}.
 * </p>
 * <p>
 * <b>JSON serialisation barrier.</b> This class is structurally unserialisable by Jackson, and it
 * carries the widest personal data set of any entity in this package, so the barrier matters here
 * as much as it does on {@link Card}. {@link JsonIgnoreType} removes any property whose declared
 * type is this class from an enclosing object's JSON, and {@link JsonAutoDetect} with every
 * visibility set to {@code NONE} switches off bean introspection entirely, so no getter, no
 * setter, no field and no creator is discoverable. An entity is a bean with public accessors, so
 * without the barrier the default behaviour of returning this type from a controller, or holding a
 * field of it on a response object, is to publish the social security number, the date of birth,
 * the full name, the three address lines, both telephone numbers, the electronic funds account
 * identifier and the credit score in one response - a personal data breach, not an over-broad
 * payload. With the barrier in place Jackson finds no properties and its default
 * {@code FAIL_ON_EMPTY_BEANS} setting turns that mistake into a loud failure at the first request
 * rather than a silent disclosure. Nothing legitimate is lost: outbound representations are built
 * by {@code com.cardemo.model.dto.AccountDto}, inbound JSON targets
 * {@code com.cardemo.model.dto.AccountUpdateRequest}, and persistence is unaffected because
 * Hibernate reads and writes the annotated fields reflectively and never consults Jackson
 * visibility. The social security number masking rule in {@code logback-spring.xml} remains a
 * backstop for accidents this barrier cannot see, never the primary defence.
 * </p>
 */
@Entity
@Table(name = "customer")
@JsonIgnoreType
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class Customer {

    /**
     * Inclusive lower bound of {@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5}.
     *
     * <p>
     * The picture clause carries no {@code S}, so the field is unsigned and zero is its
     * floor. Zero is accepted rather than treated as a sentinel, because nine display
     * digits can hold it and nothing in the corpus reserves it.
     * </p>
     */
    private static final long MIN_CUSTOMER_ID = 0L;

    /**
     * Inclusive upper bound of {@code CUST-ID PIC 9(09)}: the largest value nine unsigned
     * display digits can hold, which is also the nine-byte VSAM key width the cluster
     * allocates.
     */
    private static final long MAX_CUSTOMER_ID = 999_999_999L;

    /** Width of {@code CUST-FIRST-NAME PIC X(25)} at {@code app/cpy/CVCUS01Y.cpy:L6}. */
    private static final int FIRST_NAME_WIDTH = 25;

    /** Width of {@code CUST-MIDDLE-NAME PIC X(25)} at {@code app/cpy/CVCUS01Y.cpy:L7}. */
    private static final int MIDDLE_NAME_WIDTH = 25;

    /** Width of {@code CUST-LAST-NAME PIC X(25)} at {@code app/cpy/CVCUS01Y.cpy:L8}. */
    private static final int LAST_NAME_WIDTH = 25;

    /** Width of {@code CUST-ADDR-LINE-1 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L9}. */
    private static final int ADDRESS_LINE_1_WIDTH = 50;

    /** Width of {@code CUST-ADDR-LINE-2 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L10}. */
    private static final int ADDRESS_LINE_2_WIDTH = 50;

    /** Width of {@code CUST-ADDR-LINE-3 PIC X(50)} at {@code app/cpy/CVCUS01Y.cpy:L11}. */
    private static final int ADDRESS_LINE_3_WIDTH = 50;

    /** Width of {@code CUST-ADDR-STATE-CD PIC X(02)} at {@code app/cpy/CVCUS01Y.cpy:L12}. */
    private static final int ADDRESS_STATE_CODE_WIDTH = 2;

    /** Width of {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at {@code app/cpy/CVCUS01Y.cpy:L13}. */
    private static final int ADDRESS_COUNTRY_CODE_WIDTH = 3;

    /** Width of {@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L14}. */
    private static final int ADDRESS_ZIP_WIDTH = 10;

    /** Width of {@code CUST-PHONE-NUM-1 PIC X(15)} at {@code app/cpy/CVCUS01Y.cpy:L15}. */
    private static final int PHONE_NUMBER_1_WIDTH = 15;

    /** Width of {@code CUST-PHONE-NUM-2 PIC X(15)} at {@code app/cpy/CVCUS01Y.cpy:L16}. */
    private static final int PHONE_NUMBER_2_WIDTH = 15;

    /**
     * Width of {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L17}, carried as
     * nine characters rather than nine digits so that leading zeros survive.
     */
    private static final int SSN_WIDTH = 9;

    /** Width of {@code CUST-GOVT-ISSUED-ID PIC X(20)} at {@code app/cpy/CVCUS01Y.cpy:L18}. */
    private static final int GOVERNMENT_ISSUED_ID_WIDTH = 20;

    /** Width of {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L19}. */
    private static final int DATE_OF_BIRTH_WIDTH = 10;

    /** Width of {@code CUST-EFT-ACCOUNT-ID PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L20}. */
    private static final int EFT_ACCOUNT_ID_WIDTH = 10;

    /**
     * Width of {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} at
     * {@code app/cpy/CVCUS01Y.cpy:L21}.
     */
    private static final int PRIMARY_CARD_HOLDER_INDICATOR_WIDTH = 1;

    /**
     * Width of {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22},
     * carried as three characters rather than a number.
     *
     * <p>
     * Only the width is constrained. <b>No credit-score range is imposed</b>, because
     * {@code app/data/ASCII/custdata.txt} carries twenty-one of its fifty rows below the
     * conventional 300 floor, so a range check would reject the system of record's own
     * seed data. The exclusion register in
     * {@code src/main/resources/db/migration/V1__create_schema.sql} records the same
     * decision for the corresponding column.
     * </p>
     */
    private static final int FICO_CREDIT_SCORE_WIDTH = 3;

    /**
     * Customer identifier — {@code CUST-ID PIC 9(09)}, {@code app/cpy/CVCUS01Y.cpy:L5}.
     * Bytes 1-9 of the record, and the whole of the nine-byte VSAM key reported at
     * {@code app/catlg/LISTCAT.txt:L632} with {@code RKP 0} placing it at the record
     * start.
     *
     * <p>
     * Mapped numerically as {@code Long} over {@code NUMERIC(9)}, and deliberately so,
     * Mapped numerically as {@code Long} over {@code NUMERIC(9)}, and deliberately so,
     * even though the fixture stores it zero-padded to nine digits.
     * This is the opposite of the decision taken for {@code ssn} and
     * {@code ficoCreditScore}, and the asymmetry is intentional rather than an
     * oversight to "fix": for an identifier the zero padding is purely a
     * fixed-width <em>emission</em> concern, discharged by the batch writers when they
     * rebuild the 500-byte image, and the composite-key types in the sibling
     * {@code key} package already fix account identifiers as {@code Long}. Making this
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
     * Given name — {@code CUST-FIRST-NAME PIC X(25)}, {@code app/cpy/CVCUS01Y.cpy:L6}. Bytes 10-34. Personal
     * data: excluded from {@code toString}. Compared through {@code FUNCTION UPPER-CASE} by the service layer,
     * never here.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_first_name", nullable = false, length = FIRST_NAME_WIDTH, columnDefinition = "CHAR(25)")
    private String firstName;

    /**
     * Middle name — {@code CUST-MIDDLE-NAME PIC X(25)}, {@code app/cpy/CVCUS01Y.cpy:L7}. Bytes 35-59.
     * Legitimately all spaces when a customer has no middle name, which is why the column is {@code NOT NULL}
     * but carries no blank-rejecting constraint. Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_middle_name", nullable = false, length = MIDDLE_NAME_WIDTH, columnDefinition = "CHAR(25)")
    private String middleName;

    /**
     * Family name — {@code CUST-LAST-NAME PIC X(25)}, {@code app/cpy/CVCUS01Y.cpy:L8}. Bytes 60-84. Personal
     * data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_last_name", nullable = false, length = LAST_NAME_WIDTH, columnDefinition = "CHAR(25)")
    private String lastName;

    /**
     * First address line — {@code CUST-ADDR-LINE-1 PIC X(50)}, {@code app/cpy/CVCUS01Y.cpy:L9}. Bytes 85-134.
     * Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_line_1", nullable = false, length = ADDRESS_LINE_1_WIDTH, columnDefinition = "CHAR(50)")
    private String addressLine1;

    /**
     * Second address line — {@code CUST-ADDR-LINE-2 PIC X(50)}, {@code app/cpy/CVCUS01Y.cpy:L10}. Bytes
     * 135-184. Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_line_2", nullable = false, length = ADDRESS_LINE_2_WIDTH, columnDefinition = "CHAR(50)")
    private String addressLine2;

    /**
     * Third address line — {@code CUST-ADDR-LINE-3 PIC X(50)}, {@code app/cpy/CVCUS01Y.cpy:L11}. Bytes 185-234.
     * Legitimately all spaces when the address needs only two lines. Personal data: excluded from
     * {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_line_3", nullable = false, length = ADDRESS_LINE_3_WIDTH, columnDefinition = "CHAR(50)")
    private String addressLine3;

    /**
     * State or province code — {@code CUST-ADDR-STATE-CD PIC X(02)}, {@code app/cpy/CVCUS01Y.cpy:L12}. Bytes
     * 235-236. Membership of the recognised state set is checked by the validation lookup service against its
     * classpath resource, not by this entity.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_state_cd", nullable = false, length = ADDRESS_STATE_CODE_WIDTH,
            columnDefinition = "CHAR(2)")
    private String addressStateCode;

    /**
     * Country code — {@code CUST-ADDR-COUNTRY-CD PIC X(03)}, {@code app/cpy/CVCUS01Y.cpy:L13}. Bytes 237-239.
     * Three characters, so it is the alpha-3 form rather than alpha-2.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_country_cd", nullable = false, length = ADDRESS_COUNTRY_CODE_WIDTH,
            columnDefinition = "CHAR(3)")
    private String addressCountryCode;

    /**
     * Postal code — {@code CUST-ADDR-ZIP PIC X(10)}, {@code app/cpy/CVCUS01Y.cpy:L14}. Bytes 240-249. Text with
     * trailing space padding rather than a number, the ten-character width accommodating the extended
     * plus-four form. Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_addr_zip", nullable = false, length = ADDRESS_ZIP_WIDTH, columnDefinition = "CHAR(10)")
    private String addressZip;

    /**
     * Primary telephone number — {@code CUST-PHONE-NUM-1 PIC X(15)}, {@code app/cpy/CVCUS01Y.cpy:L15}. Bytes
     * 250-264, held as text because the stored form carries punctuation. Personal data: excluded from
     * {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_phone_num_1", nullable = false, length = PHONE_NUMBER_1_WIDTH, columnDefinition = "CHAR(15)")
    private String phoneNumber1;

    /**
     * Secondary telephone number — {@code CUST-PHONE-NUM-2 PIC X(15)}, {@code app/cpy/CVCUS01Y.cpy:L16}. Bytes
     * 265-279. Stored as text because the seed rows carry punctuation in the form
     * {@code (NNN)NNN-NNNN} within the fifteen bytes. Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_phone_num_2", nullable = false, length = PHONE_NUMBER_2_WIDTH, columnDefinition = "CHAR(15)")
    private String phoneNumber2;

    /**
     * National identifier — {@code CUST-SSN PIC 9(09)}, {@code app/cpy/CVCUS01Y.cpy:L17}. Bytes 280-288, held
     * as text so that leading zeros survive the fixed-width round trip. Personal data: excluded from
     * {@code toString} and never logged.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_ssn", nullable = false, length = SSN_WIDTH, columnDefinition = "CHAR(9)")
    private String ssn;

    /**
     * Government-issued identifier — {@code CUST-GOVT-ISSUED-ID PIC X(20)}, {@code app/cpy/CVCUS01Y.cpy:L18}.
     * Bytes 289-308. Zero-padded text in the seed rows, which is why the picture clause is {@code X(20)} and
     * the mapping is character rather than numeric. Personal data: excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_govt_issued_id", nullable = false, length = GOVERNMENT_ISSUED_ID_WIDTH,
            columnDefinition = "CHAR(20)")
    private String governmentIssuedId;

    /**
     * Date of birth — {@code CUST-DOB-YYYY-MM-DD PIC X(10)}, {@code app/cpy/CVCUS01Y.cpy:L19}. Bytes 309-318,
     * stored dash-separated as {@code YYYY-MM-DD}. The update path compares it component by component against
     * a snapshot that holds the same date without separators, so the two sides use different offsets; that
     * asymmetry is the service layer's contract and must not be normalised away here. Personal data: excluded
     * from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_dob_yyyy_mm_dd", nullable = false, length = DATE_OF_BIRTH_WIDTH, columnDefinition = "CHAR(10)")
    private String dateOfBirth;

    /**
     * Electronic-funds-transfer account identifier — {@code CUST-EFT-ACCOUNT-ID PIC X(10)},
     * {@code app/cpy/CVCUS01Y.cpy:L20}. Bytes 319-328, held as zero-padded text. Personal financial data:
     * excluded from {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_eft_account_id", nullable = false, length = EFT_ACCOUNT_ID_WIDTH,
            columnDefinition = "CHAR(10)")
    private String eftAccountId;

    /**
     * Primary-card-holder indicator — {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)},
     * {@code app/cpy/CVCUS01Y.cpy:L21}. Byte 329. A single character rather than a boolean, because the source
     * field is one byte of character data and the batch writers must re-emit exactly whatever byte was stored,
     * including any value other than {@code Y} or {@code N}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_pri_card_holder_ind", nullable = false, length = PRIMARY_CARD_HOLDER_INDICATOR_WIDTH,
            columnDefinition = "CHAR(1)")
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
     * make 50-row fixture loads fail on data the legacy system accepts. Only the
     * three-character width the picture clause declares is enforced, which admits every
     * value the 500-byte record can carry, {@code 001} included.
     * </p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cust_fico_credit_score", nullable = false, length = FICO_CREDIT_SCORE_WIDTH,
            columnDefinition = "CHAR(3)")
    private String ficoCreditScore;

    /**
     * Optimistic-locking version counter. Has <strong>no counterpart in the source record</strong> — the
     * 500-byte layout is fully accounted for by the eighteen data fields plus {@code FILLER X(168)} — and is
     * introduced because the relational target has no equivalent of the legacy read-for-update lock.
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "BIGINT")
    private Long version;

    /**
     * No-argument constructor required by the JPA specification so that the persistence provider can
     * instantiate the entity during hydration.
     */
    protected Customer() {
        // Intentionally empty: the persistence provider populates every field after construction.
    }

    /**
     * Creates a fully populated customer from the complete {@code CUST-*} field contract of
     * {@code app/cpy/CVCUS01Y.cpy:L5-L22}.
     *
     * @param customerId {@code CUST-ID PIC 9(09)}.
     * @param firstName {@code CUST-FIRST-NAME PIC X(25)}
     * @param middleName {@code CUST-MIDDLE-NAME PIC X(25)}.
     * @param lastName {@code CUST-LAST-NAME PIC X(25)}
     * @param addressLine1 {@code CUST-ADDR-LINE-1 PIC X(50)}
     * @param addressLine2 {@code CUST-ADDR-LINE-2 PIC X(50)}
     * @param addressLine3 {@code CUST-ADDR-LINE-3 PIC X(50)}.
     * @param addressStateCode {@code CUST-ADDR-STATE-CD PIC X(02)}
     * @param addressCountryCode {@code CUST-ADDR-COUNTRY-CD PIC X(03)}
     * @param addressZip {@code CUST-ADDR-ZIP PIC X(10)}
     * @param phoneNumber1 {@code CUST-PHONE-NUM-1 PIC X(15)}.
     * @param phoneNumber2 {@code CUST-PHONE-NUM-2 PIC X(15)}.
     * @param ssn {@code CUST-SSN PIC 9(09)}.
     * @param governmentIssuedId {@code CUST-GOVT-ISSUED-ID PIC X(20)}
     * @param dateOfBirth {@code CUST-DOB-YYYY-MM-DD PIC X(10)}.
     * @param eftAccountId {@code CUST-EFT-ACCOUNT-ID PIC X(10)}
     * @param primaryCardHolderIndicator {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}
     * @param ficoCreditScore {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
     * @throws IllegalArgumentException if {@code customerId} is null, since it is the nine-byte VSAM key and
     * the table's primary key, and a null there cannot be a valid record
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
        this.customerId = requireCustomerId(customerId);
        this.firstName = requireWidth(firstName, "firstName", "CUST-FIRST-NAME PIC X(25)", FIRST_NAME_WIDTH);
        this.middleName = requireWidth(middleName, "middleName", "CUST-MIDDLE-NAME PIC X(25)", MIDDLE_NAME_WIDTH);
        this.lastName = requireWidth(lastName, "lastName", "CUST-LAST-NAME PIC X(25)", LAST_NAME_WIDTH);
        this.addressLine1 = requireWidth(addressLine1, "addressLine1",
                "CUST-ADDR-LINE-1 PIC X(50)", ADDRESS_LINE_1_WIDTH);
        this.addressLine2 = requireWidth(addressLine2, "addressLine2",
                "CUST-ADDR-LINE-2 PIC X(50)", ADDRESS_LINE_2_WIDTH);
        this.addressLine3 = requireWidth(addressLine3, "addressLine3",
                "CUST-ADDR-LINE-3 PIC X(50)", ADDRESS_LINE_3_WIDTH);
        this.addressStateCode = requireWidth(addressStateCode, "addressStateCode",
                "CUST-ADDR-STATE-CD PIC X(02)", ADDRESS_STATE_CODE_WIDTH);
        this.addressCountryCode = requireWidth(addressCountryCode, "addressCountryCode",
                "CUST-ADDR-COUNTRY-CD PIC X(03)", ADDRESS_COUNTRY_CODE_WIDTH);
        this.addressZip = requireWidth(addressZip, "addressZip", "CUST-ADDR-ZIP PIC X(10)", ADDRESS_ZIP_WIDTH);
        this.phoneNumber1 = requireWidth(phoneNumber1, "phoneNumber1",
                "CUST-PHONE-NUM-1 PIC X(15)", PHONE_NUMBER_1_WIDTH);
        this.phoneNumber2 = requireWidth(phoneNumber2, "phoneNumber2",
                "CUST-PHONE-NUM-2 PIC X(15)", PHONE_NUMBER_2_WIDTH);
        this.ssn = requireWidth(ssn, "ssn", "CUST-SSN PIC 9(09)", SSN_WIDTH);
        this.governmentIssuedId = requireWidth(governmentIssuedId, "governmentIssuedId",
                "CUST-GOVT-ISSUED-ID PIC X(20)", GOVERNMENT_ISSUED_ID_WIDTH);
        this.dateOfBirth = requireWidth(dateOfBirth, "dateOfBirth",
                "CUST-DOB-YYYY-MM-DD PIC X(10)", DATE_OF_BIRTH_WIDTH);
        this.eftAccountId = requireWidth(eftAccountId, "eftAccountId",
                "CUST-EFT-ACCOUNT-ID PIC X(10)", EFT_ACCOUNT_ID_WIDTH);
        this.primaryCardHolderIndicator = requireWidth(primaryCardHolderIndicator, "primaryCardHolderIndicator",
                "CUST-PRI-CARD-HOLDER-IND PIC X(01)", PRIMARY_CARD_HOLDER_INDICATOR_WIDTH);
        this.ficoCreditScore = requireWidth(ficoCreditScore, "ficoCreditScore",
                "CUST-FICO-CREDIT-SCORE PIC 9(03)", FICO_CREDIT_SCORE_WIDTH);
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
     * @param customerId the nine-digit identifier.
     * @throws IllegalArgumentException if {@code customerId} is null
     */
    public void setCustomerId(Long customerId) {
        this.customerId = requireCustomerId(customerId);
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
     * Sets the given name — {@code CUST-FIRST-NAME PIC X(25)}. Stored verbatim: no trimming, no padding and no
     * case folding is applied.
     *
     * @param firstName the given name; must not be null and must be at
     *                  most twenty-five characters
     * @throws IllegalArgumentException if {@code firstName} is null or longer than twenty-five
     *                                  characters
     */
    public void setFirstName(String firstName) {
        this.firstName = requireWidth(firstName, "firstName", "CUST-FIRST-NAME PIC X(25)", FIRST_NAME_WIDTH);
    }

    /**
     * Returns the middle name — {@code CUST-MIDDLE-NAME PIC X(25)}.
     *
     * @return the middle name, which is legitimately all spaces when the customer has none
     */
    public String getMiddleName() {
        return middleName;
    }

    /**
     * Sets the middle name — {@code CUST-MIDDLE-NAME PIC X(25)}. Stored verbatim; a blank value is valid source
     * data and is accepted unchanged.
     *
     * @param middleName the middle name; must not be null and must be at
     *                   most twenty-five characters
     * @throws IllegalArgumentException if {@code middleName} is null or longer than twenty-five
     *                                  characters
     */
    public void setMiddleName(String middleName) {
        this.middleName = requireWidth(middleName, "middleName", "CUST-MIDDLE-NAME PIC X(25)", MIDDLE_NAME_WIDTH);
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
     * @param lastName the family name; must not be null and must be at
     *                 most twenty-five characters
     * @throws IllegalArgumentException if {@code lastName} is null or longer than twenty-five
     *                                  characters
     */
    public void setLastName(String lastName) {
        this.lastName = requireWidth(lastName, "lastName", "CUST-LAST-NAME PIC X(25)", LAST_NAME_WIDTH);
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
     * @param addressLine1 the first address line; must not be null and must be at
     *                     most fifty characters
     * @throws IllegalArgumentException if {@code addressLine1} is null or longer than fifty
     *                                  characters
     */
    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = requireWidth(addressLine1, "addressLine1",
                "CUST-ADDR-LINE-1 PIC X(50)", ADDRESS_LINE_1_WIDTH);
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
     * @param addressLine2 the second address line; must not be null and must be at
     *                     most fifty characters
     * @throws IllegalArgumentException if {@code addressLine2} is null or longer than fifty
     *                                  characters
     */
    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = requireWidth(addressLine2, "addressLine2",
                "CUST-ADDR-LINE-2 PIC X(50)", ADDRESS_LINE_2_WIDTH);
    }

    /**
     * Returns the third address line — {@code CUST-ADDR-LINE-3 PIC X(50)}.
     *
     * @return the third address line, which is legitimately all spaces for a two-line address
     */
    public String getAddressLine3() {
        return addressLine3;
    }

    /**
     * Sets the third address line — {@code CUST-ADDR-LINE-3 PIC X(50)}. Stored verbatim; a blank value is valid
     * source data.
     *
     * @param addressLine3 the third address line; must not be null and must be at
     *                     most fifty characters
     * @throws IllegalArgumentException if {@code addressLine3} is null or longer than fifty
     *                                  characters
     */
    public void setAddressLine3(String addressLine3) {
        this.addressLine3 = requireWidth(addressLine3, "addressLine3",
                "CUST-ADDR-LINE-3 PIC X(50)", ADDRESS_LINE_3_WIDTH);
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
     * Sets the state or province code — {@code CUST-ADDR-STATE-CD PIC X(02)}. Stored verbatim; membership of
     * the recognised state set is not checked here.
     *
     * @param addressStateCode the two-character state code; must not be null and must be at
     *                         most two characters
     * @throws IllegalArgumentException if {@code addressStateCode} is null or longer than two
     *                                  characters
     */
    public void setAddressStateCode(String addressStateCode) {
        this.addressStateCode = requireWidth(addressStateCode, "addressStateCode",
                "CUST-ADDR-STATE-CD PIC X(02)", ADDRESS_STATE_CODE_WIDTH);
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
     * @param addressCountryCode the three-character country code; must not be null and must be at
     *                           most three characters
     * @throws IllegalArgumentException if {@code addressCountryCode} is null or longer than three
     *                                  characters
     */
    public void setAddressCountryCode(String addressCountryCode) {
        this.addressCountryCode = requireWidth(addressCountryCode, "addressCountryCode",
                "CUST-ADDR-COUNTRY-CD PIC X(03)", ADDRESS_COUNTRY_CODE_WIDTH);
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
     * Sets the postal code — {@code CUST-ADDR-ZIP PIC X(10)}. Stored verbatim; the ten-character width
     * accommodates the extended plus-four form.
     *
     * @param addressZip the postal code; must not be null and must be at
     *                   most ten characters
     * @throws IllegalArgumentException if {@code addressZip} is null or longer than ten
     *                                  characters
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = requireWidth(addressZip, "addressZip", "CUST-ADDR-ZIP PIC X(10)", ADDRESS_ZIP_WIDTH);
    }

    /**
     * Returns the primary telephone number — {@code CUST-PHONE-NUM-1 PIC X(15)}.
     *
     * @return the formatted telephone number as stored, in the shape {@code (NNN)NNN-NNNN} with trailing spaces
     */
    public String getPhoneNumber1() {
        return phoneNumber1;
    }

    /**
     * Sets the primary telephone number — {@code CUST-PHONE-NUM-1 PIC X(15)}. Stored verbatim, punctuation and
     * padding included; no format check is applied here.
     *
     * @param phoneNumber1 the formatted telephone number; must not be null and must be at
     *                     most fifteen characters
     * @throws IllegalArgumentException if {@code phoneNumber1} is null or longer than fifteen
     *                                  characters
     */
    public void setPhoneNumber1(String phoneNumber1) {
        this.phoneNumber1 = requireWidth(phoneNumber1, "phoneNumber1",
                "CUST-PHONE-NUM-1 PIC X(15)", PHONE_NUMBER_1_WIDTH);
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
     * Sets the secondary telephone number — {@code CUST-PHONE-NUM-2 PIC X(15)}. Stored verbatim; no format
     * check is applied here.
     *
     * @param phoneNumber2 the formatted telephone number; must not be null and must be at
     *                     most fifteen characters
     * @throws IllegalArgumentException if {@code phoneNumber2} is null or longer than fifteen
     *                                  characters
     */
    public void setPhoneNumber2(String phoneNumber2) {
        this.phoneNumber2 = requireWidth(phoneNumber2, "phoneNumber2",
                "CUST-PHONE-NUM-2 PIC X(15)", PHONE_NUMBER_2_WIDTH);
    }

    /**
     * Returns the social security number — {@code CUST-SSN PIC 9(09)}.
     *
     * @return the nine-character social security number, leading zeros intact
     */
    public String getSsn() {
        return ssn;
    }

    /**
     * Sets the social security number — {@code CUST-SSN PIC 9(09)}. Stored verbatim as nine characters; do not
     * strip leading zeros and do not pass a numeric conversion.
     *
     * @param ssn the nine-character social security number; must not be null and must be at
     *            most nine characters
     * @throws IllegalArgumentException if {@code ssn} is null or longer than nine
     *                                  characters
     */
    public void setSsn(String ssn) {
        this.ssn = requireWidth(ssn, "ssn", "CUST-SSN PIC 9(09)", SSN_WIDTH);
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
     * Sets the government-issued identifier — {@code CUST-GOVT-ISSUED-ID PIC X(20)}. Stored verbatim; no case
     * folding is applied here.
     *
     * @param governmentIssuedId the identifier; must not be null and must be at
     *                           most twenty characters
     * @throws IllegalArgumentException if {@code governmentIssuedId} is null or longer than twenty
     *                                  characters
     */
    public void setGovernmentIssuedId(String governmentIssuedId) {
        this.governmentIssuedId = requireWidth(governmentIssuedId, "governmentIssuedId",
                "CUST-GOVT-ISSUED-ID PIC X(20)", GOVERNMENT_ISSUED_ID_WIDTH);
    }

    /**
     * Returns the date of birth — {@code CUST-DOB-YYYY-MM-DD PIC X(10)}.
     *
     * @return the ten-character dash-separated date of birth
     */
    public String getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * Sets the date of birth — {@code CUST-DOB-YYYY-MM-DD PIC X(10)}. Stored verbatim in the ten-character
     * dash-separated form; the separators must not be stripped and the value must not be reformatted to the
     * eight-character snapshot form. Calendar validity is the date validation service's responsibility, not
     * this setter's.
     *
     * @param dateOfBirth the ten-character dash-separated date of birth; must not be null and must be at
     *                    most ten characters
     * @throws IllegalArgumentException if {@code dateOfBirth} is null or longer than ten
     *                                  characters
     */
    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = requireWidth(dateOfBirth, "dateOfBirth",
                "CUST-DOB-YYYY-MM-DD PIC X(10)", DATE_OF_BIRTH_WIDTH);
    }

    /**
     * Returns the electronic-funds-transfer account identifier — {@code CUST-EFT-ACCOUNT-ID PIC X(10)}.
     *
     * @return the identifier as stored, zero-padded to 10 characters in the fixture data
     */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /**
     * Sets the electronic-funds-transfer account identifier — {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. Stored
     * verbatim; leading zeros are significant.
     *
     * @param eftAccountId the identifier; must not be null and must be at
     *                     most ten characters
     * @throws IllegalArgumentException if {@code eftAccountId} is null or longer than ten
     *                                  characters
     */
    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = requireWidth(eftAccountId, "eftAccountId",
                "CUST-EFT-ACCOUNT-ID PIC X(10)", EFT_ACCOUNT_ID_WIDTH);
    }

    /**
     * Returns the primary-card-holder indicator — {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}.
     *
     * @return the single stored character, re-emitted verbatim by the fixed-width writers
     */
    public String getPrimaryCardHolderIndicator() {
        return primaryCardHolderIndicator;
    }

    /**
     * Sets the primary-card-holder indicator — {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. Stored verbatim as a
     * single character and not coerced to a boolean, so that a value other than {@code Y} or {@code N} is
     * preserved for byte-exact re-emission.
     *
     * @param primaryCardHolderIndicator the single-character indicator; must not be null and must be at
     *                                   most one characters
     * @throws IllegalArgumentException if {@code primaryCardHolderIndicator} is null or longer than one
     *                                  characters
     */
    public void setPrimaryCardHolderIndicator(String primaryCardHolderIndicator) {
        this.primaryCardHolderIndicator = requireWidth(primaryCardHolderIndicator, "primaryCardHolderIndicator",
                "CUST-PRI-CARD-HOLDER-IND PIC X(01)", PRIMARY_CARD_HOLDER_INDICATOR_WIDTH);
    }

    /**
     * Returns the FICO credit score — {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
     *
     * @return the three-character credit score, leading zeros intact
     */
    public String getFicoCreditScore() {
        return ficoCreditScore;
    }

    /**
     * Sets the FICO credit score — {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}. Stored verbatim as three
     * characters, with no range check and no numeric conversion.
     *
     * @param ficoCreditScore the three-character credit score; must not be null and must be at
     *                        most three characters
     * @throws IllegalArgumentException if {@code ficoCreditScore} is null or longer than three
     *                                  characters
     */
    public void setFicoCreditScore(String ficoCreditScore) {
        this.ficoCreditScore = requireWidth(ficoCreditScore, "ficoCreditScore",
                "CUST-FICO-CREDIT-SCORE PIC 9(03)", FICO_CREDIT_SCORE_WIDTH);
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
     * @param version the version counter
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares two customers by identifier alone — {@code CUST-ID}, the nine-byte VSAM key reported at
     * {@code app/catlg/LISTCAT.txt:L632} and the table's primary key.
     *
     * @param other the object to compare with, possibly null
     * @return true if {@code other} is a customer with the same non-null identifier, or is this very object
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
     * Returns a hash code derived from the identifier alone, consistent with {@link #equals(Object)}.
     *
     * @return the identifier's hash code, or zero when the identifier is null
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(customerId);
    }

    /**
     * Returns a diagnostic string containing <strong>only</strong> the customer identifier and the
     * optimistic-locking version.
     *
     * @return a string of the form {@code Customer[customerId=1, version=0]}, carrying no personal data
     */
    @Override
    public String toString() {
        return "Customer[customerId=" + customerId + ", version=" + version + "]";
    }

    /**
     * Validates a candidate character value against the width of the COBOL field it comes
     * from and returns it unchanged.
     *
     * <p>
     * Rejects {@code null}, because every character column of this table is
     * {@code NOT NULL} and a {@code PIC X(n)} field always holds its declared width, and
     * rejects any value longer than the picture clause declares, because a 500-byte record
     * cannot contain one and the {@code CHAR(n)} column would blank-pad or refuse it while
     * naming only a column. Everything the picture clause admits is accepted, blank values
     * included: {@code CUST-MIDDLE-NAME} and {@code CUST-ADDR-LINE-3} are legitimately all
     * spaces in {@code app/data/ASCII/custdata.txt}. Nothing is trimmed, padded or case
     * folded, because the fixed-width writers must be able to re-emit the original image
     * and the change-detection comparison depends on the stored representation.
     * </p>
     *
     * <p>
     * <strong>The failure message reports the received length and never the value.</strong>
     * This record carries a social security number, a government-issued identifier, a date
     * of birth, two telephone numbers and a full postal address, every one of which
     * {@link #toString()} already excludes; a validation message is exactly the kind of
     * string that reaches a log, so it must not reintroduce what {@code toString} was
     * careful to leave out.
     * </p>
     *
     * <p>
     * Declared {@code private static} so that the constructor can call it without invoking
     * an overridable method, which would publish a partially initialised instance. The JPA
     * specification forbids a final entity, so the hazard is real and
     * {@code -Xlint:all -Werror} reports it as {@code this-escape}.
     * </p>
     *
     * <p>The width is a count of Unicode code points rather than of {@code char} values, and the unit is
     * load-bearing. A {@code PIC X(n)} clause declares n character positions and the {@code CHAR(n)} column
     * it maps to pads to n characters, while a Java {@code String} measures itself in UTF-16 code units; the
     * two disagree for any supplementary-plane character. Counting code units let a value that had been
     * accepted, stored and padded fail when it was read back and offered here again. A code point count never
     * exceeds a code unit count, so this is the same bound the write path applies rather than a looser one.
     * Held as {@code DL-MS-05} in {@code DECISION_LOG.md}.
     *
     * @param value      the candidate value, possibly null
     * @param property   the Java property name, used in the failure message
     * @param cobolField the originating COBOL field name and picture clause
     * @param width      the declared width of that field in characters
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is null or longer than
     *                                  {@code width}
     */
    private static String requireWidth(String value, String property, String cobolField, int width) {
        if (value == null) {
            throw new IllegalArgumentException(property + " (" + cobolField
                    + ") must not be null: it maps to a NOT NULL CHAR(" + width
                    + ") column of table customer");
        }
        final int characterPositions = value.codePointCount(0, value.length());
        if (characterPositions > width) {
            throw new IllegalArgumentException(property + " (" + cobolField + ") must be at most "
                    + width + " characters but was " + characterPositions);
        }
        return value;
    }

    /**
     * Validates the primary key against {@code CUST-ID PIC 9(09)} at
     * {@code app/cpy/CVCUS01Y.cpy:L5} and returns it unchanged.
     *
     * <p>
     * Rejects {@code null}, because a row without its primary key cannot be persisted and
     * failing here names the offending property instead of surfacing an opaque constraint
     * violation from the driver much later, and rejects any value outside 0 through
     * 999999999 inclusive, which is what nine unsigned display digits can hold and what
     * the nine-byte VSAM key allocates. Zero is accepted and is not a sentinel. Declared
     * {@code private static} for the reason given on
     * {@link #requireWidth(String, String, String, int)}.
     * </p>
     *
     * @param value the candidate customer identifier, possibly null
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is null, negative, or greater than
     *                                  999999999
     */
    private static Long requireCustomerId(Long value) {
        if (value == null) {
            throw new IllegalArgumentException("customerId (CUST-ID PIC 9(09)) must not be null: it is "
                    + "the primary key of table customer, derived from app/cpy/CVCUS01Y.cpy:L5");
        }
        if (value.longValue() < MIN_CUSTOMER_ID || value.longValue() > MAX_CUSTOMER_ID) {
            throw new IllegalArgumentException("customerId (CUST-ID PIC 9(09)) must be between "
                    + MIN_CUSTOMER_ID + " and " + MAX_CUSTOMER_ID + " inclusive but was " + value);
        }
        return value;
    }
}
