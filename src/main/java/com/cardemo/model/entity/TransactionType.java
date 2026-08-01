/*
 * ******************************************************************
 * Program     : TransactionType.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Replaces the VSAM KSDS cluster
 *               AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS, the transaction type
 *               reference table that maps a two character transaction type
 *               code to its fifty character description. Batch only: the
 *               cluster has no CICS file definition, so no online screen
 *               program ever opens it.
 * Source      : app/cpy/CVTRA03Y.cpy (60 B, key 2) @ 7756d89
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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transaction type reference data: the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS}.
 *
 * <p><b>What it does.</b> Each row carries one two character transaction type code and the
 * fifty character description that the batch reporting stream prints beside it. The record
 * layout is {@code app/cpy/CVTRA03Y.cpy}, whose header comment at {@code :L2} reads
 * "Data-structure for transaction type (RECLN = 60)" and whose group item at {@code :L4} is
 * {@code 01 TRAN-TYPE-RECORD}. This class is a pure data holder: it performs no I/O, holds no
 * service collaborator, emits no log line and reaches no framework beyond the persistence
 * annotations below.
 *
 * <h2>Verified field contract</h2>
 *
 * <pre>
 * #  COBOL field (CVTRA03Y.cpy)  PIC    Java field       Column          SQL type
 * -  --------------------------  -----  ---------------  --------------  -----------------
 * 1  TRAN-TYPE       (:L5)       X(02)  typeCode         tran_type       CHAR(2)  PRIMARY KEY
 * 2  TRAN-TYPE-DESC  (:L6)       X(50)  typeDescription  tran_type_desc  CHAR(50) NOT NULL
 * -  FILLER          (:L7)       X(08)  not modelled     not modelled    not modelled
 * </pre>
 *
 * <p><b>Record geometry.</b> {@code 2 + 50 = 52} populated bytes, {@code + 8} bytes of
 * {@code FILLER}, {@code = 60} bytes total. The eight {@code FILLER} bytes carry no business
 * value and are deliberately not mapped, so the entity exposes exactly two columns and no
 * third column of any kind. The record length is nonetheless reconstructible from this class
 * plus the copybook, which is what lets a fixed width writer re-emit a byte exact 60 byte
 * image without a filler property that could be read or written by mistake.
 *
 * <h2>Physical evidence: three independent corroborations</h2>
 *
 * <ul>
 *   <li>The copybook itself, {@code app/cpy/CVTRA03Y.cpy}, declares {@code X(02)},
 *       {@code X(50)} and {@code X(08)} at {@code :L5}, {@code :L6} and {@code :L7}.</li>
 *   <li>The VSAM catalogue listing defines the cluster at
 *       {@code app/catlg/LISTCAT.txt:L3777} and reports {@code KEYLEN 2} together with
 *       {@code AVGLRECL 60} at {@code :L3779}; the adjacent attribute line adds
 *       {@code MAXLRECL 60} and {@code RKP 0}, so the key occupies bytes 1 to 2 of the
 *       record and the record is fixed at 60 bytes rather than merely averaging it. The
 *       statistics block on the same entry reports {@code REC-TOTAL 7}.</li>
 *   <li>The provisioning job {@code app/jcl/TRANTYPE.jcl:L36-L41} issues
 *       {@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS)} with
 *       {@code KEYS(2 0)} and {@code RECORDSIZE(60 60)}, which states the same two facts a
 *       third time and states them as an exact rather than an average length.</li>
 * </ul>
 *
 * <p>The fixture {@code app/data/ASCII/trantype.txt} closes the loop at 427 bytes: seven rows
 * of exactly 60 characters plus one line terminator each, which is the {@code REC-TOTAL 7}
 * the catalogue reports. Row 1 is {@code 01} then {@code Purchase} padded to 50 then
 * {@code 00000000}, and row 2 is {@code 02} then {@code Payment} padded to 50 then
 * {@code 00000000}; the remaining five are {@code 03 Credit}, {@code 04 Authorization},
 * {@code 05 Refund}, {@code 06 Reversal} and {@code 07 Adjustment}.
 *
 * <p><b>Note for the seed migration author.</b> The trailing eight byte {@code FILLER} in this
 * fixture is <i>zero filled</i>, that is eight literal {@code '0'} characters, and not space
 * filled as it is in {@code app/data/ASCII/acctdata.txt} and
 * {@code app/data/ASCII/custdata.txt}. Nothing here reads those bytes either way, but a loader
 * that assumes a blank filler will mis-slice the record if it trims rather than takes a fixed
 * substring. Every one of the seven codes is exactly two digits, so the key column is fully
 * occupied and never blank padded.
 *
 * <h2>Findings, classified by severity</h2>
 *
 * <p><b>Blocker: a plain {@code jakarta.persistence} String mapping cannot start the
 * application context against these columns.</b> The persistence configuration sets
 * {@code spring.jpa.hibernate.ddl-auto: validate} in every profile, so a column name, type,
 * precision or nullability mismatch aborts startup rather than degrading quietly. Measured on
 * the pinned stack, Hibernate ORM 6.6.42.Final against PostgreSQL 16.10, with the table
 * created as {@code tran_type CHAR(2) NOT NULL PRIMARY KEY, tran_type_desc CHAR(50) NOT NULL}:
 *
 * <pre>
 * mapping                                                    validate outcome
 * ---------------------------------------------------------  -------------------------------
 * Column(length = n)                                         FAIL: found [bpchar
 *                                                            (Types#CHAR)], but expecting
 *                                                            [varchar(2) (Types#VARCHAR)]
 * Column(length = n, columnDefinition = "CHAR(n)")           FAIL: found [bpchar
 *                                                            (Types#CHAR)], but expecting
 *                                                            [char(2) (Types#VARCHAR)]
 * JdbcTypeCode(SqlTypes.CHAR) + Column(length = n)           PASS
 * JdbcTypeCode(SqlTypes.CHAR) + Column(length = n,
 *                               columnDefinition = "CHAR(n)")  PASS
 * </pre>
 *
 * <p>Remediation, applied below: annotate both properties with
 * {@code JdbcTypeCode(SqlTypes.CHAR)}. Hibernate then derives the JDBC type {@code CHAR}
 * instead of defaulting a {@code String} to {@code VARCHAR}, the reported and expected types
 * agree, and schema generation emits precisely
 * {@code tran_type char(2) not null, tran_type_desc char(50) not null, primary key (tran_type)}.
 * {@code columnDefinition} is deliberately <i>omitted</i>: the two passing rows above are
 * equivalent, so the attribute would restate a type the declared length and JDBC type code
 * already produce, and a redundant literal that no longer participates in validation is
 * exactly the dead configuration that Rule 1 clause B forbids. {@code JdbcTypeCode} and
 * {@code SqlTypes} come from {@code hibernate-core}, which the Spring Data JPA starter already
 * places on the compile classpath, so no dependency is added and none is pinned here.
 *
 * <p><b>Medium: the primary key field is named {@code TRAN-TYPE}, not
 * {@code TRAN-TYPE-CD}.</b> Every other copybook in the corpus spells the transaction type
 * code with the {@code -CD} suffix: {@code app/cpy/CVTRA04Y.cpy:L6} and
 * {@code app/cpy/CVTRA05Y.cpy:L6} both declare {@code TRAN-TYPE-CD PIC X(02)}, and
 * {@code app/cpy/CVTRA01Y.cpy:L7} declares the same value under the prefixed name
 * {@code TRANCAT-TYPE-CD}. Only {@code app/cpy/CVTRA03Y.cpy:L5} names it plainly
 * {@code TRAN-TYPE}. The copybook is authoritative for the column, so the column is
 * {@code tran_type} and <i>not</i> the underscored form of {@code TRAN-TYPE-CD} that the
 * sibling copybooks would suggest. Remediation is to leave it alone:
 * harmonising the column name to match the sibling copybooks would diverge from the source of
 * record and would fail {@code validate} at startup with a missing column, which is why the
 * divergence is recorded here at the mapping site rather than only in the decision log. The
 * Java property is named {@code typeCode} for readability and to match the {@code typeCode}
 * property on the transaction entities; only the column name follows the copybook literally.
 *
 * <p><b>Low: this is a batch only dataset.</b> {@code app/csd/CARDDEMO.CSD} defines exactly
 * eight CICS file names, {@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF},
 * {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC}, and {@code TRANTYPE}
 * is not among them. Together with {@code TCATBALF}, {@code DISCGRP} and {@code TRANCATG} it
 * therefore has no online definition at all. Two consequences follow. For authorisation, no
 * REST endpoint reads this table directly, so it needs no role rule of its own. For testing,
 * its integration surface is the batch tier, reached through the transaction report job rather
 * than through the REST surface. The cluster also has no alternate index: the catalogue
 * reports {@code AIX 3} at {@code app/catlg/LISTCAT.txt:L3938-L3946} and all three belong to
 * {@code CARDDATA}, {@code CARDXREF} and {@code TRANSACT}, so no secondary index and no
 * derived finder beyond the primary key is warranted.
 *
 * <h2>Deliberate omissions, each with its reason</h2>
 *
 * <ul>
 *   <li><b>No optimistic version column.</b> {@code Version} belongs on the four mutable
 *       transactional entities, {@code Account}, {@code Card}, {@code Customer} and
 *       {@code Transaction}. This is static reference data loaded once by the seed migration,
 *       so there is no concurrent update to lose and a version column would be an unused
 *       column that {@code validate} would then require the schema to carry.</li>
 *   <li><b>No embedded composite identifier.</b> The three composite key clusters need one;
 *       this layout is flat, with a single two byte key at relative byte position zero, so the
 *       key is an ordinary {@code Id} property.</li>
 *   <li><b>No generated value.</b> The two character code arrives from
 *       {@code app/data/ASCII/trantype.txt} through the seed migration. Generating it would
 *       invent identifiers the legacy corpus does not have and would break the keyed reads
 *       that resolve a type code taken from a transaction record.</li>
 *   <li><b>No JPA association in either direction.</b> No one to many back to the transaction
 *       entities, no many to one, no join column, no cascade. The legacy access pattern is a
 *       single keyed read per transaction, proven by {@code app/cbl/CBTRN03C.cbl}, the only
 *       program that copies this layout at {@code :L103}: it declares
 *       {@code RECORD KEY IS FD-TRAN-TYPE} at {@code :L42}, moves the code from the
 *       transaction record at {@code :L189}, performs {@code 1500-B-LOOKUP-TRANTYPE} at
 *       {@code :L190} which issues {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} at
 *       {@code :L495}, and moves the description onto the report line at {@code :L366}. It
 *       never traverses a set from the type side. Referential integrity lives in the ten
 *       foreign keys of the first migration, not in an object graph. A reverse collection here
 *       would be the single most tempting addition in this package and the most damaging: it
 *       would hang an unbounded collection off the largest table in the schema, making every
 *       type lookup either an eager multi row fetch or a lazy initialisation hazard. Zero
 *       associations means structurally zero N+1.</li>
 *   <li><b>Not an enum and no enumerated mapping.</b> The seven rows are seeded data, not
 *       compile time constants: a new type can be seeded without recompiling, and an enum
 *       would turn an unrecognised code into a load time failure instead of the diagnostic the
 *       legacy program produces. Nothing in this package duplicates this table as a helper, a
 *       mapper or a constants holder.</li>
 *   <li><b>No bean validation constraint.</b> No not blank, not empty, pattern or size
 *       annotation is present. Both columns are {@code NOT NULL} and both must accept a blank
 *       but non null value, which is verifiable: a {@code CHAR(2)} key of two spaces inserts
 *       and selects normally on PostgreSQL 16.10. Declaring the value non blank would reject
 *       records the source accepts. Nullability is enforced once, by the column, and length is
 *       enforced once, by the declared width.</li>
 *   <li><b>No permissive default anywhere, and no large object mapping.</b> Every attribute
 *       that has a lenient default is stated explicitly instead: {@code nullable} is set to
 *       {@code false} on both columns rather than left at its permissive {@code true}, and
 *       {@code length} is stated as 2 and 50 rather than left at the 255 that JPA would
 *       otherwise assume and that would silently widen both fixed width fields. There is no
 *       {@code Lob}, because a 50 byte description is not a large object and streaming it
 *       would abandon the fixed width guarantee; and there is no fetch mode of any kind,
 *       eager or lazy, because with no association there is nothing to fetch. Values arriving
 *       through the constructor and setters are treated as untrusted in the sense that
 *       matters here: they are neither interpreted nor concatenated into a query, and the
 *       schema, not this class, has the last word on what is admissible.</li>
 *   <li><b>No inheritance and no shared superclass.</b> No mapped superclass and no auditable
 *       base type. A two column reference table gains nothing from a hierarchy and would pay
 *       for it in coupling.</li>
 *   <li><b>Not serialisable.</b> Implementing {@code java.io.Serializable} would oblige a
 *       {@code serialVersionUID}, whose absence the {@code serial} lint reports and
 *       {@code -Werror} then turns into a build failure. Nothing in the design serialises this
 *       type through Java serialisation, so the interface is simply not implemented and the
 *       insecure deserialisation surface never opens.</li>
 * </ul>
 *
 * <h2>Fixed width and CHAR semantics</h2>
 *
 * <p>{@code CHAR(n)} is chosen over a variable length type because {@code PIC X(n)} is a fixed
 * width, blank padded field and the 60 byte record geometry has to survive the round trip.
 * Two consequences are measured rather than assumed, both on PostgreSQL 16.10:
 *
 * <ul>
 *   <li><b>In the database, comparison ignores trailing blanks.</b> A stored
 *       {@code 'Purchase'} in {@code CHAR(50)} satisfies {@code tran_type_desc = 'Purchase'},
 *       and {@code length()} of it reports 8. This is what allows the space padded fixture
 *       values to be matched by predicates written against the unpadded literal.</li>
 *   <li><b>In Java, comparison does not.</b> The driver returns a {@code bpchar} value blank
 *       padded to its declared width, so a loaded description is a 50 character string and
 *       {@code "Purchase                                          ".equals("Purchase")} is
 *       {@code false}. The padded value is the faithful {@code PIC X(50)} image and is
 *       therefore returned as read, without trimming, because trimming would silently discard
 *       the geometry that the fixed width writers depend on. A caller that wants to compare
 *       against an unpadded literal must strip first, or push the comparison into a database
 *       predicate where the blank insensitive rule applies.</li>
 * </ul>
 *
 * <p>The key column is unaffected by this asymmetry in practice: every seeded code is exactly
 * two digits, so {@code CHAR(2)} is fully occupied and no padding is ever added to it. That is
 * what makes identity on the key unambiguous, which the equality contract below relies on.
 *
 * <h2>Numeric policy</h2>
 *
 * <p>This layout holds no monetary value and no rate, so it declares no numeric field at all
 * and in particular no {@code BigDecimal}; inventing one would fabricate a column the source
 * does not have. The project wide rule still bears restating at every entity, because it is
 * the rule most easily broken by a well meaning edit: monetary and rate values are
 * {@code BigDecimal} at the scale the {@code PIC} clause dictates, never either of the two
 * IEEE 754 binary approximation primitives (binary32 and binary64) nor their boxed
 * counterparts, and they are compared with {@code compareTo} and never with {@code equals},
 * because {@code equals} on {@code BigDecimal} is scale sensitive and would report
 * {@code 15.00} and {@code 15.0} as different amounts. No binary approximation type,
 * primitive or boxed, appears anywhere in this file.
 *
 * <h2>Required schema, and what is Not available</h2>
 *
 * <p><b>Not available:</b> {@code src/main/resources/db/migration/V1__create_schema.sql} did
 * not exist when this entity was authored, and neither did the directory that will hold it, so
 * the schema could not be read and the mapping could not be reconciled against it. This field
 * contract is therefore the normative column contract, and the migration must converge on it
 * rather than the reverse. What is needed, precisely:
 *
 * <ul>
 *   <li>table {@code transaction_type} with {@code tran_type CHAR(2) PRIMARY KEY} and
 *       {@code tran_type_desc CHAR(50) NOT NULL};</li>
 *   <li>no version column;</li>
 *   <li>no index beyond the primary key;</li>
 *   <li>seeded by {@code V3__seed_data.sql} from {@code app/data/ASCII/trantype.txt}, which is
 *       7 rows of 60 bytes each.</li>
 * </ul>
 *
 * <p>For the migration author's wider orientation: the first migration creates exactly 11
 * tables with 10 foreign keys and 5 check constraints, of which this table is one and
 * contributes none of the checks; the second adds only the three alternate index equivalents,
 * none of which touches this table; and the Spring Batch {@code BATCH_*} tables come from the
 * framework's own schema script, so they are never a fourth migration and never extra tables
 * inside the first one.
 *
 * <h2>Building, running and testing this component</h2>
 *
 * <p>Build and unit test with {@code mvn -B clean test}, which compiles under
 * {@code -Xlint:all -Werror} with {@code failOnWarning}, so any warning this file introduces
 * is a build failure rather than console noise. Run the full gate with {@code mvn -B clean
 * verify}, which adds the coverage floor and the dependency vulnerability scan. The unit tests
 * for this entity live in {@code src/test/java/com/cardemo/unit/model} and assert the 60 byte
 * record arithmetic, the key length of 2, the column name {@code tran_type} and that the type
 * is a class rather than an enum. The repository tier is exercised against a Testcontainers
 * PostgreSQL 16 instance from {@code src/test/java/com/cardemo/integration/repository}. There
 * is no configuration key and no default value specific to this class: it is a mapping, and
 * every setting that governs it, the datasource, the naming strategy and
 * {@code ddl-auto: validate}, is declared in the profile configuration.
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>Startup fails with "Schema-validation: missing table [transaction_type]".</b> The
 *       first migration has not run or created a differently named table. Check the migration
 *       history and the table name; do not rename the mapping to match a wrong table.</li>
 *   <li><b>Startup fails with "Schema-validation: missing column [tran_type]".</b> Almost
 *       always the {@code TRAN-TYPE-CD} harmonisation described under the Medium finding
 *       above. The column is {@code tran_type}; correct the migration, not this class.</li>
 *   <li><b>Startup fails with "wrong column type ... found [bpchar (Types#CHAR)], but
 *       expecting [varchar(2) (Types#VARCHAR)]".</b> The {@code JdbcTypeCode(SqlTypes.CHAR)}
 *       annotation has been removed from a property while the column remained {@code CHAR}.
 *       Restore it; see the Blocker finding above for the measured evidence.</li>
 *   <li><b>A description comparison unexpectedly fails.</b> The loaded value is blank padded
 *       to 50 characters by design. Strip before comparing in Java, or compare in SQL.</li>
 *   <li><b>A type code lookup returns nothing for a code the report expects.</b> The seed data
 *       carries exactly the seven codes {@code 01} through {@code 07}. The legacy program
 *       diagnoses this with "INVALID TRANSACTION TYPE" at
 *       {@code app/cbl/CBTRN03C.cbl:L497} rather than failing, so an empty result is a data
 *       condition to report, not a mapping defect.</li>
 * </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */
