/*
 * ******************************************************************
 * Program     : TransactionCategoryBalanceId.java
 * Application : CardDemo
 * Type        : Java JPA composite identifier (Embeddable value type)
 * Function    : Composite primary key of the transaction category balance
 *               store. Replaces the 17 byte VSAM KSDS record key of the
 *               TCATBALF cluster with a value based Java identifier.
 * Source      : app/cpy/CVTRA01Y.cpy (key 17) @ 7756d89
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

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key of the transaction category balance store, replacing the 17 byte record key of
 * the {@code TCATBALF} VSAM KSDS cluster.
 *
 * <p><strong>What it does.</strong> This is a pure, value based identifier. It carries exactly the three
 * components of the COBOL group {@code TRAN-CAT-KEY} and nothing else, and it is consumed through
 * {@code @EmbeddedId} by the transaction category balance entity. It holds no behaviour beyond value
 * equality, exposes no mutators, performs no I/O and has no collaborators.
 *
 * <p><strong>How it is built and verified.</strong> {@code mvn -B clean verify} compiles this class under
 * {@code -Xlint:all -Werror} and enforces the project line coverage floor; its unit test lives in
 * {@code src/test/java/com/cardemo/unit/model}. The mapping itself is verified by starting the
 * application against a schema produced by the Flyway migrations, because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile and so any drift between
 * this class and the schema surfaces as a startup failure rather than as a latent fault. The measured
 * outcomes of that check, including the two column types that fail it, are recorded below.
 *
 * <h2>Source contract</h2>
 *
 * <p>Reproduced verbatim from {@code app/cpy/CVTRA01Y.cpy} (lowercase {@code .cpy} on disk):
 *
 * <pre>{@code
 * 01  TRAN-CAT-BAL-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10 TRANCAT-ACCT-ID                       PIC 9(11).
 *        10 TRANCAT-TYPE-CD                       PIC X(02).
 *        10 TRANCAT-CD                            PIC 9(04).
 *     05  TRAN-CAT-BAL                            PIC S9(09)V99.
 *     05  FILLER                                  PIC X(22).
 * }</pre>
 *
 * <p>Only the three components nested under {@code TRAN-CAT-KEY} belong to this class.
 * {@code TRAN-CAT-BAL} and {@code FILLER} are record payload, not key, and are mapped by the entity.
 *
 * <h2>Key length is 17 bytes</h2>
 *
 * <p>The width of this identifier is fixed at 17 bytes and is corroborated five independent ways, so it
 * may be treated as settled fact rather than inference:
 *
 * <ol>
 *   <li>Copybook arithmetic: {@code 9(11)} + {@code X(02)} + {@code 9(04)} = 11 + 2 + 4 = 17.</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L1371} reads {@code KEYLEN----------------17} with
 *       {@code AVGLRECL--------------50}, under the cluster
 *       {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS} declared at {@code app/catlg/LISTCAT.txt:L1369}.</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L1372} reads {@code RKP--------------------0}, so the key sits at
 *       byte 0 and is the record prefix; {@code L1373} marks the cluster {@code UNIQUE} and
 *       {@code INDEXED}.</li>
 *   <li>The seed fixture {@code app/data/ASCII/tcatbal.txt:L1} splits 11/2/4 as
 *       {@code 00000000001} + {@code 01} + {@code 0001}, followed by the eleven character
 *       {@code S9(09)V99} balance with its trailing zoned decimal overpunch sign and 22 filler bytes,
 *       totalling the catalogued 50 byte record.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L60} declares {@code RECORD KEY IS FD-TRAN-CAT-KEY}, and
 *       {@code app/cbl/CBTRN02C.cbl:L93-L96} declares that key with the identical 11/2/4 composition.
 *       This is an independent FILE-CONTROL level proof of both the width and the component order.</li>
 * </ol>
 *
 * <h2>Component order is load bearing, not cosmetic</h2>
 *
 * <p>A VSAM browse proceeds in key order, so the byte layout of a composite key <em>is</em> its sort
 * order. {@code app/cbl/CBACT04C.cbl:L188-L222} browses this file sequentially and detects an
 * account level control break by testing {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}, flushing the
 * accumulated interest for the previous account through {@code 1050-UPDATE-ACCOUNT} on each break and
 * once more when end of file is reached.
 *
 * <p>That break is correct <em>only</em> because {@code TRANCAT-ACCT-ID} is the leading component.
 * Reordering the declarations, for instance alphabetising them to {@code catCd}, {@code accountId},
 * {@code typeCd}, would change the iteration order and silently lose interest for whole accounts.
 * The declaration order {@code accountId} then {@code typeCd} then {@code catCd} is therefore
 * deliberate and must not be tidied or alphabetised. The composite primary key in the schema must
 * declare its columns in the same order.
 *
 * <h2>Ordering safety of the numeric mapping</h2>
 *
 * <p>Mapping the two numeric components to {@code Long} and {@code Integer} rather than to
 * {@code String} does not disturb that ordering. The fixture stores every numeric component as
 * zero padded, fixed width, unsigned digits ({@code 00000000001}, {@code 0001}), and for such values
 * lexicographic byte order and numeric order coincide. A {@code Long}/{@code Integer} mapping therefore
 * preserves the control break sequence exactly as a {@code String} mapping would, while additionally
 * rejecting non numeric input at the type boundary.
 *
 * <h2>Name collision warning: {@code TRAN-CAT-KEY} is declared twice</h2>
 *
 * <p>The COBOL group name {@code TRAN-CAT-KEY} appears in two unrelated copybooks with different
 * compositions, and conflating them would corrupt both mappings:
 *
 * <ul>
 *   <li><strong>{@code app/cpy/CVTRA01Y.cpy}</strong> is the source of <em>this</em> class:
 *       17 bytes, three fields, {@code TRANCAT-} prefix.</li>
 *   <li>{@code app/cpy/CVTRA04Y.cpy} is a different key altogether: 6 bytes, two fields,
 *       {@code TRAN-} prefix ({@code TRAN-TYPE-CD}, {@code TRAN-CAT-CD}), belonging to the transaction
 *       category type record whose identifier is {@code TransactionCategoryId}.</li>
 * </ul>
 *
 * <p>They are distinct contracts that merely share a group name, and the field prefixes differ as well.
 * No abstraction is extracted across them: this class deliberately has no base class, no shared helper
 * and no subclass, because the apparent structural similarity between the identifiers in this package is
 * a coincidence of shape rather than a shared concept.
 *
 * <h2>Column contract, and what is not yet available</h2>
 *
 * <p>Every component carries an explicit {@code @Column} name so that no Hibernate naming strategy can
 * rename a column implicitly. Because {@code spring.jpa.hibernate.ddl-auto} is set to {@code validate},
 * a name or type mismatch fails application context startup rather than degrading at runtime. The
 * contract this class commits to is:
 *
 * <pre>
 * table transaction_category_balance
 *   acct_id       &lt;- TRANCAT-ACCT-ID  9(11)  BIGINT      NOT NULL   part of PK, Java Long
 *   tran_type_cd  &lt;- TRANCAT-TYPE-CD  X(02)  VARCHAR(2)  NOT NULL   part of PK, Java String
 *   tran_cat_cd   &lt;- TRANCAT-CD       9(04)  INTEGER     NOT NULL   part of PK, Java Integer
 *   PRIMARY KEY (acct_id, tran_type_cd, tran_cat_cd)
 * </pre>
 *
 * <p><strong>Not available.</strong> At the time this class was authored
 * {@code src/main/resources/db/migration/V1__create_schema.sql} did not exist, and neither did the
 * companion entity. The column names and widths above are the contract derived from
 * {@code app/cpy/CVTRA01Y.cpy} and {@code app/catlg/LISTCAT.txt:L1371}; the SQL types are the ones this
 * mapping was measured to validate against, as recorded below. To close the gap, two artefacts are
 * needed: the authored {@code V1__create_schema.sql} declaring these three columns and the composite
 * primary key in the order above, and the entity that mounts this class through {@code @EmbeddedId}.
 *
 * <h2>Measured type pairings, and one correction</h2>
 *
 * <p>The Hibernate schema validator compares JDBC type codes rather than merely widths, so the Java and
 * SQL types must be paired deliberately. The three pairings below were not assumed: each was executed
 * against PostgreSQL 16.10 with Hibernate 6.6.42.Final and {@code hibernate.hbm2ddl.auto=validate}, on a
 * throwaway schema, and the outcome recorded.
 *
 * <ul>
 *   <li>{@code Long} over {@code BIGINT} validates. {@code Long} over {@code NUMERIC(11)}
 *       <strong>fails</strong>: "found [numeric (Types#NUMERIC)], but expecting [bigint
 *       (Types#BIGINT)]". Severity Medium. The remedy is to declare {@code BIGINT} in the migration,
 *       not to widen the Java type, because the Java type is pinned by the {@code 9(11)} picture
 *       clause.</li>
 *   <li>{@code String} with {@code length = 2} over {@code VARCHAR(2)} validates.</li>
 *   <li>{@code String} with {@code length = 2} over {@code CHAR(2)} <strong>fails</strong>: "found
 *       [bpchar (Types#CHAR)], but expecting [varchar(2) (Types#VARCHAR)]". <strong>Severity High</strong>
 *       — this contradicts the {@code CHAR(2)} that a literal reading of the field contract suggests for
 *       a fixed width {@code PIC X(02)} field, and it manifests as a startup blocker on first boot
 *       because {@code ddl-auto} is {@code validate}. Adding
 *       {@code @Column(columnDefinition = "char(2)")} does <em>not</em> fix it: the expected type code
 *       stays {@code Types#VARCHAR}, so validation still fails. Forcing {@code CHAR} would require the
 *       provider specific {@code @JdbcTypeCode(SqlTypes.CHAR)}, which would couple this value type to
 *       Hibernate rather than to Jakarta Persistence. The remedy adopted here is therefore
 *       {@code VARCHAR(2)}: the migration must declare {@code tran_type_cd VARCHAR(2) NOT NULL}. Nothing
 *       is lost by it, because the value is always exactly two characters, and {@code VARCHAR} avoids
 *       the trailing space semantics that {@code bpchar} comparison carries.</li>
 * </ul>
 *
 * <h2>Provider attribute ordering does not follow declaration order</h2>
 *
 * <p>One further behaviour was measured and must be known to whoever authors the migration. Hibernate
 * does not preserve the declaration order of an embeddable's attributes: it sorts them alphabetically,
 * so it resolves this key as {@code accountId}, {@code catCd}, {@code typeCd} and would emit a generated
 * primary key of {@code (acct_id, tran_cat_cd, tran_type_cd)}. That is <em>not</em> the COBOL key order.
 *
 * <p>The declaration order in this file is nevertheless correct and is deliberately left as the COBOL
 * order, for two reasons. First, the leading component is {@code accountId} under either ordering, so the
 * account level control break described above is unaffected. Second, {@code validate} was measured to
 * ignore primary key column order entirely: the pairing above validated cleanly against a table whose
 * primary key was physically declared as {@code (acct_id, tran_type_cd, tran_cat_cd)}, so the physical
 * order is set by the migration and not by the provider. Severity Medium, escalating to High only if DDL
 * is ever generated from the entity model. Remediation: {@code V1__create_schema.sql} must declare
 * {@code PRIMARY KEY (acct_id, tran_type_cd, tran_cat_cd)} explicitly, and no schema for this table may
 * be derived from provider generated DDL.
 *
 * <h2>Construction, immutability and the upsert path</h2>
 *
 * <p>An instance is freely constructible for a row that does not yet exist. This is required, not
 * incidental: {@code app/cbl/CBTRN02C.cbl:L467} ({@code 2700-UPDATE-TCATBAL}) populates the key at
 * {@code L469-L471} <em>before</em> reading, and {@code L481} accepts file status {@code '00'}
 * <em>or</em> {@code '23'}, treating record not found as an accepted control path that dispatches to
 * {@code 2700-A-CREATE-TCATBAL-REC}. This class therefore imposes no existence dependent behaviour and
 * performs no validation that would reject a key for a row awaiting creation.
 *
 * <p>Instances are effectively immutable: the components are private and no setter, wither or other
 * mutating operation is exposed, so a key placed in a persistence context cannot be altered underneath
 * it. The components are not declared {@code final} because JPA requires a no argument constructor and
 * Hibernate populates an embeddable by reflective field write after that construction; {@code final}
 * components would force the no argument constructor to assign placeholder nulls and would depend on
 * reflective mutation of final instance fields, which the language permits only under
 * {@code setAccessible(true)} and which is not a guarantee Hibernate offers for embeddable components.
 * Private fields with no mutators achieve the same practical immutability without resting on that.
 *
 * <h2>Serialization is for JPA identity only</h2>
 *
 * <p>This type implements {@link Serializable} solely because JPA requires composite identifier classes
 * to be serializable, and it declares an explicit {@code serialVersionUID} so that the form is stable
 * and the compiler's {@code serial} lint is satisfied under {@code -Werror}.
 *
 * <p><strong>It must never be used to deserialize untrusted input.</strong> No code path may feed
 * externally supplied bytes into an {@code ObjectInputStream} that reconstructs this type, and Java
 * serialization must not be used as the wire format for caching or messaging involving it. Java
 * deserialization does not run constructors and so cannot enforce any invariant declared here, which is
 * the classic insecure deserialization exposure. Transport and cache representations must use an
 * explicit, schema checked encoding such as JSON instead.
 *
 * <h2>Error modes</h2>
 *
 * <p>No operation on this class throws. Both constructors accept their arguments as given, including
 * {@code null}, so that a partially populated key remains representable for the upsert path described
 * above; {@link #equals(Object)} is total over all three components, so a partially populated key never
 * compares equal to a differently populated one. Callers that require a fully populated key must assert
 * that themselves, at the boundary where the requirement actually applies.
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */
@Embeddable
public class TransactionCategoryBalanceId implements Serializable {

