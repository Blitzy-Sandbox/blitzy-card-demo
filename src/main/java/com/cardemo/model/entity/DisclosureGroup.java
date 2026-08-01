/*
 * ******************************************************************
 * Program     : DisclosureGroup.java
 * Application : CardDemo
 * Type        : Java JPA Entity
 * Function    : Interest rate reference row. Replaces the VSAM KSDS
 *               cluster AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS, the
 *               disclosure group table keyed by account group id,
 *               transaction type code and transaction category code
 *               and read by the interest calculation job.
 * Source      : app/cpy/CVTRA02Y.cpy (50 B, composite key 16) @ 7756d89
 *               app/catlg/LISTCAT.txt:L894 (cluster), :L896 (KEYLEN 16, AVGLRECL 50)
 *               app/cbl/CBACT04C.cbl (sole consumer; also the banner exemplar, L1-L21)
 *               app/data/ASCII/discgrp.txt (51 seed rows of 50 bytes)
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

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.cardemo.model.key.DisclosureGroupId;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

/**
 * One row of the {@code DISCGRP} disclosure group reference file: the interest rate that applies to a given
 * account group, transaction type and transaction category.
 *
 * <p>{@code DIS-INT-RATE} is {@code PIC S9(04)V99}, so the column is {@code NUMERIC(6,2)} — the narrowest of
 * the three decimal tiers in this package, which must never be collapsed into one. It is carried as
 * {@link java.math.BigDecimal}; no approximate type appears on any rate or monetary path.
 *
 * <h2>Source field contract, reproduced exactly</h2>
 * <p>From {@code app/cpy/CVTRA02Y.cpy}, whose header comment at {@code :L2} reads
 * {@code *    Data-structure for disclosure group (RECLN = 50)}:</p>
 * <pre>
 * 01  DIS-GROUP-RECORD.                             offset  width
 *     05  DIS-GROUP-KEY.                             1-16     16   -&gt; id (composite)
 *        10 DIS-ACCT-GROUP-ID   PIC X(10).           1-10     10   -&gt; acct_group_id  CHAR(10)
 *        10 DIS-TRAN-TYPE-CD    PIC X(02).          11-12      2   -&gt; tran_type_cd   CHAR(2)
 *        10 DIS-TRAN-CAT-CD     PIC 9(04).          13-16      4   -&gt; tran_cat_cd    (see below)
 *     05  DIS-INT-RATE          PIC S9(04)V99.      17-22      6   -&gt; dis_int_rate   NUMERIC(6,2)
 *     05  FILLER                PIC X(28).          23-50     28   -&gt; NOT MODELLED
 * </pre>
 * <p>The record arithmetic closes exactly, which is what makes the layout safe to treat as a hard contract:
 * {@code 10 + 2 + 4 = 16} key bytes, {@code + 6} rate bytes {@code = 22} populated bytes, {@code + 28} bytes of
 * {@code FILLER} {@code = 50} bytes. That total is corroborated four independent ways:</p>
 * <ol>
 *   <li>the copybook's own header comment, {@code app/cpy/CVTRA02Y.cpy:L2}, stating {@code RECLN = 50};</li>
 *   <li>the VSAM catalogue, {@code app/catlg/LISTCAT.txt:L896}, reading
 *       {@code KEYLEN----------------16     AVGLRECL--------------50}, with {@code :L897} adding
 *       {@code RKP--------------------0} and {@code MAXLRECL--------------50} - so the key is the record
 *       prefix and the record is fixed length;</li>
 *   <li>the seed fixture {@code app/data/ASCII/discgrp.txt}, in which every one of the 51 rows measures
 *       exactly 50 characters;</li>
 *   <li>fixture row {@code app/data/ASCII/discgrp.txt:L18}, which reads
 *       <code>DEFAULT   01000100150&#123;0000000000000000000000000000</code> and decomposes as
 *       {@code DEFAULT} plus three blanks (10) + {@code 01} (2) + {@code 0001} (4) = the 16-byte key, then
 *       <code>00150&#123;</code> (6) for the rate, then 28 filler characters.</li>
 * </ol>
 * <p>{@code FILLER} is never modelled. It carries no data; it exists only to pad the record out to the
 * catalogued 50 bytes, and its 28-byte width is recorded here rather than in a field. Citing {@code :L896}
 * precisely matters, because {@code app/catlg/LISTCAT.txt:L202} (CARDDATA) and {@code :L403} (CARDXREF) also
 * report {@code KEYLEN 16} for entirely different clusters.</p>
 *
 * <h2>Blocker: the rate is NUMERIC(6,2), the narrowest tier in this package</h2>
 * <p>{@code DIS-INT-RATE PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy:L9} is four integer digits plus two
 * decimals, so the column is {@code NUMERIC(6,2)} and the mapping declares {@code precision = 6, scale = 2}.
 * This is the only {@code NUMERIC(6,2)} column in the schema. Fixture row {@code :L18} confirms the width
 * independently: the rate occupies six characters, <code>00150&#123;</code>, and no more.</p>
 * <p>Three distinct decimal tiers exist across this package and <strong>must never be collapsed into one</strong>:</p>
 * <pre>
 * COBOL picture   SQL column      fields
 * S9(10)V99       NUMERIC(12,2)   the five Account money and cycle fields
 * S9(09)V99       NUMERIC(11,2)   TRAN-AMT, DALYTRAN-AMT, TRAN-CAT-BAL
 * S9(04)V99       NUMERIC(6,2)    DIS-INT-RATE  &lt;-- this file
 * </pre>
 * <p><strong>Severity: Blocker.</strong> Declaring the wrong tier here is not a cosmetic slip. A widened column
 * silently accepts rates the source cannot represent, so a value that the legacy program would have rejected or
 * truncated is stored intact and every downstream interest figure diverges - and because
 * {@code spring.jpa.hibernate.ddl-auto: validate} is mandated in every profile, a precision or scale mismatch
 * against the migration fails application-context startup outright rather than degrading quietly. Remediation:
 * keep {@code precision = 6, scale = 2} here and {@code NUMERIC(6,2)} in the migration, in step.</p>
 * <p><strong>Exact decimal discipline.</strong> The rate is a {@link BigDecimal} and nothing else. No approximate
 * binary numeric primitive appears anywhere in this file - the security audit gate asserts that by inspection -
 * because binary approximation cannot represent a two-decimal rate exactly and would drift under accumulation.
 * Two consequences bind every caller: any rounding must use {@code RoundingMode.HALF_EVEN}, and two rates must
 * be compared with {@code compareTo}, never with {@code equals}, since {@code BigDecimal.equals} is
 * scale-sensitive and would report a rate of {@code 2.0} as unequal to {@code 2.00}.</p>
 *
 * <h2>Blocker: the key columns belong to the identifier class and are not restated here</h2>
 * <p>{@link DisclosureGroupId} is the embeddable identifier, and it already declares all three key column
 * mappings itself, in copybook order: {@code acct_group_id}, then {@code tran_type_cd}, then
 * {@code tran_cat_cd}, each explicitly non-nullable. This entity therefore declares the identifier once, as an
 * embedded id, and adds exactly one non-key column of its own.</p>
 * <p><strong>Severity: Blocker.</strong> Restating any of those three columns here - as a second column
 * mapping, as an override of the embedded attributes, as separate scalar fields shadowing the key components,
 * or through any second identity mechanism alongside the embedded id - produces either a duplicate-column
 * mapping error or two competing definitions of the same key, both of which fail at context startup. There is
 * consequently exactly one import from a sibling model package in this file, and it is the identifier type.</p>
 * <p>The identifier is exposed as a whole, through one accessor pair. No pass-through accessor reaches into it
 * to re-expose the account group id, the type code or the category code, because that would duplicate the
 * identifier's public surface in a second place and let the two drift apart.</p>
 *
 * <h2>Blocker: CHAR(10) on the group id is what makes the DEFAULT fallback work</h2>
 * <p>{@code app/cbl/CBACT04C.cbl:L415} onward implements a two-stage read that this entity's column contract has
 * to support. The rate lookup paragraph at {@code app/cbl/CBACT04C.cbl:L415-L440} reads the file and accepts
 * <em>either</em> file status {@code '00'} <em>or</em> {@code '23'}, record not found; on {@code '23'} it
 * executes {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} and retries through the default-group paragraph at
 * {@code app/cbl/CBACT04C.cbl:L443-L458}, which accepts only {@code '00'} and otherwise displays
 * {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} and abends the job. A missing default row is therefore
 * fatal, not a skip. Only the group id is substituted; the type and category codes carry over unchanged.</p>
 * <p><strong>Why {@code CHAR(10)} and not {@code VARCHAR(10)}.</strong> A COBOL {@code MOVE} of the
 * seven-character literal {@code 'DEFAULT'} into a {@code PIC X(10)} field left-justifies and space-pads it, so
 * the key actually presented is {@code 'DEFAULT'} followed by three blanks - byte-identical to what fixture row
 * {@code app/data/ASCII/discgrp.txt:L18} stores. SQL {@code CHAR} comparison ignores trailing blanks, so a
 * lookup for the bare literal matches the padded stored value either way. Under {@code VARCHAR} it would not:
 * the fallback would silently depend on every caller padding the literal to exactly ten characters, and would
 * break the moment one did not. <strong>Severity: Blocker</strong>, because the failure mode is a lookup that
 * returns nothing, which the source escalates to an abend. The column type is fixed for that reason and must
 * not be relaxed.</p>
 * <p><strong>Why the fallback is the normal path, not an edge case.</strong> Row 1 of
 * {@code app/data/ASCII/acctdata.txt} carries {@code ACCT-GROUP-ID} as ten blanks. A blank group id never
 * matches a real group, so accounts like it reach the rate table only through the default group. That is why 17
 * of the 51 rows in {@code app/data/ASCII/discgrp.txt} carry the default group id, several of them at a zero
 * rate: that set is precisely what makes the fallback succeed for those type-and-category pairs and abend for
 * any other, so tests must cover both outcomes.</p>
 * <p>The literal itself is deliberately <strong>not</strong> declared as a constant on this entity. It belongs
 * to the interest calculator's control flow, not to the shape of a row, and hosting it here would pull business
 * logic into a data holder. It is described in this documentation and declared nowhere in this file.</p>
 *
 * <h2>A zero rate is a value, not an absence</h2>
 * <p>{@code app/cbl/CBACT04C.cbl:L214} guards the interest computation with {@code IF DIS-INT-RATE NOT = 0}, so
 * a zero rate legitimately produces no interest transaction and no accumulation. Zero is an ordinary,
 * load-bearing member of the domain, and the seed fixture's default-group rows include zero-rate combinations.
 * Accordingly this entity declares no Bean Validation constraint asserting positivity or a minimum of one, and
 * treats zero as present rather than missing: a zero rate must load, round-trip and persist unchanged. What is
 * rejected is {@code null}, because the source field is fixed-width and always carries a value, together
 * with a scale above the two decimal positions {@code V99} declares and a magnitude beyond what four signed
 * integer digits can hold - neither of which the source record can represent, and the first of which
 * PostgreSQL would otherwise round half away from zero rather than refuse.</p>
 * <p>The picture is signed, {@code S9(04)V99}, so a negative rate is inside the source domain. No
 * non-negativity constraint is declared and no magnitude normalisation is applied anywhere: the sign the source
 * presents is preserved verbatim.</p>
 *
 * <h2>High: no stale rate state may be carried across iterations</h2>
 * <p>The source has a genuine quirk here. COBOL reads into a shared record area, so when the rate lookup at
 * {@code app/cbl/CBACT04C.cbl:L415-L440} hits an invalid key the previous iteration's record contents remain in
 * place until the subsequent default read overwrites them. <strong>Severity: High</strong> - a translation that
 * reproduced that by reusing one mutable instance across iterations would apply a stale rate to the wrong
 * account group, and the defect would surface as quietly wrong interest rather than as a failure.</p>
 * <p>This entity is a plain per-row value holder and deliberately offers nothing that could host such state: no
 * static mutable field, no cache, no memoised last rate, and no entity lifecycle callback. Every read yields a
 * fresh managed instance. Keeping iteration state clean is the batch layer's responsibility, and this class is
 * written so that it cannot become the place where that responsibility is quietly discharged.</p>
 *
 * <h2>Low: a batch-only dataset with no index beyond its primary key</h2>
 * <p>{@code app/csd/CARDDEMO.CSD} defines exactly eight CICS file names - {@code ACCTDAT}, {@code CARDAIX},
 * {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC}.
 * {@code DISCGRP} is not among them, so this dataset has no online definition at all and is reached only from
 * batch, as are {@code TCATBALF}, {@code TRANCATG} and {@code TRANTYPE}. <strong>Severity: Low</strong>: this
 * is an observation that shapes the authorisation surface and the integration-test surface, not a defect.</p>
 * <p>The cluster has no alternate index either - the catalogue's three alternate indexes belong to
 * {@code CARDDATA}, {@code CARDXREF} and {@code TRANSACT}. No secondary index is therefore needed or wanted for
 * this table: the composite primary key, in copybook order, already serves both the exact three-part lookup and
 * the default-group fallback lookup, since both supply the group id as the leading component. Adding one would
 * cost write throughput and storage for no read benefit.</p>
 *
 * <h2>Deliberate omissions, and why each is deliberate</h2>
 * <ul>
 *   <li><em>No association mapping of any kind</em>, in either direction, and no join column. The type and
 *       category codes are not modelled as references to {@code TransactionType} or {@code TransactionCategory},
 *       and the group id is not modelled as a reference to {@code Account}: the source enforces no such
 *       constraint, performs explicit keyed reads throughout, and a blank group id would break any such
 *       reference immediately. Referential integrity belongs in the migration's foreign keys. With no
 *       association there is also nothing to propagate persistence operations across, and no lazy-loading
 *       cliff, so no N+1 query pattern can originate here.</li>
 *   <li><em>No optimistic-locking version column.</em> This is a read-mostly reference table with no concurrent
 *       update path in the corpus; the version attribute appears on exactly four entities in this package, and
 *       this is not one of them.</li>
 *   <li><em>No generated identifier.</em> The key is assigned from source data, never allocated by the
 *       database.</li>
 *   <li><em>No date or time attribute.</em> The record carries none, so none is invented.</li>
 *   <li><em>No inheritance</em>, no shared base entity and no mapped superclass. The three composite-key
 *       entities in this package are structurally similar but describe different contracts, and a shared
 *       abstraction would assert an equivalence that does not exist.</li>
 *   <li><em>This entity does not implement {@code java.io.Serializable}.</em> The identifier class must,
 *       because a JPA identifier is required to; an entity is not, and Java deserialization of untrusted input
 *       is a risky pattern worth declining by construction. Persist and transport this row as columns or as
 *       JSON, never as a serialized object stream.</li>
 *   <li><em>No business method.</em> There is no interest calculation, no default-group predicate and no rate
 *       arithmetic here. For the reader's orientation only: the monthly interest at
 *       {@code app/cbl/CBACT04C.cbl:L462-L470} is the transaction category balance multiplied by this rate and
 *       then divided by the single literal divisor of twelve hundred that the source writes, and it must never
 *       be algebraically rewritten as a division by one hundred followed by a division by twelve, because the
 *       two forms round differently. That computation lives in the interest processor and is intentionally
 *       absent from this file.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 * <p>Every profile is mandated to set {@code spring.jpa.hibernate.ddl-auto: validate}, so the schema is never
 * generated from these annotations: a mismatch in column name, SQL type, precision, scale or nullability fails
 * application-context startup instead of surfacing later as corrupt data. There is no default value for the
 * rate; the column is non-nullable and the value always comes from source data or from a caller.</p>
 * <p><strong>{@code V1__create_schema.sql} now exists and has been reconciled against.</strong> An earlier
 * revision of this paragraph recorded the migration directory as absent; that claim is withdrawn. {@code V1}
 * declares the {@code disclosure_group} table, and its agreement with the field contract
 * documented above — including the uncommon {@code NUMERIC(6,2)} rate precision that follows from
 * {@code DIS-INT-RATE PIC S9(04)V99} — is asserted mechanically by {@code SchemaStructureTest} against
 * {@code app/cpy/CVTRA02Y.cpy}, not by inspection. The field contract remains the normative column
 * contract, so any future divergence is resolved by changing {@code V1}. The shape it declares is exactly
 * this, and {@code V2__create_indexes.sql} remains <strong>planned</strong> and absent:</p>
 * <pre>
 * table disclosure_group
 *   acct_group_id  CHAR(10)      NOT NULL   -- part of PK; mapping owned by DisclosureGroupId
 *   tran_type_cd   CHAR(2)       NOT NULL   -- part of PK; mapping owned by DisclosureGroupId
 *   tran_cat_cd    INTEGER       NOT NULL   -- part of PK; mapping owned by DisclosureGroupId; see note
 *   dis_int_rate   NUMERIC(6,2)  NOT NULL   -- declared by this entity
 *   PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)   -- in this exact COBOL field order
 * </pre>
 * <p>{@code CHAR(10)}, not {@code VARCHAR(10)}, on the group id, so the space-padded default group matches, as
 * argued above. No version column. No index beyond the primary key. To be seeded by {@code V3__seed_data.sql}
 * (planned; absent at this commit, so no row is loaded yet) from
 * {@code app/data/ASCII/discgrp.txt} - 51 rows of 50 bytes, of which 17 carry the default group id - with
 * position-aware zoned-decimal overpunch decoding driven by the picture clause: the trailing {@code &#123;} in
 * {@code 00150&#123;} denotes {@code +0}, so that row's rate is {@code +15.00}, not {@code 1500}. Across the
 * wider migration, {@code V1} creates exactly 11 tables with 10 foreign keys and 5 check constraints; Spring
 * Batch's own {@code BATCH_*} tables come from the framework's bundled script, so they belong neither in
 * {@code V1} nor in a fourth migration.</p>
 * <p><strong>Not available, and a Blocker if resolved the wrong way: the SQL type of
 * {@code tran_cat_cd}.</strong> {@code DIS-TRAN-CAT-CD PIC 9(04)} is logically four unsigned display digits,
 * which reads as {@code NUMERIC(4)}, and the source requirement for this migration states it that way. That
 * spelling does not validate. {@link DisclosureGroupId} maps the component as an {@code Integer} and records
 * having measured, against Hibernate ORM 6.6.42.Final and PostgreSQL 16.10 with schema validation enabled, that
 * an {@code Integer} validates against {@code INTEGER} and {@code BIGINT} but fails against both
 * {@code NUMERIC(4)} and {@code SMALLINT}. Because the identifier class is the artefact that actually declares
 * this column, it is authoritative for it, and {@code INTEGER} is enumerated above on that basis. Remediation
 * if a future migration prefers the literal {@code NUMERIC(4)}: the component's Java type in
 * {@link DisclosureGroupId} must change in the same commit, never this entity, which does not restate the
 * column at all. Propagating {@code NUMERIC(4)} while leaving the component an {@code Integer} would fail
 * startup - the precise outcome this section exists to prevent.</p>
 * <p><strong>Low, but do not let it prompt a wrong "fix": the migration's primary key column order must stay
 * in COBOL order even though the provider's own ordering differs.</strong> Measured by building Hibernate ORM
 * 6.6.42.Final metadata for this entity against the PostgreSQL dialect with no database attached, the mapping
 * resolves to exactly four columns - {@code acct_group_id bpchar(10)}, {@code tran_type_cd bpchar(2)},
 * {@code tran_cat_cd integer} and {@code dis_int_rate numeric(6,2)}, all non-nullable, with no version
 * attribute - which confirms the contract above. The provider does, however, order an embeddable's attributes
 * alphabetically, so the key order it derives internally reads group id, then <em>category</em>, then
 * <em>type</em>. That is immaterial and must not be propagated: schema validation compares tables and columns
 * only and never checks primary key column order, and the migration - not the provider - is what creates the
 * key here. {@code V1} must therefore declare {@code (acct_group_id, tran_type_cd, tran_cat_cd)}, because
 * copybook order is what reproduces the VSAM browse sequence. Both orderings share {@code acct_group_id} as
 * the leading column, so the default-group fallback lookup is served identically either way.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><em>Blocker - startup fails reporting a precision or scale mismatch on {@code dis_int_rate}.</em> The
 *       migration declared a different tier. Confirm it reads {@code NUMERIC(6,2)}: {@code NUMERIC(11,2)} and
 *       {@code NUMERIC(12,2)} belong to other entities and are the two likeliest wrong answers.</li>
 *   <li><em>Blocker - startup fails reporting a repeated column, or two identities for one entity.</em> A key
 *       column was restated, overridden or shadowed here. The fix is always to remove it from this entity, not
 *       to add a counterpart in the identifier class.</li>
 *   <li><em>Blocker - the default-group lookup returns nothing and the job abends.</em> Check the column type
 *       first: against {@code CHAR(10)} the padded and unpadded literal both match, against {@code VARCHAR(10)}
 *       only an exactly padded one does. If the type is right, the default rows are genuinely missing from the
 *       seed, and abending is then the source's own documented behaviour at
 *       {@code app/cbl/CBACT04C.cbl:L443-L458}.</li>
 *   <li><em>High - interest is computed at a rate belonging to a previous account group.</em> Stale state in the
 *       batch layer, not here; this entity holds none. Verify each iteration re-reads and does not reuse an
 *       instance.</li>
 *   <li><em>Medium - a rate read back does not equal the rate written.</em> Almost always a scale-sensitive
 *       comparison: use {@code compareTo}, not {@code equals}. A rate of {@code 15.0} and one of {@code 15.00}
 *       are the same number at different scales.</li>
 *   <li><em>Medium - {@code IllegalArgumentException} from the all-arguments constructor.</em> The rate was
 *       {@code null}. The message names both the Java field and the COBOL field so the origin is traceable. A
 *       zero rate is valid and never causes this.</li>
 *   <li><em>Low - a row appears absent although it is in the table.</em> All three key components must be
 *       populated; a partially populated key is a different value and matches nothing.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>Nothing here is a credential or personally identifiable: an account group id, a two-character type code, a
 * four-digit category code and an interest rate. That is why {@link #toString()} may safely render the whole
 * row, in deliberate contrast with {@code Card}, {@code Customer} and {@code UserSecurity}, whose renderings are
 * restricted because they carry card numbers, personal data and password hashes. No secret, token or connection
 * detail appears in this file, and it exposes nothing beyond the source field contract. No SQL or JPQL is
 * assembled anywhere in this class, so no injection surface exists here.</p>
 *
 * <h2>How to build and test</h2>
 * <p>{@code ./mvnw -B clean compile} compiles this class under {@code --release 25} with {@code -Xlint:all} and
 * {@code -Werror}, so any warning at all is a build failure; that is why the all-arguments constructor assigns
 * fields directly instead of calling its own setters, which would leak a partially constructed reference from a
 * class the persistence provider must be able to subclass. {@code ./mvnw -B clean test} runs the unit suite and
 * {@code ./mvnw -B clean verify} additionally enforces the JaCoCo line-coverage floor. Unit coverage for this
 * entity belongs in {@code src/test/java/com/cardemo/unit/model} and should assert the 50-byte record
 * arithmetic, the 16-byte composite key in copybook order, {@code precision = 6} with {@code scale = 2} on the
 * rate, that a zero rate round-trips, that {@code +15.00} decoded from fixture row {@code :L18} round-trips,
 * and that no key column is restated or overridden here.</p>
 *
 * <p><b>JSON serialisation barrier.</b> This class is structurally unserialisable by Jackson.
 * {@link JsonIgnoreType} removes any property whose declared type is this class from an enclosing object's
 * JSON, and {@link JsonAutoDetect} with every visibility set to {@code NONE} switches off bean
 * introspection entirely, so no getter, no setter, no field and no creator is discoverable. This row is
 * interest rate reference data: it carries no credential, no personal data and no customer figure, so
 * unlike {@link Card} or {@link Customer} it is not what the barrier was introduced to protect. It is
 * applied here anyway, and deliberately without exception, because a barrier that covers every entity in
 * the package is checkable by inspection, whereas one applied only where a reviewer judged it necessary
 * has to be re-judged every time an entity is added or a column is widened - and a rate table is exactly
 * the kind of apparently harmless type that later acquires a column worth protecting. Persistence is
 * unaffected: Hibernate reads and writes the annotated fields reflectively and never consults Jackson
 * visibility.</p>
 *
 * @see DisclosureGroupId
 */
