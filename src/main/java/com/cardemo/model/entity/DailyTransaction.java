/*
 * ******************************************************************
 * Program     : DailyTransaction.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Replaces the sequential staging dataset
 *               AWS.M2.CARDDEMO.DALYTRAN.PS, the daily-transaction
 *               input the daily posting job reads and validates.
 * Source      : app/cpy/CVTRA06Y.cpy (350 B, sequential staging) @ 7756d89
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
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Staging row for one inbound daily transaction, replacing the physical
 * sequential dataset {@code AWS.M2.CARDDEMO.DALYTRAN.PS}.
 *
 * <h2>What this component does</h2>
 * <p>This is a pure data holder. It carries one 350-byte {@code DALYTRAN-RECORD}
 * image, exactly as declared by {@code app/cpy/CVTRA06Y.cpy}, from the flat input
 * file into the relational store so the daily posting job can read, validate and
 * post it. It performs no business logic, opens no connection, emits no log
 * record and holds no state beyond its thirteen mapped columns.</p>
 *
 * <p><strong>This table holds untrusted input.</strong> Its rows are unvalidated
 * file content: the card number, type code and category code a row carries may
 * not resolve to any master row at all. Discovering that is precisely the job of
 * the posting-job validation sequence, which assigns reject code 100 for an
 * invalid card number and 101 for a missing account
 * ({@code app/cbl/CBTRN02C.cbl}). The entity therefore accepts every well-formed
 * 350-byte record without judgement, and defers all judgement to that job.</p>
 *
 * <h2>Provenance and physical facts</h2>
 * <p>Source of record: {@code app/cpy/CVTRA06Y.cpy} at anchor commit
 * {@code 7756d89}. Its header comment at {@code :L2} reads
 * "Data-structure for DALYTRANsaction record (RECLN = 350)", the group item at
 * {@code :L4} is {@code 01 DALYTRAN-RECORD.}, and the fields occupy
 * {@code :L5-L18}.</p>
 *
 * <p><strong>This dataset is not a VSAM KSDS cluster</strong>, and it is the only
 * entity in this package that is not. It therefore has no key length and no
 * cluster line in the catalogue listing, and none is cited here or invented.
 * The catalogue records it solely as a non-VSAM entry,
 * {@code AWS.M2.CARDDEMO.DALYTRAN.PS} at
 * {@code app/catlg/LISTCAT.txt:L786}, while the catalogue totals at
 * {@code :L3940} report {@code CLUSTER --------------10} - ten clusters, none of
 * them this one. The authoritative citations for this entity are the copybook
 * above and the fixture {@code app/data/ASCII/dailytran.txt}.</p>
 *
 * <p><strong>Fixture-name warning.</strong> The fixture is spelled
 * {@code dailytran.txt}, with the word "daily" in full. The mainframe DD name
 * and dataset spell it {@code DALYTRAN}, but no such {@code .txt} file exists;
 * a reference using the abbreviated spelling will not resolve. Every
 * test-resource path must use the actual name.</p>
 *
 * <h2>Byte-offset map - the normative record geometry</h2>
 * <p>These offsets drive the fixed-width reader that parses the 350-byte
 * record, so they are reproduced here verbatim rather than left implicit.
 * Offsets are one-based and inclusive, matching COBOL reference modification.</p>
 *
 * <pre>
 *  #   COBOL field (CVTRA06Y.cpy)   PIC          Offsets    Java field          Column                    SQL type
 * --- ---------------------------- ------------ ---------- ------------------- ------------------------- --------------
 *  1   DALYTRAN-ID          :L5     X(16)          1-16     transactionId       dalytran_id               CHAR(16) PK
 *  2   DALYTRAN-TYPE-CD     :L6     X(02)         17-18     typeCode            dalytran_type_cd          CHAR(2)
 *  3   DALYTRAN-CAT-CD      :L7     9(04)         19-22     categoryCode        dalytran_cat_cd           NUMERIC(4)
 *  4   DALYTRAN-SOURCE      :L8     X(10)         23-32     transactionSource   dalytran_source           CHAR(10)
 *  5   DALYTRAN-DESC        :L9     X(100)        33-132    description         dalytran_desc             CHAR(100)
 *  6   DALYTRAN-AMT         :L10    S9(09)V99    133-143    amount              dalytran_amt              NUMERIC(11,2)
 *  7   DALYTRAN-MERCHANT-ID :L11    9(09)        144-152    merchantId          dalytran_merchant_id      NUMERIC(9)
 *  8   DALYTRAN-MERCHANT-NAME :L12  X(50)        153-202    merchantName        dalytran_merchant_name    CHAR(50)
 *  9   DALYTRAN-MERCHANT-CITY :L13  X(50)        203-252    merchantCity        dalytran_merchant_city    CHAR(50)
 * 10   DALYTRAN-MERCHANT-ZIP  :L14  X(10)        253-262    merchantZip         dalytran_merchant_zip     CHAR(10)
 * 11   DALYTRAN-CARD-NUM    :L15    X(16)        263-278    cardNumber          dalytran_card_num         CHAR(16)
 * 12   DALYTRAN-ORIG-TS     :L16    X(26)        279-304    origTs              dalytran_orig_ts          CHAR(26)
 * 13   DALYTRAN-PROC-TS     :L17    X(26)        305-330    procTs              dalytran_proc_ts          CHAR(26)
 *  -   FILLER               :L18    X(20)        331-350    NOT MODELLED        none                      none
 * </pre>
 *
 * <p>Width arithmetic, reproduced so the geometry is checkable by inspection:</p>
 *
 * <pre>
 * 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 = 330 populated bytes
 *                                                          + 20 FILLER bytes
 *                                                          ------------------------
 *                                                          = 350 bytes per record
 * </pre>
 *
 * <p>The 20-byte {@code FILLER} at {@code :L18} is deliberately <em>not</em>
 * mapped. It pads the record to its declared length and carries no data, so
 * modelling it would invent a column with no source meaning. Its width is
 * recorded here, and only here, because the fixed-width reader and writer still
 * have to account for those twenty bytes to land on a 350-byte boundary. The
 * fixture confirms the padding is inert: bytes 331-350 hold exactly one distinct
 * value across all 300 rows, twenty spaces.</p>
 *
 * <h2>Naming convention - deliberately mixed, not an oversight</h2>
 * <p>Two different conventions are applied on purpose, and the apparent
 * inconsistency is intentional:</p>
 * <ul>
 *   <li>the <strong>table</strong> name follows the Java class,
 *       {@code daily_transaction}, consistent with the table names already fixed
 *       by the composite-identifier classes in {@code com.cardemo.model.key},
 *       namely {@code transaction_category_balance}, {@code disclosure_group}
 *       and {@code transaction_category};</li>
 *   <li>the <strong>column</strong> names follow the COBOL field names verbatim,
 *       {@code dalytran_*}, preserving the legacy abbreviation {@code DALYTRAN} -
 *       which is also the mainframe DD name - so that any column can be traced
 *       back to its copybook line by name alone.</li>
 * </ul>
 * <p>Readability is served by the Java-side name; traceability, which the
 * migration is contractually measured on, is served by the column name. Neither
 * convention is a substitute for the other, so both are kept.</p>
 *
 * <h2>Finding: BLOCKER - the two 26-character TS fields are text, never a
 * temporal type, and the blank value must load</h2>
 * <p>{@code app/cpy/CVTRA06Y.cpy:L16-L17} declares
 * {@code DALYTRAN-ORIG-TS PIC X(26)} and {@code DALYTRAN-PROC-TS PIC X(26)} -
 * character fields. Three independent lines of evidence make a temporal Java
 * type not merely inelegant but unusable:</p>
 * <ol>
 *   <li><strong>The fixture carries a blank value.</strong> A census of
 *       {@code app/data/ASCII/dailytran.txt} - 105,300 bytes over 300 rows, every
 *       row exactly 350 data bytes, 351 bytes per line including the terminator -
 *       shows bytes 305-330 holding exactly one distinct value across all 300
 *       rows: <strong>26 spaces, completely blank</strong>. A blank is not a
 *       parseable date-and-time value in any format.</li>
 *   <li><strong>The two populated formats are not interconvertible.</strong>
 *       Bytes 279-304 hold exactly one distinct value across all 300 rows,
 *       {@code 2022-06-10 19:27:53.000000} - space separator, six-fraction
 *       digits. The batch producer writes a different shape entirely: the format
 *       comment at {@code app/cbl/CBTRN02C.cbl:L149} reads
 *       {@code EEEE-MM-DD-UU.MM.SS.HH0000}, that is a dash before the hour, dots
 *       between the time parts, and millisecond precision followed by four
 *       literal zero characters, which {@code :L700-L701} confirm by moving the
 *       hundredths into place and then the literal {@code '0000'}. One
 *       normalising parse would silently rewrite one of these into the other.</li>
 *   <li><strong>One assignment path is pure text pass-through.</strong>
 *       {@code app/cbl/CBTRN02C.cbl:L436} moves this record's field straight
 *       across unexamined, and {@code :L438} writes the producer-formatted value
 *       into the master record. Neither path parses anything.</li>
 * </ol>
 * <p>Consequently both fields are {@code String} of length 26 mapped to
 * {@code CHAR(26)}. No temporal Java type appears anywhere in this file -
 * neither the date-and-time value classes of the JDK time API nor the JDBC ones -
 * and no temporal-mapping annotation and no attribute converter is declared.
 * Equally, no emptiness or format constraint and no parsing helper is provided:
 * the blank 26-space value present in all 300 fixture rows is the canonical
 * explicit empty case for this entity and it must load and round-trip unchanged.
 * Getting this wrong is a Blocker, not a nuisance: a temporal type cannot load
 * the fixture at all, so end-to-end boundary parity fails on the first record.</p>
 *
 * <p>A useful side effect: because these values are carried as text, this entity
 * is entirely insulated from JVM time-zone drift. Nothing here is re-interpreted
 * against a zone. That is complementary to, and independent of, the project-wide
 * Hibernate JDBC zone setting of UTC, which governs genuinely temporal columns
 * elsewhere in the schema and has no effect on these two.</p>
 *
 * <h2>Finding: BLOCKER - the source column is text, never the sibling origin
 * enum</h2>
 * <p>An enum modelling transaction origin exists in the sibling {@code model.enums}
 * package, and its own contract states that it must not be the persisted type of
 * this column, naming this field explicitly. It is therefore neither imported nor
 * referenced here, and no enum-mapping annotation and no converter is declared.</p>
 * <p>The corpus agrees. {@code app/cbl/CBTRN02C.cbl:L428} moves this record's
 * source value through to the master record unexamined - a pass-through of
 * arbitrary ten-character text, not a constrained domain. The fixture census
 * corroborates it: bytes 23-32 hold exactly two distinct values across the 300
 * rows, 250 of {@code POS TERM} and 50 of {@code OPERATOR}, each padded to ten
 * characters, and the second of those has no literal assignment site anywhere in
 * the corpus - it arrives purely through the pass-through path. A closed enum
 * here would reject valid legacy data outright, which is why this is a Blocker.</p>
 *
 * <h2>Finding: BLOCKER - the amount is NUMERIC(11,2), and it is signed</h2>
 * <p>{@code DALYTRAN-AMT} at {@code :L10} is {@code PIC S9(09)V99}: nine integer
 * digits plus two decimal digits, so eleven digits of precision and a scale of
 * two. The three precision tiers in this package are distinct and must never be
 * collapsed into one another:</p>
 *
 * <pre>
 * COBOL PIC        SQL type         Fields at this tier
 * ---------------- ---------------- ----------------------------------------------
 * S9(10)V99        NUMERIC(12,2)    the five Account balance and cycle fields
 * S9(09)V99        NUMERIC(11,2)    TRAN-AMT, DALYTRAN-AMT, TRAN-CAT-BAL
 * S9(04)V99        NUMERIC(6,2)     DIS-INT-RATE
 * </pre>
 *
 * <p>Using the wrong tier is a Blocker in either direction: too narrow truncates
 * real values, too wide accepts values the source could never have held and so
 * lets a defect through undetected.</p>
 *
 * <p><strong>The sign is real and must survive untouched.</strong> A
 * zoned-decimal overpunch census of bytes 133-143 across all 300 fixture rows
 * gives 250 positive rows, whose trailing character is one of the positive
 * overpunches, and 50 negative rows, whose trailing character is one of the
 * negative ones; both zero-magnitude overpunches occur. The decode table is:</p>
 *
 * <pre>
 * {  -.  +0        A B C D E F G H I  -.  +1 +2 +3 +4 +5 +6 +7 +8 +9
 * }  -.  -0        J K L M N O P Q R  -.  -1 -2 -3 -4 -5 -6 -7 -8 -9
 * </pre>
 *
 * <p>Negative amounts are not an anomaly to be cleaned up; they drive a specific
 * legacy branch. {@code app/cbl/CBTRN02C.cbl:L548-L552} adds the amount to the
 * balance, then adds it to the cycle-credit accumulator when it is not negative
 * and, at {@code :L551}, to the cycle-<em>debit</em> accumulator otherwise. The
 * debit accumulator therefore legitimately holds negative values, and that is
 * exactly why the over-limit expression at {@code :L403-L405} subtracts it:
 * {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}.
 * No sign normalisation of any kind is performed in this file, no bean-validation
 * sign or lower-bound constraint is declared on the amount, and the expression
 * above is never rewritten into an algebraically tidier form.</p>
 *
 * <p><strong>Decimal discipline.</strong> {@code java.math.BigDecimal} is the
 * only numeric money type used; no primitive or boxed binary approximate real
 * type appears anywhere in this file, which the security-audit gate asserts by
 * inspection. Where rounding is ever applied to a value that originated here it
 * uses banker's rounding, {@code RoundingMode.HALF_EVEN}. Callers must compare
 * monetary values with {@code compareTo} and never with {@code equals}, because
 * {@code equals} on this type is scale-sensitive: {@code 2.0} and {@code 2.00}
 * are unequal under {@code equals} yet compare as identical. This entity's own
 * {@code equals} is keyed on the identifier alone, so it is unaffected.</p>
 *
 * <h2>Finding: HIGH - the card number is never rendered</h2>
 * <p>{@code toString} deliberately omits the card number, and no alternative
 * full-field rendering helper or masking helper exists on this class, so there is
 * no accidental route by which a card number could reach a log line, an exception
 * message or a stack trace. See that method's own documentation for the exact
 * field list. The merchant name, city and postal code are omitted as well - not
 * because they are sensitive, but because they are bulk noise in a log record.</p>
 *
 * <h2>Finding: MEDIUM - no shared supertype with the master transaction entity,
 * despite the near-identical shape</h2>
 * <p>The master transaction entity, derived from {@code app/cpy/CVTRA05Y.cpy},
 * has the same thirteen fields in the same order at the same widths, differing
 * only in the COBOL field-name prefix. Extracting a mapped superclass, an
 * abstract base entity, a shared interface or a mapper class is nonetheless
 * rejected, and this decision is recorded here so it is not "tidied up" later.</p>
 * <p>The two tables are not the same kind of thing. This one is a
 * <em>staging</em> table: rows arrive from an untrusted flat file, may reference
 * master rows that do not exist, are consumed once by the posting job, and carry
 * no optimistic-locking version column. The master table is a system of record:
 * its rows are validated, referentially sound, permanent, and do carry a version
 * column. A shared supertype would couple those two lifecycles together and make
 * every future change to either one a change to both. Duplication of a field list
 * is the cheaper of the two costs.</p>
 *
 * <h2>Mapping decisions and their evidence</h2>
 * <ul>
 *   <li><strong>No optimistic-locking version column.</strong> Rows are written
 *       once by the loader and read by the posting job; there is no concurrent
 *       editor to guard against. Only four entities in this package carry such a
 *       column and this is not one of them.</li>
 *   <li><strong>No association mapping of any kind</strong> - no many-to-one,
 *       one-to-many or one-to-one mapping, no join column, and no propagation of
 *       persistence operations to related rows. A staging row may legitimately
 *       reference a card or an account that does not exist, so an association
 *       would make exactly the invalid rows the posting job needs to see and
 *       reject impossible to load. As a direct consequence <strong>this table
 *       carries no foreign-key constraint expectation</strong>: the ten
 *       foreign keys created by the first migration do not include one from this
 *       staging table. A second benefit is that, with no association to walk,
 *       there is no lazy-versus-eager choice to get wrong and no N-plus-one query
 *       pattern reachable from here.</li>
 *   <li><strong>No generated-identifier strategy and no database sequence.</strong>
 *       Staging identifiers arrive from the input file verbatim, and
 *       {@code app/cbl/CBTRN02C.cbl:L425} moves the record's own identifier
 *       straight into the master record. Generating one would destroy the
 *       correspondence between input file and row.</li>
 *   <li><strong>Every column is NOT NULL, and every text column accepts a
 *       blank-but-non-null value.</strong> A fixed-width record has no concept of
 *       absence: an unpopulated field is spaces or zeros, never nothing. No
 *       emptiness constraint is declared anywhere.</li>
 *   <li><strong>The JDK serialization marker interface is deliberately not
 *       implemented.</strong> Insecure deserialization is a known risky pattern,
 *       and this type holds untrusted content, so no serialization channel into
 *       it is opened at all.</li>
 *   <li><strong>The text columns declare their JDBC type explicitly.</strong>
 *       Schema validation is strict, and a fixed-width column really is
 *       fixed-width. Declaring the SQL type without also declaring the JDBC type
 *       code was verified to fail against a real PostgreSQL 16 database with
 *       "wrong column type encountered in column [dalytran_id] ... found [bpchar
 *       (Types#CHAR)], but expecting [char(16) (Types#VARCHAR)]", because the
 *       default mapping for a text field resolves to a variable-length code.
 *       Both are therefore declared on every text column. The annotations used
 *       come from the persistence provider already on the compile classpath; no
 *       dependency is added by this file.</li>
 *   <li><strong>No global mutable state.</strong> The class declares no static
 *       field of any kind - no counter, no cache, no shared buffer - so instances
 *       are independent and nothing here is shared across threads.</li>
 *   <li><strong>No inheritance, no large-object mapping, and no permissive
 *       default.</strong> Every column states its nullability, length and, where
 *       numeric, its precision and scale explicitly rather than relying on a
 *       provider default.</li>
 * </ul>
 *
 * <h2>Error modes</h2>
 * <p>This entity throws nothing of its own. Accessors are plain field access and
 * cannot fail. Where an argument check is ever warranted it raises
 * {@code java.lang.IllegalArgumentException} naming the offending field; the
 * project exception hierarchy is deliberately not reachable from this package,
 * because a data holder that depended on it would invert the intended layering.
 * The realistic failure modes are therefore external to the class:</p>
 * <ul>
 *   <li><em>Application context fails to start</em> with a wrong-column-type or
 *       missing-column report - the schema does not match the contract below.
 *       Fix the migration, never this entity, unless the copybook itself
 *       disagrees.</li>
 *   <li><em>A value arrives truncated</em> - the fixed-width reader is using the
 *       wrong offsets. Compare against the offset map above, which is normative.</li>
 *   <li><em>Amounts load as positive when the fixture says otherwise</em> - the
 *       seed or reader is doing a naive text conversion instead of position-aware
 *       overpunch decoding. See the decode table above.</li>
 * </ul>
 *
 * <h2>Build, test and troubleshooting</h2>
 * <p>Build with {@code mvn -B clean compile}; run the unit suite with
 * {@code mvn -B clean test}. Compilation is strict - all lint categories are
 * enabled and warnings are errors - so an unused import or an unnecessary cast
 * fails the build rather than being reported. Tests covering this entity live in
 * {@code src/test/java/com/cardemo/unit/model} and assert the 350-byte geometry
 * and offset map, that the two TS fields are declared as text of length 26, that
 * a blank 26-space process TS persists and reloads unchanged, that the amount is
 * eleven digits at scale two, and that a negative amount round-trips with its
 * sign intact. Repository and batch tiers additionally exercise the round trip
 * against a containerised PostgreSQL 16 instance; a reachable container runtime
 * is a prerequisite for those, and their absence is a prerequisite failure rather
 * than a defect in this class.</p>
 *
 * <p><strong>Key configuration and defaults affecting this class.</strong> Three
 * settings govern its runtime behaviour, and none of them lives here:
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile, so
 * the schema is never altered by the application and any disagreement with the
 * contract below is fatal at startup; the Hibernate JDBC zone is UTC, which
 * affects genuinely temporal columns elsewhere in the schema but by construction
 * cannot affect either of this table's two text TS columns; and the migration
 * tool owns the schema outright, so the correct response to a validation report
 * is always to change the migration. Within the class itself nothing is left to a
 * default: every column states its name, nullability, length or precision and
 * scale, and SQL type explicitly, precisely so that no provider default can
 * silently differ from the copybook.</p>
 *
 * <h2>Not available - the schema this entity must agree with</h2>
 * <p><strong>Not available:</strong> {@code V1__create_schema.sql} did not exist
 * when this entity was authored. The directory
 * {@code src/main/resources/db/migration} was verified to have no children at
 * that point, so there was no schema definition to conform to and none to check
 * this mapping against. Because {@code spring.jpa.hibernate.ddl-auto: validate}
 * is set in every profile, any mismatch of column name, SQL type, precision,
 * scale or nullability fails application-context startup outright rather than
 * degrading quietly - so the two artefacts must agree exactly.</p>
 *
 * <p><strong>The field table above is therefore the normative column contract,
 * and the migration must converge upon it.</strong> What is needed is table
 * {@code daily_transaction} with:</p>
 *
 * <pre>
 * dalytran_id             CHAR(16)        PRIMARY KEY
 * dalytran_type_cd        CHAR(2)         NOT NULL
 * dalytran_cat_cd         NUMERIC(4)      NOT NULL
 * dalytran_source         CHAR(10)        NOT NULL
 * dalytran_desc           CHAR(100)       NOT NULL
 * dalytran_amt            NUMERIC(11,2)   NOT NULL
 * dalytran_merchant_id    NUMERIC(9)      NOT NULL
 * dalytran_merchant_name  CHAR(50)        NOT NULL
 * dalytran_merchant_city  CHAR(50)        NOT NULL
 * dalytran_merchant_zip   CHAR(10)        NOT NULL
 * dalytran_card_num       CHAR(16)        NOT NULL
 * dalytran_orig_ts        CHAR(26)        NOT NULL
 * dalytran_proc_ts        CHAR(26)        NOT NULL
 * </pre>
 *
 * <p><strong>No version column. No foreign key.</strong> The table is seeded from
 * {@code app/data/ASCII/dailytran.txt} by {@code V3__seed_data.sql}, using
 * position-aware zoned-decimal overpunch decoding driven by the PIC clauses and
 * the decode table given earlier. Position-awareness is not optional: a naive
 * text load produces wrong values, and the very same letters occur legitimately
 * inside merchant-name text, so the decoder must key on the field position
 * established by the offset map rather than on character appearance.</p>
 *
 * <p>For the avoidance of doubt about the surrounding migration set: the first
 * migration creates exactly eleven tables with ten foreign keys and five check
 * constraints, of which this table contributes one table, no foreign key and no
 * check constraint. The batch framework's own bookkeeping tables come from the
 * framework's supplied script - never a fourth migration, and never additional
 * tables smuggled into the first one.</p>
 */