    /**
     * Explicit serialization form identifier. Required by the {@code serial} lint category, which
     * {@code -Xlint:all -Werror} promotes to a build failure, and pinned so that the serialized form
     * does not drift when unrelated members are added.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Leading key component: {@code TRANCAT-ACCT-ID PIC 9(11)}, occupying bytes 1 to 11 of the key.
     *
     * <p>Mapped to {@code Long} because an eleven digit unsigned value reaches 99,999,999,999, which
     * exceeds {@link Integer#MAX_VALUE}; {@code Integer} would silently overflow real account
     * identifiers. This component must remain first, for the control break reason documented on the
     * class.
     */
    @Column(name = "acct_id", nullable = false)
    private Long accountId;

    /**
     * Second key component: {@code TRANCAT-TYPE-CD PIC X(02)}, occupying bytes 12 to 13 of the key.
     *
     * <p>Mapped to {@code String} of exactly two characters, not to a numeric type. The picture clause
     * is {@code X}, that is alphanumeric, so the stored form is significant in its own right: the
     * fixture value {@code 01} is a two character code whose leading zero carries meaning and must not
     * be normalised away. The column is neither widened nor trimmed.
     */
    @Column(name = "tran_type_cd", nullable = false, length = 2)
    private String typeCd;

    /**
     * Trailing key component: {@code TRANCAT-CD PIC 9(04)}, occupying bytes 14 to 17 of the key.
     *
     * <p>Mapped to {@code Integer} because a four digit unsigned value reaches only 9,999, which
     * {@code Integer} represents exactly. No monetary value appears anywhere in this identifier, so no
     * decimal scale question arises here and no approximate or arbitrary precision numeric type is
     * needed; every component is an exact integral or fixed width character code.
     */
    @Column(name = "tran_cat_cd", nullable = false)
    private Integer catCd;

