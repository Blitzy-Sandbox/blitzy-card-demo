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
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Posted transaction master record: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}.
 *
 * <p>The 350-byte offset map of {@code app/cpy/CVTRA05Y.cpy} is normative, because the fixed-width writers and
 * the DFSORT symbol definitions of {@code app/proc/TRANREPT.prc} and {@code app/jcl/CREASTMT.JCL} address the
 * record by byte position rather than by field name. Where a sort deck and the copybook disagree, the copybook
 * governs.
 *
 * <p>Both 26-character timestamps are text, never a temporal type: the generated form carries millisecond
 * precision followed by four zero digits, and the report filter compares the first ten characters lexically.
 * The non-unique {@code TRANSACT.VSAM.AIX} alternate index at offset 304 is not mapped here at all — it
 * becomes a range query on {@code com.cardemo.repository.TransactionRepository}.
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
 * B-tree index on {@code tran_proc_ts} to be created by {@code V2__create_indexes.sql} (planned; absent at this
 * commit). The non-uniqueness is
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
 *       {@code yyyy-MM-dd-HH.mm.ss.SS0000}: dash separated, <strong>hundredths of a second</strong>
 *       precision, then four literal zeros. The precision is hundredths and not milliseconds:
 *       {@code DB2-MIL} is a two digit field, and {@code COB-MIL PIC X(02)} at {@code :L157} is the
 *       hundredths pair of the twenty one character {@code FUNCTION CURRENT-DATE} result. The field name
 *       invites the wrong reading, which is why the width is stated here.</li>
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
 * <em>generated</em> - in the batch layer, never here - it must be formatted to <strong>hundredths of a
 * second</strong> precision followed by four literal zeros, exactly as {@code :L700-L701} does. Neither
 * nanosecond nor millisecond precision is correct: nine fractional digits do not fit a 26-character
 * field at all, and three fractional digits followed by four zeros produces 27 characters. Only two
 * fractional digits plus {@code 0000} lands on 26, and a differently padded tail is a byte-level diff
 * against the baseline. Because these columns are text, this entity is entirely insulated from JVM
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
 *       Null is what is rejected, and it is rejected on assignment by the constructor and by every
 *       setter as well as by {@code nullable = false} on every column - the two guards are
 *       complementary, the first naming the property and the second covering the provider's own
 *       reflective writes.</li>
 *   <li><strong>No error handling of its own.</strong> The entity catches nothing, logs nothing and
 *       performs no I/O, so it has no failure of its own to handle. What it does do is refuse a value the
 *       copybook cannot represent, through {@code java.lang.IllegalArgumentException} naming the offending
 *       property, its COBOL field and that field's picture clause; importing the project exception
 *       hierarchy from a model class is deliberately avoided so that the model layer depends on
 *       nothing.</li>
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
 * <h2>The schema migration now exists and its agreement is machine-verified</h2>
 * {@code src/main/resources/db/migration/V1__create_schema.sql} <strong>exists</strong> and declares the
 * {@code transaction} table with fourteen columns. An earlier revision of this paragraph
 * recorded that the migration did not exist when this class was authored; that is no longer true and the
 * claim is withdrawn. Agreement between this field table and {@code V1} is not taken on trust: it is
 * asserted mechanically by {@code SchemaStructureTest}, which parses the DDL and cross-checks column
 * widths and primary-key order against {@code app/cpy/CVTRA05Y.cpy}. Because
 * {@code spring.jpa.hibernate.ddl-auto: validate} is set in every profile, any residual mismatch of
 * column name, SQL type, precision, scale or nullability would fail application-context startup outright
 * rather than degrading quietly. <strong>The field table above remains the normative column contract, so
 * any future divergence is resolved by changing {@code V1}, not this table.</strong> What is still absent
 * is {@code V2__create_indexes.sql}: {@code V1} declares no {@code CREATE INDEX}, so the B-tree index on
 * {@code tran_proc_ts} that replaces {@code TRANSACT.VSAM.AIX} is <strong>planned</strong>, not present.
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
 *   ./mvnw -B clean compile     compiles this class under -Xlint:all -Werror on Java 25
 *   ./mvnw -B clean test        runs the unit tests, including the model contract tests
 *   ./mvnw -B clean verify      adds the coverage floor and the dependency vulnerability scan
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
 * <p><b>JSON serialisation barrier.</b> This class is structurally unserialisable by Jackson.
 * {@link JsonIgnoreType} removes any property whose declared type is this class from an enclosing
 * object's JSON, and {@link JsonAutoDetect} with every visibility set to {@code NONE} switches off bean
 * introspection entirely, so no getter, no setter, no field and no creator is discoverable. An entity is
 * a bean with public accessors, so without the barrier the default behaviour of returning this type from
 * a controller, or holding a field of it on a response object, is to publish the card number alongside
 * the amount and the full merchant detail. With the barrier in place Jackson finds no properties and its
 * default {@code FAIL_ON_EMPTY_BEANS} setting turns that mistake into a loud failure at the first request
 * rather than a silent disclosure. Nothing legitimate is lost: outbound representations are built by
 * {@code com.cardemo.model.dto.TransactionDto}, inbound JSON targets
 * {@code com.cardemo.model.dto.TransactionAddRequest}, and persistence is unaffected because Hibernate
 * reads and writes the annotated fields reflectively and never consults Jackson visibility.
 *
 * @see #toString()
 */