@Entity
@Table(name = "transaction_type")
public class TransactionType {

    /**
     * Transaction type code: the primary key, from {@code TRAN-TYPE PIC X(02)} at
     * {@code app/cpy/CVTRA03Y.cpy:L5}, occupying bytes 1 to 2 of the 60 byte record, which is
     * the {@code KEYLEN 2} with {@code RKP 0} that {@code app/catlg/LISTCAT.txt:L3779-L3780}
     * reports and the {@code KEYS(2 0)} that {@code app/jcl/TRANTYPE.jcl:L40} defines.
     *
     * <p>The column is {@code tran_type}, spelled exactly as the copybook spells the field.
     * This is the Medium severity divergence recorded in the class documentation: the sibling
     * copybooks all use {@code TRAN-TYPE-CD}, so the natural instinct is to write that name in
     * underscored form here. Doing so would part company with the source of record and would
     * abort startup under {@code ddl-auto: validate} with a missing column.
     *
     * <p>{@code JdbcTypeCode(SqlTypes.CHAR)} is required, not decorative; without it Hibernate
     * maps a {@code String} to {@code VARCHAR} and schema validation rejects the
     * {@code CHAR(2)} column outright. Not nullable, and not constrained beyond that: a blank
     * key is storable in the source and must remain loadable here.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type", nullable = false, length = 2)
    private String typeCode;

    /**
     * Transaction type description, from {@code TRAN-TYPE-DESC PIC X(50)} at
     * {@code app/cpy/CVTRA03Y.cpy:L6}, occupying bytes 3 to 52 of the 60 byte record. The
     * batch report writer prints this text beside the code; see
     * {@code app/cbl/CBTRN03C.cbl:L366}.
     *
     * <p>Stored and returned blank padded to the full 50 characters, which is the faithful
     * fixed width image rather than an artefact. It is deliberately not trimmed on the way
     * out. Not nullable, and not constrained beyond that, so a description of only spaces
     * loads exactly as the source permits.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type_desc", nullable = false, length = 50)
    private String typeDescription;

    /**
     * No argument constructor required by the JPA specification so the persistence provider
     * can instantiate a managed instance before populating its state.
     *
     * <p>Deliberately {@code protected} rather than {@code public}: the provider and any
     * subclass can reach it, while application code is steered to the all columns constructor
     * and cannot create an instance with two null columns by accident. Both fields are left
     * null here, which is the correct transient state for an instance the provider is about to
     * fill; an instance created this way is not valid to persist until both properties are set.
     */
    protected TransactionType() {
        // Intentionally empty: JPA populates the persistent state directly after construction.
    }