@Entity
@Table(name = "daily_transaction")
public class DailyTransaction {

    /** {@code DALYTRAN-ID}, {@code PIC X(16)}, bytes 1-16. Primary key, taken verbatim from the input file. */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_id", nullable = false, length = 16, columnDefinition = "CHAR(16)")
    private String transactionId;

    /** {@code DALYTRAN-TYPE-CD}, {@code PIC X(02)}, bytes 17-18. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_type_cd", nullable = false, length = 2, columnDefinition = "CHAR(2)")
    private String typeCode;

    /** {@code DALYTRAN-CAT-CD}, {@code PIC 9(04)}, bytes 19-22. Unsigned four-digit code. */
    @Column(name = "dalytran_cat_cd", nullable = false, precision = 4, scale = 0, columnDefinition = "NUMERIC(4)")
    private Integer categoryCode;

    /** {@code DALYTRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32. Free text, never a closed domain. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_source", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String transactionSource;

    /** {@code DALYTRAN-DESC}, {@code PIC X(100)}, bytes 33-132. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_desc", nullable = false, length = 100, columnDefinition = "CHAR(100)")
    private String description;

    /** {@code DALYTRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143. Signed; negative values are valid. */
    @Column(name = "dalytran_amt", nullable = false, precision = 11, scale = 2, columnDefinition = "NUMERIC(11,2)")
    private BigDecimal amount;

