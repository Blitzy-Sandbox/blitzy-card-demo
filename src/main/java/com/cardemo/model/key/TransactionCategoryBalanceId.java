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
 * Composite primary key of the transaction category balance store, replacing the 17 byte record key of the
 * {@code TCATBALF} VSAM KSDS cluster.
 *
 * <p>This is a pure, value based identifier. It carries exactly the three components of the COBOL group
 * {@code TRAN-CAT-KEY} and nothing else, and it is consumed through {@code @EmbeddedId} by the transaction
 * category balance entity. It holds no behaviour beyond value equality, exposes no mutators, performs no I/O
 * and has no collaborators.
 *
 * <p><strong>How it is built and verified.</strong> {@code ./mvnw -B clean verify} compiles this class under
 * {@code -Xlint:all -Werror} and enforces the project line coverage floor; its unit tests live in
 * {@code src/test/java/com/cardemo/unit/model}. The mapping
 * itself is verified by starting the application against a schema produced by the Flyway migrations,
 * because
 * because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile and so any drift between
 * this class and the schema surfaces as a startup failure rather than as a latent fault. The measured
 * outcomes of that check are recorded below, together with the two type pairings that fail it and the
 * measured confirmation that {@code V1__create_schema.sql} already declares the remedied types.
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
 *   <li>The seed fixture {@code app/data/ASCII/tcatbal.txt} splits its 50 byte record 11/2/4 into a
 *       zero-padded account identifier, a two character type code and a four digit category code,
 *       followed by the eleven character
 *       {@code S9(09)V99} balance with its trailing zoned decimal overpunch sign and 22 filler bytes,
 *       totalling the catalogued 50 byte record.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L60} declares {@code RECORD KEY IS FD-TRAN-CAT-KEY}, and
 *       {@code app/cbl/CBTRN02C.cbl:L93-L96} declares that key with the identical 11/2/4 composition.
 *       This is an independent FILE-CONTROL level proof of both the width and the component order.</li>
 *   </ol>
 *
 * <h2>Component order is load bearing, not cosmetic</h2>
 *
 * <p>A VSAM browse proceeds in key order, so the byte layout of a composite key <em>is</em> its sort
 * order. {@code app/cbl/CBACT04C.cbl:L188-L222} browses this file sequentially and detects an
 * account level control break by testing {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM} at
 * {@code :L194}, flushing the accumulated interest for the previous account through
 * {@code 1050-UPDATE-ACCOUNT} at {@code :L196} on each break, guarded by the first record test at
 * {@code :L195-L199}.
 *
 * <p><strong>The source does not flush the final account.</strong> "And once more when end of file is
 * reached" is not what happens. The apparent final flush is {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} at
 * {@code :L219-L220},
 * but that {@code ELSE} belongs to {@code IF END-OF-FILE = 'N'} at {@code :L189} and can only be taken
 * when {@code END-OF-FILE} already equals {@code 'Y'} - which is exactly when the enclosing
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :L188} has already terminated, because
 * {@code PERFORM UNTIL} is test before unless {@code WITH TEST AFTER} is written and it is not written
 * there. The branch is unreachable, so for the last account in key order the interest is never added to
 * the balance, the two cycle accumulators are never zeroed, and no rewrite occurs - all three being
 * effects of {@code 1050-UPDATE-ACCOUNT} at {@code :L350-L370}.
 *
 * <p><strong>Java therefore performs no final flush either, and none may be added.</strong> An earlier
 * revision of this paragraph went on to say the Java flush must still happen, driven by the end of data
 * condition, as a labelled deviation from source behaviour; that instruction is withdrawn. It contradicted
 * the parity mandate - the loss is a deterministic arithmetic outcome rather than a corruption hazard, so
 * the boundary is carried across exactly as the source gets it wrong - and following it would post interest
 * the frozen system never posts.
 * {@code com.cardemo.batch.processors.InterestCalculationProcessor} retains the branch as
 * {@code updateAccountAtEndOfFile()}, an explicitly marked no-op called from nowhere, so the paragraph map
 * stays provable. See the fuller treatment on
 * {@code com.cardemo.repository.TransactionCategoryBalanceRepository}, which now states the same
 * constraint.
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
 * zero padded, fixed width, unsigned digits, and for such values
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
 *   </ul>
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
 * <p><strong>Both dependencies are present.</strong>
 * {@code src/main/resources/db/migration/V1__create_schema.sql} declares
 * {@code CREATE TABLE transaction_category_balance} with {@code acct_id BIGINT},
 * {@code tran_type_cd VARCHAR(2)} and {@code tran_cat_cd INTEGER}, closed by
 * {@code CONSTRAINT pk_transaction_category_balance PRIMARY KEY (acct_id, tran_type_cd, tran_cat_cd)} -
 * the component order above, exactly. And
 * {@code com.cardemo.model.entity.TransactionCategoryBalance} mounts this class through
 * {@code @EmbeddedId}. The column names and widths remain contracted by {@code app/cpy/CVTRA01Y.cpy} and
 * {@code app/catlg/LISTCAT.txt:L1371}; the SQL types are the ones this mapping validates against, as
 * recorded below.
 *
 * <h2>The type pairings this mapping depends on</h2>
 *
 * <p>The Hibernate schema validator compares JDBC type codes rather than merely widths, so the Java and
 * SQL types must be paired deliberately. The three pairings below were established by executing each
 * against PostgreSQL 16.10 with Hibernate 6.6.42.Final and {@code hibernate.hbm2ddl.auto=validate} on a
 * throwaway schema, and are reproducible from this tree without a Spring context: applying
 * {@code V1__create_schema.sql} into a throwaway schema on a PostgreSQL 16.10
 * instance yields 11 tables, 10 foreign keys and 5 check constraints, and bootstrapping Hibernate
 * 6.6.42.Final directly over all eleven annotated entities with {@code hibernate.hbm2ddl.auto=validate}
 * reports no mismatch. That check exercises this class, because {@code TransactionCategoryBalance} mounts
 * it as its {@code @EmbeddedId} and a component name or type mismatch would fail it. Only Hibernate's
 * {@code MetadataSources} bootstrap API is needed for it.
 * <ul>
 *   <li>{@code Long} over {@code BIGINT} validates. {@code Long} over {@code NUMERIC(11)}
 *       <strong>fails</strong>: "found [numeric (Types#NUMERIC)], but expecting [bigint
 *       (Types#BIGINT)]". The remedy is to declare {@code BIGINT} in the migration,
 *       not to widen the Java type, because the Java type is pinned by the {@code 9(11)} picture
 *       clause.</li>
 *   <li>{@code String} with {@code length = 2} over {@code VARCHAR(2)} validates.</li>
 *   <li>{@code String} with {@code length = 2} over {@code CHAR(2)} <strong>fails</strong>: "found
 *       [bpchar (Types#CHAR)], but expecting [varchar(2) (Types#VARCHAR)]".
 *       This contradicts the {@code CHAR(2)} that a literal reading of the field contract suggests for
 *       a fixed width {@code PIC X(02)} field, and it stops startup on first boot
 *       {@code @Column(columnDefinition = "char(2)")} does <em>not</em> fix it: the expected type code
 *       stays {@code Types#VARCHAR}, so validation still fails. Forcing {@code CHAR} would require the
 *       provider specific {@code @JdbcTypeCode(SqlTypes.CHAR)}, which would couple this value type to
 *       Hibernate rather than to Jakarta Persistence. The remedy adopted here is therefore
 *       {@code VARCHAR(2)}: the migration must declare {@code tran_type_cd VARCHAR(2) NOT NULL}. Nothing
 *       is lost by it, because the value is always exactly two characters, and {@code VARCHAR} avoids
 *       the trailing space semantics that {@code bpchar} comparison carries.</li>
 *   </ul>
 *
 * <p><strong>Both remedies are already in the migration.</strong> The
 * {@code transaction_category_balance} table that
 * {@code src/main/resources/db/migration/V1__create_schema.sql} declares carries
 * {@code acct_id BIGINT NOT NULL}, {@code tran_type_cd VARCHAR(2) NOT NULL} and
 * {@code tran_cat_cd INTEGER NOT NULL} - exactly the three types the mapping table above requires, with
 * neither {@code NUMERIC(11)} nor {@code CHAR(2)} present. Neither failing pairing is therefore live
 * against this schema; both entries are retained as the reasoning that fixed the column types, not as
 * open defects.
 *
 * <h2>Provider attribute ordering does not follow declaration order</h2>
 *
 * <p>One further provider behaviour must be known to whoever authors the migration. Hibernate
 * does not preserve the declaration order of an embeddable's attributes: it sorts them alphabetically,
 * so it resolves this key as {@code accountId}, {@code catCd}, {@code typeCd} and would emit a generated
 * primary key of {@code (acct_id, tran_cat_cd, tran_type_cd)}. That is <em>not</em> the COBOL key order.
 *
 * <p>The declaration order in this file is nevertheless correct and is deliberately left as the COBOL
 * order, for two reasons. First, the leading component is {@code accountId} under either ordering, so the
 * account level control break described above is unaffected. Second, {@code validate} ignores primary key
 * column order entirely: the pairing above validates cleanly against a table whose primary key is
 * physically declared as {@code (acct_id, tran_type_cd, tran_cat_cd)}, so the physical order is set by the
 * migration and not by the provider. It matters only if DDL is ever generated from the entity model, and
 * the rule that follows is that {@code V1__create_schema.sql} must declare
 * {@code PRIMARY KEY (acct_id, tran_type_cd, tran_cat_cd)} explicitly, and no schema for this table may
 * be derived from provider generated DDL.
 *
 * <h2>Construction, immutability and the upsert path</h2>
 *
 * <p>An instance is freely constructible for a row that does not yet exist. This is required, not
 * incidental: {@code app/cbl/CBTRN02C.cbl:L467} ({@code 2700-UPDATE-TCATBAL}) populates the key at
 * {@code L469-L471} <em>before</em> reading, and {@code L481} accepts file status {@code '00'}
 * <em>or</em> {@code '23'}, treating record not found as an accepted control path that dispatches to
 * {@code 2700-A-CREATE-TCATBAL-REC}. This class therefore imposes no <em>existence</em> dependent
 * behaviour: nothing here asks whether the row is already in the table, and a key for a row awaiting
 * creation is exactly as valid as a key for one already present.
 *
 * <p><strong>Row existence and component completeness are two different questions, and only the first is
 * open.</strong> The three {@code MOVE} statements at {@code app/cbl/CBTRN02C.cbl:L469-L471} - {@code MOVE
 * XREF-ACCT-ID TO FD-TRANCAT-ACCT-ID}, {@code MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD} and
 * {@code MOVE DALYTRAN-CAT-CD TO FD-TRANCAT-CD} - fill <em>all three</em> components before the
 * {@code READ} at {@code L472}, and COBOL has no null: a {@code PIC 9(11)}, a {@code PIC X(02)} and a
 * {@code PIC 9(04)} always hold their declared width. A partially populated key is therefore something the
 * source cannot produce and the read path never needs, which is why
 * {@link #TransactionCategoryBalanceId(Long, String, Integer)} validates every component and rejects
 * {@code null} without narrowing the upsert path by one row.
 *
 * <p>The validation is bounded by the picture clauses and by nothing else. It rejects {@code null}, an
 * account identifier outside 0 through 99999999999, a type code that is not exactly two characters, and a
 * category code outside 0 through 9999. It does <strong>not</strong> reject a blank or all-space type code,
 * because {@code PIC X(02)} admits spaces and the fixture data proves two-character codes whose leading
 * zero is significant; it does not trim, pad, upper-case or otherwise normalise anything, because any
 * adjustment would change the key bytes.
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
 * <p>Exactly one operation throws, and it is the application facing constructor.
 * {@link #TransactionCategoryBalanceId(Long, String, Integer)} raises
 * {@link IllegalArgumentException} naming the offending component, quoting its picture clause and
 * reporting what was received, when a component is {@code null} or lies outside the domain its picture
 * clause can represent. Failing there rather than at flush time is the point: the message names the
 * component, whereas a constraint violation surfacing from the provider names only a column.
 *
 * <p>Every other operation is total and throws nothing. The no-argument constructor accepts the
 * unpopulated state the persistence provider requires and is {@code protected} so that application code
 * cannot use it to route around the validation above. {@link #equals(Object)} tolerates {@code null} and a
 * foreign type, and is total over all three components, so an instance the provider has not finished
 * populating never compares equal to a differently populated one. {@link #hashCode()} and
 * {@link #toString()} are equally null tolerant.
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */
@Embeddable
public class TransactionCategoryBalanceId implements Serializable {

    /**
     * Explicit serialization form identifier. Required by the {@code serial} lint category, which
     * {@code -Xlint:all -Werror} promotes to a build failure, and pinned so that the serialized form does not
     * drift when unrelated members are added.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Smallest value {@code TRANCAT-ACCT-ID PIC 9(11)} at {@code app/cpy/CVTRA01Y.cpy:L6} can represent.
     * The picture clause carries no {@code S}, so the domain is unsigned and starts at zero.
     */
    private static final long MIN_ACCOUNT_ID = 0L;

    /**
     * Largest value {@code TRANCAT-ACCT-ID PIC 9(11)} at {@code app/cpy/CVTRA01Y.cpy:L6} can represent:
     * eleven unsigned display digits, which is also the domain of the {@code NUMERIC(11)} column. Written as
     * grouped nines so the digit count can be checked against the picture clause by eye.
     */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    /**
     * Exact character width of {@code TRANCAT-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA01Y.cpy:L7}. The
     * field is fixed width, so the requirement is equality and not an upper bound: a one character value
     * would occupy the wrong bytes of the 17 byte key.
     */
    private static final int TYPE_CD_LENGTH = 2;

    /**
     * Smallest value {@code TRANCAT-CD PIC 9(04)} at {@code app/cpy/CVTRA01Y.cpy:L8} can represent, the
     * picture clause again being unsigned.
     */
    private static final int MIN_CAT_CD = 0;

    /**
     * Largest value {@code TRANCAT-CD PIC 9(04)} at {@code app/cpy/CVTRA01Y.cpy:L8} can represent: four
     * unsigned display digits.
     */
    private static final int MAX_CAT_CD = 9999;

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
     * Second key component: {@code TRANCAT-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA01Y.cpy:L7},
     * occupying bytes 12 to 13 of the key.
     */
    @Column(name = "tran_type_cd", nullable = false, length = TYPE_CD_LENGTH)
    private String typeCd;

    /**
     * Trailing key component: {@code TRANCAT-CD PIC 9(04)} at {@code app/cpy/CVTRA01Y.cpy:L8},
     * occupying bytes 14 to 17 of the key.
     */
    @Column(name = "tran_cat_cd", nullable = false)
    private Integer catCd;

    /**
     * Creates an empty identifier with all three components unset.
     *
     * <p>Present because JPA requires a no argument constructor on an embeddable so that the provider
     * can instantiate the type before populating it reflectively.
     *
     * <p>It is {@code protected} rather than {@code public}, matching {@code TransactionCategoryId()} at
     * {@code TransactionCategoryId.java:L316} and {@code DisclosureGroupId()} at
     * {@code DisclosureGroupId.java:L281}, because an instance with three {@code null} components satisfies
     * none of the invariants {@link #TransactionCategoryBalanceId(Long, String, Integer)} enforces while
     * being indistinguishable from a real key at the type level. Leaving it {@code public} would offer a
     * silent route around that validation, and no caller under {@code src/} needs it. Hibernate is
     * unaffected: it instantiates an embeddable reflectively and requires only that the constructor exist.
     */
    protected TransactionCategoryBalanceId() {
        // Intentionally empty: JPA instantiates through this constructor and then writes the
        // components reflectively. No default is invented for any component, because inventing one
        // would fabricate a key that the source system never produced.
    }

    /**
     * Creates a fully populated identifier from its three components, in COBOL declaration order.
     *
     * @param accountId leading component, {@code TRANCAT-ACCT-ID PIC 9(11)}.
     * @param typeCd second component, {@code TRANCAT-TYPE-CD PIC X(02)}, a two character alphanumeric code.
     * @param catCd trailing component, {@code TRANCAT-CD PIC 9(04)}.
     */
    public TransactionCategoryBalanceId(final Long accountId, final String typeCd, final Integer catCd) {
        this.accountId = requireValidAccountId(accountId);
        this.typeCd = requireValidTypeCd(typeCd);
        this.catCd = requireValidCatCd(catCd);
    }

    /**
     * Validates a candidate account identifier against its source picture clause {@code PIC 9(11)}.
     *
     * <p>Declared {@code private static} deliberately: it is called from a constructor, and a
     * non-{@code static} or overridable method called from a constructor would publish {@code this} before
     * construction finished. The compiler reports exactly that as a {@code this-escape} warning, which
     * {@code -Xlint:all -Werror} turns into a build failure.
     *
     * @param value the candidate value exactly as supplied by the caller, possibly {@code null}
     * @return {@code value} unchanged when valid
     * @throws IllegalArgumentException if {@code value} is {@code null}, negative, which eleven unsigned
     *                                  display digits cannot represent, or greater than 99999999999
     */
    private static Long requireValidAccountId(final Long value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "accountId (TRANCAT-ACCT-ID PIC 9(11)) is required and must not be null");
        }
        if (value.longValue() < MIN_ACCOUNT_ID || value.longValue() > MAX_ACCOUNT_ID) {
            throw new IllegalArgumentException("accountId (TRANCAT-ACCT-ID PIC 9(11)) must be between "
                    + MIN_ACCOUNT_ID + " and " + MAX_ACCOUNT_ID + " inclusive but was " + value);
        }
        return value;
    }

    /**
     * Validates a candidate transaction type code against its source picture clause {@code PIC X(02)}.
     *
     * <p>The requirement is exact width, not a maximum: the component occupies bytes 12 and 13 of a 17 byte
     * key, so a shorter value would place the category code at the wrong offset. Spaces are accepted, since
     * an alphanumeric picture clause admits them. Declared {@code private static} for the reason given on
     * {@link #requireValidAccountId(Long)}.
     *
     * @param value the candidate value exactly as supplied by the caller, possibly {@code null}
     * @return {@code value} unchanged when valid; never trimmed, padded or case folded, because the source
     *         field is fixed width and any adjustment would change the key
     * @throws IllegalArgumentException if {@code value} is {@code null} or is not exactly 2 characters long
     */
    private static String requireValidTypeCd(final String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "typeCd (TRANCAT-TYPE-CD PIC X(02)) is required and must not be null");
        }
        if (value.length() != TYPE_CD_LENGTH) {
            throw new IllegalArgumentException("typeCd (TRANCAT-TYPE-CD PIC X(02)) must be exactly "
                    + TYPE_CD_LENGTH + " characters but was " + value.length() + ": [" + value + "]");
        }
        return value;
    }

    /**
     * Validates a candidate transaction category code against its source picture clause {@code PIC 9(04)}.
     *
     * <p>Declared {@code private static} for the reason given on {@link #requireValidAccountId(Long)}.
     *
     * @param value the candidate value exactly as supplied by the caller, possibly {@code null}
     * @return {@code value} unchanged when valid
     * @throws IllegalArgumentException if {@code value} is {@code null}, negative, which four unsigned
     *                                  display digits cannot represent, or greater than 9999
     */
    private static Integer requireValidCatCd(final Integer value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "catCd (TRANCAT-CD PIC 9(04)) is required and must not be null");
        }
        if (value.intValue() < MIN_CAT_CD || value.intValue() > MAX_CAT_CD) {
            throw new IllegalArgumentException("catCd (TRANCAT-CD PIC 9(04)) must be between "
                    + MIN_CAT_CD + " and " + MAX_CAT_CD + " inclusive but was " + value);
        }
        return value;
    }

    /**
     * Returns the leading key component, {@code TRANCAT-ACCT-ID PIC 9(11)}.
     *
     * @return the account identifier, never {@code null} on an instance built through
     *         {@link #TransactionCategoryBalanceId(Long, String, Integer)}; {@code null} only on an
     *         instance the persistence provider has not finished populating
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Returns the second key component, {@code TRANCAT-TYPE-CD PIC X(02)}.
     *
     * @return the two character transaction type code exactly as stored, never trimmed or case folded and
     *         never {@code null} on an instance built through
     *         {@link #TransactionCategoryBalanceId(Long, String, Integer)}; {@code null} only on an
     *         instance the persistence provider has not finished populating
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Returns the trailing key component, {@code TRANCAT-CD PIC 9(04)}.
     *
     * @return the transaction category code, never {@code null} on an instance built through
     *         {@link #TransactionCategoryBalanceId(Long, String, Integer)}; {@code null} only on an
     *         instance the persistence provider has not finished populating
     */
    public Integer getCatCd() {
        return catCd;
    }

    /**
     * Compares two identifiers by value across all three components.
     *
     * @param obj the object to compare against, possibly {@code null}
     * @return {@code true} if {@code obj} is an identifier of exactly this class with all three components
     * equal
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
     * @return the value based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId, typeCd, catCd);
    }

    /**
     * Returns a diagnostic rendering of the three key components.
     *
     * @return a rendering of the form
     * {@code TransactionCategoryBalanceId[accountId=..., typeCd=..., catCd=...]}
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalanceId[accountId=" + accountId
                + ", typeCd=" + typeCd
                + ", catCd=" + catCd
                + "]";
    }
}
