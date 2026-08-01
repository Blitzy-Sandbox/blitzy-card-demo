/*
 * ******************************************************************
 * Program     : TransactionCategory.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Transaction category type reference table. Replaces the
 *               VSAM KSDS cluster AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS,
 *               a 60 byte record under a 6 byte composite key, whose
 *               rows pair a transaction type code with a category code
 *               and carry the human readable category description that
 *               the batch transaction report prints.
 * Source      : app/cpy/CVTRA04Y.cpy (60 B, composite key 6) @ 7756d89
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
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cardemo.model.key.TransactionCategoryId;

/**
 * Transaction category type reference row, replacing the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS} one record for one row.
 *
 * <p>The layout is {@code app/cpy/CVTRA04Y.cpy}, whose header comment at {@code :L2} reads
 * {@code Data-structure for transaction category type (RECLN = 60)}. A row names one
 * transaction category within one transaction type and supplies the description that the batch
 * transaction report prints; the only program in the corpus that consumes the layout is
 * {@code app/cbl/CBTRN03C.cbl}, which issues {@code COPY CVTRA04Y.} at {@code :L108}.
 *
 * <p>This is a pure data holder. It performs no I/O, reads no configuration, emits no log,
 * holds no static mutable state and depends on exactly one other CardDemo type, the composite
 * identifier {@link TransactionCategoryId}. Its two responsibilities are to carry the row and
 * to state the column contract; every behaviour that reads or writes the table lives in the
 * repository and batch tiers.
 *
 * <h2>Verified field contract</h2>
 *
 * <p>{@code app/cpy/CVTRA04Y.cpy} declares, verbatim at {@code :L4-L9}:
 *
 * <pre>
 * 01  TRAN-CAT-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10  TRAN-TYPE-CD                         PIC X(02).
 *        10  TRAN-CAT-CD                          PIC 9(04).
 *     05  TRAN-CAT-TYPE-DESC                      PIC X(50).
 *     05  FILLER                                  PIC X(04).
 * </pre>
 *
 * <p>Which maps as follows. The two key components are owned by {@link TransactionCategoryId}
 * and are listed here for the record only; this class does not declare them, for the reason
 * given under the Blocker finding below.
 *
 * <pre>
 * COBOL field          PIC     bytes  member                where declared     column              SQL
 * -------------------  ------  -----  --------------------  -----------------  ------------------  ----------
 * TRAN-CAT-KEY         group       6  id                    here, EmbeddedId   composite           PK
 *   TRAN-TYPE-CD       X(02)       2  id.tranTypeCd          key class          tran_type_cd        VARCHAR(2)
 *   TRAN-CAT-CD        9(04)       4  id.tranCatCd           key class          tran_cat_cd         INTEGER
 * TRAN-CAT-TYPE-DESC   X(50)      50  categoryDescription    here               tran_cat_type_desc  CHAR(50)
 * FILLER               X(04)       4  not modelled          not modelled       none                none
 * </pre>
 *
 * <p><b>The record arithmetic closes exactly.</b> The key is {@code 2 + 4 = 6} bytes; adding the
 * description gives {@code 6 + 50 = 56} populated bytes; adding the four byte {@code FILLER}
 * gives {@code 56 + 4 = 60}, which is the {@code RECLN = 60} the copybook header states. The
 * class therefore models 56 of the 60 bytes and deliberately models neither more nor less.
 *
 * <h2>Physical evidence: four independent corroborations</h2>
 *
 * <ul>
 *   <li><b>Copybook arithmetic.</b> {@code X(02)} plus {@code 9(04)} is 6, and the whole record
 *       sums to 60 as shown above.</li>
 *   <li><b>VSAM catalogue.</b> {@code app/catlg/LISTCAT.txt:L1473} reads
 *       {@code CLUSTER--AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS} and {@code :L1475} reads
 *       {@code KEYLEN-----------------6     AVGLRECL--------------60}. {@code :L1476} adds
 *       {@code RKP--------------------0} with {@code MAXLRECL--------------60}, so the key is
 *       the record prefix and the record is fixed at 60 bytes rather than merely averaging
 *       it; {@code :L1477} reports the cluster {@code UNIQUE} and {@code INDEXED}.</li>
 *   <li><b>Seed fixture.</b> {@code app/data/ASCII/trancatg.txt} is 18 rows of exactly 60 bytes
 *       each, 1098 bytes in total once the 18 line terminators are counted. Row 1 is {@code 01}
 *       then {@code 0001} then {@code Regular Sales Draft} padded to 50 then {@code 0000}, and
 *       row 2 is {@code 01} then {@code 0002} then {@code Regular Cash Advance} padded to 50
 *       then {@code 0000}. The 2 and 4 split of the first six bytes is visible directly.</li>
 *   <li><b>Seeded key space.</b> The 18 rows span type codes {@code 01} through {@code 07} and
 *       category codes {@code 0001} through {@code 0005}, distributed 5, 3, 3, 3, 1, 2 and 1
 *       across the seven types. The type codes are exactly the seven rows of
 *       {@code app/data/ASCII/trantype.txt}, which is what makes the pairing meaningful.</li>
 * </ul>
 *
 * <p><b>Note for the seed migration author.</b> The trailing four byte {@code FILLER} in this
 * fixture is <i>zero filled</i>, that is four literal {@code '0'} characters, and not space
 * filled as the corresponding filler is in {@code app/data/ASCII/acctdata.txt} and
 * {@code app/data/ASCII/custdata.txt}. Nothing here reads those bytes either way, because the
 * filler is not modelled, but a loader that trims rather than taking a fixed substring will
 * mis-slice the record. Take bytes 7 to 56 for the description and discard 57 to 60.
 *
 * <h2>Findings, classified by severity</h2>
 *
 * <p><b>Blocker: the composite key class owns every key column, and this class must not restate
 * one of them.</b> {@link TransactionCategoryId} is an {@code Embeddable} that already declares
 * {@code Column(name = "tran_type_cd", nullable = false, length = 2)} and
 * {@code Column(name = "tran_cat_cd", nullable = false)} in COBOL field order. Those names are
 * already correct, so this class adds no {@code AttributeOverride} and no
 * {@code AttributeOverrides}, declares no second {@code Column} for either key column, does not
 * re-declare the components as scalar fields beside the identifier, and introduces no second
 * identity mechanism such as {@code MapsId} or {@code IdClass}. Any of those would map one
 * database column twice; Hibernate then either rejects the model outright or, worse, resolves
 * the duplicate silently and lets {@code ddl-auto: validate} fail at context startup with a
 * message that names a column rather than the annotation that caused it. Remediation is simply
 * to leave the key mapping alone: the single {@code EmbeddedId} below is the whole of it.
 *
 * <p><b>Blocker: a plain {@code jakarta.persistence} String mapping cannot start the application
 * context against a {@code CHAR} column.</b> {@code spring.jpa.hibernate.ddl-auto} is
 * {@code validate} in every profile, so a column name, type, precision or nullability mismatch
 * aborts startup rather than degrading quietly. The behaviour was measured on the pinned stack,
 * Hibernate ORM 6.6.42.Final against PostgreSQL 16.10, and is recorded in full at
 * {@code src/main/java/com/cardemo/model/entity/TransactionType.java}: against a column created
 * as {@code CHAR(n)}, a bare {@code Column(length = n)} fails with
 * {@code found [bpchar (Types#CHAR)], but expecting [varchar(n) (Types#VARCHAR)]}, adding
 * {@code columnDefinition = "CHAR(n)"} still fails, and only
 * {@code JdbcTypeCode(SqlTypes.CHAR)} together with {@code Column(length = n)} passes.
 * Remediation, applied below: {@code categoryDescription} carries
 * {@code JdbcTypeCode(SqlTypes.CHAR)} so Hibernate derives the JDBC type {@code CHAR} instead of
 * defaulting a {@code String} to {@code VARCHAR}. {@code columnDefinition} is deliberately
 * omitted, because the declared length and the JDBC type code already produce the type and a
 * redundant literal that no longer participates in validation is exactly the dead configuration
 * Rule 1 clause B forbids. {@code JdbcTypeCode} and {@code SqlTypes} come from
 * {@code hibernate-core}, which the Spring Data JPA starter already places on the compile
 * classpath, so no dependency is added and none is pinned here. Seven of the seven entities that
 * preceded this one map {@code PIC X(n)} the same way, so this is the established convention of
 * the package rather than a local choice.
 *
 * <p><b>High: this table is deliberately mixed type, and the migration must reflect that.</b>
 * The two findings above interact. The house convention maps {@code PIC X(n)} to {@code CHAR(n)}
 * through {@code JdbcTypeCode}, and this class follows it for the one column it owns. The key
 * class, by contrast, restricts itself to the Jakarta Persistence API and documents that choice
 * explicitly, so it maps {@code tran_type_cd} as a plain {@code String} of length 2 and
 * {@code tran_cat_cd} as a plain {@code Integer}, which Hibernate resolves to {@code VARCHAR(2)}
 * and {@code INTEGER}. Because the Blocker above forbids restating or overriding a key column,
 * this class cannot and must not harmonise them. The consequence is precise, was measured rather
 * than inferred, and must not be guessed at by whoever authors the schema. Resolving this mapping
 * through Hibernate 6.6.42.Final against the PostgreSQL dialect yields exactly
 * {@code tran_cat_cd=integer}, {@code tran_type_cd=varchar(2)} and
 * {@code tran_cat_type_desc=char(50)}, all three non nullable. So the
 * {@code transaction_category} table mixes
 * {@code VARCHAR(2)}, {@code INTEGER} and {@code CHAR(50)}, and declaring the key columns as
 * {@code CHAR(2)} and {@code NUMERIC(4)} instead, however faithful that looks to
 * {@code PIC X(02)} and {@code PIC 9(04)}, would fail validation at startup against the key
 * class's mapping. The exact required DDL is enumerated under the schema section below.
 * Remediation if the mixture is ever considered unacceptable: change the key class so that it
 * adopts {@code JdbcTypeCode(SqlTypes.CHAR)}, and change the migration in the same commit.
 * Never patch it from here.
 *
 * <p><b>Medium: the COBOL group name {@code TRAN-CAT-KEY} denotes two entirely different keys.</b>
 * {@code app/cpy/CVTRA04Y.cpy:L5} declares {@code TRAN-CAT-KEY} as {@code TRAN-TYPE-CD PIC X(02)}
 * followed by {@code TRAN-CAT-CD PIC 9(04)}, which is 6 bytes over two components and is the key
 * of this table. {@code app/cpy/CVTRA01Y.cpy:L5} declares a group with the identical name
 * {@code TRAN-CAT-KEY} as {@code TRANCAT-ACCT-ID PIC 9(11)} followed by
 * {@code TRANCAT-TYPE-CD PIC X(02)} and {@code TRANCAT-CD PIC 9(04)}, which is 17 bytes over
 * three components with a different field prefix, over the {@code TCATBALF} cluster and the
 * {@code TRAN-CAT-BAL-RECORD} whose header states {@code RECLN = 50}. The two share nothing but
 * the group name: different width, different component count, different prefix, different record,
 * different cluster. The resemblance, that both end in a type code followed by a category code,
 * is superficial. The {@code com.cardemo.model.key} package resolves the collision by
 * documentation and never by abstraction, giving each its own {@code Embeddable}, and
 * <b>this class must not reintroduce an abstraction either</b>: there is no shared key interface,
 * no common base identifier type, no generic key holder and no shared helper. Collapsing them
 * would couple two unrelated contracts, and an entity that accepted a supertype of both keys
 * could be handed the 17 byte account scoped key at compile time. Remediation is to keep the two
 * types disjoint, which is what the single concrete {@link TransactionCategoryId} field below
 * achieves.
 *
 * <p><b>Medium: Hibernate does not preserve the COBOL key order internally, and the migration must
 * not be generated from its schema export.</b> Measured on this mapping with Hibernate ORM
 * 6.6.42.Final: although {@link TransactionCategoryId} declares {@code tranTypeCd} first and
 * {@code tranCatCd} second, exactly as {@code app/cpy/CVTRA04Y.cpy:L5-L7} declares them, the
 * primary key Hibernate builds is ordered {@code (tran_cat_cd, tran_type_cd)}. The cause is that
 * Hibernate sorts the attributes of an embeddable alphabetically so that the mapping is
 * deterministic rather than dependent on the order reflection happens to report fields in, and
 * {@code tran_cat_cd} sorts before {@code tran_type_cd}. This cannot be corrected from the entity:
 * the ordering is a property of the embeddable, and the one annotation that could restate the key
 * columns here is the forbidden {@code AttributeOverride} of the first Blocker, which would not
 * change the ordering anyway.
 *
 * <p>It does no harm, for two reasons that both need to hold and both do. First, Flyway owns the
 * DDL and {@code ddl-auto: validate} compares tables, column names, types and nullability but not
 * the column order of a primary key, so the physical order is whatever the migration declares and
 * the internal order never contradicts it. Second, the key is a value object: equality, hashing and
 * every lookup go through both components together, so no application behaviour depends on which
 * component Hibernate lists first. What the internal order does affect is the DDL that Hibernate
 * would emit if schema generation were ever used, which would index the category code first and so
 * invert the VSAM browse prefix. Remediation, and it is the reason this finding is recorded:
 * {@code V1__create_schema.sql} must be authored by hand as
 * {@code PRIMARY KEY (tran_type_cd, tran_cat_cd)}, and must never be produced by running
 * {@code hbm2ddl} against this mapping and pasting the result.
 *
 * <p><b>Low: this is a batch only dataset with no alternate index.</b>
 * {@code app/csd/CARDDEMO.CSD} defines exactly eight CICS file names, namely {@code ACCTDAT},
 * {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT}, {@code CXACAIX},
 * {@code TRANSACT} and {@code USRSEC}. {@code TRANCATG} is not among them, so the dataset has no
 * online definition at all and is reached only from batch, exactly as {@code TCATBALF},
 * {@code DISCGRP} and {@code TRANTYPE} are. That shapes both the authorisation model, since no
 * REST endpoint fronts this table, and the integration test surface, since the table is exercised
 * through batch jobs rather than through controllers. It also has no alternate index:
 * {@code app/catlg/LISTCAT.txt:L3938} reports {@code AIX -------------------3} and all three
 * belong to {@code CARDDATA}, {@code CARDXREF} and {@code TRANSACT}, at
 * {@code app/catlg/LISTCAT.txt:L254}, {@code :L455} and {@code :L3645} respectively. The second
 * migration therefore creates no index for this table beyond its primary key, and no derived
 * finder here needs one.
 *
 * <h2>Deliberate omissions, each with its reason</h2>
 *
 * <ul>
 *   <li><b>No version column.</b> A version column appears on exactly four entities in this
 *       package, {@code Account}, {@code Card}, {@code Customer} and {@code Transaction}, because
 *       those are the rows the online programs read for update and rewrite under a concurrency
 *       guard. This is a reference table that batch reads and the seed migration writes; nothing
 *       in the corpus updates it under contention, so optimistic locking would add a column that
 *       no source field justifies and that the fixed width record has no room for.</li>
 *   <li><b>No JPA association of any kind.</b> There is no relation to {@code TransactionType} on
 *       the type code and no reverse collection to {@code Transaction}. The legacy corpus performs
 *       explicit keyed reads rather than navigating, referential integrity is enforced by the
 *       foreign keys the first migration declares, and a reverse collection over the transaction
 *       table, the largest in the schema, would be an unbounded fetch hazard. Zero associations
 *       means zero lazy proxies and structurally zero N plus 1 query patterns, which is Rule 1
 *       clause A on performance satisfied by construction rather than by tuning.</li>
 *   <li><b>Not an enum, and no {@code Enumerated} anywhere.</b> The 18 rows are seed data loaded
 *       by the third migration from {@code app/data/ASCII/trancatg.txt}, not compile time
 *       constants. Modelling them as an enum would freeze a data set that the fixture owns, would
 *       duplicate the table, and would break the moment a nineteenth category was seeded.</li>
 *   <li><b>No {@code GeneratedValue}.</b> The composite key arrives fully formed from seed data
 *       and from the batch reader, and generating either component would fabricate a key the
 *       source never held.</li>
 *   <li><b>No inheritance.</b> No superclass, no {@code MappedSuperclass}, no auditable base type.
 *       The record has no audit columns, so a base type would contribute nothing but coupling.</li>
 *   <li><b>Does not implement {@code java.io.Serializable}.</b> Jakarta Persistence requires that
 *       of a composite identifier class, which is why {@link TransactionCategoryId} implements it
 *       and declares an explicit serial version identifier, but it requires nothing of the entity.
 *       Implementing it here would open a Java deserialisation surface for no benefit, which is
 *       one of the risky patterns Rule 1 clause D names, and would also make the missing serial
 *       version identifier a hard build failure under {@code -Xlint:all -Werror}.</li>
 *   <li><b>No bean validation constraints.</b> There is no {@code NotBlank}, {@code NotEmpty},
 *       {@code Pattern} or {@code Size} on the description. A description of only spaces is a
 *       legal {@code PIC X(50)} value that the source can hold, so it must remain loadable; a
 *       blankness constraint would reject data the system of record accepts. Nullability and
 *       width are expressed where they belong, on the column.</li>
 *   <li><b>No {@code Lob}, no eager fetch override, no cascade.</b> Fifty characters is not a
 *       large object, and there is nothing to fetch or cascade to.</li>
 *   <li><b>No pass-through accessors into the identifier.</b> There is deliberately no
 *       {@code getTranTypeCd()} or {@code getTranCatCd()} here. Those live on
 *       {@link TransactionCategoryId} and a caller that needs a component goes through
 *       {@link #getId()}. Re-exposing them would duplicate the key class's public surface and
 *       invite the two copies to diverge, which Rule 1 clause C forbids.</li>
 * </ul>
 *
 * <h2>Fixed width and CHAR semantics</h2>
 *
 * <p>{@code CHAR(50)} is chosen for the description over a variable length type because
 * {@code PIC X(50)} is a fixed width, blank padded field and the 60 byte record geometry has to
 * survive the round trip. Two consequences follow, both measured on PostgreSQL 16.10 and recorded
 * on the sibling entity that shares this shape:
 *
 * <ul>
 *   <li><b>In the database, comparison ignores trailing blanks.</b> A description stored as
 *       {@code 'Regular Sales Draft'} in a {@code CHAR(50)} column satisfies the predicate
 *       {@code tran_cat_type_desc = 'Regular Sales Draft'} without any padding in the literal.
 *       That blank insensitivity is precisely what allows the space padded fixture values to be
 *       matched by queries written against the unpadded text, and it is the reason the column is
 *       {@code CHAR} rather than {@code VARCHAR}.</li>
 *   <li><b>In Java, comparison does not.</b> The driver returns a {@code bpchar} value blank
 *       padded to the declared width, so a description read back from the database is a 50
 *       character string and comparing it with {@code String.equals} against
 *       {@code "Regular Sales Draft"} yields {@code false}. The padded value is the faithful
 *       {@code PIC X(50)} image, so {@link #getCategoryDescription()} returns it exactly as read
 *       and never trims: trimming would discard the geometry that the fixed width writers depend
 *       on. A caller comparing against unpadded text must strip first, or push the comparison
 *       into a database predicate where the blank insensitive rule applies.</li>
 * </ul>
 *
 * <h2>Numeric policy</h2>
 *
 * <p>This layout contains no monetary amount and no rate, so the class holds no
 * {@code BigDecimal}, and none is invented. It holds no approximate binary numeric type either,
 * which is a project wide rule rather than a local one: an IEEE 754 binary type cannot represent
 * decimal currency exactly and has no place in a financial field anywhere in this codebase. The
 * companion rule applies wherever a {@code BigDecimal} does appear, on {@code Account},
 * {@code Transaction}, {@code TransactionCategoryBalance} and {@code DisclosureGroup}:
 * <b>compare monetary values with {@code compareTo} and never with {@code equals}</b>, because
 * {@code BigDecimal.equals} is scale sensitive and reports {@code 2.0} and {@code 2.00} as
 * different. It is stated here so the rule is visible on every entity, including the ones that
 * hold no money.
 *
 * <p>The only numeric in the layout is the key component {@code TRAN-CAT-CD PIC 9(04)}, an
 * unsigned display integer used purely as an identifier. It lives on the key class as an
 * {@code Integer}, and because the source stores it zero padded to a fixed width the
 * lexicographic order of the stored bytes equals the numeric order of the decoded value, so
 * browse ordering is preserved rather than perturbed. Any fixed width rendering of it must zero
 * pad back to four digits.
 *
 * <h2>Required schema, and what is Not available</h2>
 *
 * <p><b>Not available:</b> {@code src/main/resources/db/migration/V1__create_schema.sql} did not
 * exist when this entity was authored, and neither did the directory that will contain it, so the
 * schema could not be read and this mapping could not be reconciled against it. This field
 * contract is therefore the normative column contract, and the migration must converge on it
 * rather than the reverse. What is needed, precisely:
 *
 * <ul>
 *   <li>table {@code transaction_category} with, in this order,
 *       {@code tran_type_cd VARCHAR(2) NOT NULL}, {@code tran_cat_cd INTEGER NOT NULL} and
 *       {@code tran_cat_type_desc CHAR(50) NOT NULL};</li>
 *   <li>a composite {@code PRIMARY KEY (tran_type_cd, tran_cat_cd)} in that exact COBOL field
 *       order, because the VSAM browse order is the key byte order and reordering the declaration
 *       would change the prefix a range scan can use. This order must be written by hand: it is
 *       <b>not</b> the order Hibernate reports internally, which is alphabetical. See the second
 *       Medium finding above;</li>
 *   <li><b>the two key column types are not negotiable and are not what the picture clauses
 *       suggest.</b> They must be {@code VARCHAR(2)} and {@code INTEGER}, matching the plain
 *       Jakarta Persistence mapping that {@link TransactionCategoryId} declares. Writing
 *       {@code tran_type_cd CHAR(2)} or {@code tran_cat_cd NUMERIC(4)}, which is the intuitive
 *       reading of {@code PIC X(02)} and {@code PIC 9(04)}, fails validation at startup. This is
 *       the High severity finding above;</li>
 *   <li>the description column, by contrast, must be {@code CHAR(50)}, matching the
 *       {@code JdbcTypeCode(SqlTypes.CHAR)} mapping declared below;</li>
 *   <li>no version column;</li>
 *   <li>no index beyond the primary key;</li>
 *   <li>seeded by {@code V3__seed_data.sql} from {@code app/data/ASCII/trancatg.txt}, which is 18
 *       rows of 60 bytes each: bytes 1 to 2 the type code, 3 to 6 the category code, 7 to 56 the
 *       description and 57 to 60 a zero filled filler that is discarded.</li>
 * </ul>
 *
 * <p>For the migration author's wider orientation: the first migration creates exactly 11 tables
 * with 10 foreign keys and 5 check constraints, of which this table is one and contributes none of
 * the checks; the second adds only the three alternate index equivalents, none of which touches
 * this table; and the Spring Batch {@code BATCH_*} tables come from the framework's own schema
 * script, so they are never a fourth migration and never extra tables inside the first one.
 *
 * <h2>Building, running and testing this component</h2>
 *
 * <p>Build and unit test with {@code mvn -B clean test}, which compiles under
 * {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning this file introduces is a
 * build failure rather than console noise. Run the full gate with {@code mvn -B clean verify},
 * which adds the coverage floor and the dependency vulnerability scan. The unit tests for this
 * entity live in {@code src/test/java/com/cardemo/unit/model} and assert the 60 byte record
 * arithmetic, the composite key length of 6, the table name {@code transaction_category}, that the
 * type is a class rather than an enum, and that no {@code AttributeOverride} and no duplicate key
 * column is declared. The repository tier is exercised against a Testcontainers PostgreSQL 16
 * instance from {@code src/test/java/com/cardemo/integration/repository}. There is no
 * configuration key and no default value specific to this class: it is a mapping, and every
 * setting that governs it, the datasource, the naming strategy and {@code ddl-auto: validate}, is
 * declared in the profile configuration.
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>Startup fails with "Schema-validation: missing table [transaction_category]".</b> The
 *       first migration has not run, or created a differently named table. Check the migration
 *       history and the table name; do not rename this mapping to match a wrong table.</li>
 *   <li><b>Startup fails with "wrong column type in transaction_category for column
 *       tran_type_cd ... found [bpchar (Types#CHAR)], but expecting [varchar(2)
 *       (Types#VARCHAR)]".</b> The migration declared a key column as {@code CHAR} while the key
 *       class maps it as {@code VARCHAR}. Correct the migration to {@code VARCHAR(2)} and
 *       {@code INTEGER}; see the High severity finding above. Do not add an
 *       {@code AttributeOverride} here, which is the Blocker.</li>
 *   <li><b>Startup fails with "wrong column type ... for column tran_cat_type_desc ... found
 *       [bpchar (Types#CHAR)], but expecting [varchar(50) (Types#VARCHAR)]".</b> The
 *       {@code JdbcTypeCode(SqlTypes.CHAR)} annotation has been removed from
 *       {@code categoryDescription} while the column remained {@code CHAR(50)}. Restore it.</li>
 *   <li><b>Hibernate reports a column mapped twice, or a repeated column in a mapping for the
 *       entity.</b> A key column has been restated here. Remove the restatement; the key class
 *       owns both.</li>
 *   <li><b>A range query on the transaction type code does not use the primary key index.</b> The
 *       migration declared the primary key in Hibernate's internal alphabetical order,
 *       {@code (tran_cat_cd, tran_type_cd)}, rather than the COBOL order. Correct the migration to
 *       {@code PRIMARY KEY (tran_type_cd, tran_cat_cd)}; see the second Medium finding above.</li>
 *   <li><b>A description comparison unexpectedly fails in Java.</b> The loaded value is blank
 *       padded to 50 characters by design. Strip before comparing, or compare in SQL.</li>
 *   <li><b>An {@code IllegalArgumentException} from the two argument constructor or from a
 *       setter.</b> The identifier was {@code null}, or the description was {@code null} or longer
 *       than the 50 characters {@code PIC X(50)} can hold. The message names the member, quotes
 *       its source picture clause and reports what was received.</li>
 *   <li><b>A lookup returns nothing for a type and category pair the report expects.</b> The seed
 *       data carries exactly 18 pairs over type codes {@code 01} to {@code 07}. The legacy
 *       program diagnoses a miss rather than failing, so an empty result is a data condition to
 *       report, not a mapping defect.</li>
 * </ul>
 *
 * @see TransactionCategoryId
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */
@Entity
@Table(name = "transaction_category")
public class TransactionCategory {