    /** {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)}, bytes 144-152. Unsigned nine-digit code. */
    @Column(name = "dalytran_merchant_id", nullable = false, precision = 9, scale = 0,
            columnDefinition = "NUMERIC(9)")
    private Long merchantId;

    /** {@code DALYTRAN-MERCHANT-NAME}, {@code PIC X(50)}, bytes 153-202. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_merchant_name", nullable = false, length = 50, columnDefinition = "CHAR(50)")
    private String merchantName;

    /** {@code DALYTRAN-MERCHANT-CITY}, {@code PIC X(50)}, bytes 203-252. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_merchant_city", nullable = false, length = 50, columnDefinition = "CHAR(50)")
    private String merchantCity;

    /** {@code DALYTRAN-MERCHANT-ZIP}, {@code PIC X(10)}, bytes 253-262. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_merchant_zip", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String merchantZip;

    /** {@code DALYTRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278. Never rendered by {@code toString}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_card_num", nullable = false, length = 16, columnDefinition = "CHAR(16)")
    private String cardNumber;

    /** {@code DALYTRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304. Carried as text, never parsed. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_orig_ts", nullable = false, length = 26, columnDefinition = "CHAR(26)")
    private String origTs;

    /** {@code DALYTRAN-PROC-TS}, {@code PIC X(26)}, bytes 305-330. Blank in every fixture row; must load. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_proc_ts", nullable = false, length = 26, columnDefinition = "CHAR(26)")
    private String procTs;

    /**
     * No-argument constructor required by the persistence provider so that it can
     * create a row object before populating it.
     *
     * <p>Visibility is {@code protected} rather than {@code public} on purpose: the
     * provider and any subclass can reach it, while application code cannot create
     * a half-populated staging row by accident. Application code uses the
     * all-columns constructor instead, which cannot leave a column unset.</p>
     *
     * <p>Every field is left at its default until the provider assigns it. That is
     * safe here and only here, because the provider always follows this call with a
     * complete field population from the result set. It performs no work, has no
     * side effect and cannot fail.</p>
     */
    protected DailyTransaction() {
        // Intentionally empty: the persistence provider populates every field
        // immediately after this call returns. No default value is invented here,
        // because inventing one would mask a column the provider failed to set.
    }

