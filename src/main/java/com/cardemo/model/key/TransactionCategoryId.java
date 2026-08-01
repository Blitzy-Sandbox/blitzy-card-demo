/*
 * ******************************************************************
 * Program     : TransactionCategoryId.java
 * Application : CardDemo
 * Type        : Java Persistence composite identifier (Embeddable)
 * Function    : Composite primary key of the transaction category type
 *               file TRANCATG. Reproduces the COBOL group TRAN-CAT-KEY
 *               of app/cpy/CVTRA04Y.cpy exactly - TRAN-TYPE-CD PIC
 *               X(02) followed by TRAN-CAT-CD PIC 9(04) - a six byte
 *               key at relative byte position zero of a 60 byte record.
 * Source      : app/cpy/CVTRA04Y.cpy (key 6) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
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
package com.cardemo.model.key;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Composite primary key of the transaction category type table, reproducing the COBOL group
 * {@code TRAN-CAT-KEY} of {@code app/cpy/CVTRA04Y.cpy} field for field and in source order.
 *
 * <p>This is a pure value type. It carries the two key components of the {@code TRANCATG} VSAM KSDS
 * cluster and nothing else: it holds no business data, performs no I/O, reads no configuration and
 * depends on no other CardDemo type. It is consumed through {@code jakarta.persistence.EmbeddedId} by
 * {@code com.cardemo.model.entity.TransactionCategory}.
 *
 * <p><strong>Source field contract.</strong> {@code app/cpy/CVTRA04Y.cpy} declares, verbatim:
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
 * <p>Only the two fields nested inside {@code TRAN-CAT-KEY} belong to this class.
 * {@code TRAN-CAT-TYPE-DESC} and the four byte {@code FILLER} are payload rather than key and are
 * modelled on the owning entity; together with the key they account for the record length the copybook
 * header states as {@code RECLN = 60}, since {@code 6 + 50 + 4 = 60}.
 *
 * <p>The mapping, in COBOL declaration order:
 * <ul>
 *   <li>{@code TRAN-TYPE-CD}, {@code PIC X(02)}, 2 bytes, exposed by {@link #getTranTypeCd()}, column
 *       {@code tran_type_cd}.</li>
 *   <li>{@code TRAN-CAT-CD}, {@code PIC 9(04)}, 4 bytes, exposed by {@link #getTranCatCd()}, column
 *       {@code tran_cat_cd}.</li>
 * </ul>
 *
 * <p><strong>The key length is 6, corroborated four independent ways.</strong>
 * <ul>
 *   <li>Copybook arithmetic: {@code X(02)} plus {@code 9(04)} is {@code 2 + 4 = 6}.</li>
 *   <li>VSAM catalogue: {@code app/catlg/LISTCAT.txt:L1475} reads
 *       {@code KEYLEN-----------------6     AVGLRECL--------------60}, beneath
 *       {@code CLUSTER--AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS} at
 *       {@code app/catlg/LISTCAT.txt:L1473}.</li>
 *   <li>Record offset: {@code app/catlg/LISTCAT.txt:L1476} reads {@code RKP--------------------0}, so the
 *       key is the record prefix, and {@code app/catlg/LISTCAT.txt:L1477} shows the cluster is
 *       {@code UNIQUE} and {@code INDEXED}.</li>
 *   <li>Seed fixture: {@code app/data/ASCII/trancatg.txt} line 1 reads
 *       {@code 010001Regular Sales Draft}, a 2 and 4 split of {@code 01} and {@code 0001}, and line 2
 *       reads {@code 010002Regular Cash Advance}; all 18 rows are exactly 60 bytes wide.</li>
 * </ul>
 *
 * <p><strong>Declaration order is load bearing, not cosmetic.</strong> VSAM browses proceed in key order
 * and the composite key's byte layout is that order, so {@code tranTypeCd} precedes {@code tranCatCd}
 * exactly as the copybook declares and the relational primary key must be declared as
 * {@code (tran_type_cd, tran_cat_cd)} in the same sequence. The components are deliberately not
 * alphabetised and must not be reordered or tidied.
 *
 * <p><strong>Ordering safety of the numeric component.</strong> {@code TRAN-CAT-CD} is stored as zero
 * padded fixed width unsigned digits: the fixture holds {@code 0001} through {@code 0005} and all 18 of
 * its rows match a four digit pattern. The lexicographic order of the VSAM byte image therefore equals
 * the numeric order of the decoded value, so mapping this component to {@link Integer} preserves browse
 * ordering rather than perturbing it. Any fixed width rendering of the component must zero pad it back to
 * four digits.
 *
 * <p><strong>WARNING - the COBOL group name {@code TRAN-CAT-KEY} is declared in two different copybooks
 * with two entirely different compositions.</strong> The identically named {@code TRAN-CAT-KEY} group in
 * {@code app/cpy/CVTRA01Y.cpy} is a different key: 17 bytes over three fields, namely
 * {@code TRANCAT-ACCT-ID PIC 9(11)}, {@code TRANCAT-TYPE-CD PIC X(02)} and {@code TRANCAT-CD PIC 9(04)},
 * over the {@code TCATBALF} cluster, and it belongs to {@code TransactionCategoryBalanceId} rather than to
 * this class. This class is the 6 byte, two component key of the {@code TRANCATG} cluster and has no
 * account identifier component at all.
 *
 * <p>Every observable property except the group name separates the two:
 * <ul>
 *   <li>{@code app/cpy/CVTRA04Y.cpy}, this class: 6 bytes, 2 fields, {@code TRAN-} field name prefix,
 *       record {@code TRAN-CAT-RECORD} at {@code RECLN = 60}, cluster {@code TRANCATG} with
 *       {@code KEYLEN 6} at {@code app/catlg/LISTCAT.txt:L1475}.</li>
 *   <li>{@code app/cpy/CVTRA01Y.cpy}, {@code TransactionCategoryBalanceId}: 17 bytes, 3 fields,
 *       {@code TRANCAT-} field name prefix, record {@code TRAN-CAT-BAL-RECORD} at {@code RECLN = 50},
 *       cluster {@code TCATBALF} with {@code KEYLEN 17} at {@code app/catlg/LISTCAT.txt:L1371}.</li>
 * </ul>
 *
 * <p>The resemblance between the two, that both end in a type code followed by a category code, is
 * superficial, and the shared group name is a naming coincidence in the legacy corpus rather than
 * evidence of a shared concept. The collision is consequently resolved by documentation and never by
 * abstraction: this class deliberately has no shared superclass, no common interface, no abstract key
 * type and no shared helper with {@code TransactionCategoryBalanceId} or {@code DisclosureGroupId}.
 * Repeating a few lines of {@link #equals(Object)} and {@link #hashCode()} across three independent value
 * types is the intended outcome; collapsing them would couple three unrelated contracts, and that is the
 * specific mistake this paragraph exists to prevent.
 *
 * <p><strong>Relational contract.</strong> Each component states its column name explicitly so that no
 * Hibernate naming strategy can rename a column silently. Because {@code spring.jpa.hibernate.ddl-auto}
 * is {@code validate} in every profile, a column name or type mismatch fails application context startup
 * rather than degrading quietly at runtime. Flyway owns the DDL, and this mapping must agree with it:
 *
 * <pre>
 * table transaction_category
 *   tran_type_cd   from TRAN-TYPE-CD  PIC X(02)   VARCHAR(2)  NOT NULL   part of PK
 *   tran_cat_cd    from TRAN-CAT-CD   PIC 9(04)   INTEGER     NOT NULL   part of PK
 *   PRIMARY KEY (tran_type_cd, tran_cat_cd)
 * </pre>
 *
 * <p>The column names {@code tran_type_cd} and {@code tran_cat_cd} recur on the tables keyed by
 * {@code TransactionCategoryBalanceId} and {@code DisclosureGroupId}. That recurrence is a deliberate
 * normalisation rather than a literal identity in the copybooks, because the three sources prefix the
 * equivalent fields differently and one of them abbreviates: {@code app/cpy/CVTRA04Y.cpy} declares
 * {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD}, {@code app/cpy/CVTRA02Y.cpy} declares
 * {@code DIS-TRAN-TYPE-CD} and {@code DIS-TRAN-CAT-CD}, and {@code app/cpy/CVTRA01Y.cpy} declares
 * {@code TRANCAT-TYPE-CD} and {@code TRANCAT-CD}. Dropping the record level prefix yields the same two
 * column names on all three tables, which is convenient but carries no further meaning: the reuse spans
 * three separate tables, it is not evidence of a shared type, and each key class declares its own columns
 * independently.
 *
 * <p><strong>Not available, and what is needed to close it.</strong>
 * {@code src/main/resources/db/migration/V1__create_schema.sql} and
 * {@code com.cardemo.model.entity.TransactionCategory} were not available when this class was authored:
 * the {@code src} tree did not yet exist, so neither the authored SQL types nor the owning entity could be
 * read. Closing this gap requires both artefacts. Until they exist the contract above is normative,
 * derived solely from {@code app/cpy/CVTRA04Y.cpy} and {@code app/catlg/LISTCAT.txt:L1475}, and no SQL
 * type has been invented beyond it.
 *
 * <p><strong>Finding, severity Medium - JDBC type code pairing.</strong> Hibernate's schema validator
 * compares JDBC type codes and not merely column names, so the Java type and the SQL type must be paired
 * consistently across this class, the owning entity and the migration. Under the plain Jakarta Persistence
 * mapping used here a {@link String} declared with a length of 2 is {@code VARCHAR(2)} and an
 * {@link Integer} is {@code INTEGER}. Declaring the columns as {@code CHAR(2)} or {@code NUMERIC(4)} in
 * the migration would therefore fail validation at startup. Remediation: declare
 * {@code tran_type_cd VARCHAR(2) NOT NULL} and {@code tran_cat_cd INTEGER NOT NULL}. A provider specific
 * annotation such as Hibernate's {@code JdbcTypeCode} would be needed to map {@code CHAR} and is
 * deliberately not used, because this class is restricted to the Jakarta Persistence API; the
 * {@code columnDefinition} attribute was considered and rejected, because it only influences generated
 * DDL, which Flyway owns here, and so cannot reconcile a type code mismatch.
 *
 * <p><strong>Serialization, and the deserialization constraint.</strong> This type implements
 * {@link Serializable} for exactly one reason: Jakarta Persistence requires the class of a composite
 * identifier to be serializable so that a provider can use it as an identity map key and place it in a
 * second level cache. An explicit {@code serialVersionUID} is declared so the serialized form is stable
 * rather than compiler derived. This type must never be used to deserialize untrusted input. There is no
 * {@code ObjectInputStream} path anywhere in the class, no Java serialization based caching or messaging
 * is built on it, and serialized bytes originating outside the application must not be accepted. Java
 * serialization is not a wire format in this application; wire representations belong to the DTO layer and
 * are JSON.
 *
 * <p><strong>Immutability.</strong> The class exposes no mutator. The components are not declared
 * {@code final} because a persistence provider instantiates the class through its no argument constructor
 * and then populates the fields reflectively, which a {@code final} instance field forbids; immutability
 * is therefore enforced by the complete absence of setters rather than by the modifier. Mutating a live
 * identifier would corrupt the persistence context's identity map, so no setter may be added.
 *
 * <p><strong>Determinism.</strong> No method performs a case conversion, a locale sensitive format, a
 * charset conversion or a time zone dependent operation, so no behaviour here can vary with the platform
 * default locale, charset or zone. {@link #hashCode()} is a cheap value hash, and nothing may depend on
 * hash iteration order, because the ordering that matters in this domain is the VSAM key order described
 * above.
 *
 * <p><strong>Build and test.</strong> This class is compiled by the root {@code pom.xml} for Java 25 with
 * {@code -Xlint:all -Werror}, so an unused import, a raw type or a missing {@code serialVersionUID} is a
 * build failure rather than a warning. Build with {@code mvn -B clean compile}, run the unit suite with
 * {@code mvn -B clean test} and gate coverage with {@code mvn -B verify}. The class introduces no
 * dependency and uses no annotation processor; Lombok is deliberately absent.
 *
 * <p><strong>Common failure modes.</strong>
 * <ul>
 *   <li>An {@link IllegalArgumentException} from the two argument constructor means a component violated
 *       the width or range of its picture clause. The message names the component and reports the value
 *       that was received.</li>
 *   <li>A Hibernate schema management failure naming {@code tran_type_cd} or {@code tran_cat_cd} during
 *       startup means the migration's column type does not pair with the mapping documented above.</li>
 *   <li>A repository lookup that unexpectedly finds nothing is usually a key built with the two
 *       components transposed, or a type code that was trimmed instead of being kept at its fixed width
 *       of 2 characters.</li>
 * </ul>
 *
 * <p>In the legacy corpus this record layout is consumed by {@code app/cbl/CBTRN03C.cbl:L108}, which
 * issues {@code COPY CVTRA04Y.} to resolve a transaction category description while producing the
 * transaction report. That program also carries a deliberately preserved legacy quirk, in that its
 * control break triggers on the card number while the emitted label reads {@code "Account Total"}; the
 * quirk is reproduced in the batch processor layer and is explicitly not modelled here.
 */