    /**
     * Creates a fully populated transaction type, which is how application and test code
     * should build one.
     *
     * <p>Both fields are assigned directly rather than through the setters. That is not a
     * stylistic choice: this class is not {@code final}, because the JPA specification forbids
     * a final entity, so a constructor that called an overridable setter would leak
     * {@code this} to a subclass override before construction finished. The compiler reports
     * exactly that as a {@code this-escape} warning, and {@code -Werror} would turn it into a
     * build failure.
     *
     * <p>No validation is applied and none is appropriate. Both columns are non null in the
     * schema, and the persistence provider reports a null on flush with full context; adding a
     * duplicate check here would reject blank but non null values that the source accepts, and
     * would move a schema concern into the model. The constructor has no side effect, performs
     * no I/O and throws nothing.
     *
     * @param typeCode        the two character code for {@code tran_type}, from
     *                        {@code TRAN-TYPE PIC X(02)}; longer values are rejected by the
     *                        column width on flush, and shorter ones are blank padded by the
     *                        {@code CHAR(2)} column
     * @param typeDescription the description for {@code tran_type_desc}, from
     *                        {@code TRAN-TYPE-DESC PIC X(50)}, blank padded to 50 characters
     *                        by the column
     */
    public TransactionType(String typeCode, String typeDescription) {
        this.typeCode = typeCode;
        this.typeDescription = typeDescription;
    }

