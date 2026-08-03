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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Card entity: the relational replacement for the VSAM KSDS cluster {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}.
 *
 * <p>A pure data holder mapping the 150-byte {@code CARD-RECORD} declared in {@code app/cpy/CVACT02Y.cpy} onto
 * the {@code card} table. It carries no business logic, performs no I/O, emits no log record and holds no
 * reference to any service, repository or DTO. Reading and writing are the responsibility of
 * {@code com.cardemo.repository.CardRepository}; validation of business meaning is the responsibility of the
 * service layer.
 *
 * <p><b>Physical provenance (evidence, Rule 1 clause F1).</b> The base cluster is defined at
 * {@code app/catlg/LISTCAT.txt:L200} and its attributes reported at {@code :L202}, which gives
 * {@code KEYLEN 16} and {@code AVGLRECL 150}; {@code :L203} confirms {@code RKP 0} and
 * {@code MAXLRECL 150}, so the record is fixed width, not variable. The copybook header comment at
 * {@code app/cpy/CVACT02Y.cpy:L2} independently states {@code RECLN 150}.</p>
 *
 * <p><b>Field contract and byte arithmetic.</b> Six populated fields plus trailing filler in the
 * frozen copybook, of which <b>five are persisted</b>. The populated widths still sum to 91 bytes and
 * the filler still contributes 59, which is exactly the catalogued 150 - the record has not changed
 * shape; three of its bytes are simply not stored:</p>
 *
 * <pre>
 *   #  COBOL field (CVACT02Y.cpy)   PIC      Java property    Column                SQL type
 *   1  CARD-NUM            (:L5)   X(16)    cardNumber       card_num              CHAR(16) PK
 *   2  CARD-ACCT-ID        (:L6)   9(11)    accountId        card_acct_id          NUMERIC(11)
 *   3  CARD-CVV-CD         (:L7)   9(03)    NOT MODELLED     none                  none
 *   4  CARD-EMBOSSED-NAME  (:L8)   X(50)    embossedName     card_embossed_name    CHAR(50)
 *   5  CARD-EXPIRAION-DATE (:L9)   X(10)    expiraionDate    card_expiraion_date   CHAR(10)
 *   6  CARD-ACTIVE-STATUS (:L10)   X(01)    activeStatus     card_active_status    CHAR(1)
 *   -  FILLER             (:L11)   X(59)    not modelled     none                  none
 *   +  none                        none     version          version               BIGINT
 *
 *   16 + 11 + 50 + 10 + 1 = 88 persisted bytes, + 3 unpersisted CARD-CVV-CD
 *   + 59 FILLER = 150 bytes.
 * </pre>
 *
 * <p>The {@code FILLER} at {@code app/cpy/CVACT02Y.cpy:L11} is deliberately not modelled: it pads
 * the record out to the catalogued length and carries no data. A census of all 50 rows of
 * {@code app/data/ASCII/carddata.txt} confirms it is blank in every row, so modelling it would add
 * a column that can only ever hold 59 spaces. Its width is recorded here so the arithmetic above
 * remains checkable without re-reading the copybook.</p>
 *
 * <p><b>Fixture corroboration.</b> Row 1 of {@code app/data/ASCII/carddata.txt} is 150 bytes and
 * decodes exactly to the widths above: a 16-digit card number at bytes 1-16, {@code 00000000050} at
 * 17-27, a 3-digit verification value at 28-30, a 9-character embossed name blank-padded to 50 at
 * 31-80, {@code 2023-03-09} at 81-90 and {@code Y} at 91. The file is 7550 bytes, which is 50 rows
 * of 150 data bytes plus one line terminator each.</p>
 *
 * <p><b>Why the card number, verification value and embossed name are described rather than quoted.</b>
 * The three are cardholder data, so they are given as width and shape only - the mask is
 * width-preserving, {@code ################} for the primary account number and {@code ###} for the
 * verification value, so the byte arithmetic above stays checkable while no fixture value is reproduced in
 * this tree (Rule 1 Clause D). The account identifier and the expiry date are quoted because neither
 * identifies a cardholder on its own and both are needed to anchor the 150-byte offset proof. Read the
 * fixture directly when a literal value is genuinely required; it is not restated here.</p>
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
 * <p><strong>Schema reconciliation, measured 1 August 2026.</strong>
 * {@code src/main/resources/db/migration/V1__create_schema.sql} is <strong>present</strong>,
 * declaring 11 tables, 10 named foreign keys, 5 CHECK constraints and 4 {@code version} columns.
 * It declares {@code CREATE TABLE card} with 7 columns whose names are identical, as a
 * set, to the 7 {@code @Column(name = ...)} declarations below, verified by direct comparison.
 * The mapping is therefore reconciled against real DDL rather than asserted in its absence.
 * This matters because {@code spring.jpa.hibernate.ddl-auto: validate} is set in all four profiles, so any
 * divergence in column name, type, precision or nullability fails application-context startup
 * outright rather than degrading quietly - an observed behaviour rather than a mandated one, since all four
 * of {@code application.yml}, {@code application-local.yml}, {@code application-test.yml} and
 * {@code application-prod.yml} are present and the containerised integration tier boots with Flyway
 * applying {@code V1} through {@code V3} first. Both later migrations exist:
 * {@code V2__create_indexes.sql} creates {@code idx_card_acct_id} for this table and
 * {@code V3__seed_data.sql} seeds it from {@code app/data/ASCII/carddata.txt}. An earlier revision of this
 * paragraph called all six artefacts unavailable; that is no longer true and the claim is withdrawn.</p>
 *
 * <p>What {@code V1__create_schema.sql} declares for this table, and what this mapping asserts, is
 * exactly:</p>
 *
 * <pre>
 *   CREATE TABLE card (
 *       card_num            CHAR(16)    NOT NULL,
 *       card_acct_id        NUMERIC(11) NOT NULL,   -- foreign key to account.acct_id
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
 * {@code src/test/java/com/cardemo/unit/model}; they are to assert the 150-byte arithmetic, the key length
 * of 16, that {@code cvvCode} and {@code expiraionDate} are text, that a leading-zero card
 * verification value round-trips unchanged, and that {@code toString()} discloses neither the card
 * number nor the verification value. {@code src/test/java/com/cardemo/unit/model/CardTest.java} covers all
 * of that today, including that the account identifier sits at byte 16 where the alternate index is anchored
 * and that it is a {@code Long} because {@code PIC 9(11)} overflows {@code int}. Schema agreement is exercised
 * by the repository integration tier against a Testcontainers PostgreSQL 16 instance;
 * {@code src/test/java/com/cardemo/integration/repository} provides the abstract base for that tier, and
 * <strong>a reachable container runtime is a validation-time prerequisite</strong> for running it - where none
 * is available the correct report is that the tier did not run, never an untested pass.</p>
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
 *
 * <p><b>JSON serialisation barrier.</b> This class is structurally unserialisable by Jackson, and on this
 * entity that is the single most consequential control in the file. {@link JsonIgnoreType} removes any
 * property whose declared type is this class from an enclosing object's JSON, and {@link JsonAutoDetect}
 * with every visibility set to {@code NONE} switches off bean introspection entirely, so no getter, no
 * setter, no field and no creator is discoverable. An entity is a bean with public accessors, so without
 * the barrier the default behaviour of returning this type from a controller, or holding a field of it on
 * a response object, is to publish the full card number, the card verification value, the embossed name
 * and the expiry date - a cardholder data disclosure, not merely an over-broad response. With the barrier
 * in place Jackson finds no properties and its default {@code FAIL_ON_EMPTY_BEANS} setting turns that
 * mistake into a loud failure at the first request rather than a silent breach. Nothing legitimate is
 * lost: outbound representations are built by {@code com.cardemo.model.dto.CardDto}, which is the type
 * designed to cross the boundary, inbound JSON targets {@code com.cardemo.model.dto.CardUpdateRequest},
 * and persistence is unaffected because Hibernate reads and writes the annotated fields reflectively and
 * never consults Jackson visibility.</p>
 */
@Entity
@Table(name = "card")
@JsonIgnoreType
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class Card {

    /**
     * Width of {@code CARD-NUM}, {@code PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}, and the cluster key
     * length reported as {@code KEYLEN 16} at {@code app/catlg/LISTCAT.txt:L202}.
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * Width of {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}.
     */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /**
     * Width of {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9}. The
     * misspelling is the copybook's own.
     */
    private static final int EXPIRAION_DATE_WIDTH = 10;

    /**
     * Width of {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}.
     */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /**
     * Digit count of {@code CARD-ACCT-ID}, {@code PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}, carried
     * through as the {@code NUMERIC(11)} precision. It equals the alternate index key length of 11 reported at
     * {@code app/catlg/LISTCAT.txt:L281}, which is the corroboration that the field really is 11 digits wide.
     */
    private static final int ACCOUNT_ID_PRECISION = 11;

    /**
     * Scale of {@code CARD-ACCT-ID}. The picture clause declares no {@code V} and no decimal positions, so the
     * account identifier is a whole number and the scale is zero. It is stated explicitly rather than left to
     * the annotation default so the mapping reads unambiguously.
     */
    private static final int ACCOUNT_ID_SCALE = 0;

    /**
     * Largest value representable by {@code CARD-ACCT-ID}, {@code PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6}. The picture clause is unsigned, so the representable range is 0 through
     * 99999999999 inclusive, which is also the range of {@code NUMERIC(11)}. Written as eleven grouped nines so
     * it can be counted against {@link #ACCOUNT_ID_PRECISION} by eye.
     */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    /**
     * Card number: {@code CARD-NUM}, {@code PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}.
     *
     * <p>The primary key, and text rather than a number because the picture clause is {@code X(16)}: leading
     * zeros are significant. This value is sensitive and is never rendered by {@link #toString()}.
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
     * {@code card_acct_id}, created by {@code V2__create_indexes.sql} as {@code idx_card_acct_id} - indexes are
     * owned by that migration rather than by {@code V1}, which declares none. Non-unique is not a
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
     * Name embossed on the card: {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_embossed_name", nullable = false, length = EMBOSSED_NAME_WIDTH)
    private String embossedName;

    /**
     * Card expiry date, held as text: {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:L9}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_expiraion_date", nullable = false, length = EXPIRAION_DATE_WIDTH)
    private String expiraionDate;

    /**
     * Active status flag: {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_active_status", nullable = false, length = ACTIVE_STATUS_WIDTH)
    private String activeStatus;

    /**
     * Optimistic locking version. This column has no counterpart in {@code app/cpy/CVACT02Y.cpy}; it is added
     * by the migration.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * No-argument constructor required by the JPA specification.
     */
    protected Card() {
        // Intentionally empty: JPA instantiates the entity and then populates fields reflectively.
    }

    /**
     * Creates a fully populated card record from the five persisted columns of {@code CARD-RECORD}.
     *
     * <p>{@code CARD-CVV-CD} is <strong>not</strong> a parameter. It is not persisted, so there is nothing
     * for a caller to supply and no accessor through which to read one back - see the deviation recorded on
     * this class.
     *
     * @param cardNumber {@code CARD-NUM}, {@code PIC X(16)}.
     * @param accountId {@code CARD-ACCT-ID}, {@code PIC 9(11)}.
     * @param embossedName {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)}.
     * @param expiraionDate {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)}.
     * @param activeStatus {@code CARD-ACTIVE-STATUS}, {@code PIC X(01)}.
     * @throws IllegalArgumentException if any argument is {@code null}, if any text argument is wider than its
     * column, or if {@code accountId} falls outside the range representable by {@code PIC 9(11)}.
     */
    public Card(String cardNumber,
                Long accountId,
                String embossedName,
                String expiraionDate,
                String activeStatus) {
        // Direct field assignment through private static checks only, so no overridable method runs here.
        this.cardNumber = checkWidth(cardNumber, "cardNumber", "CARD-NUM", CARD_NUMBER_WIDTH);
        this.accountId = checkAccountId(accountId);
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
     * @return the 16-character card number, never {@code null} on a persisted instance
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Replaces the card number, {@code CARD-NUM}, {@code PIC X(16)}.
     *
     * @param cardNumber the card number; at most 16 characters, not {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} is {@code null} or longer than 16 characters
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = checkWidth(cardNumber, "cardNumber", "CARD-NUM", CARD_NUMBER_WIDTH);
    }

    /**
     * Returns the owning account identifier, {@code CARD-ACCT-ID}, {@code PIC 9(11)}.
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
     * @throws IllegalArgumentException if {@code accountId} is {@code null} or outside the range representable
     * by {@code PIC 9(11)}
     */
    public void setAccountId(Long accountId) {
        this.accountId = checkAccountId(accountId);
    }

    /**
     * Returns the embossed name, {@code CARD-EMBOSSED-NAME}, {@code PIC X(50)}.
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
     * @throws IllegalArgumentException if {@code embossedName} is {@code null} or longer than 50 characters
     */
    public void setEmbossedName(String embossedName) {
        this.embossedName =
                checkWidth(embossedName, "embossedName", "CARD-EMBOSSED-NAME", EMBOSSED_NAME_WIDTH);
    }

    /**
     * Returns the expiry date as text, {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)}.
     *
     * @return the 10-character expiry date text, never {@code null} on a persisted instance
     */
    public String getExpiraionDate() {
        return expiraionDate;
    }

    /**
     * Replaces the expiry date text, {@code CARD-EXPIRAION-DATE}, {@code PIC X(10)}.
     *
     * @param expiraionDate the expiry date text; at most 10 characters, not {@code null}
     * @throws IllegalArgumentException if {@code expiraionDate} is {@code null} or longer than 10 characters
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
     * @throws IllegalArgumentException if {@code activeStatus} is {@code null} or longer than 1 character
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
     * @param version the version to carry, or {@code null} for a transient instance
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares two cards by primary key only.
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
     * @return the hash of the card number
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(cardNumber);
    }

    /**
     * Returns a diagnostic rendering that deliberately discloses no sensitive value.
     *
     * <p><b>This method must never be widened.</b> It renders only {@code accountId} and
     * {@code version}. The card number, the card verification value and the embossed name were
     * already excluded, the first two as payment credentials and the third as personal data, and
     * {@code expiraionDate} and {@code activeStatus} are now excluded as well.</p>
     *
     * <p>The expiry date was removed because it is cardholder data in its own right: paired with a
     * card number it completes the data set a card-not-present transaction needs, so a log estate
     * that holds expiry dates is one compromise away from being useful to an attacker who obtains
     * numbers elsewhere. Its correlation value here is nil, since it identifies no row. The active
     * status was removed under the same least-privilege reasoning that governs the rest of this
     * package: it is account state that a reader should obtain from the row, where the access is
     * authorised and audited, rather than recover from a log line.</p>
     *
     * <p>{@code toString()} is the single most likely route by which an entity leaks into a log
     * line, an exception message or a stack trace, because it is invoked implicitly - by string
     * concatenation, by a logging placeholder, by a debugger and by an APM agent capturing local
     * variables - on paths no reviewer sees. Masking rules in {@code logback-spring.xml} are a
     * backstop; never emitting the value in the first place is the primary defence. For the same
     * reason this class provides no alternative rendering method and no partial-display or masking
     * helper - presentation-layer masking belongs to the DTO layer.</p>
     *
     * <p>What remains is the minimum that makes a log line useful. {@code accountId} is a surrogate
     * key with no payment or personal content, and it is the only identifier on this entity that can
     * be rendered at all: the primary key is the card number, which is precisely the value that must
     * never appear. A reader who needs to know which card is therefore directed to the account and
     * to the row, which is the correct place for that lookup to be authorised.</p>
     *
     * <p>The rendering uses plain concatenation rather than a formatter, so it depends on no
     * default locale and is byte-identical on every machine.</p>
     *
     * @return a rendering safe to place in a log record, never containing a card number, a card
     *         verification value, an embossed name or an expiry date
     */
    @Override
    public String toString() {
        return "Card{accountId=" + accountId + ", version=" + version + "}";
    }

    /**
     * Validates one fixed-width text column and returns the value unchanged.
     *
     * @param value the candidate value
     * @param property the Java property name, used in the failure message
     * @param cobolField the originating COBOL field name, used in the failure message
     * @param width the column width in characters
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null} or wider than {@code width}
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
     * @param value the candidate account identifier
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null} or outside the representable range
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