@Embeddable
public class TransactionCategoryId implements Serializable {

    /**
     * Explicit serialization version. It is declared rather than left to the compiler so that the
     * serialized form of a composite identifier stays stable across builds. See the deserialization
     * constraint documented on the class: this identifier is serializable for persistence provider use
     * only and must never be reconstituted from untrusted bytes.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Exact width, in characters, of {@code TRAN-TYPE-CD}, whose picture clause is {@code PIC X(02)}. The
     * COBOL field is fixed width, so a value is neither widened nor trimmed on the way in or out.
     */
    private static final int TRAN_TYPE_CD_LENGTH = 2;

    /**
     * Inclusive lower bound of {@code TRAN-CAT-CD}. Its picture clause {@code PIC 9(04)} is unsigned, so
     * zero is the smallest representable value and a negative category code cannot exist in the source.
     */
    private static final int TRAN_CAT_CD_MIN_VALUE = 0;

    /**
     * Inclusive upper bound of {@code TRAN-CAT-CD}: the largest value four unsigned display digits can
     * hold. The seed fixture uses {@code 0001} through {@code 0005}, comfortably inside this range.
     */
    private static final int TRAN_CAT_CD_MAX_VALUE = 9999;

    /**
     * First key component, from {@code TRAN-TYPE-CD PIC X(02)}, occupying bytes 1 to 2 of the record. It
     * is declared first because the VSAM key byte layout is the browse order and this component leads it.
     * The value is held exactly as supplied at its fixed width of {@code TRAN_TYPE_CD_LENGTH} characters:
     * it is never trimmed, padded or case folded.
     */
    @Column(name = "tran_type_cd", nullable = false, length = TRAN_TYPE_CD_LENGTH)
    private String tranTypeCd;