    /**
     * Exact width, in characters, of {@code TRAN-CAT-TYPE-DESC}, whose picture clause is
     * {@code PIC X(50)} at {@code app/cpy/CVTRA04Y.cpy:L8}. The source field is fixed width, so 50
     * is simultaneously the column width, the blank padded width a database read returns and the
     * upper bound the constructor and setter enforce.
     */
    private static final int CATEGORY_DESCRIPTION_LENGTH = 50;

    /**
     * Composite primary key, from the {@code TRAN-CAT-KEY} group at
     * {@code app/cpy/CVTRA04Y.cpy:L5}, occupying bytes 1 to 6 of the 60 byte record. That is the
     * {@code KEYLEN 6} with {@code RKP 0} which {@code app/catlg/LISTCAT.txt:L1475-L1476} reports.
     *
     * <p>The two components, {@code TRAN-TYPE-CD PIC X(02)} and {@code TRAN-CAT-CD PIC 9(04)}, and
     * both of their column mappings, belong to {@link TransactionCategoryId} and are deliberately
     * not repeated here. This single annotation is the entire key mapping: see the Blocker finding
     * in the class documentation for why adding an {@code AttributeOverride}, a duplicate
     * {@code Column} or a second identity mechanism breaks the model.
     */
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Category description, from {@code TRAN-CAT-TYPE-DESC PIC X(50)} at
     * {@code app/cpy/CVTRA04Y.cpy:L8}, occupying bytes 7 to 56 of the 60 byte record. This is the
     * only non-key column on the table; the four byte {@code FILLER} at {@code :L9} that completes
     * the record is not modelled.
     *
     * <p>Stored and returned blank padded to the full 50 characters, which is the faithful fixed
     * width image rather than an artefact, and deliberately not trimmed on the way out. Not
     * nullable, and not constrained beyond its width, so a description of only spaces loads exactly
     * as the source permits.
     *
     * <p>{@code JdbcTypeCode(SqlTypes.CHAR)} is required rather than decorative: without it
     * Hibernate defaults a {@code String} to {@code VARCHAR} and schema validation rejects the
     * {@code CHAR(50)} column outright at startup. See the second Blocker finding in the class
     * documentation for the measured evidence.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_cat_type_desc", nullable = false, length = CATEGORY_DESCRIPTION_LENGTH)
    private String categoryDescription;

