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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Staging row for one inbound daily transaction, replacing the physical sequential dataset
 * {@code AWS.M2.CARDDEMO.DALYTRAN.PS}.
 *
 * <p>This is the only table in the model whose source is not a VSAM KSDS cluster: the dataset is catalogued as
 * a {@code NONVSAM} entry and has no cluster, no record key and no alternate index. The 350-byte layout is
 * {@code app/cpy/CVTRA06Y.cpy}, and the ASCII fixture that seeds it spells the word in full as
 * {@code app/data/ASCII/dailytran.txt} even though the mainframe DD name and dataset are {@code DALYTRAN}.
 *
 * <p>Its rows are untrusted input. The posting job reads them before validating them, so every field must load
 * exactly as presented; no mutator here trims, normalises or rejects a value, and the amount keeps its sign
 * because the fixture carries genuinely negative amounts that drive the cycle-debit branch.
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
 *  -   none - not a record field    none         none       ingestSequence      ingest_seq                NUMERIC(9) PK
 *  1   DALYTRAN-ID          :L5     X(16)          1-16     transactionId       dalytran_id               CHAR(16)
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
 * <p><strong>{@code ingest_seq} appears in the table above with no offsets and no
 * PIC clause, and that is deliberate.</strong> It is the one column on this entity
 * that is not record content: it occupies none of the 350 bytes, so it contributes
 * zero to the width arithmetic below and the fixed-width reader and writer neither
 * consume nor emit it. It is listed first because it is the identifier, and its
 * blank offset cells are the marker that it is table metadata rather than a
 * copybook field. Adding it to the byte budget would break the geometry; omitting
 * it from this map altogether would hide the row's identity.</p>
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
 *       back to its copybook line by name alone;</li>
 *   <li>the one <strong>synthetic</strong> column, {@code ingest_seq}, deliberately
 *       does <em>not</em> carry the {@code dalytran_} prefix, precisely because it
 *       has no copybook line to trace back to. The prefix is what separates the
 *       thirteen record columns from the one metadata column, so a reader can tell
 *       them apart by name alone. Naming it {@code dalytran_ingest_seq} would
 *       assert a copybook field that does not exist.</li>
 * </ul>
 * <p>Readability is served by the Java-side name; traceability, which the
 * migration is contractually measured on, is served by the column name. Neither
 * convention is a substitute for the other, so both are kept.</p>
 *
 * <h2>Finding: BLOCKER - {@code DALYTRAN-ID} is not the identifier, and must never
 * be made one</h2>
 * <p>This is the single most consequential mapping decision on the class, and the
 * intuitive reading of the copybook gets it wrong. {@code DALYTRAN-ID} at
 * {@code :L5} is the first field of the record and looks exactly like the key of
 * the master transaction record it feeds, so promoting it to {@code @Id} is the
 * obvious move. It is also incorrect, because <strong>the dataset it stages has no
 * key at all</strong>.</p>
 *
 * <p><strong>The corpus evidence is exhaustive and entirely negative.</strong>
 * Exactly two programs open this file, {@code app/cbl/CBTRN01C.cbl} and
 * {@code app/cbl/CBTRN02C.cbl}, and both declare it identically -
 * {@code ORGANIZATION IS SEQUENTIAL} and {@code ACCESS MODE IS SEQUENTIAL} at
 * {@code CBTRN02C.cbl:L30-L31} and {@code CBTRN01C.cbl:L30-L31}. Their whole verb
 * inventory against it is three statements:</p>
 * <ul>
 *   <li>{@code OPEN INPUT} at {@code CBTRN02C.cbl:L238} and
 *       {@code CBTRN01C.cbl:L254};</li>
 *   <li>a bare {@code READ ... INTO} inside {@code 1000-DALYTRAN-GET-NEXT} at
 *       {@code CBTRN02C.cbl:L346} and {@code CBTRN01C.cbl:L203}, whose only two
 *       accepted file statuses are {@code '00'} continue and {@code '10'} end of
 *       file, with anything else abending;</li>
 *   <li>{@code CLOSE} at {@code CBTRN02C.cbl:L584} and
 *       {@code CBTRN01C.cbl:L363}.</li>
 * </ul>
 * <p>There is no {@code STARTBR}, no keyed {@code READ}, no {@code READ ... KEY IS}
 * and no {@code INVALID KEY} path against this file anywhere in {@code app/cbl},
 * because a physical sequential dataset has no key to browse. The catalogue agrees:
 * there is no cluster block for it and no IDCAMS {@code KEYS(...)} operand for it in
 * any job - {@code app/jcl/POSTTRAN.jcl:L30-L31} allocates it with
 * {@code DISP=SHR} and a dataset name and nothing else. A flat file may therefore
 * legitimately carry the same transaction identifier twice, and the source posts
 * both records without complaint.</p>
 *
 * <p><strong>Why this is a Blocker rather than a nuisance: the shipped fixture
 * cannot detect the error.</strong> {@code app/data/ASCII/dailytran.txt} is both
 * unique on that field <em>and</em> already ascending by it. A unique key over
 * {@code dalytran_id} therefore passes every test built from the fixture, and every
 * ordering assertion that sorts by identifier also passes, and the invented
 * constraint then rejects real input in production. The fixture masks the defect
 * completely, which is why the correct treatment is stated here rather than left to
 * be inferred from the copybook.</p>
 *
 * <p><strong>The identity is therefore an ingestion sequence.</strong> For a
 * sequential dataset the record's position in the file <em>is</em> its identity, and
 * the source already counts exactly that: {@code WS-TRANSACTION-COUNT}, declared
 * {@code PIC 9(09) VALUE 0} at {@code app/cbl/CBTRN02C.cbl:L185}, incremented once
 * per accepted read by {@code ADD 1 TO WS-TRANSACTION-COUNT} at {@code :L206}, and
 * reported at {@code :L227}. {@code ingestSequence} is that ordinal: one-based,
 * dense, in read order. Its {@code NUMERIC(9)} column is not a guess - it is the
 * declared precision of the counter being modelled, so the domain is 1 through
 * 999,999,999. That domain is documented rather than constrained: the first
 * migration's check-constraint budget is exactly five and this table contributes
 * none of them, so a sixth is not added to express a range the loader controls
 * anyway.</p>
 *
 * <p><strong>The ordinal is assigned by the loader, never by the database.</strong>
 * There is no generated-value strategy, no identity column and no sequence, here or
 * anywhere in the schema. Three reasons, in order of weight. First, determinism:
 * re-staging the same input file must yield the same ordinals, which a
 * database-side allocator cannot promise because it keeps counting across a
 * truncate-and-reload. Second, parity: the ordinal has to equal the source's own
 * read counter for the two to be comparable at all, and only the reader knows the
 * read position. Third, load shape: staged rows arrive through a batched JDBC
 * update, and a database-side identity forces a per-row round trip to retrieve the
 * generated value. This is also why the all-columns constructor takes the ordinal
 * as its first argument - a staging row cannot be constructed without one.</p>
 *
 * <p><strong>Consequences a caller must respect.</strong> {@code transactionId}
 * remains an ordinary, non-unique, fixed-width sixteen-character column and no
 * uniqueness may be reintroduced over it in any artefact. Ordering a staged read by
 * {@code ingestSequence} reproduces the flat-file read order exactly; ordering it by
 * {@code transactionId} does not, and would silently reorder real input. And
 * {@code equals} and {@code hashCode} are keyed on the ordinal, so two staged rows
 * that happen to share a transaction identifier are correctly unequal.</p>
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
 *       between the time parts, and <strong>hundredths of a second</strong>
 *       precision followed by four literal zero characters, which
 *       {@code :L700-L701} confirm by moving the hundredths into place and then
 *       the literal {@code '0000'}. The fractional field is two digits wide, not
 *       three: {@code DB2-MIL} is {@code PIC 9(002)} at {@code :L173}. One
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
 * {@code equals} is keyed on the ingestion ordinal alone, so it is unaffected.</p>
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
 * carries the same thirteen record fields in the same order at the same widths,
 * differing only in the COBOL field-name prefix. Extracting a mapped superclass, an
 * abstract base entity, a shared interface or a mapper class is nonetheless
 * rejected, and this decision is recorded here so it is not "tidied up" later.</p>
 * <p>The two tables are not the same kind of thing, and their identities are not
 * even the same shape. The master table is keyed on {@code TRAN-ID}, the natural
 * sixteen-character key of a catalogued VSAM cluster; this table is keyed on an
 * ingestion ordinal, because its dataset is unkeyed. So the two entities agree on
 * thirteen columns and disagree on the fourteenth, which is the identifier - the one
 * column a supertype would most want to own. Beyond that, this one is a
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
 *   <li><strong>No generated-identifier strategy and no database sequence,
 *       including for the ordinal that is the identifier.</strong> The ingestion
 *       ordinal is assigned by the loader in read order, so no generated-value
 *       annotation and no sequence generator appears on this class - see the
 *       identity finding above for the three reasons. The business identifier is
 *       equally never generated: it arrives from the input file verbatim, and
 *       {@code app/cbl/CBTRN02C.cbl:L425} moves the record's own identifier
 *       straight into the master record, so generating one would destroy the
 *       correspondence between input file and row.</li>
 *   <li><strong>Every column is NOT NULL, and every text column accepts a
 *       blank-but-non-null value.</strong> A fixed-width record has no concept of
 *       absence: an unpopulated field is spaces or zeros, never nothing. No
 *       emptiness constraint is declared anywhere. The ordinal is {@code NOT NULL}
 *       for the stronger reason that it is the primary key.</li>
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
 * <p>Accessors are plain field access and cannot fail. The constructor and every
 * setter refuse a value the 350-byte record layout cannot have produced - a
 * {@code null}, a field wider than its picture clause, a code outside its
 * unsigned range, or an amount outside {@code S9(09)V99} - and report it with
 * {@code java.lang.IllegalArgumentException} naming the offending property and
 * that field's picture clause. Nothing else is refused, so no business rule and
 * no reject code is pre-empted here. The project exception hierarchy is
 * deliberately not reachable from this package, because a data holder that
 * depended on it would invert the intended layering. The remaining failure modes
 * are external to the class:</p>
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
 * <p>Build with {@code ./mvnw -B clean compile}; run the unit suite with
 * {@code ./mvnw -B clean test}. Compilation is strict - every lint category
 * {@code javac} 25 publishes is enabled and warnings are errors - so an unnecessary
 * cast or a deprecated call fails the build rather than being reported. An unused
 * import does not: {@code javac} 25.0.3 publishes no lint key for one, so that
 * prohibition is review-enforced. Tests covering this entity belong in
 * {@code src/test/java/com/cardemo/unit/model} and are to assert the 350-byte geometry
 * and offset map, that the two TS fields are declared as text of length 26, that
 * a blank 26-space process TS persists and reloads unchanged, that the amount is
 * eleven digits at scale two, and that a negative amount round-trips with its
 * sign intact. <strong>Not available, measured 1 August 2026:</strong> no
 * {@code DailyTransactionTest} exists and neither the repository nor the batch tier
 * exists, so that whole list is the coverage owed rather than coverage that runs.
 * Repository and batch tiers are additionally to exercise the round trip
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
 * <h2>Schema reconciliation, and what is still not available</h2>
 * <p><strong>Measured 1 August 2026:</strong> {@code V1__create_schema.sql} is
 * <strong>present</strong> and declares
 * {@code CREATE TABLE daily_transaction} with 13 columns whose names
 * are identical, as a set, to the 13 {@code @Column(name = ...)} declarations in
 * this class, verified by direct comparison. Because
 * {@code spring.jpa.hibernate.ddl-auto: validate} is the mandated setting, any
 * mismatch of column name, SQL type, precision, scale or nullability would fail
 * application-context startup outright rather than degrading quietly - so the two
 * artefacts must agree exactly. What remains <strong>not available</strong> is
 * {@code V2__create_indexes.sql}, {@code V3__seed_data.sql} and all four
 * {@code application*.yml} profiles, so that {@code validate} behaviour is
 * mandated rather than observed.</p>
 *
 * <p>What that migration declares for table {@code daily_transaction}, and what
 * this mapping asserts, is:</p>
 *
 * <pre>
 * ingest_seq              NUMERIC(9)      PRIMARY KEY   &lt;- no copybook line
 * dalytran_id             CHAR(16)        NOT NULL      &lt;- NOT unique
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
 * <p><strong>No version column. No foreign key. No unique constraint and no unique
 * index over {@code dalytran_id}</strong>, in this migration or any later one - see
 * the identity finding above for why that would be a fabricated constraint the
 * shipped fixture cannot detect. The table is seeded from
 * {@code app/data/ASCII/dailytran.txt} by {@code V3__seed_data.sql}, using
 * position-aware zoned-decimal overpunch decoding driven by the PIC clauses and
 * the decode table given earlier, and assigning {@code ingest_seq} as the
 * one-based row ordinal in file order. Position-awareness is not optional: a naive
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
 *
 * <p><b>JSON serialisation barrier.</b> This class is structurally unserialisable by
 * Jackson. {@link JsonIgnoreType} removes any property whose declared type is this class
 * from an enclosing object's JSON, and {@link JsonAutoDetect} with every visibility set
 * to {@code NONE} switches off bean introspection entirely, so no getter, no setter, no
 * field and no creator is discoverable. An entity is a bean with public accessors, so
 * without the barrier the default behaviour of returning this type from a controller, or
 * holding a field of it on a response object, is to publish the card number alongside the
 * amount and the full merchant detail. That risk is not theoretical on a staging table:
 * this is the entity a batch diagnostic endpoint would most plausibly be asked to expose.
 * With the barrier in place Jackson finds no properties and its default
 * {@code FAIL_ON_EMPTY_BEANS} setting turns the mistake into a loud failure rather than a
 * silent disclosure. Nothing legitimate is lost, because this staging type has no outbound
 * representation at all - it is written by the reader and consumed by the posting
 * processor - and persistence is unaffected because Hibernate reads and writes the
 * annotated fields reflectively and never consults Jackson visibility.</p>
 */