    /**
     * Second key component, from {@code TRAN-CAT-CD PIC 9(04)}, occupying bytes 3 to 6 of the record. It is
     * an unsigned display integer used purely as an identifier, so it maps to {@link Integer}. It is not a
     * monetary amount, and no {@code BigDecimal}, {@code float} or {@code double} appears anywhere in this
     * class.
     */
    @Column(name = "tran_cat_cd", nullable = false)
    private Integer tranCatCd;

    /**
     * No argument constructor required by Jakarta Persistence so that a provider can instantiate the
     * identifier before populating its components reflectively.
     *
     * <p>It is {@code protected} rather than {@code public} because application code has no legitimate use
     * for an unpopulated identifier and must use {@link #TransactionCategoryId(String, Integer)}, which
     * validates both components. No component is defaulted here: a synthetic default would be
     * indistinguishable from a real key and would defeat the validation the public constructor performs.
     */
    protected TransactionCategoryId() {
        // Intentionally empty. The persistence provider assigns both components by field reflection
        // immediately after instantiation, so validating or defaulting anything here would either reject a
        // legitimate provider created instance or fabricate a key value that was never read from the
        // database. This is a required Jakarta Persistence hook, not an unfinished implementation.
    }

    /**
     * Creates a fully populated identifier from its two components, in COBOL declaration order.
     *
     * <p>The parameter order mirrors {@code TRAN-CAT-KEY} in {@code app/cpy/CVTRA04Y.cpy} and therefore the
     * VSAM key byte order. Both arguments are validated against their picture clauses before assignment, so
     * a constructed instance is always a structurally valid 6 byte key. The constructor has no side effect
     * beyond initialising the two components.
     *
     * @param tranTypeCd the transaction type code from {@code TRAN-TYPE-CD PIC X(02)}; must be non
     *                   {@code null} and exactly 2 characters, matching the fixed width source field
     * @param tranCatCd  the transaction category code from {@code TRAN-CAT-CD PIC 9(04)}; must be non
     *                   {@code null} and between 0 and 9999 inclusive, the unsigned range of four display
     *                   digits
     * @throws IllegalArgumentException if either component is {@code null}, or lies outside the width or
     *                                  range its picture clause permits; the message names the offending
     *                                  component, quotes its source picture clause and reports the value
     *                                  that was received
     */
    public TransactionCategoryId(final String tranTypeCd, final Integer tranCatCd) {
        this.tranTypeCd = requireValidTranTypeCd(tranTypeCd);
        this.tranCatCd = requireValidTranCatCd(tranCatCd);
    }