    /**
     * Creates an empty identifier with all three components unset.
     *
     * <p>Present because JPA requires a no argument constructor on an embeddable so that the provider
     * can instantiate the type before populating it reflectively. Application code should prefer
     * {@link #TransactionCategoryBalanceId(Long, String, Integer)}, which produces a fully populated
     * key in one step.
     */
    public TransactionCategoryBalanceId() {
        // Intentionally empty: JPA instantiates through this constructor and then writes the
        // components reflectively. No default is invented for any component, because inventing one
        // would fabricate a key that the source system never produced.
    }

    /**
     * Creates a fully populated identifier from its three components, in COBOL declaration order.
     *
     * <p>Arguments are accepted as given and are not rejected when {@code null}, so that a key may be
     * built for a row that does not yet exist. That is the daily posting upsert path recorded on the
     * class: {@code app/cbl/CBTRN02C.cbl:L469-L471} builds the key before the read at {@code L481}
     * decides whether the row must be created or updated.
     *
     * @param accountId leading component, {@code TRANCAT-ACCT-ID PIC 9(11)}; may be {@code null} for a
     *                  key that is not yet fully determined
     * @param typeCd    second component, {@code TRANCAT-TYPE-CD PIC X(02)}, a two character
     *                  alphanumeric code; may be {@code null}
     * @param catCd     trailing component, {@code TRANCAT-CD PIC 9(04)}; may be {@code null}
     */
    public TransactionCategoryBalanceId(final Long accountId, final String typeCd, final Integer catCd) {
        this.accountId = accountId;
        this.typeCd = typeCd;
        this.catCd = catCd;
    }

