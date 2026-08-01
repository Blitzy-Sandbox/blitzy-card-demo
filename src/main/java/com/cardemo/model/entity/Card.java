/*
 * ******************************************************************
 * Program     : Card.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Persistent card record. Replaces the VSAM KSDS cluster
 *               AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS and the CICS file
 *               CARDDAT that fronted that cluster online.
 * Source      : app/cpy/CVACT02Y.cpy (150 B, key 16) @ 7756d89
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

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Card entity: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}.
 *
 * <p><b>Purpose.</b> A pure data holder mapping the 150-byte {@code CARD-RECORD} declared in
 * {@code app/cpy/CVACT02Y.cpy} onto the {@code card} table. It carries no business logic, performs
 * no I/O, emits no log record and holds no reference to any service, repository or DTO. Reading
 * and writing are the responsibility of {@code com.cardemo.repository.CardRepository}; validation
 * of business meaning is the responsibility of the service layer.</p>
 *
 * <p><b>Physical provenance (evidence, Rule 1 clause F1).</b> The base cluster is defined at
 * {@code app/catlg/LISTCAT.txt:L200} and its attributes reported at {@code :L202}, which gives
 * {@code KEYLEN 16} and {@code AVGLRECL 150}; {@code :L203} confirms {@code RKP 0} and
 * {@code MAXLRECL 150}, so the record is fixed width, not variable. The copybook header comment at
 * {@code app/cpy/CVACT02Y.cpy:L2} independently states {@code RECLN 150}.</p>
 *
 * <p><b>Field contract and byte arithmetic.</b> Six populated fields plus trailing filler. The
 * populated widths sum to 91 bytes and the filler contributes the remaining 59, which is exactly
 * the catalogued 150:</p>
 *
 * <pre>
 *   #  COBOL field (CVACT02Y.cpy)   PIC      Java property    Column                SQL type
 *   1  CARD-NUM            (:L5)   X(16)    cardNumber       card_num              CHAR(16) PK
 *   2  CARD-ACCT-ID        (:L6)   9(11)    accountId        card_acct_id          NUMERIC(11)
 *   3  CARD-CVV-CD         (:L7)   9(03)    cvvCode          card_cvv_cd           CHAR(3)
 *   4  CARD-EMBOSSED-NAME  (:L8)   X(50)    embossedName     card_embossed_name    CHAR(50)
 *   5  CARD-EXPIRAION-DATE (:L9)   X(10)    expiraionDate    card_expiraion_date   CHAR(10)
 *   6  CARD-ACTIVE-STATUS (:L10)   X(01)    activeStatus     card_active_status    CHAR(1)
 *   -  FILLER             (:L11)   X(59)    not modelled     none                  none
 *   +  none                        none     version          version               BIGINT
 *
 *   16 + 11 + 3 + 50 + 10 + 1 = 91 populated bytes, + 59 FILLER = 150 bytes.
 * </pre>
 *
 * <p>The {@code FILLER} at {@code app/cpy/CVACT02Y.cpy:L11} is deliberately not modelled: it pads
 * the record out to the catalogued length and carries no data. A census of all 50 rows of
 * {@code app/data/ASCII/carddata.txt} confirms it is blank in every row, so modelling it would add
 * a column that can only ever hold 59 spaces. Its width is recorded here so the arithmetic above
 * remains checkable without re-reading the copybook.</p>
 *
 * <p><b>Fixture corroboration.</b> Row 1 of {@code app/data/ASCII/carddata.txt} is 150 bytes and
 * decodes exactly to the widths above: {@code 0500024453765740} at bytes 1-16,
 * {@code 00000000050} at 17-27, {@code 747} at 28-30, {@code Aniya Von} blank-padded to 50 at
 * 31-80, {@code 2023-03-09} at 81-90 and {@code Y} at 91. The file is 7550 bytes, which is 50 rows
 * of 150 data bytes plus one line terminator each.</p>
 *
 * <p><b>No monetary field exists on this entity.</b> The card layout declares no
 * {@code PIC S9(n)V99}, no {@code COMP-3} and no rate, so there is deliberately no
 * {@code java.math.BigDecimal} property here and none may be introduced. The project-wide rule
 * still applies wherever money does appear: compare decimal amounts with
 * {@code BigDecimal.compareTo}, never {@code BigDecimal.equals}, because {@code equals} is
 * scale-sensitive and would treat 1.0 and 1.00 as different values. No {@code float} or
 * {@code double} appears anywhere in this class.</p>
 *
 * <p><b>Findings, classified by severity (Rule 1 clause F2).</b></p>
 *
 * <p><i>Blocker - the account identifier property must be named {@code accountId}.</i>
 * {@code com.cardemo.repository.CardRepository} declares the derived finder
 * {@code findByAccountId}, and Spring Data resolves derived finders against JavaBean property
 * names. The property is therefore a plain scalar {@code Long} named {@code accountId} with
 * accessor {@code getAccountId()}, and not an association. See the field documentation for the
 * full reasoning.</p>
 *
 * <p><i>Blocker - the card number and the card verification value must never be emitted.</i>
 * {@code toString()} exposes only {@code accountId}, {@code activeStatus}, {@code expiraionDate}
 * and {@code version}. There is no debug-rendering method, no masking helper and no
 * {@code java.io.Serializable} implementation. See {@link #toString()}.</p>
 *
 * <p><i>High - the copybook misspelling {@code EXPIRAION} is retained.</i>
 * {@code app/cpy/CVACT02Y.cpy:L9} declares {@code CARD-EXPIRAION-DATE}, missing the {@code T} of
 * EXPIRATION. The same misspelling occurs in {@code app/cpy/CVACT01Y.cpy:L11} as
 * {@code ACCT-EXPIRAION-DATE}, so it is a corpus-wide spelling and part of the field contract, not
 * a transcription slip. The Java property is {@code expiraionDate} and the column is
 * {@code card_expiraion_date}. Correcting the spelling would silently break the traceability
 * mapping and the column contract.</p>
 *
 * <p><i>Medium - the card verification value is {@code PIC 9(03)} yet maps to text.</i> A census of
 * all 50 rows of {@code app/data/ASCII/carddata.txt} at bytes 28-30 finds 8 rows whose value
 * begins with a zero, the distinct set being 003, 021, 028, 031, 033, 045, 067 and 075. A numeric
 * mapping would drop that leading zero and break byte-exact re-emission. See the field
 * documentation for the deliberate asymmetry against {@code accountId}.</p>
 *
 * <p><b>Column type mapping, and why {@code JdbcTypeCode} is present.</b> The columns are genuine
 * {@code CHAR(n)} and {@code NUMERIC(11)}, which is what preserves the fixed-width geometry the
 * parity comparison depends on: PostgreSQL blank-pads {@code CHAR(n)} on read, so a 9-character
 * embossed name returns as 50 characters exactly as the 50-byte COBOL field did. Hibernate
 * schema validation compares the mapped JDBC type code against the code reported by database
 * metadata, and {@code org.hibernate.type.SqlTypes.isVarcharType} excludes {@code Types.CHAR}
 * while {@code isIntegral} excludes {@code Types.NUMERIC}. Mapping a plain {@code String} or a
 * plain {@code Long} against these columns therefore fails startup outright. Measured on
 * PostgreSQL 16.10 with {@code hibernate.hbm2ddl.auto=validate}, a plain {@code String} reports
 * "wrong column type encountered in column [card_num]; found [bpchar (Types#CHAR)], but expecting
 * [varchar(16) (Types#VARCHAR)]", and a plain {@code Long} reports "found [numeric
 * (Types#NUMERIC)], but expecting [bigint (Types#BIGINT)]". Declaring
 * {@code columnDefinition} does not fix either case, because it changes only the rendered DDL
 * string and not the JDBC type code, so it is deliberately not used here; the schema is owned by
 * Flyway, not by Hibernate, which would make {@code columnDefinition} inert as well as
 * misleading. Setting the type code explicitly is the mapping that validates. This adds no
 * dependency: {@code hibernate-core} is already a compile-scope transitive of
 * {@code spring-boot-starter-data-jpa}.</p>
 *
 * <p><b>Required schema - "Not available" disclosure (Rule 1 clause F4).</b>
 * {@code src/main/resources/db/migration/V1__create_schema.sql} was <b>Not available</b> when this
 * entity was authored: the migration directory had no planned children at that point. The field
 * table above is therefore the <b>normative column contract</b>, and {@code V1} must converge upon
 * it rather than the reverse. This matters because {@code spring.jpa.hibernate.ddl-auto: validate}
 * is set in every profile, so any divergence in column name, type, precision or nullability fails
 * application-context startup outright rather than degrading quietly. What is needed from
 * {@code V1__create_schema.sql} is exactly:</p>
 *
 * <pre>
 *   CREATE TABLE card (
 *       card_num            CHAR(16)    NOT NULL,
 *       card_acct_id        NUMERIC(11) NOT NULL,   -- foreign key to account.acct_id
 *       card_cvv_cd         CHAR(3)     NOT NULL,
 *       card_embossed_name  CHAR(50)    NOT NULL,
 *       card_expiraion_date CHAR(10)    NOT NULL,
 *       card_active_status  CHAR(1)     NOT NULL,
 *       version             BIGINT      NOT NULL,
 *       PRIMARY KEY (card_num)
 *   );
 * </pre>
 *
 * <p>and, from {@code src/main/resources/db/migration/V2__create_indexes.sql}, a <b>non-unique</b>
 * B-tree index on {@code card_acct_id}. Every column is {@code NOT NULL}; there is no nullable
 * column on this table. For the avoidance of doubt about the surrounding migration set:
 * {@code V1} creates exactly 11 tables with 10 foreign keys and 5 check constraints, and the
 * Spring Batch {@code BATCH_*} tables are created by the framework's own schema script, never by a
 * fourth migration and never as extra tables inside {@code V1}.</p>
 *
 * <p><b>How to build and test this component (Rule 1 clause E).</b> Compile with
 * {@code ./mvnw -B clean compile} and run the unit suite with {@code ./mvnw -B clean test}; the
 * build compiles under {@code -Xlint:all -Werror} with {@code failOnWarning}, so a warning here is
 * a build failure. The unit tests covering this entity live in
 * {@code src/test/java/com/cardemo/unit/model}; they assert the 150-byte arithmetic, the key length
 * of 16, that {@code cvvCode} and {@code expiraionDate} are text, that a leading-zero card
 * verification value round-trips unchanged, and that {@code toString()} discloses neither the card
 * number nor the verification value. Schema agreement is exercised by the repository integration
 * tier against a Testcontainers PostgreSQL 16 instance, which requires a reachable container
 * runtime.</p>
 *
 * <p><b>Key configuration and defaults.</b> This class has none of its own: it reads no property
 * and holds no static mutable state. Its behaviour depends only on
 * {@code spring.jpa.hibernate.ddl-auto} being {@code validate} and on the {@code card} table
 * matching the contract above.</p>
 *
 * <p><b>Common failure modes and troubleshooting.</b> A startup message of the form "wrong column
 * type encountered in column [...] in table [card]" means {@code V1__create_schema.sql} diverged
 * from the contract above; fix the migration, not this mapping. An
 * {@code IllegalArgumentException} naming a property means a caller supplied {@code null} for a
 * {@code NOT NULL} column, a text value wider than its fixed-width column, or an account
 * identifier outside the range representable by {@code PIC 9(11)}. An
 * {@code org.springframework.orm.ObjectOptimisticLockingFailureException} on save means another
 * transaction changed the row first; see the {@code version} property for why that guard is
 * necessary but not sufficient. Values read back longer than they were written are not a defect:
 * {@code CHAR(n)} blank-pads, which is the fixed-width behaviour being reproduced.</p>
 *
 * <p><b>Deliberate omissions.</b> No inheritance and no mapped superclass, so the mapping can be
 * read in one place. No {@code jakarta.validation} constraint annotation: the columns are
 * {@code NOT NULL} but blank is a legitimate value in a fixed-width record, and {@code @NotBlank}
 * or {@code @NotEmpty} would reject rows the legacy system accepts. No {@code @Lob}, no fetch or
 * cascade declaration, and no association of any kind.</p>
 */
