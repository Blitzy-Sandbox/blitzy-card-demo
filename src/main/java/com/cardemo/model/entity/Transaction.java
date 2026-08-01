/*
 ******************************************************************
 * Program     : Transaction.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Posted transaction master. Replaces the VSAM KSDS cluster
 *               AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS - the keyed store behind
 *               the CICS file TRANSACT and behind the batch posting,
 *               interest, combine, statement and report jobs.
 * Source      : app/cpy/CVTRA05Y.cpy (350 B, key 16) @ 7756d89
 ******************************************************************
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
 ******************************************************************
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
 * Posted transaction master record.
 *
 * <h2>What this is</h2>
 * A pure data holder mapping one row of the relational table {@code transaction} onto one 350-byte
 * record of the VSAM KSDS cluster {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}. The record layout is
 * {@code app/cpy/CVTRA05Y.cpy}, whose header comment at {@code :L2} reads
 * "Data-structure for TRANsaction record (RECLN = 350)" and whose fields occupy {@code :L5-L18}.
 *
 * <p>The physical facts are catalogued, not inferred. {@code app/catlg/LISTCAT.txt:L3591} names the
 * cluster and {@code :L3593} reports {@code KEYLEN 16} with {@code AVGLRECL 350}. The adjacent
 * {@code :L3594} adds {@code RKP 0} and {@code MAXLRECL 350}: the key starts at byte 1 of the record
 * (so it is {@code TRAN-ID}), and because the average and maximum record lengths are both 350 the
 * record is fixed width rather than merely averaging 350.
 *
 * <h2>Responsibilities and non-responsibilities</h2>
 * This class holds state and nothing else. It performs no I/O, emits no log, starts no transaction,
 * validates no business rule, formats no timestamp and parses no text. Reading and writing are the
 * repository's concern; fixed-width encoding and decoding are the batch layer's concern; the offset
 * map below exists here because this is the one place where the field contract is normative, and the
 * batch writers and comparators are expected to consume it from here rather than re-derive it.
 *
 * <h2>The proven offset map</h2>
 * The offsets are load-bearing: they drive fixed-width emission in the batch writers and the
 * DFSORT-equivalent comparators, so they are reproduced here in full rather than left implicit.
 * Byte positions are 1-based and inclusive, exactly as DFSORT expresses them.
 *
 * <pre>
 *   #  COBOL field         Line  PIC       Bytes    Java field         Java type   Column              SQL type
 *   1  TRAN-ID             :L5   X(16)      1-16    transactionId      String      tran_id             CHAR(16)
 *   2  TRAN-TYPE-CD        :L6   X(02)     17-18    typeCode           String      tran_type_cd        CHAR(2)
 *   3  TRAN-CAT-CD         :L7   9(04)     19-22    categoryCode       Integer     tran_cat_cd         NUMERIC(4)
 *   4  TRAN-SOURCE         :L8   X(10)     23-32    transactionSource  String      tran_source         CHAR(10)
 *   5  TRAN-DESC           :L9   X(100)    33-132   description        String      tran_desc           CHAR(100)
 *   6  TRAN-AMT            :L10  S9(09)V99 133-143  amount             BigDecimal  tran_amt            NUMERIC(11,2)
 *   7  TRAN-MERCHANT-ID    :L11  9(09)     144-152  merchantId         Long        tran_merchant_id    NUMERIC(9)
 *   8  TRAN-MERCHANT-NAME  :L12  X(50)     153-202  merchantName       String      tran_merchant_name  CHAR(50)
 *   9  TRAN-MERCHANT-CITY  :L13  X(50)     203-252  merchantCity       String      tran_merchant_city  CHAR(50)
 *  10  TRAN-MERCHANT-ZIP   :L14  X(10)     253-262  merchantZip        String      tran_merchant_zip   CHAR(10)
 *  11  TRAN-CARD-NUM       :L15  X(16)     263-278  cardNumber         String      tran_card_num       CHAR(16)
 *  12  TRAN-ORIG-TS        :L16  X(26)     279-304  origTs             String      tran_orig_ts        CHAR(26)
 *  13  TRAN-PROC-TS        :L17  X(26)     305-330  procTs             String      tran_proc_ts        CHAR(26)
 *   -  FILLER              :L18  X(20)     331-350  NOT MODELLED       -           -                   -
 *   +  (none)              -     -         -        version            Long        version             BIGINT
 * </pre>
 *
 * Field 1 carries {@code @Id}; the trailing {@code version} column carries {@code @Version} and has no
 * COBOL counterpart. The arithmetic closes exactly:
 *
 * <pre>
 *   16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 = 330 populated bytes
 *                                                        + 20 FILLER = 350 bytes
 * </pre>
 *
 * {@code FILLER X(20)} at {@code :L18} is deliberately <em>not</em> modelled. It exists only to pad the
 * record out to the catalogued 350 bytes and carries no data - a census of all 300 rows of
 * {@code app/data/ASCII/dailytran.txt} finds bytes 331-350 blank in every row. Its 20 bytes are
 * recorded here so that a fixed-width writer can reproduce the record length without needing a column
 * for it.
 *
 * <h2>How the offset map was proven</h2>
 * Three independent sources agree, which is why the map is stated as fact rather than as a derivation:
 *
 * <ol>
 *   <li><strong>The copybook arithmetic.</strong> Accumulating the PIC widths of
 *       {@code app/cpy/CVTRA05Y.cpy:L5-L18} in declaration order produces the byte ranges above.</li>
 *   <li><strong>The DFSORT symbol definitions.</strong> {@code app/proc/TRANREPT.prc:L38} opens
 *       {@code //SYMNAMES DD *}, then {@code :L39} declares {@code TRAN-CARD-NUM,263,16,ZD} and
 *       {@code :L40} declares {@code TRAN-PROC-DT,305,10,CH}. Offsets 263 and 305 match rows 11 and 13
 *       exactly. Independently, {@code app/jcl/CREASTMT.JCL:L53} sorts
 *       {@code FIELDS=(263,16,CH,A,1,16,CH,A)} and {@code :L54} projects
 *       {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}, with {@code :L32} declaring
 *       {@code RECORDSIZE(350 350)}.</li>
 *   <li><strong>The fixture data.</strong> Every one of the 300 rows of
 *       {@code app/data/ASCII/dailytran.txt} is exactly 350 bytes (105,300 bytes total, 300 records of
 *       350 data bytes plus a newline), and every field parses cleanly at the offsets above.</li>
 * </ol>
 *
 * <h3>Medium: the two DFSORT declarations disagree about the card number, and the copybook wins</h3>
 * {@code app/proc/TRANREPT.prc:L39} types bytes 263-278 as {@code ZD} (zoned decimal) while
 * {@code app/jcl/CREASTMT.JCL:L53} types the same bytes as {@code CH} (character). The two legacy sort
 * decks contradict each other, so neither can settle the column type. <strong>The copybook is
 * authoritative</strong>: {@code TRAN-CARD-NUM} is declared {@code PIC X(16)} at
 * {@code app/cpy/CVTRA05Y.cpy:L15}, therefore the column is {@code CHAR(16)} and the Java type is
 * {@code String}. This is recorded explicitly so that nobody later "fixes" the column to a numeric
 * type on the strength of the {@code ZD} entry: a numeric column would drop leading zeros, and card
 * numbers are identifiers rather than quantities. Severity Medium - it is a real contradiction in the
 * source, but the resolution is unambiguous.
 *
 * <h2>Alternate index provenance (documentation, not a mapping)</h2>
 * {@code app/catlg/LISTCAT.txt:L3672} defines the alternate index
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} and {@code :L3674} reports {@code KEYLEN 26}. A 26-byte
 * alternate key can only be one of the two 26-byte fields, and {@code :L3676} settles which:
 * {@code AXRKP 304} is a zero-based displacement, so the key begins at 1-based byte 305, which is
 * {@code TRAN-PROC-TS}. That is why this class names the property {@code procTs} - a repository finder
 * derived from that property is the replacement for the alternate index. This is the third of the three
 * alternate indexes in the catalogue; {@code :L3938} reports {@code AIX 3} and {@code :L3946} reports
 * {@code PATH 3}.
 *
 * <p><strong>The alternate index is not an entity and is not mapped here.</strong> It becomes exactly
 * two things: a processing-timestamp finder on the transaction repository, and a <em>non-unique</em>
 * B-tree index on {@code tran_proc_ts} created by {@code V2__create_indexes.sql}. The non-uniqueness is
 * not a judgement call - {@code app/catlg/LISTCAT.txt:L3678} declares the alternate index
 * {@code NONUNIQKEY} in so many words, and the fixture bears that out, since all 300 staging rows share
 * a single processing-timestamp value. A unique index would reject legitimate legacy data.
 *
 * <h2>Blocker: the two timestamps are TEXT, never a temporal type</h2>
 * <strong>Severity Blocker.</strong> {@code app/cpy/CVTRA05Y.cpy:L16-L17} declares
 * {@code TRAN-ORIG-TS PIC X(26)} and {@code TRAN-PROC-TS PIC X(26)}. These are character fields. Both
 * are mapped here as {@code String} to {@code CHAR(26)}, and this file contains no
 * {@code LocalDateTime}, {@code LocalDate}, {@code Instant}, {@code OffsetDateTime},
 * {@code java.sql.Timestamp}, {@code @Temporal} or temporal {@code @Convert} anywhere.
 *
 * <p>The reason is not stylistic. Three mutually incompatible producers write these columns, so no
 * single temporal type or format can serve all of them:
 *
 * <ol>
 *   <li><strong>Batch generated.</strong> {@code app/cbl/CBTRN02C.cbl:L438} moves
 *       {@code DB2-FORMAT-TS} into {@code TRAN-PROC-TS}. That value is built by
 *       {@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code :L692-L705} over the {@code X(26)} field declared
 *       at {@code :L159}, whose tail is {@code DB2-MIL PIC 9(002)} at {@code :L173} and
 *       {@code DB2-REST PIC X(04)} at {@code :L174}, assigned by {@code :L700}
 *       {@code MOVE COB-MIL TO DB2-MIL} and {@code :L701} {@code MOVE '0000' TO DB2-REST}. The format
 *       comment at {@code :L149} reads {@code EEEE-MM-DD-UU.MM.SS.HH0000}, that is
 *       {@code yyyy-MM-dd-HH.mm.ss.SS0000}: dash separated, millisecond precision, then four literal
 *       zeros.</li>
 *   <li><strong>Online generated.</strong> The bill-payment path writes
 *       {@code yyyy-MM-dd HH:mm:ss.000000} - space separator, six-digit fraction - into
 *       <em>both</em> columns.</li>
 *   <li><strong>Pure text pass-through.</strong> {@code app/cbl/CBTRN02C.cbl:L436} moves
 *       {@code DALYTRAN-ORIG-TS} straight into {@code TRAN-ORIG-TS}, copying whatever text arrived
 *       without examining it.</li>
 * </ol>
 *
 * <p>A census of {@code app/data/ASCII/dailytran.txt} makes the consequence concrete. All 300 rows
 * carry the text {@code 2022-06-10 19:27:53.000000} in bytes 279-304 - the space-separated form of
 * producer 2, not the dash-separated form of producer 1 - and all 300 rows carry a
 * <strong>blank 26-space</strong> value in bytes 305-330. A 26-space blank is not a parseable timestamp
 * in any format, and the two populated formats are not interconvertible without loss. A temporal type
 * here would therefore fail end-to-end boundary parity <em>and</em> would be unable to load the
 * fixture at all.
 *
 * <p>Consequences that follow from that decision and are enforced here: no format validation, no
 * {@code @Pattern}, and no parsing helper method on this class. Where a timestamp is
 * <em>generated</em> - in the batch layer, never here - it must be formatted to millisecond precision
 * followed by four zeros, exactly as {@code :L700-L701} does, and never to nanosecond precision, since
 * a 26-character field cannot hold nine fractional digits and a differently padded tail is a byte-level
 * diff against the baseline. Because these columns are text, this entity is entirely insulated from JVM
 * time-zone drift; that is a property worth keeping alongside the project-wide setting that the
 * Hibernate JDBC time zone is UTC.
 *
 * <h2>Blocker: the amount is NUMERIC(11,2), not NUMERIC(12,2)</h2>
 * <strong>Severity Blocker.</strong> {@code TRAN-AMT} is {@code PIC S9(09)V99} at
 * {@code app/cpy/CVTRA05Y.cpy:L10} - nine integer digits plus two decimal digits - so the column is
 * {@code NUMERIC(11,2)} and the mapping declares {@code precision = 11, scale = 2}. Three distinct
 * precision tiers exist across this package and collapsing them is a real and easy mistake:
 *
 * <pre>
 *   COBOL PIC     SQL type        Fields
 *   S9(10)V99     NUMERIC(12,2)   the five Account money and cycle fields
 *   S9(09)V99     NUMERIC(11,2)   TRAN-AMT (this class), DALYTRAN-AMT, TRAN-CAT-BAL
 *   S9(04)V99     NUMERIC(6,2)    DIS-INT-RATE
 * </pre>
 *
 * Choosing the wrong tier fails application-context startup outright wherever
 * {@code ddl-auto: validate} is in force, and where it does not fail it produces silent scale
 * divergence in money - which is worse, because nothing reports it.
 *
 * <p><strong>Decimal discipline.</strong> {@code java.math.BigDecimal} is the only numeric type used
 * for money here; this file contains no {@code float} and no {@code double} anywhere, which the
 * security-audit gate asserts by inspection. Any rounding performed on a value read from this class
 * must use {@code RoundingMode.HALF_EVEN}. Monetary values must be compared with
 * {@code compareTo} and never with {@code equals}: {@code BigDecimal.equals} is scale-sensitive, so a
 * value of 2.0 is not equal to a value of 2.00 even though they are the same amount of money. That is
 * also why {@link #equals(Object)} on this class compares the primary key only and never the amount.
 *
 * <p><strong>The amount is signed and may legitimately be negative.</strong> A census of the 300 rows
 * of {@code app/data/ASCII/dailytran.txt} finds 250 rows with a positive zoned-decimal overpunch sign
 * and 50 rows with a negative one, and no row with anything else. Negative amounts are not errors: they
 * drive the cycle-debit branch at {@code app/cbl/CBTRN02C.cbl:L551}: the sign test at {@code :L548}
 * routes a non-negative amount to the cycle credit, and the {@code ELSE} at {@code :L551} adds the
 * negative amount to the cycle <em>debit</em> accumulator. That accumulator legitimately holds negative
 * values, which is precisely why the over-limit formula elsewhere subtracts it. Consequently this field
 * carries no {@code @Positive}, no {@code @PositiveOrZero} and no {@code @Min}, and neither this class
 * nor anything reading it may apply {@code abs()}, {@code negate()} or any other absolute-value
 * normalisation.
 *
 * <h2>Blocker: the source column is a String, never the TransactionSource enum</h2>
 * <strong>Severity Blocker.</strong> {@code transactionSource} is a {@code String} mapped to
 * {@code CHAR(10)}. Although a {@code TransactionSource} enum exists in the sibling
 * {@code com.cardemo.model.enums} package, it is deliberately <em>not</em> the persisted type of this
 * column, this file does not import it, and the field carries no {@code @Enumerated} and no converter.
 *
 * <p>The reason is that the column is not a closed set in the source. Of the assignment sites in the
 * corpus, some are pass-throughs of arbitrary ten-character text: {@code app/cbl/CBTRN02C.cbl:L428}
 * moves {@code DALYTRAN-SOURCE} into {@code TRAN-SOURCE} without examining it, so any ten characters
 * that reach the staging record reach this column. A census of bytes 23-32 of the 300 rows of
 * {@code app/data/ASCII/dailytran.txt} yields exactly two values, 250 rows of {@code "POS TERM  "} and
 * 50 rows of {@code "OPERATOR  "}, and {@code OPERATOR} has no literal {@code MOVE} site anywhere in
 * the corpus - it arrives purely through that pass-through path. An enum-typed column would reject
 * valid legacy data outright, and one fifth of the reference fixture is exactly such data.
 *
 * <h2>High: the card number must never be emitted</h2>
 * <strong>Severity High.</strong> {@link #toString()} deliberately omits {@code cardNumber}. A
 * primary account number is exactly the kind of value that must not reach a log line, and log masking
 * is a backstop rather than a primary defence - never emitting it in the first place is the primary
 * defence. The merchant name, city and postal code are omitted too: they are not secret, but they add
 * no diagnostic value and would inflate every log line that renders a transaction. No
 * {@code toDebugString}, {@code toFullString} or masking helper is provided, because such a method is
 * an invitation to route the card number into a log by accident.
 *
 * <p>This class also does not implement {@code java.io.Serializable}. Java serialization is a
 * deserialization-gadget surface with no use here - the entity crosses process boundaries as JSON via
 * a DTO, never as a serialized object graph.
 *
 * <h2>Optimistic locking: necessary but not sufficient</h2>
 * The {@code version} column is the store-level optimistic guard. It detects that <em>some</em>
 * concurrent write occurred, and that is all it detects. It is <strong>not</strong> a substitute for the
 * business-level change detection the legacy account-update path performs: that path compares specific
 * business field values against a snapshot captured when the screen was first populated, so it detects
 * <em>which</em> fields changed and in what representation. The two guarantees differ - a concurrent
 * write that set a field back to its original value passes the legacy check and fails a version check.
 * The explicit field-by-field snapshot comparison is carried by {@code AccountUpdateRequest} in
 * {@code com.cardemo.model.dto}, whose source is {@code app/cbl/COACTUPC.cbl:L4109-L4193}. Both layers
 * are required and neither substitutes for the other.
 *
 * <p>Note also what is deliberately absent: {@code transactionId} carries no {@code @GeneratedValue}
 * and no database sequence. Identifiers are generated upstream by a descending-key browse of the
 * maximum key plus one - an inherently racy algorithm retained deliberately for parity - and a
 * collision is meant to surface as a duplicate-key violation against this table's primary key.
 * Introducing a sequence would change the generated values and break comparison against the legacy
 * baseline.
 *
 * <h2>Deliberate structural choices</h2>
 * <ul>
 *   <li><strong>No JPA associations.</strong> There is no {@code @ManyToOne}, {@code @OneToMany},
 *       {@code @OneToOne}, {@code @JoinColumn} or cascade anywhere. {@code cardNumber},
 *       {@code typeCode} and {@code categoryCode} are plain scalar columns even though the schema
 *       declares foreign keys over them. The legacy corpus performs explicit keyed reads, so modelling
 *       associations would add lazy-loading proxies and an N+1 hazard while buying nothing; zero
 *       associations means zero N+1 by construction. The performance mechanism for the
 *       processing-timestamp access path is the B-tree index in {@code V2__create_indexes.sql}, not an
 *       association.</li>
 *   <li><strong>No inheritance.</strong> There is no base entity, no {@code @MappedSuperclass} and no
 *       auditable superclass. In particular, although the {@code DailyTransaction} staging entity has
 *       an almost identical shape, <strong>the two share no superclass and no common interface</strong>
 *       and must not be made to. They are different things that happen to look alike: one is the posted
 *       master with a version column and an alternate-index access path, the other is a staging image of
 *       an input dataset. Extracting a common parent would couple the two and would let a change made
 *       for staging reasons silently alter the master contract.</li>
 *   <li><strong>No global mutable state.</strong> There is no static mutable field of any kind. The
 *       interest job's run-sequential identifier suffix counter, {@code WS-TRANID-SUFFIX} at
 *       {@code app/cbl/CBACT04C.cbl:L173} (incremented at {@code :L474}, consumed at {@code :L477}),
 *       is COBOL working storage and belongs to that job's step scope - never to this entity.</li>
 *   <li><strong>Blank but non-null text is valid.</strong> Every text column accepts a
 *       blank-but-non-null value; the 26-space {@code procTs} of the reference fixture is the canonical
 *       case. There is therefore no {@code @NotBlank} and no {@code @NotEmpty} anywhere in this class.
 *       Null is what is rejected, by {@code nullable = false} on every column.</li>
 *   <li><strong>No error handling of its own.</strong> The entity throws nothing on its own behalf.
 *       Argument checks, where present, use {@code java.lang.IllegalArgumentException} naming the
 *       offending field; importing the project exception hierarchy from a model class is deliberately
 *       avoided so that the model layer depends on nothing.</li>
 * </ul>
 *
 * <h2>Blocker: fixed-width CHAR columns need an explicit JDBC type code</h2>
 * <strong>Severity Blocker.</strong> The field table mandates {@code CHAR(n)} columns and every profile
 * sets {@code ddl-auto: validate}. Those two facts together force a third one that is easy to miss:
 * Hibernate maps a {@code String} attribute to JDBC {@code VARCHAR} by default, and
 * {@code columnDefinition} does <em>not</em> change that. {@code columnDefinition} only supplies the DDL
 * text Hibernate would emit if it were generating the schema; the attribute's expected JDBC type code
 * stays {@code VARCHAR}. Schema validation compares type codes, so a {@code CHAR} column and a
 * {@code VARCHAR} expectation do not match and the context refuses to start with:
 *
 * <pre>
 *   SchemaManagementException: Schema-validation: wrong column type encountered in column
 *   [tran_id] in table [transaction]; found [bpchar (Types#CHAR)], but expecting
 *   [char(16) (Types#VARCHAR)]
 * </pre>
 *
 * That is a real, reproduced failure, not a hypothetical one. The remedy is
 * {@code @JdbcTypeCode(SqlTypes.CHAR)} on each of the ten character attributes, which makes the
 * expected type code {@code CHAR} and lets validation agree with the mandated DDL. The same reasoning
 * applies to the two integral numerics: {@code categoryCode} is an {@code Integer}, which would
 * otherwise expect {@code INTEGER} against a {@code NUMERIC(4)} column, and {@code merchantId} is a
 * {@code Long}, which would otherwise expect {@code BIGINT} against {@code NUMERIC(9)}; both therefore
 * carry {@code @JdbcTypeCode(SqlTypes.NUMERIC)}. Two attributes need no help and deliberately have
 * none: {@code amount} is a {@code BigDecimal}, which already expects {@code NUMERIC}, and
 * {@code version} is a {@code Long} against a genuine {@code BIGINT} column.
 *
 * <p>{@code columnDefinition} is kept alongside the type code because the two do different jobs and are
 * not duplicative: the type code is what validation compares, while {@code columnDefinition} records
 * the exact SQL type this mapping is contracted against and is therefore machine-checkable by a test.
 *
 * <p>These two annotations are the only provider-specific ones in this class. They add no dependency -
 * {@code org.hibernate.annotations.JdbcTypeCode} and {@code org.hibernate.type.SqlTypes} both arrive
 * with {@code hibernate-core}, which is already on the compile classpath through the declared
 * {@code spring-boot-starter-data-jpa} starter, and Hibernate is the mandated persistence provider.
 * The alternative - widening the columns to {@code VARCHAR} - was rejected because it would contradict
 * the mandated DDL and would discard the blank padding that makes a fixed-width record faithful. The
 * blank 26-space processing timestamp survives a database round trip precisely <em>because</em> the
 * column is {@code CHAR(26)}.
 *
 * <h2>Not available: the schema migration did not exist when this entity was authored</h2>
 * <strong>Not available</strong> - {@code src/main/resources/db/migration/V1__create_schema.sql} did
 * not exist at the time this class was written, so its column definitions could not be read and
 * conformed to. Because {@code spring.jpa.hibernate.ddl-auto: validate} is set in every profile, any
 * mismatch of column name, SQL type, precision, scale or nullability fails application-context startup
 * outright rather than degrading quietly. <strong>The field table above is therefore the normative
 * column contract, and {@code V1} must converge upon it.</strong>
 *
 * <p>What is needed from {@code V1__create_schema.sql} is precisely this table:
 *
 * <pre>
 *   transaction (
 *     tran_id             CHAR(16)       PRIMARY KEY,
 *     tran_type_cd        CHAR(2)        NOT NULL,
 *     tran_cat_cd         NUMERIC(4)     NOT NULL,
 *     tran_source         CHAR(10)       NOT NULL,
 *     tran_desc           CHAR(100)      NOT NULL,
 *     tran_amt            NUMERIC(11,2)  NOT NULL,
 *     tran_merchant_id    NUMERIC(9)     NOT NULL,
 *     tran_merchant_name  CHAR(50)       NOT NULL,
 *     tran_merchant_city  CHAR(50)       NOT NULL,
 *     tran_merchant_zip   CHAR(10)       NOT NULL,
 *     tran_card_num       CHAR(16)       NOT NULL,
 *     tran_orig_ts        CHAR(26)       NOT NULL,
 *     tran_proc_ts        CHAR(26)       NOT NULL,
 *     version             BIGINT         NOT NULL
 *   )
 * </pre>
 *
 * and, from {@code V2__create_indexes.sql}, a <em>non-unique</em> B-tree index on
 * {@code tran_proc_ts} standing in for the {@code NONUNIQKEY} alternate index of
 * {@code app/catlg/LISTCAT.txt:L3672-L3678}.
 *
 * <p>That enumeration is not a guess. This mapping was validated against a live PostgreSQL 16 instance
 * by creating exactly the table above in an isolated schema and booting Hibernate with
 * {@code hbm2ddl.auto=validate}: validation passes, all 300 rows of {@code app/data/ASCII/dailytran.txt}
 * persist and read back unchanged - including the blank 26-space processing timestamp in every row and
 * the 50 negative amounts - the amount total reconciles to the value decoded from the fixture, the
 * version counter advances on update, and a query by processing timestamp resolves through the index
 * path. So a {@code V1} that reproduces the table above will start; one that deviates from it will not.
 *
 * <p>Two further constraints on {@code V1} follow from this mapping and are easy to violate.
 * <strong>The table name must be the unquoted identifier {@code transaction}.</strong> PostgreSQL
 * lists {@code TRANSACTION} as a non-reserved keyword, so an unquoted {@code transaction} is a valid
 * table name; this entity does not quote it, so {@code V1} must not quote it either, or
 * {@code validate} will compare a quoted identifier against an unquoted one and disagree. And
 * {@code V1} creates <strong>exactly 11 tables</strong> with 10 foreign keys and 5 check constraints -
 * no more - because the Spring Batch {@code BATCH_*} tables come from the framework's own schema
 * script. They must never be added to {@code V1} and must never become a fourth migration.
 *
 * <h2>How to build, run and test</h2>
 * <pre>
 *   mvn -B clean compile        compiles this class under -Xlint:all -Werror on Java 25
 *   mvn -B clean test           runs the unit tests, including the model contract tests
 *   mvn -B clean verify         adds the coverage floor and the dependency vulnerability scan
 *   docker compose up -d        brings up PostgreSQL 16 so Flyway and validate have a target
 * </pre>
 *
 * Key configuration this class depends on: {@code spring.jpa.hibernate.ddl-auto: validate} in every
 * profile, and the Hibernate JDBC time zone set to UTC. No property of this class is itself
 * configurable - the field contract is fixed by the copybook.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><em>Context fails to start with a schema-validation error naming {@code tran_amt}</em> - the
 *       migration almost certainly declared {@code NUMERIC(12,2)} by analogy with the account money
 *       fields. The correct tier for {@code S9(09)V99} is {@code NUMERIC(11,2)}.</li>
 *   <li><em>Context fails to start naming {@code transaction} as an unknown table</em> - the migration
 *       quoted the table name. Use the unquoted spelling.</li>
 *   <li><em>Context fails to start with "found [bpchar (Types#CHAR)], but expecting [... Types#VARCHAR]"
 *       </em> - a character attribute lost its {@code @JdbcTypeCode(SqlTypes.CHAR)}. Adding
 *       {@code columnDefinition} alone does not fix this; see the Blocker section above.</li>
 *   <li><em>Text values come back space-padded and comparisons start failing</em> - that is
 *       {@code CHAR(n)} behaving correctly. The columns are fixed width by design, so trim at the point
 *       of comparison rather than widening the column.</li>
 *   <li><em>Fixture load fails, or a timestamp column arrives empty</em> - something is trying to parse
 *       {@code tran_proc_ts} as a temporal value. It is text, and in the reference fixture it is 26
 *       spaces.</li>
 *   <li><em>Amounts differ from the baseline in the second decimal place</em> - a {@code double} or a
 *       non-HALF_EVEN rounding mode has been introduced somewhere on the path, or the scale was
 *       widened.</li>
 *   <li><em>Rows are rejected as having an invalid source</em> - the source column has been narrowed to
 *       an enum. It is a ten-character string, and {@code OPERATOR} arrives by pass-through.</li>
 * </ul>
 *
 * @see #toString()
 */