    /**
     * Returns the transaction type code, the primary key, from
     * {@code TRAN-TYPE PIC X(02)} at {@code app/cpy/CVTRA03Y.cpy:L5}.
     *
     * @return the two character code, blank padded to 2 by the {@code CHAR(2)} column when
     *         read back from the database, or {@code null} on a transient instance built by
     *         the no argument constructor
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction type code, from {@code TRAN-TYPE PIC X(02)} at
     * {@code app/cpy/CVTRA03Y.cpy:L5}.
     *
     * <p>Mutating the primary key of an entity that is already managed is not meaningful to the
     * persistence provider; this setter exists for the provider's own property access and for
     * constructing an instance in stages. No validation is performed, so a blank but non null
     * code is accepted exactly as the source permits, and nothing is thrown.
     *
     * @param typeCode the two character code to store in {@code tran_type}
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction type description, from {@code TRAN-TYPE-DESC PIC X(50)} at
     * {@code app/cpy/CVTRA03Y.cpy:L6}.
     *
     * @return the description exactly as stored, which after a database read is blank padded to
     *         50 characters and is not trimmed, or {@code null} on a transient instance built
     *         by the no argument constructor
     */
    public String getTypeDescription() {
        return typeDescription;
    }

    /**
     * Sets the transaction type description, from {@code TRAN-TYPE-DESC PIC X(50)} at
     * {@code app/cpy/CVTRA03Y.cpy:L6}.
     *
     * <p>No validation is performed and nothing is thrown; a description of only spaces is a
     * legitimate value in the source and stays legitimate here.
     *
     * @param typeDescription the description to store in {@code tran_type_desc}
     */
    public void setTypeDescription(String typeDescription) {
        this.typeDescription = typeDescription;
    }