@Entity
@Table(name = "transaction")
@JsonIgnoreType
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class Transaction {

    /**
     * Character widths of the ten alphanumeric fields of {@code app/cpy/CVTRA05Y.cpy}, in record order.
     * The copybook is the authority for every one of these numbers; the same values appear on the
     * {@code @Column} declarations below, and {@code ddl-auto: validate} refuses to start the application
     * if either drifts from the migration.
     */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** Width of {@code TRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA05Y.cpy:L6}. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of {@code TRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8}. */
    private static final int TRANSACTION_SOURCE_WIDTH = 10;

    /** Width of {@code TRAN-DESC PIC X(100)} at {@code app/cpy/CVTRA05Y.cpy:L9}. */
    private static final int DESCRIPTION_WIDTH = 100;

    /** Width of {@code TRAN-MERCHANT-NAME PIC X(50)} at {@code app/cpy/CVTRA05Y.cpy:L12}. */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** Width of {@code TRAN-MERCHANT-CITY PIC X(50)} at {@code app/cpy/CVTRA05Y.cpy:L13}. */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** Width of {@code TRAN-MERCHANT-ZIP PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L14}. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** Width of {@code TRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L15}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Width of {@code TRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16}. */
    private static final int ORIG_TS_WIDTH = 26;

    /** Width of {@code TRAN-PROC-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L17}. */
    private static final int PROC_TS_WIDTH = 26;

    /**
     * Smallest value {@code TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy:L7} can represent. The
     * picture clause carries no {@code S}, so the domain is unsigned and starts at zero.
     */
    private static final int MIN_CATEGORY_CODE = 0;

    /** Largest value {@code TRAN-CAT-CD PIC 9(04)} can represent: four unsigned display digits. */
    private static final int MAX_CATEGORY_CODE = 9999;

    /**
     * Smallest value {@code TRAN-MERCHANT-ID PIC 9(09)} at {@code app/cpy/CVTRA05Y.cpy:L11} can
     * represent, the picture clause again being unsigned.
     */
    private static final long MIN_MERCHANT_ID = 0L;

    /** Largest value {@code TRAN-MERCHANT-ID PIC 9(09)} can represent: nine unsigned display digits. */
    private static final long MAX_MERCHANT_ID = 999_999_999L;

    /**
     * Integer digit count of {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}: nine.
     * This is the {@code S9(09)V99} tier, not the {@code S9(10)V99} tier the account entity's money
     * fields use.
     */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Decimal digit count of {@code TRAN-AMT PIC S9(09)V99}: the two digits after the implied {@code V}. */
    private static final int AMOUNT_SCALE = 2;

    /** Total precision of the mapped {@code NUMERIC} column: nine integer digits plus two decimal digits. */
    private static final int AMOUNT_PRECISION = AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE;

    /**
     * Largest value {@code TRAN-AMT PIC S9(09)V99} can represent, and equally the largest
     * {@code NUMERIC(11,2)} can hold. Built from a string literal so the bound is exact.
     */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    /**
     * Smallest value {@code TRAN-AMT PIC S9(09)V99} can represent. The picture clause carries an
     * {@code S}, so the domain is symmetric about zero: {@code app/data/ASCII/dailytran.txt} carries
     * close-brace overpunch characters, the zoned-decimal encoding of a negative zero digit, and
     * therefore genuinely negative amounts.
     */
    private static final BigDecimal MIN_AMOUNT = MAX_AMOUNT.negate();

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
    @Column(name = "tran_id", nullable = false, length = TRANSACTION_ID_WIDTH, columnDefinition = "CHAR(16)")
    private String transactionId;

    /**
     * Transaction type code: {@code TRAN-TYPE-CD}, {@code PIC X(02)} at {@code app/cpy/CVTRA05Y.cpy:L6},
     * bytes 17-18.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type_cd", nullable = false, length = TYPE_CODE_WIDTH, columnDefinition = "CHAR(2)")
    private String typeCode;

    /**
     * Transaction category code: {@code TRAN-CAT-CD}, {@code PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy:L7},
     * bytes 19-22.
     */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "tran_cat_cd", nullable = false, columnDefinition = "NUMERIC(4)")
    private Integer categoryCode;

    /**
     * Origin of the transaction: {@code TRAN-SOURCE}, {@code PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8},
     * bytes 23-32. Free text rather than an enumeration, because the corpus writes literals into it.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_source", nullable = false, length = TRANSACTION_SOURCE_WIDTH, columnDefinition = "CHAR(10)")
    private String transactionSource;

    /**
     * Transaction description: {@code TRAN-DESC}, {@code PIC X(100)} at {@code app/cpy/CVTRA05Y.cpy:L9},
     * bytes 33-132.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_desc", nullable = false, length = DESCRIPTION_WIDTH, columnDefinition = "CHAR(100)")
    private String description;

    /**
     * Signed transaction amount: {@code TRAN-AMT}, {@code PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10},
     * bytes 133-143, so the column is {@code NUMERIC(11,2)}. Negative values are legitimate and are never
     * normalised.
     */
    @Column(name = "tran_amt", nullable = false,
            precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE)
    private BigDecimal amount;

    /**
     * Merchant identifier: {@code TRAN-MERCHANT-ID}, {@code PIC 9(09)} at {@code app/cpy/CVTRA05Y.cpy:L11},
     * bytes 144-152.
     */
    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "tran_merchant_id", nullable = false, columnDefinition = "NUMERIC(9)")
    private Long merchantId;

    /**
     * Merchant name: {@code TRAN-MERCHANT-NAME}, {@code PIC X(50)} at {@code app/cpy/CVTRA05Y.cpy:L12},
     * bytes 153-202.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_merchant_name", nullable = false, length = MERCHANT_NAME_WIDTH, columnDefinition = "CHAR(50)")
    private String merchantName;

    /**
     * Merchant city: {@code TRAN-MERCHANT-CITY}, {@code PIC X(50)} at {@code app/cpy/CVTRA05Y.cpy:L13},
     * bytes 203-252.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_merchant_city", nullable = false, length = MERCHANT_CITY_WIDTH, columnDefinition = "CHAR(50)")
    private String merchantCity;

    /**
     * Merchant postal code: {@code TRAN-MERCHANT-ZIP}, {@code PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L14},
     * bytes 253-262.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_merchant_zip", nullable = false, length = MERCHANT_ZIP_WIDTH, columnDefinition = "CHAR(10)")
    private String merchantZip;

    /**
     * Card number the transaction was made against: {@code TRAN-CARD-NUM}, {@code PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L15}, bytes 263-278. Sensitive: never rendered by {@link #toString()}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_card_num", nullable = false, length = CARD_NUMBER_WIDTH, columnDefinition = "CHAR(16)")
    private String cardNumber;

    /**
     * Originating timestamp: {@code TRAN-ORIG-TS}, {@code PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16},
     * bytes 279-304, held as text because more than one producer format reaches this column.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_orig_ts", nullable = false, length = ORIG_TS_WIDTH, columnDefinition = "CHAR(26)")
    private String origTs;

    /**
     * Processing timestamp: {@code TRAN-PROC-TS}, {@code PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L17},
     * bytes 305-330, held as text because it arrives blank on inbound rows and the report filter compares its
     * first ten characters lexically.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_proc_ts", nullable = false, length = PROC_TS_WIDTH, columnDefinition = "CHAR(26)")
    private String procTs;

    /**
     * Optimistic-locking version counter. No counterpart in {@code app/cpy/CVTRA05Y.cpy}; added by the
     * migration as the store-level guard.
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "BIGINT")
    private Long version;

    /**
     * No-argument constructor required by the JPA specification so that the persistence provider can
     * instantiate the entity before populating it.
     */
    protected Transaction() {
        // Intentionally empty. The persistence provider assigns every field after construction.
    }

    /**
     * Creates a fully populated transaction.
     *
     * @param transactionId {@code TRAN-ID}, {@code X(16)}, bytes 1-16.
     * @param typeCode {@code TRAN-TYPE-CD}, {@code X(02)}, bytes 17-18
     * @param categoryCode {@code TRAN-CAT-CD}, {@code 9(04)}, bytes 19-22
     * @param transactionSource {@code TRAN-SOURCE}, {@code X(10)}, bytes 23-32.
     * @param description {@code TRAN-DESC}, {@code X(100)}, bytes 33-132
     * @param amount {@code TRAN-AMT}, {@code S9(09)V99}, bytes 133-143.
     * @param merchantId {@code TRAN-MERCHANT-ID}, {@code 9(09)}, bytes 144-152
     * @param merchantName {@code TRAN-MERCHANT-NAME}, {@code X(50)}, bytes 153-202
     * @param merchantCity {@code TRAN-MERCHANT-CITY}, {@code X(50)}, bytes 203-252
     * @param merchantZip {@code TRAN-MERCHANT-ZIP}, {@code X(10)}, bytes 253-262
     * @param cardNumber {@code TRAN-CARD-NUM}, {@code X(16)}, bytes 263-278.
     * @param origTs {@code TRAN-ORIG-TS}, {@code X(26)}, bytes 279-304.
     * @param procTs {@code TRAN-PROC-TS}, {@code X(26)}, bytes 305-330.
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
        // Every check is a private static helper, so this constructor invokes no overridable method and
        // cannot publish a partially built instance to a subclass override. JPA forbids a final entity, so
        // the hazard is real and -Xlint:all -Werror reports it as this-escape.
        this.transactionId = requireWidth(transactionId,
                "transactionId", "TRAN-ID PIC X(16)", TRANSACTION_ID_WIDTH);
        this.typeCode = requireWidth(typeCode, "typeCode", "TRAN-TYPE-CD PIC X(02)", TYPE_CODE_WIDTH);
        this.categoryCode = requireCategoryCode(categoryCode);
        this.transactionSource = requireWidth(transactionSource,
                "transactionSource", "TRAN-SOURCE PIC X(10)", TRANSACTION_SOURCE_WIDTH);
        this.description = requireWidth(description,
                "description", "TRAN-DESC PIC X(100)", DESCRIPTION_WIDTH);
        this.amount = requireAmount(amount);
        this.merchantId = requireMerchantId(merchantId);
        this.merchantName = requireWidth(merchantName,
                "merchantName", "TRAN-MERCHANT-NAME PIC X(50)", MERCHANT_NAME_WIDTH);
        this.merchantCity = requireWidth(merchantCity,
                "merchantCity", "TRAN-MERCHANT-CITY PIC X(50)", MERCHANT_CITY_WIDTH);
        this.merchantZip = requireWidth(merchantZip,
                "merchantZip", "TRAN-MERCHANT-ZIP PIC X(10)", MERCHANT_ZIP_WIDTH);
        this.cardNumber = requireWidth(cardNumber,
                "cardNumber", "TRAN-CARD-NUM PIC X(16)", CARD_NUMBER_WIDTH);
        this.origTs = requireWidth(origTs, "origTs", "TRAN-ORIG-TS PIC X(26)", ORIG_TS_WIDTH);
        this.procTs = requireWidth(procTs, "procTs", "TRAN-PROC-TS PIC X(26)", PROC_TS_WIDTH);
    }

    /**
     * Returns the transaction identifier.
     *
     * @return {@code TRAN-ID}, {@code app/cpy/CVTRA05Y.cpy:L5}, {@code PIC X(16)}, bytes 1-16.
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Sets the transaction identifier.
     *
     * @param transactionId {@code TRAN-ID}, {@code PIC X(16)}, bytes 1-16
     */
    public void setTransactionId(String transactionId) {
        this.transactionId = requireWidth(transactionId,
                "transactionId", "TRAN-ID PIC X(16)", TRANSACTION_ID_WIDTH);
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
        this.typeCode = requireWidth(typeCode, "typeCode", "TRAN-TYPE-CD PIC X(02)",
                TYPE_CODE_WIDTH);
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
        this.categoryCode = requireCategoryCode(categoryCode);
    }

    /**
     * Returns the transaction source as free text.
     *
     * @return {@code TRAN-SOURCE}, {@code app/cpy/CVTRA05Y.cpy:L8}, {@code PIC X(10)}, bytes 23-32.
     */
    public String getTransactionSource() {
        return transactionSource;
    }

    /**
     * Sets the transaction source.
     *
     * @param transactionSource {@code TRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32
     */
    public void setTransactionSource(String transactionSource) {
        this.transactionSource = requireWidth(transactionSource,
                "transactionSource", "TRAN-SOURCE PIC X(10)", TRANSACTION_SOURCE_WIDTH);
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
        this.description = requireWidth(description,
                "description", "TRAN-DESC PIC X(100)", DESCRIPTION_WIDTH);
    }

    /**
     * Returns the signed transaction amount.
     *
     * @return {@code TRAN-AMT}, {@code app/cpy/CVTRA05Y.cpy:L10}, {@code PIC S9(09)V99}, bytes 133-143, scale
     * 2.
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Sets the signed transaction amount.
     *
     * @param amount {@code TRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143.
     */
    public void setAmount(BigDecimal amount) {
        this.amount = requireAmount(amount);
    }

    /**
     * Returns the merchant identifier.
     *
     * @return {@code TRAN-MERCHANT-ID}, {@code app/cpy/CVTRA05Y.cpy:L11}, {@code PIC 9(09)}, bytes 144-152
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
        this.merchantId = requireMerchantId(merchantId);
    }

    /**
     * Returns the merchant name.
     *
     * @return {@code TRAN-MERCHANT-NAME}, {@code app/cpy/CVTRA05Y.cpy:L12}, {@code PIC X(50)}, bytes 153-202
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
        this.merchantName = requireWidth(merchantName,
                "merchantName", "TRAN-MERCHANT-NAME PIC X(50)", MERCHANT_NAME_WIDTH);
    }

    /**
     * Returns the merchant city.
     *
     * @return {@code TRAN-MERCHANT-CITY}, {@code app/cpy/CVTRA05Y.cpy:L13}, {@code PIC X(50)}, bytes 203-252
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
        this.merchantCity = requireWidth(merchantCity,
                "merchantCity", "TRAN-MERCHANT-CITY PIC X(50)", MERCHANT_CITY_WIDTH);
    }

    /**
     * Returns the merchant postal code.
     *
     * @return {@code TRAN-MERCHANT-ZIP}, {@code app/cpy/CVTRA05Y.cpy:L14}, {@code PIC X(10)}, bytes 253-262
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
        this.merchantZip = requireWidth(merchantZip,
                "merchantZip", "TRAN-MERCHANT-ZIP PIC X(10)", MERCHANT_ZIP_WIDTH);
    }

    /**
     * Returns the card number the transaction was made against.
     *
     * @return {@code TRAN-CARD-NUM}, {@code app/cpy/CVTRA05Y.cpy:L15}, {@code PIC X(16)}, bytes 263-278
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Sets the card number.
     *
     * @param cardNumber {@code TRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278.
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = requireWidth(cardNumber,
                "cardNumber", "TRAN-CARD-NUM PIC X(16)", CARD_NUMBER_WIDTH);
    }

    /**
     * Returns the originating timestamp as text.
     *
     * @return {@code TRAN-ORIG-TS}, {@code app/cpy/CVTRA05Y.cpy:L16}, {@code PIC X(26)}, bytes 279-304
     */
    public String getOrigTs() {
        return origTs;
    }

    /**
     * Sets the originating timestamp text.
     *
     * @param origTs {@code TRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304.
     */
    public void setOrigTs(String origTs) {
        this.origTs = requireWidth(origTs, "origTs", "TRAN-ORIG-TS PIC X(26)", ORIG_TS_WIDTH);
    }

    /**
     * Returns the processing timestamp as text.
     *
     * @return {@code TRAN-PROC-TS}, {@code app/cpy/CVTRA05Y.cpy:L17}, {@code PIC X(26)}, bytes 305-330
     */
    public String getProcTs() {
        return procTs;
    }

    /**
     * Sets the processing timestamp text.
     *
     * @param procTs {@code TRAN-PROC-TS}, {@code PIC X(26)}, bytes 305-330.
     */
    public void setProcTs(String procTs) {
        this.procTs = requireWidth(procTs, "procTs", "TRAN-PROC-TS PIC X(26)", PROC_TS_WIDTH);
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * @return the version counter maintained by the persistence provider.
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter.
     *
     * @param version the version counter, or {@code null} for a transient instance
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares two transactions by primary key alone.
     *
     * @param other the object to compare with, possibly {@code null}
     * @return {@code true} if {@code other} is a {@code Transaction} with an equal {@code transactionId}
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
     * Returns a hash code derived from the primary key alone, consistent with {@link #equals(Object)}.
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
     * <p><strong>The amount is excluded too, and that is a change from an earlier, wider form of this
     * method.</strong> A transaction amount is customer financial data. On its own it may look innocuous,
     * but this rendering also carries the transaction identifier, so a log estate that holds both holds a
     * per-transaction amount ledger keyed by a value that joins straight back to the row - which is the
     * substance of the account activity, reconstructable without any database access at all. The same
     * reasoning removes {@code origTs} and {@code procTs}: paired with the amount they turn that ledger
     * into a timeline, and neither timestamp identifies a row that {@code transactionId} does not already
     * identify. {@code typeCode}, {@code categoryCode} and {@code transactionSource} are removed under
     * least privilege - they are classification state a reader should obtain from the row, where the
     * access is authorised and audited, rather than recover from a log line.
     *
     * <p>Included: {@code transactionId} and {@code version} - the minimum that identifies which row a
     * log line refers to and distinguishes two readings of it. No companion method rendering the full
     * record is provided, deliberately.
     *
     * @return a single-line rendering of the transaction identifier and version, never containing a card
     *         number, an amount, a timestamp or merchant detail
     */
    @Override
    public String toString() {
        return "Transaction{transactionId=" + transactionId + ", version=" + version + '}';
    }

    /**
     * Validates a candidate character value against the width of the COBOL field it comes from and returns
     * it unchanged.
     *
     * <p>Rejects {@code null}, because every character column here is {@code NOT NULL} and because a fixed
     * width COBOL field cannot be null in the first place, and rejects any value longer than the picture
     * clause declares, because the {@code CHAR} column would refuse or truncate it and the resulting
     * diagnostic would name only a column. Everything the picture clause admits is accepted: a value of
     * only spaces - the 26-space {@code TRAN-PROC-TS} of the reference fixture is exactly that - a shorter
     * value that the {@code CHAR} column blank pads, and any character content whatever.
     *
     * <p><strong>The failure message reports the length and never the value.</strong> Two of the ten
     * character fields guarded here, {@code TRAN-CARD-NUM} and {@code TRAN-ID}, are sensitive or
     * identifying, and a validation message is exactly the kind of string that ends up in a log.
     *
     * <p>Declared {@code private static} so that the constructor can call it without invoking an
     * overridable method, which would otherwise publish a partially initialised instance; the JPA
     * specification forbids a final entity, so the hazard is real and {@code -Xlint:all -Werror} reports it
     * as {@code this-escape}.
     *
     * @param value      the candidate value, possibly {@code null}
     * @param property   the Java property name, used in the failure message
     * @param cobolField the originating COBOL field name and picture clause, used in the failure message
     * @param width      the declared width of that field in characters
     * @return {@code value}, unchanged and never trimmed, padded or case folded
     * @throws IllegalArgumentException if {@code value} is {@code null} or longer than {@code width}
     */
    private static String requireWidth(String value, String property, String cobolField, int width) {
        if (value == null) {
            throw new IllegalArgumentException(property + " (" + cobolField
                    + ") must not be null: it maps to a NOT NULL CHAR(" + width
                    + ") column of table transaction");
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(property + " (" + cobolField + ") must be at most " + width
                    + " characters but was " + value.length());
        }
        return value;
    }

    /**
     * Validates a candidate category code against {@code TRAN-CAT-CD PIC 9(04)} and returns it unchanged.
     *
     * <p>Rejects {@code null} and any value outside 0 through 9999 inclusive, which is what four unsigned
     * display digits and equally a {@code NUMERIC(4)} column can hold. No membership check against the
     * transaction-category table happens here: that is a referential rule and belongs to the schema's
     * foreign key and to the service layer, not to a field guard. Declared {@code private static} for the
     * reason given on {@link #requireWidth(String, String, String, int)}.
     *
     * @param value the candidate category code, possibly {@code null}
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}, negative, or greater than 9999
     */
    private static Integer requireCategoryCode(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("categoryCode (TRAN-CAT-CD PIC 9(04)) must not be null: it "
                    + "maps to a NOT NULL NUMERIC(4) column of table transaction");
        }
        if (value.intValue() < MIN_CATEGORY_CODE || value.intValue() > MAX_CATEGORY_CODE) {
            throw new IllegalArgumentException("categoryCode (TRAN-CAT-CD PIC 9(04)) must be between "
                    + MIN_CATEGORY_CODE + " and " + MAX_CATEGORY_CODE + " inclusive but was " + value);
        }
        return value;
    }

    /**
     * Validates a candidate merchant identifier against {@code TRAN-MERCHANT-ID PIC 9(09)} and returns it
     * unchanged.
     *
     * <p>Rejects {@code null} and any value outside 0 through 999999999 inclusive, which is what nine
     * unsigned display digits and equally a {@code NUMERIC(9)} column can hold. Zero is accepted and is
     * not a sentinel to reject: {@code app/cbl/CBACT04C.cbl:L473-L516} builds its synthetic interest
     * transactions with a merchant identifier of zero. Declared {@code private static} for the reason given
     * on {@link #requireWidth(String, String, String, int)}.
     *
     * @param value the candidate merchant identifier, possibly {@code null}
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}, negative, or greater than
     *                                  999999999
     */
    private static Long requireMerchantId(Long value) {
        if (value == null) {
            throw new IllegalArgumentException("merchantId (TRAN-MERCHANT-ID PIC 9(09)) must not be null: "
                    + "it maps to a NOT NULL NUMERIC(9) column of table transaction");
        }
        if (value.longValue() < MIN_MERCHANT_ID || value.longValue() > MAX_MERCHANT_ID) {
            throw new IllegalArgumentException("merchantId (TRAN-MERCHANT-ID PIC 9(09)) must be between "
                    + MIN_MERCHANT_ID + " and " + MAX_MERCHANT_ID + " inclusive but was " + value);
        }
        return value;
    }

    /**
     * Validates a candidate amount against the domain {@code TRAN-AMT PIC S9(09)V99} can represent and
     * returns it unchanged.
     *
     * <p>Three things are checked and nothing else. {@code null} is rejected, because the column is
     * {@code NOT NULL} and a packed numeric field always holds a value. A scale greater than two is
     * rejected, because {@code V99} declares exactly two decimal positions and PostgreSQL rounds a
     * {@code NUMERIC(11,2)} insert half away from zero rather than refusing it - a silent alteration, and
     * by a rounding mode that is not the {@code RoundingMode.HALF_EVEN} the batch layer uses. A magnitude
     * outside -999999999.99 through 999999999.99 is rejected, because nine integer digits cannot hold it.
     *
     * <p><strong>What is deliberately not checked:</strong> the sign, because the picture clause carries an
     * {@code S} and {@code app/cbl/CBTRN02C.cbl:L547-L552} adds a negative amount to the cycle debit
     * accumulator, which is precisely why the over-limit formula subtracts it; zero; and a scale smaller
     * than two, since {@code 2} and {@code 2.00} denote the same amount. <strong>No absolute value, no
     * rescaling and no rounding is applied anywhere in this class.</strong>
     *
     * <p>Declared {@code private static} for the reason given on
     * {@link #requireWidth(String, String, String, int)}.
     *
     * @param value the candidate amount, possibly {@code null}
     * @return {@code value}, unchanged and unrescaled
     * @throws IllegalArgumentException if {@code value} is {@code null}, has more than two decimal digits,
     *                                  or falls outside the representable range
     */
    private static BigDecimal requireAmount(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("amount (TRAN-AMT PIC S9(09)V99) must not be null: it maps "
                    + "to a NOT NULL NUMERIC(" + AMOUNT_PRECISION + "," + AMOUNT_SCALE
                    + ") column of table transaction");
        }
        if (value.scale() > AMOUNT_SCALE) {
            throw new IllegalArgumentException("amount (TRAN-AMT PIC S9(09)V99) must carry at most "
                    + AMOUNT_SCALE + " decimal digits but had a scale of " + value.scale()
                    + "; rescale it explicitly with RoundingMode.HALF_EVEN rather than letting the NUMERIC("
                    + AMOUNT_PRECISION + "," + AMOUNT_SCALE + ") column round it");
        }
        if (value.compareTo(MIN_AMOUNT) < 0 || value.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("amount (TRAN-AMT PIC S9(09)V99) must be between "
                    + MIN_AMOUNT.toPlainString() + " and " + MAX_AMOUNT.toPlainString()
                    + " inclusive, which is what " + AMOUNT_INTEGER_DIGITS
                    + " signed integer digits can hold, but was " + value.toPlainString());
        }
        return value;
    }
}