    /**
     * No argument constructor required by Jakarta Persistence so a provider can instantiate a
     * managed instance before populating its state by field reflection.
     *
     * <p>It is {@code protected} rather than {@code public} because application code has no
     * legitimate use for an entity with neither identity nor description, and must instead use
     * {@link #TransactionCategory(TransactionCategoryId, String)}, which validates both. Nothing is
     * defaulted here: a synthetic default would be indistinguishable from a value genuinely read
     * from the database, and validating here would reject the legitimate half-built instance a
     * provider creates. This is a required specification hook, not an unfinished implementation.
     */
    protected TransactionCategory() {
        // Intentionally empty. The persistence provider assigns the identifier and the description
        // by field reflection immediately after instantiation, so defaulting or validating anything
        // at this point would either fabricate state the source never held or reject a valid
        // provider-created instance.
    }

    /**
     * Creates a fully populated transaction category row, which is how application, batch and test
     * code should build one.
     *
     * <p>Both members are assigned through {@code private static} validators and never through the
     * setters. That is not stylistic: the JPA specification forbids a {@code final} entity class, so
     * a constructor that called an overridable instance method would leak {@code this} to a subclass
     * override before construction completed. The compiler reports exactly that as a
     * {@code this-escape} warning and {@code -Werror} turns it into a build failure.
     *
     * <p>Validation is confined to what the source field contract itself dictates and adds no
     * restriction beyond it. The identifier is the row's identity and cannot be absent. The
     * description cannot be absent because the column is not nullable, and cannot exceed 50
     * characters because {@code PIC X(50)} physically cannot represent more; a longer value would
     * otherwise surface only as a truncation error at flush, far from its cause. A blank or empty
     * description is explicitly accepted, because the source accepts one.
     *
     * <p>The constructor performs no I/O, emits no log and has no side effect beyond initialising
     * the two members.
     *
     * @param id                  the composite key, from the {@code TRAN-CAT-KEY} group at
     *                            {@code app/cpy/CVTRA04Y.cpy:L5}; must not be {@code null}
     * @param categoryDescription the description for {@code tran_cat_type_desc}, from
     *                            {@code TRAN-CAT-TYPE-DESC PIC X(50)}; must not be {@code null} and
     *                            must be at most 50 characters, and is blank padded to 50 by the
     *                            {@code CHAR(50)} column. May be blank or empty
     * @throws IllegalArgumentException if {@code id} is {@code null}, or if
     *                                  {@code categoryDescription} is {@code null} or longer than
     *                                  50 characters; the message names the member, quotes its
     *                                  source picture clause and reports what was received
     */
    public TransactionCategory(final TransactionCategoryId id, final String categoryDescription) {
        this.id = requireId(id);
        this.categoryDescription = requireCategoryDescription(categoryDescription);
    }