@Entity
@Table(name = "daily_transaction")
@JsonIgnoreType
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class DailyTransaction {

    /**
     * Character widths of the ten alphanumeric fields of {@code app/cpy/CVTRA06Y.cpy}, in record order,
     * together with the numeric domains of the three others. The copybook is the authority for every one of
     * these numbers, and the fixed-width reader slices exactly these byte counts out of each 350-byte
     * record, which is why a value that violates one of them cannot have come from the input file at all.
     */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** Width of {@code DALYTRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA06Y.cpy:L6}. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** Width of {@code DALYTRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA06Y.cpy:L8}. */
    private static final int TRANSACTION_SOURCE_WIDTH = 10;

    /** Width of {@code DALYTRAN-DESC PIC X(100)} at {@code app/cpy/CVTRA06Y.cpy:L9}. */
    private static final int DESCRIPTION_WIDTH = 100;

    /** Width of {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at {@code app/cpy/CVTRA06Y.cpy:L12}. */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** Width of {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at {@code app/cpy/CVTRA06Y.cpy:L13}. */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** Width of {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} at {@code app/cpy/CVTRA06Y.cpy:L14}. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** Width of {@code DALYTRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA06Y.cpy:L15}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Width of {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}. */
    private static final int ORIG_TS_WIDTH = 26;

    /** Width of {@code DALYTRAN-PROC-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L17}. */
    private static final int PROC_TS_WIDTH = 26;

    /** Smallest value the unsigned {@code DALYTRAN-CAT-CD PIC 9(04)} at {@code :L7} can represent. */
    private static final int MIN_CATEGORY_CODE = 0;

    /** Largest value {@code DALYTRAN-CAT-CD PIC 9(04)} can represent: four unsigned display digits. */
    private static final int MAX_CATEGORY_CODE = 9999;

    /** Smallest value the unsigned {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at {@code :L11} can represent. */
    private static final long MIN_MERCHANT_ID = 0L;

    /** Largest value {@code DALYTRAN-MERCHANT-ID PIC 9(09)} can represent: nine unsigned display digits. */
    private static final long MAX_MERCHANT_ID = 999_999_999L;

    /** Integer digit count of {@code DALYTRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA06Y.cpy:L10}. */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Decimal digit count of {@code DALYTRAN-AMT PIC S9(09)V99}: the two digits after the implied V. */
    private static final int AMOUNT_SCALE = 2;

    /** Total precision of the mapped column: nine integer digits plus two decimal digits. */
    private static final int AMOUNT_PRECISION = AMOUNT_INTEGER_DIGITS + AMOUNT_SCALE;

    /**
     * Largest value {@code DALYTRAN-AMT PIC S9(09)V99} can represent. Built from a string literal so the
     * bound is exact, and reached from the eleven zoned-decimal bytes the record allocates to the field.
     */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    /**
     * Smallest value {@code DALYTRAN-AMT PIC S9(09)V99} can represent. The picture clause is signed and
     * {@code app/data/ASCII/dailytran.txt} carries close-brace overpunch characters, so genuinely negative
     * amounts are legitimate input and are never normalised.
     */
    private static final BigDecimal MIN_AMOUNT = MAX_AMOUNT.negate();

    /** {@code DALYTRAN-ID}, {@code PIC X(16)}, bytes 1-16. Primary key, taken verbatim from the input file. */
    @Id
    @Column(name = "ingest_seq", nullable = false, precision = 9, scale = 0, columnDefinition = "NUMERIC(9)")
    private Long ingestSequence;

    /**
     * {@code DALYTRAN-ID}, {@code PIC X(16)}, bytes 1-16.
     *
     * <p><strong>Ordinary non-unique data, not the key.</strong> Taken verbatim from the
     * input file, which is unkeyed physical sequential and may legitimately repeat it.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_id", nullable = false, length = TRANSACTION_ID_WIDTH, columnDefinition = "CHAR(16)")
    private String transactionId;

    /**
     * {@code DALYTRAN-TYPE-CD}, {@code PIC X(02)}, bytes 17-18.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_type_cd", nullable = false, length = TYPE_CODE_WIDTH, columnDefinition = "CHAR(2)")
    private String typeCode;

    /**
     * {@code DALYTRAN-CAT-CD}, {@code PIC 9(04)}, bytes 19-22. Unsigned four-digit code.
     */
    @Column(name = "dalytran_cat_cd", nullable = false, precision = 4, scale = 0, columnDefinition = "NUMERIC(4)")
    private Integer categoryCode;

    /**
     * {@code DALYTRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32. Free text, never a closed domain.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_source", nullable = false, length = TRANSACTION_SOURCE_WIDTH,
            columnDefinition = "CHAR(10)")
    private String transactionSource;

    /**
     * {@code DALYTRAN-DESC}, {@code PIC X(100)}, bytes 33-132.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_desc", nullable = false, length = DESCRIPTION_WIDTH,
            columnDefinition = "CHAR(100)")
    private String description;

    /** {@code DALYTRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143. Signed; negative values are valid. */
    @Column(name = "dalytran_amt", nullable = false, precision = AMOUNT_PRECISION, scale = AMOUNT_SCALE,
            columnDefinition = "NUMERIC(11,2)")
    private BigDecimal amount;

    /**
     * {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)}, bytes 144-152. Unsigned nine-digit code.
     */
    @Column(name = "dalytran_merchant_id", nullable = false, precision = 9, scale = 0,
            columnDefinition = "NUMERIC(9)")
    private Long merchantId;

    /**
     * {@code DALYTRAN-MERCHANT-NAME}, {@code PIC X(50)}, bytes 153-202.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_merchant_name", nullable = false, length = MERCHANT_NAME_WIDTH,
            columnDefinition = "CHAR(50)")
    private String merchantName;

    /**
     * {@code DALYTRAN-MERCHANT-CITY}, {@code PIC X(50)}, bytes 203-252.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_merchant_city", nullable = false, length = MERCHANT_CITY_WIDTH,
            columnDefinition = "CHAR(50)")
    private String merchantCity;

    /**
     * {@code DALYTRAN-MERCHANT-ZIP}, {@code PIC X(10)}, bytes 253-262.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_merchant_zip", nullable = false, length = MERCHANT_ZIP_WIDTH,
            columnDefinition = "CHAR(10)")
    private String merchantZip;

    /**
     * {@code DALYTRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278. Never rendered by {@code toString}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_card_num", nullable = false, length = CARD_NUMBER_WIDTH, columnDefinition = "CHAR(16)")
    private String cardNumber;

    /**
     * {@code DALYTRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304. Carried as text, never parsed.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_orig_ts", nullable = false, length = ORIG_TS_WIDTH, columnDefinition = "CHAR(26)")
    private String origTs;

    /**
     * {@code DALYTRAN-PROC-TS}, {@code PIC X(26)}, bytes 305-330. Blank in every fixture row; must load.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dalytran_proc_ts", nullable = false, length = PROC_TS_WIDTH, columnDefinition = "CHAR(26)")
    private String procTs;

    /**
     * No-argument constructor required by the persistence provider so that it can create a row object before
     * populating it.
     */
    protected DailyTransaction() {
        // Intentionally empty: the provider populates every field afterwards, and a default invented here
        // would mask a column it failed to set.
    }

    /**
     * Creates a fully populated staging row from one 350-byte {@code DALYTRAN-RECORD} image.
     *
     * <p>The <strong>ingestion ordinal comes first</strong>, because it is the
     * identifier and because the reader knows it before it parses anything: it is the
     * read counter's current value. Taking it as a required argument is what makes a
     * staging row impossible to construct without an identity, which matters here
     * precisely because no database-side allocator will supply one - see the identity
     * finding in the class documentation. The remaining thirteen parameters are in the
     * COBOL field order of {@code app/cpy/CVTRA06Y.cpy:L5-L17}, so a caller reading the
     * record left to right supplies them top to bottom with no reordering. The 20-byte
     * {@code FILLER} at {@code :L18} has no parameter because it carries no data.</p>
     *
     * <p>All fourteen values are stored exactly as supplied. Nothing is trimmed,
     * padded, upper-cased, re-signed, rounded or re-formatted, because every one of
     * those transformations would break byte-level parity with the source record.
     * In particular a blank process TS and a negative amount are both stored
     * unchanged, since the fixture proves both are legitimate, and a
     * {@code transactionId} that duplicates one already staged is accepted, since the
     * unkeyed input may legitimately repeat it.</p>
     *
     * <p><strong>Every value is nevertheless checked against the record layout,
     * and that is a structural check rather than a business one.</strong> This
     * table deliberately holds unvalidated business content - a card number with
     * no cross-reference row, an amount that will breach a credit limit - and none
     * of that is rejected here, because rejecting it would pre-empt the posting
     * job's reject codes. What is rejected is a value the 350-byte record cannot
     * have produced: {@code null}, since the reader slices fixed byte ranges and a
     * {@code PIC X} field always holds its width; text longer than its field; a
     * category code or merchant identifier outside its unsigned range; and an
     * amount outside {@code S9(09)V99} or carrying more than two decimal digits.
     * Such a value can only be a defect in the caller, and naming the property and
     * its picture clause here is more use than a constraint violation naming a
     * column at flush time.</p>
     *
     * <p>This constructor performs field assignment through private static
     * helpers only. It calls no method on the instance under construction, so no
     * partially initialised reference can escape, and it has no side effect beyond
     * populating this object.</p>
     *
     * @param ingestSequence    the one-based ordinal of this record within the staged
     *                          file, in read order; the primary key, modelling
     *                          {@code WS-TRANSACTION-COUNT} at
     *                          {@code app/cbl/CBTRN02C.cbl:L185} and {@code :L206}. Not
     *                          a record field and not derived from one
     * @param transactionId     {@code DALYTRAN-ID}, {@code PIC X(16)}, bytes 1-16;
     *                          ordinary non-unique data taken verbatim from the input
     *                          file, and explicitly not the key
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
            final Long ingestSequence,
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
        this.ingestSequence = ingestSequence;
        this.transactionId = requireWidth(transactionId,
                "transactionId", "DALYTRAN-ID PIC X(16)", TRANSACTION_ID_WIDTH);
        this.typeCode = requireWidth(typeCode,
                "typeCode", "DALYTRAN-TYPE-CD PIC X(02)", TYPE_CODE_WIDTH);
        this.categoryCode = requireCategoryCode(categoryCode);
        this.transactionSource = requireWidth(transactionSource,
                "transactionSource", "DALYTRAN-SOURCE PIC X(10)", TRANSACTION_SOURCE_WIDTH);
        this.description = requireWidth(description,
                "description", "DALYTRAN-DESC PIC X(100)", DESCRIPTION_WIDTH);
        this.amount = requireAmount(amount);
        this.merchantId = requireMerchantId(merchantId);
        this.merchantName = requireWidth(merchantName,
                "merchantName", "DALYTRAN-MERCHANT-NAME PIC X(50)", MERCHANT_NAME_WIDTH);
        this.merchantCity = requireWidth(merchantCity,
                "merchantCity", "DALYTRAN-MERCHANT-CITY PIC X(50)", MERCHANT_CITY_WIDTH);
        this.merchantZip = requireWidth(merchantZip,
                "merchantZip", "DALYTRAN-MERCHANT-ZIP PIC X(10)", MERCHANT_ZIP_WIDTH);
        this.cardNumber = requireWidth(cardNumber,
                "cardNumber", "DALYTRAN-CARD-NUM PIC X(16)", CARD_NUMBER_WIDTH);
        this.origTs = requireWidth(origTs, "origTs", "DALYTRAN-ORIG-TS PIC X(26)", ORIG_TS_WIDTH);
        this.procTs = requireWidth(procTs, "procTs", "DALYTRAN-PROC-TS PIC X(26)", PROC_TS_WIDTH);
    }

    /**
     * Returns the primary key: the one-based ordinal of this record within the
     * staged file.
     *
     * <p>This is the value to order a staged read by. Ordering by it reproduces the
     * flat-file read order that {@code 1000-DALYTRAN-GET-NEXT} at
     * {@code app/cbl/CBTRN02C.cbl:L345-L346} produces; ordering by
     * {@link #getTransactionId()} does not, and would silently reorder real input.</p>
     *
     * @return the one-based ingestion ordinal, or {@code null} on an instance the
     *         persistence provider has created but not yet populated
     */
    public Long getIngestSequence() {
        return this.ingestSequence;
    }

    /**
     * Sets the primary key: the one-based ordinal of this record within the staged
     * file.
     *
     * <p>Assigned by the loader from its own read counter, modelling
     * {@code WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:L185} which
     * {@code :L206} increments once per accepted read. It is not generated by the
     * database, so this setter is the only route by which a new row acquires an
     * identity, and the all-columns constructor is the preferred one.</p>
     *
     * <p>Stored verbatim. The value is not range-checked here: the modelled counter is
     * {@code PIC 9(09)}, so the domain is 1 through 999,999,999, and that domain is
     * documented rather than enforced because the loader controls it and the first
     * migration's check-constraint budget is closed. Mutating the ordinal of a row
     * that is already persistent changes its identity and must not be done.</p>
     *
     * @param ingestSequence the one-based ingestion ordinal, in read order
     */
    public void setIngestSequence(final Long ingestSequence) {
        this.ingestSequence = ingestSequence;
    }

    /**
     * Returns {@code DALYTRAN-ID}, {@code PIC X(16)}, bytes 1-16 of the record.
     *
     * @return the sixteen-character staging identifier, exactly as loaded
     */
    public String getTransactionId() {
        return this.transactionId;
    }

    /**
     * Sets {@code DALYTRAN-ID}, {@code PIC X(16)}, bytes 1-16 of the record.
     *
     * @param transactionId the sixteen-character staging identifier
     */
    public void setTransactionId(final String transactionId) {
        this.transactionId = requireWidth(transactionId,
                "transactionId", "DALYTRAN-ID PIC X(16)", TRANSACTION_ID_WIDTH);
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
     * @param typeCode the two-character transaction type code
     */
    public void setTypeCode(final String typeCode) {
        this.typeCode = requireWidth(typeCode,
                "typeCode", "DALYTRAN-TYPE-CD PIC X(02)", TYPE_CODE_WIDTH);
    }

    /**
     * Returns {@code DALYTRAN-CAT-CD}, {@code PIC 9(04)}, bytes 19-22 of the record.
     *
     * @return the four-digit transaction category code, exactly as loaded
     */
    public Integer getCategoryCode() {
        return this.categoryCode;
    }

    /**
     * Sets {@code DALYTRAN-CAT-CD}, {@code PIC 9(04)}, bytes 19-22 of the record.
     *
     * @param categoryCode the four-digit transaction category code
     */
    public void setCategoryCode(final Integer categoryCode) {
        this.categoryCode = requireCategoryCode(categoryCode);
    }

    /**
     * Returns {@code DALYTRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32 of the record.
     *
     * @return the ten-character origin text, exactly as loaded
     */
    public String getTransactionSource() {
        return this.transactionSource;
    }

    /**
     * Sets {@code DALYTRAN-SOURCE}, {@code PIC X(10)}, bytes 23-32 of the record.
     *
     * @param transactionSource the ten-character origin text
     */
    public void setTransactionSource(final String transactionSource) {
        this.transactionSource = requireWidth(transactionSource,
                "transactionSource", "DALYTRAN-SOURCE PIC X(10)", TRANSACTION_SOURCE_WIDTH);
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
     * @param description the hundred-character description
     */
    public void setDescription(final String description) {
        this.description = requireWidth(description,
                "description", "DALYTRAN-DESC PIC X(100)", DESCRIPTION_WIDTH);
    }

    /**
     * Returns {@code DALYTRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143 of the record: eleven digits of
     * precision at a scale of two.
     *
     * @return the signed transaction amount at scale two, exactly as loaded
     */
    public BigDecimal getAmount() {
        return this.amount;
    }

    /**
     * Sets {@code DALYTRAN-AMT}, {@code PIC S9(09)V99}, bytes 133-143 of the record.
     *
     * @param amount the signed transaction amount, negative values included
     */
    public void setAmount(final BigDecimal amount) {
        this.amount = requireAmount(amount);
    }

    /**
     * Returns {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)}, bytes 144-152 of the record.
     *
     * @return the nine-digit merchant code, exactly as loaded
     */
    public Long getMerchantId() {
        return this.merchantId;
    }

    /**
     * Sets {@code DALYTRAN-MERCHANT-ID}, {@code PIC 9(09)}, bytes 144-152 of the record.
     *
     * @param merchantId the nine-digit merchant code
     */
    public void setMerchantId(final Long merchantId) {
        this.merchantId = requireMerchantId(merchantId);
    }

    /**
     * Returns {@code DALYTRAN-MERCHANT-NAME}, {@code PIC X(50)}, bytes 153-202 of the record.
     *
     * @return the fifty-character merchant name, exactly as loaded
     */
    public String getMerchantName() {
        return this.merchantName;
    }

    /**
     * Sets {@code DALYTRAN-MERCHANT-NAME}, {@code PIC X(50)}, bytes 153-202 of the record.
     *
     * @param merchantName the fifty-character merchant name
     */
    public void setMerchantName(final String merchantName) {
        this.merchantName = requireWidth(merchantName,
                "merchantName", "DALYTRAN-MERCHANT-NAME PIC X(50)", MERCHANT_NAME_WIDTH);
    }

    /**
     * Returns {@code DALYTRAN-MERCHANT-CITY}, {@code PIC X(50)}, bytes 203-252 of the record.
     *
     * @return the fifty-character merchant city, exactly as loaded
     */
    public String getMerchantCity() {
        return this.merchantCity;
    }

    /**
     * Sets {@code DALYTRAN-MERCHANT-CITY}, {@code PIC X(50)}, bytes 203-252 of the record.
     *
     * @param merchantCity the fifty-character merchant city
     */
    public void setMerchantCity(final String merchantCity) {
        this.merchantCity = requireWidth(merchantCity,
                "merchantCity", "DALYTRAN-MERCHANT-CITY PIC X(50)", MERCHANT_CITY_WIDTH);
    }

    /**
     * Returns {@code DALYTRAN-MERCHANT-ZIP}, {@code PIC X(10)}, bytes 253-262 of the record.
     *
     * @return the ten-character merchant postal code, exactly as loaded
     */
    public String getMerchantZip() {
        return this.merchantZip;
    }

    /**
     * Sets {@code DALYTRAN-MERCHANT-ZIP}, {@code PIC X(10)}, bytes 253-262 of the record.
     *
     * @param merchantZip the ten-character merchant postal code
     */
    public void setMerchantZip(final String merchantZip) {
        this.merchantZip = requireWidth(merchantZip,
                "merchantZip", "DALYTRAN-MERCHANT-ZIP PIC X(10)", MERCHANT_ZIP_WIDTH);
    }

    /**
     * Returns {@code DALYTRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278 of the record.
     *
     * @return the sixteen-character card number, exactly as loaded
     */
    public String getCardNumber() {
        return this.cardNumber;
    }

    /**
     * Sets {@code DALYTRAN-CARD-NUM}, {@code PIC X(16)}, bytes 263-278 of the record.
     *
     * @param cardNumber the sixteen-character card number
     */
    public void setCardNumber(final String cardNumber) {
        this.cardNumber = requireWidth(cardNumber,
                "cardNumber", "DALYTRAN-CARD-NUM PIC X(16)", CARD_NUMBER_WIDTH);
    }

    /**
     * Returns {@code DALYTRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304 of the record.
     *
     * @return the twenty-six-character origination TS text, exactly as loaded
     */
    public String getOrigTs() {
        return this.origTs;
    }

    /**
     * Sets {@code DALYTRAN-ORIG-TS}, {@code PIC X(26)}, bytes 279-304 of the record.
     *
     * @param origTs the twenty-six-character origination TS text
     */
    public void setOrigTs(final String origTs) {
        this.origTs = requireWidth(origTs, "origTs", "DALYTRAN-ORIG-TS PIC X(26)", ORIG_TS_WIDTH);
    }

    /**
     * Returns {@code DALYTRAN-PROC-TS}, {@code PIC X(26)}, bytes 305-330 of the
     * record.
     *
     * <p><strong>This value is blank on input.</strong> All 300 fixture rows hold
     * the same single value in bytes 305-330: twenty-six spaces. The field is filled
     * in downstream, not upstream - {@code app/cbl/CBTRN02C.cbl:L438} writes the
     * producer-formatted value into the master record at posting time, in the shape
     * documented at {@code :L149}, which is hundredths-of-a-second precision followed
     * by four literal zero characters as {@code :L700-L701} confirm.</p>
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
     * @param procTs the twenty-six-character process TS text, possibly all spaces
     */
    public void setProcTs(final String procTs) {
        this.procTs = requireWidth(procTs, "procTs", "DALYTRAN-PROC-TS PIC X(26)", PROC_TS_WIDTH);
    }

    /**
     * Compares two staging rows on the ingestion ordinal alone.
     *
     * @param other the object to compare against.
     * @return {@code true} when {@code other} is a staging row with an equal {@code DALYTRAN-ID}, {@code false}
     * otherwise
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DailyTransaction that)) {
            return false;
        }
        return Objects.equals(this.ingestSequence, that.ingestSequence);
    }

    /**
     * Returns a hash consistent with {@link #equals(Object)}, derived from {@code DALYTRAN-ID} alone.
     *
     * @return the hash of the ingestion ordinal, or zero when it is not yet set
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.ingestSequence);
    }

    /**
     * Returns a diagnostic rendering of this row that deliberately omits the card number.
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
     * <p><b>The signed amount and both TS values are excluded as well, which narrows an
     * earlier and wider form of this method.</b> An amount is customer financial data,
     * and because this rendering also carries the transaction identifier, a log estate
     * holding both holds a per-transaction amount ledger keyed by a value that joins
     * straight back to the row - the substance of the account activity, reconstructable
     * with no database access. Adding the two TS values would turn that ledger into a
     * timeline. Neither timestamp identifies a row that the identifier does not already
     * identify, so nothing correlational is lost by their removal. The type code, the
     * category code and the origin text go for the least-privilege reason that governs
     * the rest of this package: they are classification state, and a reader should
     * obtain that from the row, where the access is authorised and audited.</p>
     *
     * <p>What remains is the identifier alone, which is the minimum that tells a reader
     * which row a line refers to. This entity carries no optimistic lock version to
     * render alongside it - it is a staging table that is loaded and consumed within one
     * job, never concurrently updated - so unlike {@link Transaction} there is no
     * version counter here and its absence is deliberate rather than an omission. The
     * identifier is rendered by plain concatenation, independent of locale, so the
     * output is identical on every machine.</p>
     *
     * <p>The format is a diagnostic aid, not an interface. It is not parsed anywhere
     * and no caller should depend on its exact shape.</p>
     *
     * @return a rendering carrying the transaction identifier only, never the card
     *         number, the amount, either TS value or merchant detail
     */
    @Override
    public String toString() {
        return "DailyTransaction{transactionId=" + this.transactionId + '}';
    }

    /**
     * Validates a candidate character value against the width of the COBOL field it
     * comes from and returns it unchanged.
     *
     * <p>Rejects {@code null}, because every character column here is {@code NOT
     * NULL} and because the fixed-width reader always yields a string of the
     * field's width, and rejects any value longer than the picture clause declares,
     * because a 350-byte record cannot contain one. Everything the picture clause
     * admits is accepted, including a value of only spaces - the blank
     * {@code DALYTRAN-PROC-TS} of every fixture row is exactly that - and any
     * character content whatever. Nothing is trimmed, padded or case folded.</p>
     *
     * <p><strong>The failure message reports the length and never the value.</strong>
     * Two of the ten character fields guarded here, {@code DALYTRAN-CARD-NUM} and
     * {@code DALYTRAN-ID}, are sensitive or identifying, and a validation message is
     * exactly the kind of string that reaches a log.</p>
     *
     * <p>Declared {@code private static} so that the constructor can call it without
     * invoking an overridable method, which would otherwise publish a partially
     * initialised instance; the JPA specification forbids a final entity, so the
     * hazard is real and {@code -Xlint:all -Werror} reports it as
     * {@code this-escape}.</p>
     *
     * @param value      the candidate value, possibly {@code null}
     * @param property   the Java property name, used in the failure message
     * @param cobolField the originating COBOL field name and picture clause
     * @param width      the declared width of that field in characters
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null} or longer
     *                                  than {@code width}
     */
    private static String requireWidth(final String value,
                                       final String property,
                                       final String cobolField,
                                       final int width) {
        if (value == null) {
            throw new IllegalArgumentException(property + " (" + cobolField
                    + ") must not be null: it maps to a NOT NULL CHAR(" + width
                    + ") column of table daily_transaction");
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(property + " (" + cobolField
                    + ") must be at most " + width + " characters but was "
                    + value.length());
        }
        return value;
    }

    /**
     * Validates a candidate category code against {@code DALYTRAN-CAT-CD PIC 9(04)}
     * and returns it unchanged.
     *
     * <p>Rejects {@code null} and any value outside 0 through 9999 inclusive, which
     * is what four unsigned display digits can hold. <strong>No membership check
     * against the transaction-category table happens here</strong>: this staging
     * table exists precisely to hold rows whose business content may be wrong, and
     * the posting job owns that judgement. Declared {@code private static} for the
     * reason given on {@link #requireWidth(String, String, String, int)}.</p>
     *
     * @param value the candidate category code, possibly {@code null}
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}, negative, or
     *                                  greater than 9999
     */
    private static Integer requireCategoryCode(final Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("categoryCode (DALYTRAN-CAT-CD PIC "
                    + "9(04)) must not be null: it maps to a NOT NULL NUMERIC(4) "
                    + "column of table daily_transaction");
        }
        if (value.intValue() < MIN_CATEGORY_CODE || value.intValue() > MAX_CATEGORY_CODE) {
            throw new IllegalArgumentException("categoryCode (DALYTRAN-CAT-CD PIC "
                    + "9(04)) must be between " + MIN_CATEGORY_CODE + " and "
                    + MAX_CATEGORY_CODE + " inclusive but was " + value);
        }
        return value;
    }

    /**
     * Validates a candidate merchant identifier against
     * {@code DALYTRAN-MERCHANT-ID PIC 9(09)} and returns it unchanged.
     *
     * <p>Rejects {@code null} and any value outside 0 through 999999999 inclusive,
     * which is what nine unsigned display digits can hold. Zero is accepted and is
     * not a sentinel to reject. Declared {@code private static} for the reason given
     * on {@link #requireWidth(String, String, String, int)}.</p>
     *
     * @param value the candidate merchant identifier, possibly {@code null}
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}, negative, or
     *                                  greater than 999999999
     */
    private static Long requireMerchantId(final Long value) {
        if (value == null) {
            throw new IllegalArgumentException("merchantId (DALYTRAN-MERCHANT-ID PIC "
                    + "9(09)) must not be null: it maps to a NOT NULL NUMERIC(9) "
                    + "column of table daily_transaction");
        }
        if (value.longValue() < MIN_MERCHANT_ID || value.longValue() > MAX_MERCHANT_ID) {
            throw new IllegalArgumentException("merchantId (DALYTRAN-MERCHANT-ID PIC "
                    + "9(09)) must be between " + MIN_MERCHANT_ID + " and "
                    + MAX_MERCHANT_ID + " inclusive but was " + value);
        }
        return value;
    }

    /**
     * Validates a candidate amount against the domain
     * {@code DALYTRAN-AMT PIC S9(09)V99} can represent and returns it unchanged.
     *
     * <p>Three things are checked and nothing else. {@code null} is rejected. A scale
     * greater than two is rejected, because {@code V99} declares exactly two decimal
     * positions and PostgreSQL rounds a {@code NUMERIC(11,2)} insert half away from
     * zero rather than refusing it - a silent alteration, and by a rounding mode that
     * is not the {@code RoundingMode.HALF_EVEN} the posting job uses. A magnitude
     * outside -999999999.99 through 999999999.99 is rejected, because the eleven
     * zoned-decimal bytes the record allocates cannot hold more.</p>
     *
     * <p><strong>What is deliberately not checked:</strong> the sign, because the
     * picture clause carries an {@code S} and
     * {@code app/cbl/CBTRN02C.cbl:L547-L552} adds a negative amount to the cycle
     * debit accumulator; zero; and a scale smaller than two. <strong>No absolute
     * value, no rescaling and no rounding is applied anywhere in this
     * class.</strong></p>
     *
     * <p>Declared {@code private static} for the reason given on
     * {@link #requireWidth(String, String, String, int)}.</p>
     *
     * @param value the candidate amount, possibly {@code null}
     * @return {@code value}, unchanged and unrescaled
     * @throws IllegalArgumentException if {@code value} is {@code null}, has more than
     *                                  two decimal digits, or falls outside the
     *                                  representable range
     */
    private static BigDecimal requireAmount(final BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("amount (DALYTRAN-AMT PIC S9(09)V99) "
                    + "must not be null: it maps to a NOT NULL NUMERIC("
                    + AMOUNT_PRECISION + "," + AMOUNT_SCALE
                    + ") column of table daily_transaction");
        }
        if (value.scale() > AMOUNT_SCALE) {
            throw new IllegalArgumentException("amount (DALYTRAN-AMT PIC S9(09)V99) "
                    + "must carry at most " + AMOUNT_SCALE
                    + " decimal digits but had a scale of " + value.scale()
                    + "; rescale it explicitly with RoundingMode.HALF_EVEN rather "
                    + "than letting the NUMERIC(" + AMOUNT_PRECISION + ","
                    + AMOUNT_SCALE + ") column round it");
        }
        if (value.compareTo(MIN_AMOUNT) < 0 || value.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("amount (DALYTRAN-AMT PIC S9(09)V99) "
                    + "must be between " + MIN_AMOUNT.toPlainString() + " and "
                    + MAX_AMOUNT.toPlainString() + " inclusive, which is what "
                    + AMOUNT_INTEGER_DIGITS + " signed integer digits can hold, but "
                    + "was " + value.toPlainString());
        }
        return value;
    }
}