    /**
     * Creates a fully populated staging row from one 350-byte
     * {@code DALYTRAN-RECORD} image.
     *
     * <p>The parameter order is the COBOL field order of
     * {@code app/cpy/CVTRA06Y.cpy:L5-L17}, so a caller reading the record
     * left to right supplies the arguments top to bottom with no reordering. The
     * 20-byte {@code FILLER} at {@code :L18} has no parameter because it carries
     * no data.</p>
     *
     * <p>All thirteen values are stored exactly as supplied. Nothing is trimmed,
     * padded, upper-cased, re-signed, rounded or re-formatted, because every one of
     * those transformations would break byte-level parity with the source record.
     * In particular a blank process TS and a negative amount are both stored
     * unchanged, since the fixture proves both are legitimate.</p>
     *
     * <p>This constructor performs only direct field assignment. It calls no
     * method on the instance under construction, so no partially initialised
     * reference can escape, and it has no side effect beyond populating this
     * object. It throws nothing.</p>
     *
     * @param transactionId     {@code DALYTRAN-ID}, {@code PIC X(16)}, bytes 1-16;
     *                          the primary key, taken verbatim from the input file
     * @param typeCode          {@code DALYTRAN-TYPE-CD}, {@code PIC X(02)}, bytes 17-18
     * @param categoryCode      {@code DALYTRAN-CAT-CD}, {@code PIC 9(04)}, bytes 19-22
     * @param transactionSource {@code DALYTRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32;
     *                          free ten-character text, not a closed domain
     * @param description       {@code DALYTRAN-DESC}, {@code PIC X(100)}, bytes 33-132
     * @param amount            {@code DALYTRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143;
     *                          signed, and a negative value is valid input
     * @param merchantId        {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)}, bytes 144-152
     * @param merchantName      {@code DALYTRAN-MERCHANT-NAME}, {@code PIC X(50)}, bytes 153-202
     * @param merchantCity      {@code DALYTRAN-MERCHANT-CITY}, {@code PIC X(50)}, bytes 203-252
     * @param merchantZip       {@code DALYTRAN-MERCHANT-ZIP}, {@code PIC X(10)}, bytes 253-262
     * @param cardNumber        {@code DALYTRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278;
     *                          never rendered by {@code toString}
     * @param origTs            {@code DALYTRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304;
     *                          26 characters of text, never parsed
     * @param procTs            {@code DALYTRAN-PROC-TS}, {@code PIC X(26)}, bytes 305-330;
     *                          26 characters of text, blank in every fixture row
     */
    public DailyTransaction(
            final String transactionId,
            final String typeCode,
            final Integer categoryCode,
            final String transactionSource,
            final String description,
            final BigDecimal amount,
            final Long merchantId,
            final String merchantName,
            final String merchantCity,
            final String merchantZip,
            final String cardNumber,
            final String origTs,
            final String procTs) {
        this.transactionId = transactionId;
        this.typeCode = typeCode;
        this.categoryCode = categoryCode;
        this.transactionSource = transactionSource;
        this.description = description;
        this.amount = amount;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantZip = merchantZip;
        this.cardNumber = cardNumber;
        this.origTs = origTs;
        this.procTs = procTs;
    }