@Entity
@Table(name = "transaction")
public class Transaction {

    /**
     * Transaction identifier - the primary key.
     *
     * <p>Source {@code TRAN-ID}, {@code app/cpy/CVTRA05Y.cpy:L5}, {@code PIC X(16)}, bytes 1-16.
     * This is the VSAM primary key: {@code app/catlg/LISTCAT.txt:L3593} reports {@code KEYLEN 16} and
     * {@code :L3594} reports {@code RKP 0}, together placing a 16-byte key at the very start of the
     * record, which is this field and only this field.
     *
     * <p>Held as text rather than as a number because it is an identifier, not a quantity: the values
     * are zero-padded sixteen-character strings such as {@code "0000000000683580"}, and a numeric
     * column would discard the padding. No {@code @GeneratedValue} and no sequence - see the class
     * documentation on identifier generation.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.CHAR)} is required, not decorative: without it Hibernate expects
     * {@code VARCHAR} for a {@code String} and schema validation rejects the {@code CHAR(16)} column.
     * See the Blocker section in the class documentation.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_id", nullable = false, length = 16, columnDefinition = "CHAR(16)")
    private String transactionId;

    /**
     * Transaction type code.
     *
     * <p>Source {@code TRAN-TYPE-CD}, {@code app/cpy/CVTRA05Y.cpy:L6}, {@code PIC X(02)}, bytes 17-18.
     * A plain scalar column: no association is modelled to the transaction-type table even though the
     * schema declares a foreign key over it.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type_cd", nullable = false, length = 2, columnDefinition = "CHAR(2)")
    private String typeCode;

    /**
     * Transaction category code.
     *
     * <p>Source {@code TRAN-CAT-CD}, {@code app/cpy/CVTRA05Y.cpy:L7}, {@code PIC 9(04)}, bytes 19-22.
     * Unsigned four-digit numeric, so {@code Integer} over {@code NUMERIC(4)}. Together with
     * {@code typeCode} this forms the natural key of the transaction-category table, but as with
     * {@code typeCode} no association is modelled.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.NUMERIC)} is required so that schema validation expects
     * {@code NUMERIC} rather than the {@code INTEGER} an {@code Integer} attribute would otherwise imply.
     */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "tran_cat_cd", nullable = false, columnDefinition = "NUMERIC(4)")
    private Integer categoryCode;

    /**
     * Origin of the transaction, as free ten-character text.
     *
     * <p>Source {@code TRAN-SOURCE}, {@code app/cpy/CVTRA05Y.cpy:L8}, {@code PIC X(10)}, bytes 23-32.
     *
     * <p><strong>Blocker-class contract: this is a {@code String}, never an enum.</strong>
     * {@code app/cbl/CBTRN02C.cbl:L428} moves the staging record's source into this field without
     * examining it, so the column can hold any ten characters. The reference fixture proves the point:
     * a census of bytes 23-32 across the 300 rows of {@code app/data/ASCII/dailytran.txt} yields 250
     * rows of {@code "POS TERM  "} and 50 rows of {@code "OPERATOR  "}, and {@code OPERATOR} has no
     * literal assignment site anywhere in the corpus. Narrowing this column to an enumerated type would
     * reject a fifth of the reference data.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_source", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String transactionSource;

    /**
     * Free-text transaction description.
     *
     * <p>Source {@code TRAN-DESC}, {@code app/cpy/CVTRA05Y.cpy:L9}, {@code PIC X(100)}, bytes 33-132.
     * The widest column in the record. Not a {@code @Lob}: it is a fixed hundred-character field, and
     * mapping it as a large object would change both the storage shape and the fetch behaviour.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_desc", nullable = false, length = 100, columnDefinition = "CHAR(100)")
    private String description;

    /**
     * Signed transaction amount.
     *
     * <p>Source {@code TRAN-AMT}, {@code app/cpy/CVTRA05Y.cpy:L10}, {@code PIC S9(09)V99}, bytes
     * 133-143 - eleven bytes, being nine integer digits plus two decimal digits with a trailing
     * zoned-decimal overpunch sign.
     *
     * <p><strong>Blocker-class contract: {@code NUMERIC(11,2)}, not {@code NUMERIC(12,2)}.</strong>
     * The declared {@code precision = 11, scale = 2} follows directly from {@code S9(09)V99}. The
     * account money fields are {@code S9(10)V99} and therefore {@code NUMERIC(12,2)}; the two tiers must
     * never be collapsed.
     *
     * <p><strong>The value may legitimately be negative</strong> - 50 of the 300 reference fixture rows
     * carry a negative overpunch sign - and negatives feed the cycle-debit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L551}. No sign constraint and no absolute-value normalisation is
     * applied here or permitted downstream. Compare amounts with {@code compareTo} rather than
     * {@code equals}, because {@code BigDecimal.equals} also compares scale.
     *
     * <p>This attribute deliberately carries no {@code @JdbcTypeCode}: a {@code BigDecimal} already
     * expects {@code NUMERIC}, so the declared {@code precision} and {@code scale} are sufficient for
     * schema validation to agree.
     */
    @Column(name = "tran_amt", nullable = false, precision = 11, scale = 2)
    private BigDecimal amount;

    /**
     * Merchant identifier.
     *
     * <p>Source {@code TRAN-MERCHANT-ID}, {@code app/cpy/CVTRA05Y.cpy:L11}, {@code PIC 9(09)}, bytes
     * 144-152. Unsigned nine-digit numeric, which exceeds the range of a signed 32-bit integer at its
     * upper end, so {@code Long} over {@code NUMERIC(9)}. {@code @JdbcTypeCode(SqlTypes.NUMERIC)} is
     * required so that validation expects {@code NUMERIC} rather than the {@code BIGINT} a {@code Long}
     * would otherwise imply.
     */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "tran_merchant_id", nullable = false, columnDefinition = "NUMERIC(9)")
    private Long merchantId;

    /**
     * Merchant name.
     *
     * <p>Source {@code TRAN-MERCHANT-NAME}, {@code app/cpy/CVTRA05Y.cpy:L12}, {@code PIC X(50)}, bytes
     * 153-202. Deliberately excluded from {@link #toString()} - not because it is sensitive, but because
     * it adds no diagnostic value and would inflate every log line.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_merchant_name", nullable = false, length = 50, columnDefinition = "CHAR(50)")
    private String merchantName;

    /**
     * Merchant city.
     *
     * <p>Source {@code TRAN-MERCHANT-CITY}, {@code app/cpy/CVTRA05Y.cpy:L13}, {@code PIC X(50)}, bytes
     * 203-252. Excluded from {@link #toString()} for the same reason as {@link #getMerchantName()}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_merchant_city", nullable = false, length = 50, columnDefinition = "CHAR(50)")
    private String merchantCity;

    /**
     * Merchant postal code.
     *
     * <p>Source {@code TRAN-MERCHANT-ZIP}, {@code app/cpy/CVTRA05Y.cpy:L14}, {@code PIC X(10)}, bytes
     * 253-262. Text rather than numeric: postal codes are labels, and the legacy field is a character
     * field that the interest job fills with spaces. Excluded from {@link #toString()}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_merchant_zip", nullable = false, length = 10, columnDefinition = "CHAR(10)")
    private String merchantZip;

    /**
     * Card number the transaction was made against.
     *
     * <p>Source {@code TRAN-CARD-NUM}, {@code app/cpy/CVTRA05Y.cpy:L15}, {@code PIC X(16)}, bytes
     * 263-278. The offset is corroborated twice over: {@code app/proc/TRANREPT.prc:L39} declares
     * {@code TRAN-CARD-NUM,263,16,ZD} and {@code app/jcl/CREASTMT.JCL:L53} sorts on
     * {@code FIELDS=(263,16,CH,A,...)}.
     *
     * <p>Those two declarations disagree about the <em>type</em> - {@code ZD} against {@code CH} - and
     * the copybook settles it: {@code PIC X(16)} means {@code CHAR(16)} and {@code String}. Do not
     * convert this column to a numeric type on the strength of the {@code ZD} entry; leading zeros are
     * significant in a card number.
     *
     * <p><strong>High-severity handling rule: this value must never be emitted.</strong> It is excluded
     * from {@link #toString()} by design, and no masking or debug-rendering helper is provided.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_card_num", nullable = false, length = 16, columnDefinition = "CHAR(16)")
    private String cardNumber;

    /**
     * Originating timestamp, as 26 characters of text.
     *
     * <p>Source {@code TRAN-ORIG-TS}, {@code app/cpy/CVTRA05Y.cpy:L16}, {@code PIC X(26)}, bytes
     * 279-304.
     *
     * <p><strong>Blocker-class contract: text, never a temporal type.</strong> The copybook declares a
     * character field, and {@code app/cbl/CBTRN02C.cbl:L436} copies whatever text arrived on the staging
     * record straight into it without examining it. All 300 rows of
     * {@code app/data/ASCII/dailytran.txt} carry {@code 2022-06-10 19:27:53.000000} here - a
     * space-separated form with a six-digit fraction - whereas the batch generator produces a
     * dash-separated form. Two incompatible formats in one column cannot be represented by one temporal
     * type, so no parsing, no format validation and no conversion happens here.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_orig_ts", nullable = false, length = 26, columnDefinition = "CHAR(26)")
    private String origTs;

    /**
     * Processing timestamp, as 26 characters of text.
     *
     * <p>Source {@code TRAN-PROC-TS}, {@code app/cpy/CVTRA05Y.cpy:L17}, {@code PIC X(26)}, bytes
     * 305-330.
     *
     * <p><strong>This field backs the alternate index.</strong>
     * {@code app/catlg/LISTCAT.txt:L3672} defines {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} and
     * {@code :L3674} reports {@code KEYLEN 26}; {@code :L3676} reports {@code AXRKP 304}, a zero-based
     * displacement that resolves to 1-based byte 305 - this field. The index is replaced by a finder
     * derived from this property on the transaction repository plus a <em>non-unique</em> B-tree index
     * on {@code tran_proc_ts} in {@code V2__create_indexes.sql}. Non-unique is not a choice:
     * {@code :L3678} declares the alternate index {@code NONUNIQKEY}, and every row of the reference
     * fixture shares one value here.
     *
     * <p><strong>Blocker-class contract: text, never a temporal type.</strong> In the reference fixture
     * this field is 26 spaces in all 300 rows - blank, and therefore unparseable as a timestamp in any
     * format - because the staging dataset is written before posting assigns a processing time. Blank
     * but non-null is a valid, expected value, which is why no blank-rejecting constraint appears here.
     * When the batch layer does generate a value it must format to millisecond precision followed by
     * four literal zeros, matching {@code app/cbl/CBTRN02C.cbl:L700-L701} and the format comment at
     * {@code :L149}; nanosecond precision does not fit 26 characters and would diverge from the
     * baseline byte for byte.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_proc_ts", nullable = false, length = 26, columnDefinition = "CHAR(26)")
    private String procTs;

    /**
     * Optimistic-locking version counter.
     *
     * <p>No COBOL counterpart: the legacy program held a read-for-update lock for the duration of a
     * CICS task instead. This is the store-level guard only - it proves that no concurrent write
     * intervened, but it does not reproduce the legacy business-level change detection, which compares
     * specific field values against a snapshot. See the class documentation for why both layers are
     * required.
     *
     * <p>No {@code @JdbcTypeCode} is needed here: a {@code Long} already expects {@code BIGINT}, which is
     * exactly what the column is.
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "BIGINT")
    private Long version;

    /**
     * No-argument constructor required by the JPA specification so that the persistence provider can
     * instantiate the entity before populating it.
     *
     * <p>Declared {@code protected} rather than {@code public} on purpose: the provider and subclasses
     * can reach it, but application code cannot use it to create a half-built transaction that would
     * violate the not-null contract on every column. Application code uses the all-columns constructor
     * instead.
     */
    protected Transaction() {
        // Intentionally empty. The persistence provider assigns every field after construction.
    }

    /**
     * Creates a fully populated transaction.
     *
     * <p>The parameters appear in {@code app/cpy/CVTRA05Y.cpy} declaration order, so the argument list
     * reads in the same sequence as the 350-byte record and as the offset map in the class
     * documentation. {@code version} is deliberately not a parameter: it is owned by the persistence
     * provider, which assigns it on first flush and increments it on every subsequent update.
     *
     * <p>No argument is validated, normalised, trimmed, upper-cased or reformatted. That is a deliberate
     * parity decision rather than an omission: the legacy record accepts blank text - the 26-space
     * processing timestamp of the reference fixture is the canonical case - and it accepts negative
     * amounts, so a constructor that rejected or normalised either would be unable to represent valid
     * legacy data. Null rejection is enforced by the database through {@code nullable = false} on every
     * column, at the point where it can be enforced consistently for both this constructor and the
     * setters.
     *
     * @param transactionId     {@code TRAN-ID}, {@code X(16)}, bytes 1-16; the primary key
     * @param typeCode          {@code TRAN-TYPE-CD}, {@code X(02)}, bytes 17-18
     * @param categoryCode      {@code TRAN-CAT-CD}, {@code 9(04)}, bytes 19-22
     * @param transactionSource {@code TRAN-SOURCE}, {@code X(10)}, bytes 23-32; free text, not an enum
     * @param description       {@code TRAN-DESC}, {@code X(100)}, bytes 33-132
     * @param amount            {@code TRAN-AMT}, {@code S9(09)V99}, bytes 133-143; signed, may be
     *                          negative
     * @param merchantId        {@code TRAN-MERCHANT-ID}, {@code 9(09)}, bytes 144-152
     * @param merchantName      {@code TRAN-MERCHANT-NAME}, {@code X(50)}, bytes 153-202
     * @param merchantCity      {@code TRAN-MERCHANT-CITY}, {@code X(50)}, bytes 203-252
     * @param merchantZip       {@code TRAN-MERCHANT-ZIP}, {@code X(10)}, bytes 253-262
     * @param cardNumber        {@code TRAN-CARD-NUM}, {@code X(16)}, bytes 263-278; never logged
     * @param origTs            {@code TRAN-ORIG-TS}, {@code X(26)}, bytes 279-304; text, not temporal
     * @param procTs            {@code TRAN-PROC-TS}, {@code X(26)}, bytes 305-330; text, may be blank
     */
    public Transaction(String transactionId,
                       String typeCode,
                       Integer categoryCode,
                       String transactionSource,
                       String description,
                       BigDecimal amount,
                       Long merchantId,
                       String merchantName,
                       String merchantCity,
                       String merchantZip,
                       String cardNumber,
                       String origTs,
                       String procTs) {
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
     * Returns the transaction identifier.
     *
     * @return {@code TRAN-ID}, {@code app/cpy/CVTRA05Y.cpy:L5}, {@code PIC X(16)}, bytes 1-16; the
     *         primary key, a zero-padded sixteen-character string
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Sets the transaction identifier.
     *
     * <p>Changing the primary key of a persistent instance is not supported by JPA; this setter exists
     * for the provider and for constructing detached instances.
     *
     * @param transactionId {@code TRAN-ID}, {@code PIC X(16)}, bytes 1-16
     */
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return {@code TRAN-TYPE-CD}, {@code app/cpy/CVTRA05Y.cpy:L6}, {@code PIC X(02)}, bytes 17-18
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction type code.
     *
     * @param typeCode {@code TRAN-TYPE-CD}, {@code PIC X(02)}, bytes 17-18
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction category code.
     *
     * @return {@code TRAN-CAT-CD}, {@code app/cpy/CVTRA05Y.cpy:L7}, {@code PIC 9(04)}, bytes 19-22
     */
    public Integer getCategoryCode() {
        return categoryCode;
    }

    /**
     * Sets the transaction category code.
     *
     * @param categoryCode {@code TRAN-CAT-CD}, {@code PIC 9(04)}, bytes 19-22
     */
    public void setCategoryCode(Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

    /**
     * Returns the transaction source as free text.
     *
     * @return {@code TRAN-SOURCE}, {@code app/cpy/CVTRA05Y.cpy:L8}, {@code PIC X(10)}, bytes 23-32; any
     *         ten characters, since the posting path passes the staging value through unexamined at
     *         {@code app/cbl/CBTRN02C.cbl:L428}
     */
    public String getTransactionSource() {
        return transactionSource;
    }

    /**
     * Sets the transaction source.
     *
     * <p>Accepts any ten-character text. No enumeration is applied, by design.
     *
     * @param transactionSource {@code TRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32
     */
    public void setTransactionSource(String transactionSource) {
        this.transactionSource = transactionSource;
    }

    /**
     * Returns the transaction description.
     *
     * @return {@code TRAN-DESC}, {@code app/cpy/CVTRA05Y.cpy:L9}, {@code PIC X(100)}, bytes 33-132
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the transaction description.
     *
     * @param description {@code TRAN-DESC}, {@code PIC X(100)}, bytes 33-132
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the signed transaction amount.
     *
     * <p>Compare the returned value with {@code compareTo} rather than {@code equals}, because
     * {@code BigDecimal.equals} also compares scale and would report 2.0 and 2.00 as different.
     *
     * @return {@code TRAN-AMT}, {@code app/cpy/CVTRA05Y.cpy:L10}, {@code PIC S9(09)V99}, bytes 133-143,
     *         scale 2; may be negative, and negatives must not be normalised
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Sets the signed transaction amount.
     *
     * <p>The value is stored exactly as supplied. No sign normalisation, no rescaling and no rounding
     * happens here; where rounding is unavoidable elsewhere it must use {@code RoundingMode.HALF_EVEN}.
     *
     * @param amount {@code TRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143; negative values are valid
     */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Returns the merchant identifier.
     *
     * @return {@code TRAN-MERCHANT-ID}, {@code app/cpy/CVTRA05Y.cpy:L11}, {@code PIC 9(09)}, bytes
     *         144-152
     */
    public Long getMerchantId() {
        return merchantId;
    }

    /**
     * Sets the merchant identifier.
     *
     * @param merchantId {@code TRAN-MERCHANT-ID}, {@code PIC 9(09)}, bytes 144-152
     */
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name.
     *
     * @return {@code TRAN-MERCHANT-NAME}, {@code app/cpy/CVTRA05Y.cpy:L12}, {@code PIC X(50)}, bytes
     *         153-202
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Sets the merchant name.
     *
     * @param merchantName {@code TRAN-MERCHANT-NAME}, {@code PIC X(50)}, bytes 153-202
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city.
     *
     * @return {@code TRAN-MERCHANT-CITY}, {@code app/cpy/CVTRA05Y.cpy:L13}, {@code PIC X(50)}, bytes
     *         203-252
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Sets the merchant city.
     *
     * @param merchantCity {@code TRAN-MERCHANT-CITY}, {@code PIC X(50)}, bytes 203-252
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant postal code.
     *
     * @return {@code TRAN-MERCHANT-ZIP}, {@code app/cpy/CVTRA05Y.cpy:L14}, {@code PIC X(10)}, bytes
     *         253-262
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Sets the merchant postal code.
     *
     * @param merchantZip {@code TRAN-MERCHANT-ZIP}, {@code PIC X(10)}, bytes 253-262
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the card number the transaction was made against.
     *
     * <p><strong>Handle as sensitive.</strong> The returned value is a primary account number. It is
     * excluded from {@link #toString()} on purpose, and callers must not place it into a log message, an
     * exception message, a metric tag or a span attribute.
     *
     * @return {@code TRAN-CARD-NUM}, {@code app/cpy/CVTRA05Y.cpy:L15}, {@code PIC X(16)}, bytes 263-278
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Sets the card number.
     *
     * @param cardNumber {@code TRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278; sensitive, never logged
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /**
     * Returns the originating timestamp as text.
     *
     * <p>The value is 26 characters of text in one of several mutually incompatible legacy formats. Do
     * not assume it parses; do not convert it to a temporal type.
     *
     * @return {@code TRAN-ORIG-TS}, {@code app/cpy/CVTRA05Y.cpy:L16}, {@code PIC X(26)}, bytes 279-304
     */
    public String getOrigTs() {
        return origTs;
    }

    /**
     * Sets the originating timestamp text.
     *
     * @param origTs {@code TRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304; stored verbatim
     */
    public void setOrigTs(String origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns the processing timestamp as text.
     *
     * <p>The value is 26 characters of text and may legitimately be entirely blank - it is 26 spaces in
     * every row of the reference staging fixture. Do not assume it parses; do not convert it to a
     * temporal type. This is the property behind the alternate-index replacement finder.
     *
     * @return {@code TRAN-PROC-TS}, {@code app/cpy/CVTRA05Y.cpy:L17}, {@code PIC X(26)}, bytes 305-330
     */
    public String getProcTs() {
        return procTs;
    }

    /**
     * Sets the processing timestamp text.
     *
     * <p>A generated value must carry millisecond precision followed by four literal zeros, per
     * {@code app/cbl/CBTRN02C.cbl:L700-L701}. Blank is valid.
     *
     * @param procTs {@code TRAN-PROC-TS}, {@code PIC X(26)}, bytes 305-330; stored verbatim
     */
    public void setProcTs(String procTs) {
        this.procTs = procTs;
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * <p>{@code null} on a transient instance that has never been flushed.
     *
     * @return the version counter maintained by the persistence provider; no COBOL counterpart
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter.
     *
     * <p>Present for the persistence provider and for tests that need to construct a detached instance
     * with a known version. Application code has no reason to call it: assigning a version by hand
     * defeats the guard it exists to provide.
     *
     * @param version the version counter, or {@code null} for a transient instance
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares two transactions by primary key alone.
     *
     * <p>Only {@code transactionId} participates. Two reasons, both deliberate. First, the identifier is
     * the record's identity in the legacy store, so two instances bearing the same identifier denote the
     * same transaction regardless of any field that has since been edited. Second, including
     * {@code amount} would make equality scale-sensitive, because {@code BigDecimal.equals} treats 2.0
     * and 2.00 as different; a monetary comparison must use {@code compareTo}, which is not what an
     * {@code equals} contract can offer.
     *
     * <p>A consequence worth stating: two instances that have not yet been assigned an identifier are
     * equal to each other under this definition. Entities are compared after their key is set.
     *
     * @param other the object to compare with, possibly {@code null}
     * @return {@code true} if {@code other} is a {@code Transaction} with an equal
     *         {@code transactionId}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transaction that)) {
            return false;
        }
        return Objects.equals(this.transactionId, that.transactionId);
    }

    /**
     * Returns a hash code derived from the primary key alone, consistent with
     * {@link #equals(Object)}.
     *
     * <p>Because it depends only on {@code transactionId}, which does not change over the life of a
     * persistent instance, the hash code is stable while the entity sits in a collection - which a hash
     * code computed over mutable business fields would not be.
     *
     * @return a hash code over {@code transactionId}
     */
    @Override
    public int hashCode() {
        return Objects.hash(transactionId);
    }

    /**
     * Returns a diagnostic rendering that deliberately omits sensitive and low-value fields.
     *
     * <p><strong>The card number is excluded on purpose and must stay excluded.</strong> A primary
     * account number in a log line is a data-protection incident, and {@code toString} is the single
     * most likely route for one to get there, because logging frameworks, exception messages, collection
     * renderings and debuggers all call it implicitly. Log masking is the backstop; not emitting the
     * value at all is the primary defence. The merchant name, city and postal code are omitted as well -
     * they are not sensitive, but they contribute nothing diagnostically and would triple the length of
     * every rendered line. The description is omitted for the same reason.
     *
     * <p>Included: {@code transactionId}, {@code typeCode}, {@code categoryCode},
     * {@code transactionSource}, {@code amount}, {@code origTs}, {@code procTs} and {@code version} -
     * enough to identify the record, see how it was classified and routed, and reason about optimistic
     * locking. No companion method rendering the full record is provided, deliberately.
     *
     * @return a single-line rendering containing no sensitive field
     */
    @Override
    public String toString() {
        return "Transaction{transactionId=" + transactionId
                + ", typeCode=" + typeCode
                + ", categoryCode=" + categoryCode
                + ", transactionSource=" + transactionSource
                + ", amount=" + amount
                + ", origTs=" + origTs
                + ", procTs=" + procTs
                + ", version=" + version
                + '}';
    }
}