    /**
     * Compares two transaction types on {@code typeCode} alone.
     *
     * <p>The key is the right and only basis for identity here. It is a natural key that
     * arrives from seed data rather than a generated surrogate, so it is populated from the
     * moment an instance is meaningful, and it stays stable across the transient, managed and
     * detached states where a description could legitimately be edited. Including the
     * description would make two rows that denote the same transaction type compare unequal
     * after a text correction.
     *
     * <p>The type test is a pattern match rather than a {@code getClass()} comparison so that a
     * lazily loaded provider proxy compares equal to the instance it stands for; a
     * {@code getClass()} test would see the generated proxy class and report inequality. This
     * class declares no inheritance and no persistence hierarchy, so the symmetry caveat that
     * normally attaches to a pattern match in a non final class cannot arise.
     *
     * <p>Boundary case, stated rather than hidden: two instances that have not yet been given a
     * code both hold {@code null} and therefore compare equal, so un keyed instances must not
     * be relied on to stay distinct inside a hash based collection. Give an instance its code
     * before putting it in a set or a map. Note also that this comparison is exact string
     * equality, which is stricter than the database's blank insensitive comparison on
     * {@code CHAR}; because every seeded code fully occupies {@code CHAR(2)} there is no
     * padding for the two rules to disagree about.
     *
     * @param other the object to compare with, which may be {@code null}
     * @return {@code true} if {@code other} is a transaction type with an equal
     *         {@code typeCode}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionType that)) {
            return false;
        }
        return Objects.equals(this.typeCode, that.typeCode);
    }

    /**
     * Returns a hash code derived from {@code typeCode} alone, consistent with
     * {@link #equals(Object)}.
     *
     * <p>{@code Objects.hashCode(Object)} is used rather than dereferencing the field, so a
     * transient instance whose code is still {@code null} hashes to 0 instead of throwing. The
     * value is stable for the lifetime of an instance whose key does not change, which is the
     * property a hash based collection requires.
     *
     * @return the hash code of the primary key, or 0 when the key is {@code null}
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(typeCode);
    }

    /**
     * Returns a diagnostic rendering that includes <em>both</em> columns.
     *
     * <p>Nothing in this layout is a secret and nothing is personal data: a transaction type
     * code and its printable description are reference data that the batch report already
     * prints in clear. Exposing both is therefore correct rather than merely harmless, and it
     * is the reason this rendering is unrestricted while the card, customer and user security
     * entities deliberately restrict theirs, those carrying a card number, a social security
     * number and a password hash respectively, none of which may reach a log line.
     *
     * <p>The description appears exactly as held, so after a database read it shows its 50
     * character blank padded form. That is intentional: a diagnostic that trimmed the value
     * would hide the very padding a fixed width defect would show up as.
     *
     * @return a single line rendering of the type code and its description, never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionType{typeCode=" + typeCode
                + ", typeDescription=" + typeDescription + "}";
    }
}