    /**
     * Validates a candidate transaction type code against its source picture clause {@code PIC X(02)}.
     *
     * <p>The method is deliberately {@code static}: a constructor of a non final class must not invoke an
     * overridable instance method, because a subclass override would observe a partially initialised
     * instance, which the compiler's {@code this-escape} diagnostic reports and this build escalates to an
     * error.
     *
     * @param value the candidate value exactly as supplied by the caller, possibly {@code null}
     * @return {@code value} unchanged when valid; it is never trimmed, padded or case folded, because the
     *         source field is fixed width and any adjustment would change the key
     * @throws IllegalArgumentException if {@code value} is {@code null} or is not exactly 2 characters long
     */
    private static String requireValidTranTypeCd(final String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "tranTypeCd (TRAN-TYPE-CD PIC X(02)) is required and must not be null");
        }
        if (value.length() != TRAN_TYPE_CD_LENGTH) {
            throw new IllegalArgumentException("tranTypeCd (TRAN-TYPE-CD PIC X(02)) must be exactly "
                    + TRAN_TYPE_CD_LENGTH + " characters but was " + value.length() + ": [" + value + "]");
        }
        return value;
    }

    /**
     * Validates a candidate transaction category code against its source picture clause {@code PIC 9(04)}.
     *
     * <p>The method is {@code static} for the same reason as {@link #requireValidTranTypeCd(String)}: it is
     * called from a constructor and must not be overridable.
     *
     * @param value the candidate value exactly as supplied by the caller, possibly {@code null}
     * @return {@code value} unchanged when valid
     * @throws IllegalArgumentException if {@code value} is {@code null}, negative, which four unsigned
     *                                  display digits cannot represent, or greater than 9999, which they
     *                                  cannot hold
     */
    private static Integer requireValidTranCatCd(final Integer value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "tranCatCd (TRAN-CAT-CD PIC 9(04)) is required and must not be null");
        }
        if (value.intValue() < TRAN_CAT_CD_MIN_VALUE || value.intValue() > TRAN_CAT_CD_MAX_VALUE) {
            throw new IllegalArgumentException("tranCatCd (TRAN-CAT-CD PIC 9(04)) must be between "
                    + TRAN_CAT_CD_MIN_VALUE + " and " + TRAN_CAT_CD_MAX_VALUE + " inclusive but was "
                    + value);
        }
        return value;
    }

    /**
     * Returns the first key component, the transaction type code.
     *
     * @return the value of {@code TRAN-TYPE-CD PIC X(02)} exactly as stored, at its fixed width of 2
     *         characters and never trimmed or case folded; {@code null} only on an instance that a
     *         persistence provider has created but not yet populated
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Returns the second key component, the transaction category code.
     *
     * @return the value of {@code TRAN-CAT-CD PIC 9(04)} as an unsigned identifier in the inclusive range 0
     *         to 9999; {@code null} only on an instance that a persistence provider has created but not yet
     *         populated
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Compares this identifier with another object for value equality over both key components.
     *
     * <p>The comparison requires the two runtime classes to be identical rather than merely assignable.
     * That keeps the relation symmetric in the presence of any subclass, and it guarantees that this 6 byte
     * two component key can never compare equal to a structurally similar but semantically different key
     * such as {@code TransactionCategoryBalanceId}, even when both happen to hold the same type code and
     * category code. A partially populated identifier is equal only to another identifier of this exact
     * class that is unpopulated in exactly the same components, so an absent component never silently
     * matches a present one.
     *
     * @param other the object to compare with, possibly {@code null}
     * @return {@code true} if {@code other} is a {@code TransactionCategoryId} whose transaction type code
     *         and transaction category code are both equal to this identifier's; {@code false} otherwise,
     *         including when {@code other} is {@code null} or of any other class
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final TransactionCategoryId that = (TransactionCategoryId) other;
        return Objects.equals(tranTypeCd, that.tranTypeCd)
                && Objects.equals(tranCatCd, that.tranCatCd);
    }

    /**
     * Returns a value based hash consistent with {@link #equals(Object)}, computed over both components in
     * COBOL declaration order.
     *
     * <p>Jakarta Persistence depends on this hash being stable for the lifetime of an instance, because a
     * provider uses the identifier as a key in its identity map and a hash that changed would strand the
     * managed entity. Stability follows from the class having no mutator. The computation is a plain value
     * hash with no allocation beyond the argument array, so it is cheap enough to be called on every map
     * lookup.
     *
     * @return a hash derived from the transaction type code and the transaction category code, equal for any
     *         two identifiers that compare equal
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }

    /**
     * Returns a diagnostic rendering of the two key components.
     *
     * <p>This is intended for developer diagnostics only and is deliberately not a log or wire format: no
     * caller may parse it and it may change without notice. Both components are non sensitive identifiers,
     * a transaction type code and a transaction category code, so nothing confidential is exposed. The
     * rendering is plain concatenation with no locale sensitive formatting, so it cannot vary with the
     * platform default locale, and it does not zero pad the category code, which is why it must not be used
     * to build a fixed width key image.
     *
     * @return the simple class name followed by both components in COBOL declaration order, never
     *         {@code null}
     */
    @Override
    public String toString() {
        return "TransactionCategoryId[tranTypeCd=" + tranTypeCd + ", tranCatCd=" + tranCatCd + "]";
    }
}