@Entity
@Table(name = "disclosure_group")
@JsonIgnoreType
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class DisclosureGroup {

    /**
     * Decimal positions declared by the {@code V99} of {@code DIS-INT-RATE PIC S9(04)V99} at
     * {@code app/cpy/CVTRA02Y.cpy:L9}: exactly two.
     */
    private static final int INTEREST_RATE_SCALE = 2;

    /** Integer digit count of {@code DIS-INT-RATE PIC S9(04)V99}: four. */
    private static final int INTEREST_RATE_INTEGER_DIGITS = 4;

    /**
     * Total precision of column {@code dis_int_rate}, {@code NUMERIC(6,2)}: the four integer digits plus the
     * two decimal positions the picture clause declares.
     */
    private static final int INTEREST_RATE_PRECISION = INTEREST_RATE_INTEGER_DIGITS + INTEREST_RATE_SCALE;

    /** Inclusive upper bound of {@code DIS-INT-RATE}: the largest magnitude {@code S9(04)V99} can hold. */
    private static final BigDecimal MAX_INTEREST_RATE = new BigDecimal("9999.99");

    /**
     * Inclusive lower bound of {@code DIS-INT-RATE}. The picture clause carries an {@code S}, so the full
     * negative range is representable and is deliberately admitted.
     */
    private static final BigDecimal MIN_INTEREST_RATE = MAX_INTEREST_RATE.negate();

    /**
     * The composite primary key: {@code DIS-GROUP-KEY} at {@code app/cpy/CVTRA02Y.cpy:L5}, sixteen bytes made
     * up of the account group id, the transaction type code and the transaction category code in that order.
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * {@code DIS-INT-RATE}, {@code PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy:L9}, occupying bytes 17 to 22
     * of the 50-byte record: the annual interest rate for this group, type and category combination.
     */
    @Column(name = "dis_int_rate", nullable = false, precision = INTEREST_RATE_PRECISION,
            scale = INTEREST_RATE_SCALE)
    private BigDecimal interestRate;

    /**
     * Creates an empty disclosure group row.
     */
    protected DisclosureGroup() {
        // Intentionally empty: Jakarta Persistence populates the fields directly after instantiation.
    }

    /**
     * Creates a disclosure group row from its composite key and its interest rate.
     *
     * @param id the composite key, {@code DIS-GROUP-KEY} at {@code app/cpy/CVTRA02Y.cpy:L5}.
     * @param interestRate {@code DIS-INT-RATE}, {@code PIC S9(04)V99} at {@code app/cpy/CVTRA02Y.cpy:L9}.
     * @throws IllegalArgumentException if {@code id} or {@code interestRate} is {@code null}.
     */
    public DisclosureGroup(final DisclosureGroupId id, final BigDecimal interestRate) {
        // Direct field assignment, never a setter: the provider subclasses this class for lazy-loading
        // proxies, so calling an overridable method here would leak a partially constructed reference.
        this.id = requireId(id);
        this.interestRate = requireInterestRate(interestRate);
    }

    /**
     * Returns the composite primary key, {@code DIS-GROUP-KEY} at {@code app/cpy/CVTRA02Y.cpy:L5}.
     *
     * @return the sixteen-byte composite key, or {@code null} on an instance created by the no-argument
     * constructor and not yet populated
     */
    public DisclosureGroupId getId() {
        return id;
    }

    /**
     * Replaces the composite primary key.
     *
     * @param id the composite key to apply, {@code DIS-GROUP-KEY} at {@code app/cpy/CVTRA02Y.cpy:L5}.
     * @throws IllegalArgumentException if {@code id} is {@code null}, since a row without a key cannot be
     * addressed
     */
    public void setId(final DisclosureGroupId id) {
        this.id = requireId(id);
    }

    /**
     * Returns the interest rate, {@code DIS-INT-RATE}, {@code PIC S9(04)V99} at
     * {@code app/cpy/CVTRA02Y.cpy:L9}.
     *
     * @return the annual interest rate as an exact decimal, or {@code null} on an instance created by the
     * no-argument constructor and not yet populated
     */
    public BigDecimal getInterestRate() {
        return interestRate;
    }

    /**
     * Replaces the interest rate.
     *
     * @param interestRate the rate to apply, {@code DIS-INT-RATE}, {@code PIC S9(04)V99}.
     * @throws IllegalArgumentException if {@code interestRate} is {@code null}
     */
    public void setInterestRate(final BigDecimal interestRate) {
        this.interestRate = requireInterestRate(interestRate);
    }

    /**
     * Compares this row with another for entity identity, on the composite key alone.
     *
     * @param other the object to compare with, possibly {@code null} or of a foreign type
     * @return {@code true} if {@code other} is a {@code DisclosureGroup} with an equal, non-null key
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DisclosureGroup group)) {
            return false;
        }
        return id != null && id.equals(group.getId());
    }

    /**
     * Returns a hash code derived from the composite key alone, consistent with {@link #equals(Object)}.
     *
     * @return a hash code over the composite key, or the hash of {@code null} on an unpopulated instance
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Returns a diagnostic rendering of the whole row: the composite key and the interest rate.
     *
     * @return a stable, locale-independent description of this row
     */
    @Override
    public String toString() {
        return "DisclosureGroup[id=" + id + ", interestRate=" + interestRate + "]";
    }

    /**
     * Validates the composite key.
     *
     * @param value the candidate key, which may be {@code null}
     * @return {@code value} unchanged when it satisfies the contract
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    private static DisclosureGroupId requireId(final DisclosureGroupId value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "id (DIS-GROUP-KEY) must not be null: it is the composite primary key of table "
                            + "disclosure_group, derived from app/cpy/CVTRA02Y.cpy:L5");
        }
        return value;
    }

    /**
     * Validates the interest rate.
     *
     * @param value the candidate rate, which may be {@code null}
     * @return {@code value} unchanged when it satisfies the contract
     * @throws IllegalArgumentException if {@code value} is {@code null}, carries more than two decimal
     *                                  digits, or falls outside the range {@code S9(04)V99} can hold
     */
    private static BigDecimal requireInterestRate(final BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "interestRate (DIS-INT-RATE) must not be null: it is a non-nullable NUMERIC("
                            + INTEREST_RATE_PRECISION + "," + INTEREST_RATE_SCALE + ") column of table "
                            + "disclosure_group, derived from PIC S9(04)V99 at app/cpy/CVTRA02Y.cpy:L9");
        }
        if (value.scale() > INTEREST_RATE_SCALE) {
            throw new IllegalArgumentException("interestRate (DIS-INT-RATE PIC S9(04)V99) must carry at most "
                    + INTEREST_RATE_SCALE + " decimal digits but had a scale of " + value.scale()
                    + "; rescale it explicitly with RoundingMode.HALF_EVEN rather than letting the NUMERIC("
                    + INTEREST_RATE_PRECISION + "," + INTEREST_RATE_SCALE
                    + ") column round it half away from zero");
        }
        if (value.compareTo(MIN_INTEREST_RATE) < 0 || value.compareTo(MAX_INTEREST_RATE) > 0) {
            throw new IllegalArgumentException("interestRate (DIS-INT-RATE PIC S9(04)V99) must be between "
                    + MIN_INTEREST_RATE.toPlainString() + " and " + MAX_INTEREST_RATE.toPlainString()
                    + " inclusive, which is what " + INTEREST_RATE_INTEGER_DIGITS
                    + " signed integer digits can hold, but was " + value.toPlainString());
        }
        return value;
    }
}