    /**
     * Returns the leading key component, {@code TRANCAT-ACCT-ID PIC 9(11)}.
     *
     * @return the account identifier, or {@code null} if unset
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Returns the second key component, {@code TRANCAT-TYPE-CD PIC X(02)}.
     *
     * @return the two character transaction type code, or {@code null} if unset
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Returns the trailing key component, {@code TRANCAT-CD PIC 9(04)}.
     *
     * @return the transaction category code, or {@code null} if unset
     */
    public Integer getCatCd() {
        return catCd;
    }

    /**
     * Compares two identifiers by value across all three components.
     *
     * <p>Equality is total: two identifiers are equal only when their account identifier, type code and
     * category code all agree, so a partially populated key never compares equal to a differently
     * populated one. {@code null} and foreign types compare unequal. The runtime class is compared
     * exactly rather than with an {@code instanceof} widening, which keeps the relation symmetric; that
     * is safe here because an embeddable component is instantiated directly by the persistence provider
     * and is never replaced by a lazy loading proxy subclass, and because this class is not extended.
     *
     * <p>A correct implementation matters more than it appears: JPA relies on it for identity map
     * lookups, so a broken one degrades {@code find}, {@code merge} and dirty checking into
     * intermittent data faults rather than clean failures.
     *
     * @param obj the object to compare against, possibly {@code null}
     * @return {@code true} if {@code obj} is an identifier of exactly this class with all three
     *         components equal
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final TransactionCategoryBalanceId other = (TransactionCategoryBalanceId) obj;
        return Objects.equals(accountId, other.accountId)
                && Objects.equals(typeCd, other.typeCd)
                && Objects.equals(catCd, other.catCd);
    }

    /**
     * Returns a hash consistent with {@link #equals(Object)} over all three components.
     *
     * <p>The hash is derived from exactly the components that {@link #equals(Object)} compares, so the
     * two are mutually consistent, and it is stable for a given value because the components are never
     * mutated after construction. Hash order carries no meaning: the account level control break in
     * {@code app/cbl/CBACT04C.cbl:L188-L222} depends on key order and must never be driven from hash
     * bucket iteration order.
     *
     * @return the value based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId, typeCd, catCd);
    }

    /**
     * Returns a diagnostic rendering of the three key components.
     *
     * <p>Intended for diagnostics only and deliberately not treated as a stable log or wire format.
     * Only key components appear, and none of them is credential or personally identifying material:
     * an account identifier, a transaction type code and a transaction category code. The rendering is
     * produced by plain concatenation, so it consults no locale, charset or time zone and is identical
     * on every machine.
     *
     * @return a rendering of the form {@code TransactionCategoryBalanceId[accountId=..., typeCd=...,
     *         catCd=...]}
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalanceId[accountId=" + accountId
                + ", typeCd=" + typeCd
                + ", catCd=" + catCd
                + "]";
    }
}