@Entity
@Table(name = "card")
public class Card {

    /**
     * Width of {@code CARD-NUM}, {@code PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}, and the
     * cluster key length reported as {@code KEYLEN 16} at {@code app/catlg/LISTCAT.txt:L202}.
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Width of {@code CARD-CVV-CD}, {@code PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}. */
    private static final int CVV_CODE_WIDTH = 3;

    /** Width of {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}. */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /**
     * Width of {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:L9}. The misspelling is the copybook's own.
     */
    private static final int EXPIRAION_DATE_WIDTH = 10;

    /** Width of {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}. */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /**
     * Digit count of {@code CARD-ACCT-ID}, {@code PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6},
     * carried through as the {@code NUMERIC(11)} precision. It equals the alternate index key
     * length of 11 reported at {@code app/catlg/LISTCAT.txt:L281}, which is the corroboration that
     * the field really is 11 digits wide.
     */
    private static final int ACCOUNT_ID_PRECISION = 11;

    /**
     * Scale of {@code CARD-ACCT-ID}. The picture clause declares no {@code V} and no decimal
     * positions, so the account identifier is a whole number and the scale is zero. It is stated
     * explicitly rather than left to the annotation default so the mapping reads unambiguously.
     */
    private static final int ACCOUNT_ID_SCALE = 0;