    /**
     * Returns {@code DALYTRAN-ID}, {@code PIC X(16)}, bytes 1-16 of the record.
     *
     * <p>The primary key. Sixteen characters of digits as written by the upstream
     * system; it is a fixed-width identifier, not a number, so it is never
     * converted to an integral type - leading zeros are significant.</p>
     *
     * @return the sixteen-character staging identifier, exactly as loaded
     */
    public String getTransactionId() {
        return this.transactionId;
    }

    /**
     * Sets {@code DALYTRAN-ID}, {@code PIC X(16)}, bytes 1-16 of the record.
     *
     * <p>Stored verbatim. No generated-identifier strategy applies to this column:
     * the value arrives from the input file and must correspond to it byte for
     * byte, which {@code app/cbl/CBTRN02C.cbl:L425} relies on when it moves the
     * identifier into the master record.</p>
     *
     * @param transactionId the sixteen-character staging identifier
     */
    public void setTransactionId(final String transactionId) {
        this.transactionId = transactionId;
    }

    /**
     * Returns {@code DALYTRAN-TYPE-CD}, {@code PIC X(02)}, bytes 17-18 of the record.
     *
     * @return the two-character transaction type code, exactly as loaded
     */
    public String getTypeCode() {
        return this.typeCode;
    }

    /**
     * Sets {@code DALYTRAN-TYPE-CD}, {@code PIC X(02)}, bytes 17-18 of the record.
     *
     * <p>Stored verbatim. The value is not checked against the transaction-type
     * table here; the posting job performs that lookup and rejects the record if it
     * fails, which is why an unknown code must still be loadable.</p>
     *
     * @param typeCode the two-character transaction type code
     */
    public void setTypeCode(final String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns {@code DALYTRAN-CAT-CD}, {@code PIC 9(04)}, bytes 19-22 of the record.
     *
     * <p>An unsigned four-digit code. It is integral rather than textual because
     * the PIC clause carries no sign and no character positions beyond the digits.</p>
     *
     * @return the four-digit transaction category code, exactly as loaded
     */
    public Integer getCategoryCode() {
        return this.categoryCode;
    }

    /**
     * Sets {@code DALYTRAN-CAT-CD}, {@code PIC 9(04)}, bytes 19-22 of the record.
     *
     * <p>Stored verbatim. As with the type code, validity is the posting job's
     * concern, not this class's.</p>
     *
     * @param categoryCode the four-digit transaction category code
     */
    public void setCategoryCode(final Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

    /**
     * Returns {@code DALYTRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32 of the record.
     *
     * <p>Ten characters of free text describing where the transaction originated.
     * This is deliberately not a closed domain: {@code app/cbl/CBTRN02C.cbl:L428}
     * passes the value straight through unexamined, and the fixture contains a
     * value that has no literal assignment site anywhere in the corpus.</p>
     *
     * @return the ten-character origin text, exactly as loaded
     */
    public String getTransactionSource() {
        return this.transactionSource;
    }

    /**
     * Sets {@code DALYTRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32 of the record.
     *
     * <p>Stored verbatim as text. Any value of ten characters or fewer is accepted,
     * because constraining this column to the values the corpus happens to assign
     * would reject legacy data that the source system accepts.</p>
     *
     * @param transactionSource the ten-character origin text
     */
    public void setTransactionSource(final String transactionSource) {
        this.transactionSource = transactionSource;
    }

    /**
     * Returns {@code DALYTRAN-DESC}, {@code PIC X(100)}, bytes 33-132 of the record.
     *
     * @return the hundred-character description, exactly as loaded
     */
    public String getDescription() {
        return this.description;
    }

    /**
     * Sets {@code DALYTRAN-DESC}, {@code PIC X(100)}, bytes 33-132 of the record.
     *
     * <p>Stored verbatim, including any trailing spaces, which are part of the
     * fixed-width image rather than incidental padding to be trimmed away.</p>
     *
     * @param description the hundred-character description
     */
    public void setDescription(final String description) {
        this.description = description;
    }

    /**
     * Returns {@code DALYTRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143 of the
     * record: eleven digits of precision at a scale of two.
     *
     * <p><strong>The value is signed and may be negative.</strong> Fifty of the 300
     * fixture rows are negative, and that is not a data-quality problem: a negative
     * amount is what drives the cycle-debit branch at
     * {@code app/cbl/CBTRN02C.cbl:L551}, which is in turn why the over-limit
     * expression at {@code :L403-L405} subtracts the debit accumulator. Callers must
     * not normalise the sign.</p>
     *
     * <p>Compare the returned value with {@code compareTo} and never with
     * {@code equals}: {@code equals} on this type is scale-sensitive, so a value
     * loaded at scale two will not equal an otherwise identical literal written at a
     * different scale. Any rounding a caller applies must use
     * {@code RoundingMode.HALF_EVEN}.</p>
     *
     * @return the signed transaction amount at scale two, exactly as loaded
     */
    public BigDecimal getAmount() {
        return this.amount;
    }

    /**
     * Sets {@code DALYTRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143 of the record.
     *
     * <p>Stored verbatim. The sign is preserved exactly as supplied: this method
     * performs no sign normalisation, no magnitude conversion and no rescaling,
     * because each of those would silently change a posted balance.</p>
     *
     * @param amount the signed transaction amount, negative values included
     */
    public void setAmount(final BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Returns {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)}, bytes 144-152 of the
     * record.
     *
     * <p>An unsigned nine-digit code. It is integral because the PIC clause is
     * unsigned numeric, and it is widened to a long rather than an int because nine
     * digits reach 999,999,999, which is close enough to the int ceiling that a
     * later widening of the field would be a breaking change.</p>
     *
     * @return the nine-digit merchant code, exactly as loaded
     */
    public Long getMerchantId() {
        return this.merchantId;
    }

    /**
     * Sets {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)}, bytes 144-152 of the
     * record.
     *
     * <p>Stored verbatim.</p>
     *
     * @param merchantId the nine-digit merchant code
     */
    public void setMerchantId(final Long merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns {@code DALYTRAN-MERCHANT-NAME}, {@code PIC X(50)}, bytes 153-202 of
     * the record.
     *
     * <p>Note for anyone writing a decoder over the raw record: this text field can
     * legitimately contain the very letters that act as sign overpunches in the
     * numeric fields, which is exactly why overpunch decoding must be driven by
     * field position rather than by character appearance.</p>
     *
     * @return the fifty-character merchant name, exactly as loaded
     */
    public String getMerchantName() {
        return this.merchantName;
    }

    /**
     * Sets {@code DALYTRAN-MERCHANT-NAME}, {@code PIC X(50)}, bytes 153-202 of the
     * record.
     *
     * <p>Stored verbatim.</p>
     *
     * @param merchantName the fifty-character merchant name
     */
    public void setMerchantName(final String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns {@code DALYTRAN-MERCHANT-CITY}, {@code PIC X(50)}, bytes 203-252 of
     * the record.
     *
     * @return the fifty-character merchant city, exactly as loaded
     */
    public String getMerchantCity() {
        return this.merchantCity;
    }

    /**
     * Sets {@code DALYTRAN-MERCHANT-CITY}, {@code PIC X(50)}, bytes 203-252 of the
     * record.
     *
     * <p>Stored verbatim.</p>
     *
     * @param merchantCity the fifty-character merchant city
     */
    public void setMerchantCity(final String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns {@code DALYTRAN-MERCHANT-ZIP}, {@code PIC X(10)}, bytes 253-262 of the
     * record.
     *
     * <p>Ten characters of text, not a number: the field is declared alphanumeric so
     * that it can hold postal codes with leading zeros or non-digit characters.</p>
     *
     * @return the ten-character merchant postal code, exactly as loaded
     */
    public String getMerchantZip() {
        return this.merchantZip;
    }

    /**
     * Sets {@code DALYTRAN-MERCHANT-ZIP}, {@code PIC X(10)}, bytes 253-262 of the
     * record.
     *
     * <p>Stored verbatim.</p>
     *
     * @param merchantZip the ten-character merchant postal code
     */
    public void setMerchantZip(final String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns {@code DALYTRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278 of the
     * record.
     *
     * <p><strong>Handle with care.</strong> This is the only sensitive value on the
     * record. It is deliberately excluded from {@code toString}, so a caller that
     * obtains it here becomes responsible for keeping it out of log records,
     * exception messages and any serialised response. There is no masking helper on
     * this class by design; masking belongs to the layer that renders output, not to
     * the data holder.</p>
     *
     * <p>Sixteen characters of digits, kept as text because leading zeros are
     * significant and the value is an identifier rather than a quantity.</p>
     *
     * @return the sixteen-character card number, exactly as loaded
     */
    public String getCardNumber() {
        return this.cardNumber;
    }

    /**
     * Sets {@code DALYTRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278 of the
     * record.
     *
     * <p>Stored verbatim. The value is not checked against the card cross-reference
     * here: an unknown card number is precisely what the posting job detects, and it
     * assigns reject code 100 when it does, so such a row must remain loadable.</p>
     *
     * @param cardNumber the sixteen-character card number
     */
    public void setCardNumber(final String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /**
     * Returns {@code DALYTRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304 of the
     * record.
     *
     * <p>Twenty-six characters of text, carried as text and never parsed. Every one
     * of the 300 fixture rows holds the same value, {@code 2022-06-10 19:27:53.000000},
     * whose shape - a space separator and six fraction digits - differs from the
     * shape the batch producer writes for the process field, so the two are not
     * interconvertible and neither may be normalised into the other.
     * {@code app/cbl/CBTRN02C.cbl:L436} moves this field straight across as text.</p>
     *
     * @return the twenty-six-character origination TS text, exactly as loaded
     */
    public String getOrigTs() {
        return this.origTs;
    }

    /**
     * Sets {@code DALYTRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304 of the record.
     *
     * <p>Stored verbatim as text. No format is enforced and no conversion is
     * attempted, so a blank or partially populated value is accepted.</p>
     *
     * @param origTs the twenty-six-character origination TS text
     */
    public void setOrigTs(final String origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns {@code DALYTRAN-PROC-TS}, {@code PIC X(26)}, bytes 305-330 of the
     * record.
     *
     * <p><strong>This value is blank on input.</strong> All 300 fixture rows hold
     * the same single value in bytes 305-330: twenty-six spaces. The field is filled
     * in downstream, not upstream - {@code app/cbl/CBTRN02C.cbl:L438} writes the
     * producer-formatted value into the master record at posting time, in the shape
     * documented at {@code :L149}, which is millisecond precision followed by four
     * literal zero characters as {@code :L700-L701} confirm.</p>
     *
     * <p>A blank is therefore the normal, expected state of this column on a staging
     * row, and it is the canonical explicit empty case for this entity. Callers must
     * not treat blank as missing or as an error, and must not attempt to convert it:
     * twenty-six spaces are not a valid date-and-time value in any format.</p>
     *
     * @return the twenty-six-character process TS text, typically all spaces
     */
    public String getProcTs() {
        return this.procTs;
    }

    /**
     * Sets {@code DALYTRAN-PROC-TS}, {@code PIC X(26)}, bytes 305-330 of the record.
     *
     * <p>Stored verbatim as text. A blank value of twenty-six spaces is explicitly
     * supported and round-trips through the database unchanged; this is asserted by
     * the unit and repository tests rather than assumed.</p>
     *
     * @param procTs the twenty-six-character process TS text, possibly all spaces
     */
    public void setProcTs(final String procTs) {
        this.procTs = procTs;
    }

    /**
     * Compares two staging rows on {@code DALYTRAN-ID} alone.
     *
     * <p>Identity is the primary key and nothing else, for three reasons. The key is
     * supplied by the input file rather than generated, so it is stable from the
     * moment the object exists and does not change when the row is persisted.
     * Comparing the remaining twelve columns as well would make two loads of the same
     * row unequal after any edit, which breaks collection membership. And a
     * field-by-field comparison would have to compare the amount, where the natural
     * equality of the decimal type is scale-sensitive and would report
     * {@code 2.0} and {@code 2.00} as different values - a subtle wrong answer rather
     * than an obvious one.</p>
     *
     * <p>Two rows with a null identifier are never equal unless they are the same
     * object, which the reference check at the top handles. This follows directly
     * from delegating to null-safe value comparison and is the correct behaviour for
     * an unsaved instance.</p>
     *
     * <p>Pattern matching is used rather than a class comparison so that the type
     * test and the cast cannot disagree. Note that a persistence provider may hand
     * back a generated subtype, which this test accepts.</p>
     *
     * @param other the object to compare against; may be null
     * @return {@code true} when {@code other} is a staging row with an equal
     *         {@code DALYTRAN-ID}, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DailyTransaction that)) {
            return false;
        }
        return Objects.equals(this.transactionId, that.transactionId);
    }

    /**
     * Returns a hash consistent with {@link #equals(Object)}, derived from
     * {@code DALYTRAN-ID} alone.
     *
     * <p>Because the identifier is assigned from the input file and never
     * regenerated, the hash is stable for the whole life of the object, including
     * across the transition from new to persistent. That is what makes it safe to
     * put a staging row into a hash-based collection before it is written.</p>
     *
     * @return the hash of the staging identifier, or zero when it is not yet set
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.transactionId);
    }

    /**
     * Returns a diagnostic rendering of this row that deliberately omits the card
     * number.
     *
     * <p><strong>Security restriction.</strong> The card number is the one sensitive
     * value on the record, and {@code toString} is the single most likely route by
     * which a field reaches a log record, an exception message or a stack trace -
     * often without the author intending it. It is therefore excluded outright
     * rather than masked, so that no formatting mistake can ever leak it. There is no
     * companion method on this class that renders the full field set, so this
     * exclusion cannot be bypassed from here.</p>
     *
     * <p>The merchant name, city and postal code are also omitted. They are not
     * sensitive, but together they add up to a hundred and ten characters of
     * low-value bulk that would dominate every log line in which a row appears. The
     * description is omitted for the same reason.</p>
     *
     * <p>What remains is exactly the seven values needed to identify a row and reason
     * about the posting decision taken on it: the identifier, the type and category
     * codes, the origin text, the signed amount, and the two TS values. The amount is
     * rendered by the decimal type's own conversion and the codes by their integral
     * ones, all of which are independent of locale, so the output is identical on
     * every machine. Both TS values are wrapped in brackets, because they are usually
     * space-padded and the process one is normally entirely blank; without a
     * delimiter a reader cannot tell a blank field from a missing one.</p>
     *
     * <p>The format is a diagnostic aid, not an interface. It is not parsed anywhere
     * and no caller should depend on its exact shape.</p>
     *
     * @return a rendering carrying the identifier, type code, category code, origin
     *         text, signed amount and both TS values, and never the card number
     */
    @Override
    public String toString() {
        return "DailyTransaction{transactionId=" + this.transactionId
                + ", typeCode=" + this.typeCode
                + ", categoryCode=" + this.categoryCode
                + ", transactionSource=" + this.transactionSource
                + ", amount=" + this.amount
                + ", origTs=[" + this.origTs + ']'
                + ", procTs=[" + this.procTs + ']'
                + '}';
    }
}