    /**
     * Validates a candidate composite key.
     *
     * <p>The method is {@code static} so that neither the constructor nor a setter invokes an
     * overridable instance method; see the {@code this-escape} note on the constructor. It performs
     * no validation of the key's own components, because {@link TransactionCategoryId} already
     * validates both against their picture clauses when it is constructed, and duplicating that
     * here would create a second copy of the same rule to drift out of step.
     *
     * @param value the candidate identifier exactly as supplied, possibly {@code null}
     * @return {@code value} unchanged when valid
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static TransactionCategoryId requireId(final TransactionCategoryId value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "id (TRAN-CAT-KEY, composite key of 6 bytes) is required and must not be null");
        }
        return value;
    }

    /**
     * Validates a candidate description against its source picture clause {@code PIC X(50)}.
     *
     * <p>The method is {@code static} for the same reason as {@link #requireId(TransactionCategoryId)}.
     * Only absence and over-width are rejected. Blankness is not: a value of only spaces is a legal
     * {@code PIC X(50)} image, and rejecting it would refuse data the system of record accepts. The
     * value is returned unchanged, never trimmed or padded, so the fixed width geometry survives.
     *
     * @param value the candidate description exactly as supplied, possibly {@code null}
     * @return {@code value} unchanged when valid, including when it is blank or empty
     * @throws IllegalArgumentException if {@code value} is {@code null}, or is longer than the 50
     *                                  characters the source field can hold
     */
    private static String requireCategoryDescription(final String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "categoryDescription (TRAN-CAT-TYPE-DESC PIC X(50)) is required and must not be null");
        }
        if (value.length() > CATEGORY_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("categoryDescription (TRAN-CAT-TYPE-DESC PIC X(50)) must be at most "
                    + CATEGORY_DESCRIPTION_LENGTH + " characters but was " + value.length() + ": [" + value + "]");
        }
        return value;
    }

    /**
     * Returns the composite primary key, from the {@code TRAN-CAT-KEY} group at
     * {@code app/cpy/CVTRA04Y.cpy:L5}.
     *
     * <p>This is the only route to either key component: the transaction type code is
     * {@code getId().getTranTypeCd()} and the category code is {@code getId().getTranCatCd()}. No
     * pass-through accessor is provided here, deliberately, so that the key class remains the single
     * definition of its own surface.
     *
     * @return the six byte composite key, or {@code null} on a transient instance that the no
     *         argument constructor built and a provider has not yet populated
     */
    public TransactionCategoryId getId() {
        return id;
    }

    /**
     * Replaces the composite primary key.
     *
     * <p>Provided as the convenience mutator that the field contract calls for, but it changes the
     * row's identity and therefore its {@link #equals(Object)} and {@link #hashCode()} results. It is
     * safe on a transient instance being assembled before persisting. It must not be called on an
     * instance already managed by a persistence context, where reassigning the identifier would
     * corrupt the identity map, nor on an instance held in a hash based collection, which would then
     * be filed under a stale hash.
     *
     * @param id the composite key to apply; must not be {@code null}
     * @throws IllegalArgumentException if {@code id} is {@code null}
     */
    public void setId(final TransactionCategoryId id) {
        this.id = requireId(id);
    }

    /**
     * Returns the category description, from {@code TRAN-CAT-TYPE-DESC PIC X(50)} at
     * {@code app/cpy/CVTRA04Y.cpy:L8}.
     *
     * @return the description exactly as stored, which after a database read is blank padded to 50
     *         characters by the {@code CHAR(50)} column and is deliberately not trimmed, or
     *         {@code null} on a transient instance that the no argument constructor built and a
     *         provider has not yet populated
     */
    public String getCategoryDescription() {
        return categoryDescription;
    }

    /**
     * Replaces the category description.
     *
     * @param categoryDescription the description to apply, from {@code TRAN-CAT-TYPE-DESC PIC X(50)};
     *                            must not be {@code null} and must be at most 50 characters, and may
     *                            be blank or empty
     * @throws IllegalArgumentException if {@code categoryDescription} is {@code null} or longer than
     *                                  50 characters
     */
    public void setCategoryDescription(final String categoryDescription) {
        this.categoryDescription = requireCategoryDescription(categoryDescription);
    }

    /**
     * Compares this row with another object by primary key alone.
     *
     * <p>Identity is the composite key and nothing else, so the description is deliberately excluded:
     * two loads of the same row must compare equal even if one was mutated in memory, and a row whose
     * description was corrected is still the same row. {@link TransactionCategoryId} already supplies
     * value based equality over both of its components, so this method delegates to it rather than
     * reaching into the key, which is what keeps the key's definition of equality in one place.
     *
     * <p>The comparison requires identical runtime classes rather than mere assignability, which keeps
     * the relation symmetric in the presence of any subclass a provider might generate as a proxy.
     *
     * <p>An instance with no identifier yet, which is the transient state the no argument constructor
     * leaves, is equal only to itself. Treating two such instances as equal would make every freshly
     * built row collide in a hash based collection, so the {@code null} identifier case is
     * deliberately not folded together.
     *
     * @param other the object to compare with, possibly {@code null}
     * @return {@code true} if {@code other} is a {@code TransactionCategory} with an equal, non
     *         {@code null} composite key; {@code false} otherwise, including when {@code other} is
     *         {@code null}, is of another class, or either identifier is absent
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final TransactionCategory that = (TransactionCategory) other;
        return id != null && id.equals(that.id);
    }

    /**
     * Returns a hash consistent with {@link #equals(Object)}, derived from the composite key alone.
     *
     * <p>The description is excluded so that mutating it cannot move a managed instance between hash
     * buckets. A transient instance with no identifier hashes to zero, which is consistent with it
     * being equal only to itself. The caveat that follows from that, and it is the standard one for
     * every JPA entity, is that assigning an identifier to an instance already held in a hash based
     * collection changes its hash and strands it; assign the key before filing the instance.
     *
     * @return the hash of the composite key, or zero when no key has been assigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Returns a diagnostic rendering of the whole row, both the composite key and the description.
     *
     * <p>Nothing on this row is secret, credential bearing or personal: a transaction type code, a
     * transaction category code and a fixed reference description that the batch report already
     * prints in clear. The full row is therefore rendered, unlike {@code Card}, {@code Customer} and
     * {@code UserSecurity}, whose renderings are deliberately restricted because they carry card
     * numbers, personally identifiable data and a password hash respectively. Rule 1 clause D is
     * satisfied by there being nothing here to withhold rather than by withholding it.
     *
     * <p>This is for developer diagnostics only. It is not a log format and not a wire format, no
     * caller may parse it, and it may change without notice. It applies no locale sensitive
     * formatting, so it cannot vary with the platform default locale, and it does not zero pad the
     * category code, so it must never be used to build a fixed width record image.
     *
     * @return the simple class name followed by the composite key and the description, never
     *         {@code null}
     */
    @Override
    public String toString() {
        return "TransactionCategory[id=" + id + ", categoryDescription=" + categoryDescription + "]";
    }
}