    /**
     * Largest value representable by {@code CARD-ACCT-ID}, {@code PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6}. The picture clause is unsigned, so the representable range
     * is 0 through 99999999999 inclusive, which is also the range of {@code NUMERIC(11)}. Written
     * as eleven grouped nines so it can be counted against {@link #ACCOUNT_ID_PRECISION} by eye.
     */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    /**
     * Card number: {@code CARD-NUM}, {@code PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}.
     *
     * <p>This is the primary key, matching the cluster key length of 16 reported at
     * {@code app/catlg/LISTCAT.txt:L202} with {@code RKP 0} at {@code :L203}, that is, the key
     * begins at the first byte of the record. Mapped as text rather than a number because the
     * picture clause is {@code X(16)}, not {@code 9(16)}: leading zeros are significant, as row 1
     * of {@code app/data/ASCII/carddata.txt} shows with {@code 0500024453765740}.</p>
     *
     * <p><b>This value is sensitive and is never rendered by {@code toString()}.</b></p>
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", nullable = false, length = CARD_NUMBER_WIDTH)
    private String cardNumber;

    /**
     * Owning account identifier: {@code CARD-ACCT-ID}, {@code PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6}.
     *
     * <p><b>Blocker-severity naming requirement.</b> This property must be named exactly
     * {@code accountId}, with accessors {@code getAccountId()} and {@code setAccountId(Long)}.
     * {@code com.cardemo.repository.CardRepository} declares the derived finder
     * {@code findByAccountId}, and Spring Data derives queries from JavaBean property names, so
     * renaming this to {@code acctId} or {@code cardAcctId} would make that finder unresolvable
     * and fail context startup. Do not rename it.</p>
     *
     * <p><b>It is deliberately a plain scalar, not an association.</b> Modelling the owning
     * account as {@code @ManyToOne Account account} would rename the derived finder to
     * {@code findByAccountAccountId} and break {@code findByAccountId} in the same way. It would
     * also introduce a lazy proxy on every card read and expose the card list endpoints to N+1
     * query behaviour for no benefit, since nothing on this entity needs to navigate to the
     * account. Referential integrity is enforced instead by one of the ten foreign keys created in
     * {@code V1__create_schema.sql}, where it belongs. This mapping must not be "improved" into an
     * association later.</p>
     *
     * <p><b>Alternate index provenance - documentation, not a mapping.</b>
     * {@code app/catlg/LISTCAT.txt:L279} defines {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} over
     * this field, and {@code :L281} through {@code :L283} report {@code KEYLEN 11},
     * {@code RKP 5} and {@code AXRKP 16}. The alternate key is 11 bytes wide, which is exactly the
     * width of this field, and it sits at byte offset 16 of the base record, which is exactly
     * where this field begins immediately after the 16-byte card number. The alternate index is
     * not an entity and has no class of its own. It becomes two things: the derived finder in
     * {@code com.cardemo.repository.CardRepository}, and a <b>non-unique</b> B-tree index on
     * {@code card_acct_id} created by {@code V2__create_indexes.sql}. Non-unique is not a
     * relaxation but the catalogued fact: {@code app/catlg/LISTCAT.txt:L285} declares the
     * alternate index {@code NONUNIQKEY}, consistent with one account legitimately holding many
     * cards.</p>
     *
     * <p><b>Numeric here, text for the verification value - a deliberate asymmetry.</b> This field
     * stays a {@code Long} even though {@code app/data/ASCII/carddata.txt} stores it zero-padded
     * as {@code 00000000050}, because zero padding of an identifier is a fixed-width emission
     * concern owned by the batch writers, and because the composite key classes in
     * {@code com.cardemo.model.key} already fix account identifiers as {@code Long}; making this
     * field text would split that representation across the model. The verification value below
     * takes the opposite decision for the opposite reason.</p>
     */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "card_acct_id",
            nullable = false,
            precision = ACCOUNT_ID_PRECISION,
            scale = ACCOUNT_ID_SCALE)
    private Long accountId;

    /**
     * Card verification value: {@code CARD-CVV-CD}, {@code PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}.
     *
     * <p><b>Medium-severity mapping decision: a numeric picture clause deliberately mapped to
     * text.</b> A census of all 50 rows of {@code app/data/ASCII/carddata.txt} at bytes 28-30
     * finds 8 rows whose value begins with a zero, the distinct set being 003, 021, 028, 031, 033,
     * 045, 067 and 075. Held as a number, {@code 003} would re-emit as {@code 3} and the fixture
     * round-trip would no longer be byte-exact. The value is never an arithmetic operand anywhere
     * in the legacy corpus, so nothing is lost by holding it as text and the leading zero is
     * preserved. Contrast {@code accountId} above, which stays numeric for the reasons given
     * there.</p>
     *
     * <p>No format validation is applied: there is no digit pattern, no length-equality
     * requirement and no normalisation, only the fixed-width bound that {@code CHAR(3)} enforces
     * in any case. Constraining the format here would reject values the legacy system loads.</p>
     *
     * <p><b>This value is sensitive and is never rendered by {@code toString()}.</b></p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_cvv_cd", nullable = false, length = CVV_CODE_WIDTH)
    private String cvvCode;

    /**
     * Name embossed on the card: {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}.
     *
     * <p>Row 1 of {@code app/data/ASCII/carddata.txt} holds {@code Aniya Von} blank-padded to the
     * full 50 bytes, which is why the column is {@code CHAR(50)}: the padding is part of the
     * record image, and {@code CHAR} reproduces it on read.</p>
     *
     * <p><b>This value is personal data and is never rendered by {@code toString()}.</b></p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_embossed_name", nullable = false, length = EMBOSSED_NAME_WIDTH)
    private String embossedName;

    /**
     * Card expiry date, held as text: {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:L9}.
     *
     * <p><b>High-severity note: the misspelling is deliberate provenance.</b> The copybook field
     * is spelled {@code CARD-EXPIRAION-DATE}, without the {@code T} of EXPIRATION, and the same
     * misspelling appears in {@code app/cpy/CVACT01Y.cpy:L11} as {@code ACCT-EXPIRAION-DATE}. It
     * is therefore a spelling used across the corpus and part of the field contract rather than a
     * transcription error in this file. The property is {@code expiraionDate} and the column is
     * {@code card_expiraion_date}. Renaming either to {@code expirationDate} would break the
     * documented mapping and the column contract; do not "correct" it.</p>
     *
     * <p><b>Text, not a date.</b> The picture clause is {@code X(10)}, and row 1 of
     * {@code app/data/ASCII/carddata.txt} holds the dash-separated value {@code 2023-03-09} at
     * bytes 81-90. The column stays {@code CHAR(10)} and the property stays a {@code String}:
     * there is no {@code java.time.LocalDate}, no temporal annotation and no format validation
     * here. Keeping it textual is what allows blank and legacy-invalid values to load and
     * round-trip byte-exactly; parsing and validating the value is the job of the date validation
     * service, which is the Java replacement for {@code app/cbl/CSUTLDTC.cbl}.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_expiraion_date", nullable = false, length = EXPIRAION_DATE_WIDTH)
    private String expiraionDate;

    /**
     * Active status flag: {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10}.
     *
     * <p>A single character. Every one of the 50 rows of {@code app/data/ASCII/carddata.txt}
     * carries {@code Y}, so the fixture exercises only the active case; the column is not
     * constrained to an enumeration here because the copybook declares none, and inventing one
     * would reject values the legacy file could hold.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_active_status", nullable = false, length = ACTIVE_STATUS_WIDTH)
    private String activeStatus;

    /**
     * Optimistic locking version. This column has no counterpart in
     * {@code app/cpy/CVACT02Y.cpy}; it is added by the migration.
     *
     * <p><b>Necessary but not sufficient.</b> This is the store-level guard only: it detects that
     * some concurrent transaction changed the row, and causes the second writer's flush to fail.
     * It does not reproduce the legacy change-detection semantics. The card update path mirrors
     * the account update pattern, whose COBOL analogue is paragraph
     * {@code 9700-CHECK-CHANGE-IN-REC} at {@code app/cbl/COACTUPC.cbl:L4109-L4193}, and that
     * paragraph compares individual business field values against a snapshot captured when the
     * screen was first populated. The two guarantees differ: a concurrent write that set a field
     * back to its original value passes the legacy check but fails a version check. Reproducing
     * the legacy behaviour therefore additionally requires an explicit field-by-field comparison
     * in the service layer against a snapshot carried on the request, because the target is
     * stateless and cannot hold that snapshot between requests. Both layers are required; neither
     * substitutes for the other.</p>
     *
     * <p>The value is managed by the persistence provider, which assigns it on insert and
     * increments it on update. It is consequently not a constructor parameter, and it is
     * {@code null} on a newly constructed, not-yet-persisted instance.</p>
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * No-argument constructor required by the JPA specification.
     *
     * <p>Visible to the persistence provider and to subclasses only. It leaves every property
     * {@code null}; the provider populates the fields directly by reflection, which is why it does
     * not route through the validating setters. Application code must use
     * {@link #Card(String, Long, String, String, String, String)} so that the {@code NOT NULL} and
     * fixed-width contracts are checked at construction time.</p>
     */
    protected Card() {
        // Intentionally empty: JPA instantiates the entity and then populates fields reflectively.
    }

    /**
     * Creates a fully populated card record from the six columns of {@code CARD-RECORD}.
     *
     * <p>The {@code version} column is deliberately not a parameter: it has no counterpart in
     * {@code app/cpy/CVACT02Y.cpy} and is assigned by the persistence provider, so a caller
     * creating a new card has no meaningful value to supply. Use {@link #setVersion(Long)} only
     * when reattaching a detached instance whose version is already known.</p>
     *
     * <p>Every argument is validated: {@code null} is rejected because every column is
     * {@code NOT NULL}, text longer than its column is rejected because the record is fixed width,
     * and the account identifier is range-checked against {@code PIC 9(11)}. Text shorter than its
     * column, including blank, is accepted: blank is a legitimate value in a fixed-width record
     * and {@code CHAR(n)} pads it on read.</p>
     *
     * @param cardNumber    {@code CARD-NUM}, {@code PIC X(16)}; at most 16 characters, not
     *                      {@code null}
     * @param accountId     {@code CARD-ACCT-ID}, {@code PIC 9(11)}; 0 through 99999999999
     *                      inclusive, not {@code null}
     * @param cvvCode       {@code CARD-CVV-CD}, {@code PIC 9(03)}; at most 3 characters, not
     *                      {@code null}
     * @param embossedName  {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)}; at most 50 characters,
     *                      not {@code null}
     * @param expiraionDate {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)}; at most 10 characters,
     *                      not {@code null}. The parameter name carries the copybook's own
     *                      misspelling by design
     * @param activeStatus  {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)}; at most 1 character, not
     *                      {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}, if any text argument is
     *                                  wider than its column, or if {@code accountId} falls
     *                                  outside the range representable by {@code PIC 9(11)}. The
     *                                  message names the offending property
     */
    public Card(String cardNumber,
                Long accountId,
                String cvvCode,
                String embossedName,
                String expiraionDate,
                String activeStatus) {
        // Fields are assigned directly, and every check is a private static helper, so this
        // constructor invokes no overridable method and cannot leak a partially built instance.
        this.cardNumber = checkWidth(cardNumber, "cardNumber", "CARD-NUM", CARD_NUMBER_WIDTH);
        this.accountId = checkAccountId(accountId);
        this.cvvCode = checkWidth(cvvCode, "cvvCode", "CARD-CVV-CD", CVV_CODE_WIDTH);
        this.embossedName =
                checkWidth(embossedName, "embossedName", "CARD-EMBOSSED-NAME", EMBOSSED_NAME_WIDTH);
        this.expiraionDate = checkWidth(expiraionDate,
                "expiraionDate",
                "CARD-EXPIRAION-DATE",
                EXPIRAION_DATE_WIDTH);
        this.activeStatus =
                checkWidth(activeStatus, "activeStatus", "CARD-ACTIVE-STATUS", ACTIVE_STATUS_WIDTH);
    }

    /**
     * Returns the card number, {@code CARD-NUM}, {@code PIC X(16)}, which is the primary key.
     *
     * <p>Callers are reminded that this is sensitive data: it is excluded from
     * {@link #toString()} by design, so anything that logs or serialises the returned value takes
     * on that responsibility itself.</p>
     *
     * @return the 16-character card number, never {@code null} on a persisted instance
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Replaces the card number, {@code CARD-NUM}, {@code PIC X(16)}.
     *
     * <p>This is the primary key, so changing it on an already-persisted instance changes identity
     * and is not something the update paths do; it exists for construction and test support.</p>
     *
     * @param cardNumber the card number; at most 16 characters, not {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} is {@code null} or longer than 16
     *                                  characters
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = checkWidth(cardNumber, "cardNumber", "CARD-NUM", CARD_NUMBER_WIDTH);
    }

    /**
     * Returns the owning account identifier, {@code CARD-ACCT-ID}, {@code PIC 9(11)}.
     *
     * <p>This accessor name is load-bearing: it is what makes the derived finder
     * {@code findByAccountId} on {@code com.cardemo.repository.CardRepository} resolve. Do not
     * rename it.</p>
     *
     * @return the account identifier, never {@code null} on a persisted instance
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Replaces the owning account identifier, {@code CARD-ACCT-ID}, {@code PIC 9(11)}.
     *
     * @param accountId the account identifier; 0 through 99999999999 inclusive, not {@code null}
     * @throws IllegalArgumentException if {@code accountId} is {@code null} or outside the range
     *                                  representable by {@code PIC 9(11)}
     */
    public void setAccountId(Long accountId) {
        this.accountId = checkAccountId(accountId);
    }

    /**
     * Returns the card verification value, {@code CARD-CVV-CD}, {@code PIC 9(03)}, held as text so
     * that a leading zero survives.
     *
     * <p>Callers are reminded that this is sensitive data: it is excluded from
     * {@link #toString()} by design.</p>
     *
     * @return the verification value, never {@code null} on a persisted instance
     */
    public String getCvvCode() {
        return cvvCode;
    }

    /**
     * Replaces the card verification value, {@code CARD-CVV-CD}, {@code PIC 9(03)}.
     *
     * <p>No format validation is performed and no normalisation is applied, so a leading zero is
     * stored exactly as supplied.</p>
     *
     * @param cvvCode the verification value; at most 3 characters, not {@code null}
     * @throws IllegalArgumentException if {@code cvvCode} is {@code null} or longer than 3
     *                                  characters
     */
    public void setCvvCode(String cvvCode) {
        this.cvvCode = checkWidth(cvvCode, "cvvCode", "CARD-CVV-CD", CVV_CODE_WIDTH);
    }

    /**
     * Returns the embossed name, {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)}.
     *
     * <p>Read from a {@code CHAR(50)} column, the value is blank-padded to the full 50 characters,
     * reproducing the fixed-width record image. Callers are reminded that this is personal data
     * and is excluded from {@link #toString()} by design.</p>
     *
     * @return the embossed name, never {@code null} on a persisted instance
     */
    public String getEmbossedName() {
        return embossedName;
    }

    /**
     * Replaces the embossed name, {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)}.
     *
     * @param embossedName the embossed name; at most 50 characters, not {@code null}
     * @throws IllegalArgumentException if {@code embossedName} is {@code null} or longer than 50
     *                                  characters
     */
    public void setEmbossedName(String embossedName) {
        this.embossedName =
                checkWidth(embossedName, "embossedName", "CARD-EMBOSSED-NAME", EMBOSSED_NAME_WIDTH);
    }

    /**
     * Returns the expiry date as text, {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)}.
     *
     * <p>The name carries the copybook's own misspelling by design; see the field documentation.
     * The value is not parsed here and may be blank or legacy-invalid.</p>
     *
     * @return the 10-character expiry date text, never {@code null} on a persisted instance
     */
    public String getExpiraionDate() {
        return expiraionDate;
    }

    /**
     * Replaces the expiry date text, {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)}.
     *
     * <p>No date parsing or format validation is performed, so blank and legacy-invalid values are
     * accepted exactly as the legacy file holds them.</p>
     *
     * @param expiraionDate the expiry date text; at most 10 characters, not {@code null}
     * @throws IllegalArgumentException if {@code expiraionDate} is {@code null} or longer than 10
     *                                  characters
     */
    public void setExpiraionDate(String expiraionDate) {
        this.expiraionDate = checkWidth(expiraionDate,
                "expiraionDate",
                "CARD-EXPIRAION-DATE",
                EXPIRAION_DATE_WIDTH);
    }

    /**
     * Returns the active status flag, {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)}.
     *
     * @return the single-character status flag, never {@code null} on a persisted instance
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Replaces the active status flag, {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)}.
     *
     * @param activeStatus the status flag; at most 1 character, not {@code null}
     * @throws IllegalArgumentException if {@code activeStatus} is {@code null} or longer than 1
     *                                  character
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus =
                checkWidth(activeStatus, "activeStatus", "CARD-ACTIVE-STATUS", ACTIVE_STATUS_WIDTH);
    }

    /**
     * Returns the optimistic locking version.
     *
     * @return the version, or {@code null} on an instance that has not been persisted yet
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Replaces the optimistic locking version.
     *
     * <p>{@code null} is accepted here, unlike the mapped data columns, because a transient
     * instance legitimately has no version yet and because reattaching a detached instance
     * sometimes requires restoring the value the caller holds. The persistence provider normally
     * owns this field and writes it reflectively; setting it by hand overrides the store-level
     * concurrency guard, so callers should have a specific reason to do so.</p>
     *
     * @param version the version to carry, or {@code null} for a transient instance
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares two cards by primary key only.
     *
     * <p>{@code cardNumber} is the whole identity of the row, which is why no other property takes
     * part: two instances describing the same card must compare equal even if one of them has
     * unsaved edits, and comparing mutable business fields would break the contract with
     * {@link #hashCode()} as soon as one of them changed. The test is {@code instanceof} rather
     * than an exact class comparison so that a lazily initialised provider proxy still compares
     * equal to the instance it stands for.</p>
     *
     * @param other the object to compare against, possibly {@code null}
     * @return {@code true} if {@code other} is a card with the same card number
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Card that)) {
            return false;
        }
        return Objects.equals(this.cardNumber, that.cardNumber);
    }

    /**
     * Returns a hash derived from the primary key only, consistently with {@link #equals(Object)}.
     *
     * <p>Because {@link #Card(String, Long, String, String, String, String)} rejects a
     * {@code null} card number, any instance created through the public constructor has a stable
     * hash for its whole lifetime. An instance created reflectively by the persistence provider is
     * populated before it becomes reachable by application code.</p>
     *
     * @return the hash of the card number
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(cardNumber);
    }

    /**
     * Returns a diagnostic rendering that deliberately discloses no sensitive value.
     *
     * <p><b>This method must never be widened.</b> It renders only {@code accountId},
     * {@code activeStatus}, {@code expiraionDate} and {@code version}. The card number, the card
     * verification value and the embossed name are all excluded: the first two are payment
     * credentials and the third is personal data, and {@code toString()} is the single most likely
     * route by which an entity leaks into a log line, an exception message or a stack trace.
     * Masking rules in {@code logback-spring.xml} are a backstop; never emitting the value in the
     * first place is the primary defence. For the same reason this class provides no alternative
     * rendering method and no partial-display or masking helper - presentation-layer masking
     * belongs to the DTO layer.</p>
     *
     * <p>The rendering uses plain concatenation rather than a formatter, so it depends on no
     * default locale and is byte-identical on every machine.</p>
     *
     * @return a rendering safe to place in a log record
     */
    @Override
    public String toString() {
        return "Card{accountId=" + accountId
                + ", activeStatus=" + activeStatus
                + ", expiraionDate=" + expiraionDate
                + ", version=" + version
                + "}";
    }

    /**
     * Validates one fixed-width text column and returns the value unchanged.
     *
     * <p>Rejects {@code null}, because every column on this table is {@code NOT NULL}, and rejects
     * a value wider than the column, because the record is fixed width and the database would
     * otherwise fail the insert with a message that names neither the COBOL field nor the Java
     * property. Shorter values, blank included, are accepted unchanged: blank is legitimate in a
     * fixed-width record, and no trimming, padding, case folding or format check is applied, so
     * the value round-trips byte for byte.</p>
     *
     * <p>Declared {@code private static} so that the constructor can call it without invoking an
     * overridable method, which would otherwise publish a partially initialised instance.</p>
     *
     * @param value      the candidate value
     * @param property   the Java property name, used in the failure message
     * @param cobolField the originating COBOL field name, used in the failure message
     * @param width      the column width in characters
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null} or wider than
     *                                  {@code width}
     */
    private static String checkWidth(String value, String property, String cobolField, int width) {
        if (value == null) {
            throw new IllegalArgumentException(property
                    + " must not be null: " + cobolField
                    + " maps to a NOT NULL CHAR(" + width + ") column");
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(property
                    + " must be at most " + width + " characters to fit " + cobolField
                    + ", but was " + value.length());
        }
        return value;
    }

    /**
     * Validates the account identifier and returns it unchanged.
     *
     * <p>Rejects {@code null} because the column is {@code NOT NULL}, and rejects any value
     * outside 0 through 99999999999 inclusive, which is the range representable by the unsigned
     * {@code PIC 9(11)} of {@code CARD-ACCT-ID} and equally by {@code NUMERIC(11)}. Catching it
     * here names the property; letting it reach the database would not.</p>
     *
     * <p>Declared {@code private static} for the same reason as {@link #checkWidth}.</p>
     *
     * @param value the candidate account identifier
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null} or outside the
     *                                  representable range
     */
    private static Long checkAccountId(Long value) {
        if (value == null) {
            throw new IllegalArgumentException("accountId must not be null: CARD-ACCT-ID maps to a "
                    + "NOT NULL NUMERIC(11) column");
        }
        if (value < 0L || value > MAX_ACCOUNT_ID) {
            throw new IllegalArgumentException("accountId must be between 0 and " + MAX_ACCOUNT_ID
                    + " inclusive to fit CARD-ACCT-ID PIC 9(11), but was " + value);
        }
        return value;
    }
}
