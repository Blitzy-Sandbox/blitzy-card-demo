/*
 * ******************************************************************
 * Program     : DisclosureGroupId.java
 * Application : CardDemo
 * Type        : Java 25 / JPA composite identifier (immutable value type)
 * Function    : Composite primary key of the disclosure group (interest
 *               rate) reference file: account group id, transaction type
 *               code and transaction category code, in COBOL key order.
 * Source      : app/cpy/CVTRA02Y.cpy (key 16) @ 7756d89
 *               app/catlg/LISTCAT.txt:L896 (KEYLEN 16, AVGLRECL 50)
 *               app/cbl/CBACT04C.cbl:L107 (COPY CVTRA02Y)
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
 * Composite primary key of the {@code DISCGRP} disclosure group (interest rate) reference file.
 *
 * <h2>What it does</h2>
 * <p>This is a pure, immutable value type. It carries the three components of the COBOL group
 * {@code DIS-GROUP-KEY} declared in {@code app/cpy/CVTRA02Y.cpy}, in the copybook's declaration order, and
 * nothing else. It is consumed through {@code jakarta.persistence.EmbeddedId} by
 * {@code com.cardemo.model.entity.DisclosureGroup}, which replaces the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS} catalogued at {@code app/catlg/LISTCAT.txt:L859}.</p>
 *
 * <h2>Source field contract, reproduced exactly</h2>
 * <p>From {@code app/cpy/CVTRA02Y.cpy:L4-L10} (record length 50):</p>
 * <pre>
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *        10 DIS-ACCT-GROUP-ID   PIC X(10).   -- accountGroupId, column acct_group_id
 *        10 DIS-TRAN-TYPE-CD    PIC X(02).   -- tranTypeCd,     column tran_type_cd
 *        10 DIS-TRAN-CAT-CD     PIC 9(04).   -- tranCatCd,      column tran_cat_cd
 *     05  DIS-INT-RATE          PIC S9(04)V99.  -- NOT part of the key: entity payload
 *     05  FILLER                PIC X(28).      -- NOT part of the key: record slack
 * </pre>
 * <p>The three key components sum to 10 + 2 + 4 = <strong>16</strong> bytes. That key length is corroborated
 * five independent ways, which is why it is safe to treat as a hard contract:</p>
 * <ol>
 *   <li>copybook arithmetic above, {@code app/cpy/CVTRA02Y.cpy:L6-L8};</li>
 *   <li>the VSAM catalogue, {@code app/catlg/LISTCAT.txt:L896} reading
 *       {@code KEYLEN----------------16     AVGLRECL--------------50};</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L897} reading {@code RKP--------------------0}, so the key is the
 *       record prefix, and {@code L898} declaring the cluster {@code UNIQUE} and {@code INDEXED};</li>
 *   <li>the consuming program's own file record area, {@code app/cbl/CBACT04C.cbl:L77-L82}, where
 *       {@code FD-DISCGRP-KEY} re-declares the same three fields in the same order followed by
 *       {@code FD-DISCGRP-DATA PIC X(34)}, giving 16 + 34 = 50;</li>
 *   <li>the seed fixture {@code app/data/ASCII/discgrp.txt:L1}, whose first record begins
 *       {@code A000000000} + {@code 01} + {@code 0001} - a 10/2/4 split - followed by the six-byte rate field
 *       and 28 filler bytes, 50 bytes in total across all 51 rows.</li>
 * </ol>
 * <p>Citing {@code L896} precisely matters: {@code app/catlg/LISTCAT.txt:L202} (CARDDATA, {@code AVGLRECL 150})
 * and {@code L403} (CARDXREF, {@code AVGLRECL 50}) also report {@code KEYLEN 16} for entirely different
 * clusters, so a vaguer citation would point at the wrong dataset.</p>
 *
 * <h2>Why the component order is load-bearing</h2>
 * <p>A VSAM browse proceeds in key order, and the composite key's byte layout <em>is</em> that order. The
 * declaration order {@code accountGroupId}, {@code tranTypeCd}, {@code tranCatCd} is therefore behaviour, not
 * cosmetics, and it must never be alphabetised or otherwise tidied.</p>
 * <p>There is a trap in the source. {@code app/cbl/CBACT04C.cbl:L210-L212} populates the lookup key with
 * {@code MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID}, then {@code MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD},
 * then {@code MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD} - that is group id, then <em>category</em>, then
 * <em>type</em>. The order in which fields are assigned has no bearing on the key layout; the copybook, and
 * the file record area at {@code L77-L82} that agrees with it, are authoritative. Component order here
 * deliberately follows the copybook and not those MOVE statements.</p>
 * <p>{@code DIS-TRAN-CAT-CD} is stored as fixed-width, zero-padded, unsigned display digits (for example
 * {@code 0001}), so lexicographic byte order and numeric order coincide over the value domain. Mapping it to
 * {@code Integer} therefore preserves VSAM browse ordering, and rendering it back to four zero-padded digits
 * is the responsibility of whichever layer emits fixed-width records, not of this identifier.</p>
 *
 * <h2>Consumer, and the DEFAULT group fallback</h2>
 * <p>{@code app/cbl/CBACT04C.cbl:L107} copies this layout with {@code COPY CVTRA02Y.}. Paragraph
 * {@code 1200-GET-INTEREST-RATE} at {@code app/cbl/CBACT04C.cbl:L415-L440} reads the file and accepts
 * <em>either</em> file status {@code '00'} <em>or</em> {@code '23'} (record not found); on {@code '23'} it
 * executes {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} at {@code L437} and retries through
 * {@code 1200-A-GET-DEFAULT-INT-RATE} at {@code L443-L460}, which accepts only {@code '00'} and abends the
 * job otherwise. Only the group id is replaced; the type and category codes are carried over unchanged. In
 * the Java target that fallback is a second query, never an exception.</p>
 * <p><strong>The padding question, answered explicitly.</strong> A COBOL {@code MOVE} of the seven-character
 * literal {@code 'DEFAULT'} into {@code PIC X(10)} left-justifies and space-pads, so the key actually
 * presented by {@code L437} is {@code 'D','E','F','A','U','L','T',' ',' ',' '} - byte-identical to the fixture
 * row at {@code app/data/ASCII/discgrp.txt:L18}, which reads {@code DEFAULT} followed by three blanks, then
 * {@code 01}, then {@code 0001}, then the rate field. Seventeen of the fifty-one fixture rows carry that
 * padded group id, including zero-rate combinations.</p>
 * <p>This class does <strong>not</strong> trim, pad, case-fold or otherwise normalise any component. Two
 * reasons: an identifier must remain a faithful carrier of the sixteen key bytes, so two distinct byte images
 * must never compare equal; and blank-padded comparison is a property of the storage layer, not of a Java
 * value type. Trailing-blank equivalence is consequently delegated to SQL {@code CHAR} semantics, which
 * ignore trailing blanks on comparison, and that is exactly what lets the fallback lookup match whether the
 * literal is supplied padded or unpadded. Callers that build the fallback key in Java should pass the value
 * as the source presents it.</p>
 *
 * <h2>Key configuration and defaults</h2>
 * <p>Every profile sets {@code spring.jpa.hibernate.ddl-auto: validate}, so a column name or type mismatch
 * fails application-context startup rather than surfacing later as a runtime warning. The contract this class
 * is written against, derived from {@code app/cpy/CVTRA02Y.cpy} plus {@code app/catlg/LISTCAT.txt:L896}, is:</p>
 * <pre>
 * table disclosure_group
 *   acct_group_id  &lt;- DIS-ACCT-GROUP-ID  X(10)  CHAR(10)  NOT NULL   (part of PK)
 *   tran_type_cd   &lt;- DIS-TRAN-TYPE-CD   X(02)  CHAR(2)   NOT NULL   (part of PK)
 *   tran_cat_cd    &lt;- DIS-TRAN-CAT-CD    9(04)  INTEGER   NOT NULL   (part of PK)
 *   PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)   -- in this exact order
 * </pre>
 * <p>Every column name is stated explicitly on the field so that no Hibernate naming strategy can silently
 * rename one, and the two character components additionally declare {@code columnDefinition = "bpchar(n)"}.
 * That spelling is deliberate and was chosen by measurement rather than by convention. Hibernate's schema
 * validator accepts a column when the mapped SQL type name is a prefix of the type name the database reports,
 * or when the two JDBC type codes are equivalent. PostgreSQL reports {@code CHAR(n)} as {@code bpchar} with
 * JDBC type code {@code CHAR}, whereas a plain {@code String} maps to {@code VARCHAR}, so neither branch
 * matches and startup fails. These combinations were measured directly against Hibernate ORM 6.6.42.Final and
 * PostgreSQL 16.10 with {@code hibernate.hbm2ddl.auto=validate}:</p>
 * <pre>
 * mapping of the character components  column type   outcome
 * plain String                         CHAR(n)       FAIL  expected varchar(10) VARCHAR, found bpchar CHAR
 * plain String                         VARCHAR(n)    pass
 * columnDefinition = "char(n)"         CHAR(n)       FAIL  "char(10)" is not a prefix of "bpchar"
 * columnDefinition = "bpchar(n)"       CHAR(n)       pass  type-name prefix branch matches
 * columnDefinition = "bpchar(n)"       VARCHAR(n)    pass  JDBC type-code branch matches
 * columnDefinition = "bpchar(n)"       TEXT          pass  JDBC type-code branch matches
 * </pre>
 * <p>{@code bpchar(n)} is therefore the only spelling that validates against the normative {@code CHAR(n)}
 * contract, and it is simultaneously the most forgiving: it is the single choice that survives {@code CHAR},
 * {@code VARCHAR} and {@code TEXT} alike, so a migration written to any of the three still starts. Pinning the
 * JDBC type code instead would require {@code org.hibernate.annotations}, which is outside the import set
 * permitted for this class. The {@code length} attributes are retained alongside {@code columnDefinition},
 * which takes precedence for the SQL type, because they record the source field widths of {@code PIC X(10)}
 * and {@code PIC X(02)} in the mapping metadata itself.</p>
 * <p>Keeping {@code CHAR(n)} in the migration is not cosmetic either. Measured on the same PostgreSQL 16.10
 * instance, comparing a {@code char(10)} column holding the padded DEFAULT group against the unpadded literal
 * {@code 'DEFAULT'} yields true, while the same comparison against a {@code varchar(10)} column yields false.
 * Switching those columns to {@code VARCHAR} would therefore break the DEFAULT group fallback for any caller
 * that supplies the literal unpadded, and such a caller would then have to pad to ten characters exactly as
 * {@code app/cbl/CBACT04C.cbl:L437} does. Record that consequence rather than making the change silently.</p>
 * <p><strong>Not available.</strong> At the time of writing, {@code src/main/resources/db/migration/} does not
 * exist, so {@code V1__create_schema.sql} is not available, and neither is the sibling entity
 * {@code com.cardemo.model.entity.DisclosureGroup}. Closing that gap needs exactly those two artefacts. Until
 * they exist the contract above is normative and no SQL type beyond it has been invented here.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><em>Blocker, if the migration disagrees - schema validation rejects {@code tran_cat_cd}.</em> Measured
 *       on Hibernate ORM 6.6.42.Final and PostgreSQL 16.10, an {@code Integer} component validates against an
 *       {@code INTEGER} column, and against {@code BIGINT}, but fails against both {@code NUMERIC(4)} and
 *       {@code SMALLINT}. That {@code SMALLINT} also fails is worth stating plainly, because it is the
 *       intuitive narrow choice for a four-digit code and it does not work. Remediation: declare
 *       {@code tran_cat_cd} as {@code INTEGER} in {@code V1__create_schema.sql}. Anyone who deviates must
 *       change this component in step and say so, so that entity, key and migration stay paired.</li>
 *   <li><em>Medium - the DEFAULT group lookup finds nothing.</em> Check whether the group id was supplied
 *       unpadded against a {@code VARCHAR} column; against {@code CHAR(10)} it matches either way, as measured
 *       above. The legacy behaviour when the default row is genuinely missing is an abend, per
 *       {@code app/cbl/CBACT04C.cbl:L443-L460}.</li>
 *   <li><em>Low - an entity appears not to be found although the row exists.</em> Confirm all three components
 *       are populated: a partially populated key is a distinct value and never compares equal to a complete
 *       one, by design.</li>
 *   <li><em>Low - {@code IllegalArgumentException} from the all-components constructor.</em> The message names
 *       the offending component and the limit it broke; the input violated the source field width or the
 *       unsigned four-digit domain of {@code PIC 9(04)}.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>No component is credential or personally identifiable material: an account group id, a two-character
 * transaction type code and a four-digit transaction category code only. {@link Serializable} is implemented
 * solely so this type can serve as a JPA identifier, and {@code serialVersionUID} is declared explicitly for
 * that purpose. <strong>Java deserialization of untrusted input is prohibited for this type:</strong> it must
 * never be reconstructed from bytes arriving over a network, a cache, a message payload or any other external
 * source, because Java serialization offers no way to validate such input before object graph construction.
 * Persist and transport it as JPA columns or as JSON, never as a serialized object stream.</p>
 *
 * <h2>How to build and test</h2>
 * <p>{@code mvn -B clean compile} compiles this class under {@code --release 25} with {@code -Xlint:all}
 * and {@code -Werror}, so any warning is a build failure. {@code mvn -B clean test} runs the unit suite, and
 * {@code mvn -B clean verify} additionally enforces the JaCoCo line-coverage floor. Verified on
 * OpenJDK 25.0.3 with Maven 3.9.11: all three goals succeed and the compiler reports no warning against this
 * source.</p>
 * <p><strong>Not available.</strong> Package-level documentation for {@code com.cardemo.model.key} belongs in
 * a sibling {@code package-info.java}, which does not exist yet; this class documentation is deliberately
 * self-contained in the meantime, and no claim is made here about content that is not present.</p>
 *
 * <h2>Design constraints deliberately honoured</h2>
 * <p>No base class is extended and none is introduced. The two sibling identifiers in this package describe
 * genuinely different contracts that merely look structurally similar - the COBOL name {@code TRAN-CAT-KEY} is
 * even declared twice in the corpus, in {@code app/cpy/CVTRA01Y.cpy} with three fields over 17 bytes and in
 * {@code app/cpy/CVTRA04Y.cpy} with two fields over 6 bytes - so a shared abstraction would create a false
 * equivalence. This class names its own copybook, {@code app/cpy/CVTRA02Y.cpy}, so the distinction is
 * unambiguous. There is no framework dependency beyond Jakarta Persistence, and no code generation.</p>
 *
 * @see java.io.Serializable
 */
@Embeddable
public class DisclosureGroupId implements Serializable {

    /**
     * Serialization version identifier. Declared explicitly because this type implements
     * {@link Serializable} for JPA identifier purposes; see the security note on the class documentation.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Width of {@code DIS-ACCT-GROUP-ID}, {@code PIC X(10)} at {@code app/cpy/CVTRA02Y.cpy:L6}. Compile-time
     * constant, not persistent state: it is the single source of truth shared by the column mapping and the
     * constructor's boundary check.
     */
    private static final int ACCOUNT_GROUP_ID_LENGTH = 10;

    /**
     * Width of {@code DIS-TRAN-TYPE-CD}, {@code PIC X(02)} at {@code app/cpy/CVTRA02Y.cpy:L7}. Compile-time
     * constant, not persistent state.
     */
    private static final int TRAN_TYPE_CD_LENGTH = 2;

    /**
     * Lowest value expressible by {@code DIS-TRAN-CAT-CD}, {@code PIC 9(04)} at
     * {@code app/cpy/CVTRA02Y.cpy:L8}. The picture is unsigned, so the domain starts at zero.
     */
    private static final int TRAN_CAT_CD_MIN_VALUE = 0;

    /**
     * Highest value expressible by {@code DIS-TRAN-CAT-CD}, {@code PIC 9(04)}: four unsigned display digits.
     */
    private static final int TRAN_CAT_CD_MAX_VALUE = 9999;

    /**
     * {@code DIS-ACCT-GROUP-ID}, {@code PIC X(10)}, bytes 1 to 10 of the sixteen-byte key
     * ({@code app/cpy/CVTRA02Y.cpy:L6}). Stored verbatim, including any trailing blanks the source presents -
     * see the padding discussion on the class documentation. The {@code bpchar} column definition is what
     * makes this mapping validate against a {@code CHAR(10)} column; the measured evidence for that choice is
     * also on the class documentation.
     */
    @Column(name = "acct_group_id", nullable = false, length = ACCOUNT_GROUP_ID_LENGTH,
            columnDefinition = "bpchar(10)")
    private String accountGroupId;

    /**
     * {@code DIS-TRAN-TYPE-CD}, {@code PIC X(02)}, bytes 11 to 12 of the sixteen-byte key
     * ({@code app/cpy/CVTRA02Y.cpy:L7}). Stored verbatim, with the same {@code bpchar} rationale as the
     * account group id above.
     */
    @Column(name = "tran_type_cd", nullable = false, length = TRAN_TYPE_CD_LENGTH,
            columnDefinition = "bpchar(2)")
    private String tranTypeCd;

    /**
     * {@code DIS-TRAN-CAT-CD}, {@code PIC 9(04)}, bytes 13 to 16 of the sixteen-byte key
     * ({@code app/cpy/CVTRA02Y.cpy:L8}). An unsigned four-digit display identifier, not a monetary amount, so
     * it is an {@link Integer} and never a floating-point or decimal type. No {@code columnDefinition} is
     * declared here because the {@code INTEGER} column the migration must use already matches the mapped JDBC
     * type code; see the schema-validation failure mode on the class documentation.
     */
    @Column(name = "tran_cat_cd", nullable = false)
    private Integer tranCatCd;

    /**
     * Creates an empty identifier.
     *
     * <p>Required by Jakarta Persistence, which instantiates an embeddable through its no-argument constructor
     * and then populates the fields directly, bypassing the validating constructor below. All three components
     * are consequently {@code null} immediately after this call, and such an instance is not a usable key
     * until the provider has populated it. Application code should prefer
     * {@link #DisclosureGroupId(String, String, Integer)}.</p>
     */
    public DisclosureGroupId() {
        // Intentionally empty: Jakarta Persistence populates the fields directly after instantiation.
    }

    /**
     * Creates an identifier from the three components of {@code DIS-GROUP-KEY}, in the declaration order of
     * {@code app/cpy/CVTRA02Y.cpy:L6-L8}.
     *
     * <p>Values are stored verbatim: nothing is trimmed, padded, upper-cased or reformatted, so the instance
     * stays a faithful carrier of the sixteen key bytes. The constructor has no side effects and touches no
     * shared state.</p>
     *
     * @param accountGroupId {@code DIS-ACCT-GROUP-ID}, {@code PIC X(10)}; must be non-null and at most 10
     *                       characters, and may legitimately carry trailing blanks, as the DEFAULT group does
     * @param tranTypeCd     {@code DIS-TRAN-TYPE-CD}, {@code PIC X(02)}; must be non-null and at most 2
     *                       characters
     * @param tranCatCd      {@code DIS-TRAN-CAT-CD}, {@code PIC 9(04)}; must be non-null and within 0 to 9999,
     *                       the unsigned four-digit display domain
     * @throws IllegalArgumentException if any component is {@code null}, if either character component is
     *                                  longer than its source field width, or if {@code tranCatCd} falls
     *                                  outside the unsigned four-digit domain. The message names the offending
     *                                  component, both as its Java name and as its COBOL field name, together
     *                                  with the limit that was broken
     */
    public DisclosureGroupId(final String accountGroupId, final String tranTypeCd, final Integer tranCatCd) {
        this.accountGroupId = requireWidth(
                "accountGroupId", "DIS-ACCT-GROUP-ID", accountGroupId, ACCOUNT_GROUP_ID_LENGTH);
        this.tranTypeCd = requireWidth("tranTypeCd", "DIS-TRAN-TYPE-CD", tranTypeCd, TRAN_TYPE_CD_LENGTH);
        this.tranCatCd = requireCategoryCode(tranCatCd);
    }

    /**
     * Returns {@code DIS-ACCT-GROUP-ID}, the first component of the key.
     *
     * <p>The value is returned exactly as stored, trailing blanks included; the DEFAULT group is genuinely
     * {@code "DEFAULT"} followed by three blanks in the source data.</p>
     *
     * @return the ten-character account group id, or {@code null} on an instance that was created by the
     *         no-argument constructor and has not been populated
     */
    public String getAccountGroupId() {
        return accountGroupId;
    }

    /**
     * Returns {@code DIS-TRAN-TYPE-CD}, the second component of the key.
     *
     * @return the two-character transaction type code, or {@code null} on an instance that was created by the
     *         no-argument constructor and has not been populated
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Returns {@code DIS-TRAN-CAT-CD}, the third component of the key.
     *
     * <p>The source stores four zero-padded unsigned display digits. Rendering the value back to that
     * fixed-width form is the responsibility of the layer that emits fixed-width records, not of this
     * identifier.</p>
     *
     * @return the transaction category code within 0 to 9999, or {@code null} on an instance that was created
     *         by the no-argument constructor and has not been populated
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Compares this identifier with another object for value equality over all three key components.
     *
     * <p>Comparison is exact. Nothing is trimmed, padded or case folded, so {@code "DEFAULT"} and
     * {@code "DEFAULT"} followed by three blanks are distinct values here even though SQL {@code CHAR}
     * comparison treats them as the same; blank-padded equivalence deliberately belongs to the storage layer.
     * A partially populated key therefore never compares equal to a fully populated one. The runtime classes
     * must match exactly, which keeps the relation symmetric even though the class cannot be declared
     * {@code final}.</p>
     *
     * @param obj the object to compare with, possibly {@code null} or of a foreign type
     * @return {@code true} if {@code obj} is a {@code DisclosureGroupId} whose three components all equal this
     *         identifier's, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final DisclosureGroupId other = (DisclosureGroupId) obj;
        return Objects.equals(accountGroupId, other.accountGroupId)
                && Objects.equals(tranTypeCd, other.tranTypeCd)
                && Objects.equals(tranCatCd, other.tranCatCd);
    }

    /**
     * Returns a hash code derived from all three key components, consistent with {@link #equals(Object)}.
     *
     * <p>Correct hashing is not optional for a JPA identifier: without it the persistence context's identity
     * map cannot recognise a managed instance, and {@code find}, {@code merge} and dirty checking then
     * misbehave as intermittent data errors rather than as clean failures. Nothing in the application may rely
     * on hash iteration order, because key order - not hash order - is what the legacy VSAM browse semantics
     * depend on.</p>
     *
     * @return a hash code over the account group id, the transaction type code and the transaction category
     *         code
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountGroupId, tranTypeCd, tranCatCd);
    }

    /**
     * Returns a diagnostic rendering of the three key components in COBOL key order.
     *
     * <p>Intended for logs and error messages only. It exposes nothing beyond the key components, which are
     * non-sensitive identifiers; it performs no case-dependent or locale-dependent formatting, so it is stable
     * on every host; and it is not a wire or record format, so callers must never parse it.</p>
     *
     * @return a stable, locale-independent description of this identifier
     */
    @Override
    public String toString() {
        return "DisclosureGroupId[accountGroupId=" + accountGroupId
                + ", tranTypeCd=" + tranTypeCd
                + ", tranCatCd=" + tranCatCd + "]";
    }

    /**
     * Validates one fixed-width character component against the width of its COBOL picture.
     *
     * <p>Declared {@code private static} deliberately: a constructor of a class that cannot be {@code final}
     * must not call an overridable method, and a static helper additionally keeps the check independent of
     * instance state. An over-long value is rejected rather than truncated, because truncation would silently
     * produce a different sixteen-byte key.</p>
     *
     * @param javaName  the Java component name, reported in the failure message
     * @param cobolName the COBOL field name, reported in the failure message so a failure is traceable
     *                  straight back to {@code app/cpy/CVTRA02Y.cpy}
     * @param value     the candidate value, which may be {@code null}
     * @param maxLength the source field width in characters
     * @return {@code value} unchanged when it satisfies the contract
     * @throws IllegalArgumentException if {@code value} is {@code null} or longer than {@code maxLength}
     */
    private static String requireWidth(final String javaName, final String cobolName, final String value,
            final int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException(javaName + " (" + cobolName + ") must not be null");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(javaName + " (" + cobolName + ") must not exceed " + maxLength
                    + " characters but was " + value.length() + ": '" + value + "'");
        }
        return value;
    }

    /**
     * Validates {@code DIS-TRAN-CAT-CD} against the unsigned four-digit display domain of {@code PIC 9(04)}.
     *
     * <p>Declared {@code private static} for the same reasons as {@link #requireWidth(String, String, String,
     * int)}. The picture carries no sign, so negative values are outside the domain and are rejected rather
     * than coerced.</p>
     *
     * @param value the candidate transaction category code, which may be {@code null}
     * @return {@code value} unchanged when it satisfies the contract
     * @throws IllegalArgumentException if {@code value} is {@code null} or falls outside 0 to 9999
     */
    private static Integer requireCategoryCode(final Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("tranCatCd (DIS-TRAN-CAT-CD) must not be null");
        }
        if (value < TRAN_CAT_CD_MIN_VALUE || value > TRAN_CAT_CD_MAX_VALUE) {
            throw new IllegalArgumentException("tranCatCd (DIS-TRAN-CAT-CD) must be within "
                    + TRAN_CAT_CD_MIN_VALUE + " to " + TRAN_CAT_CD_MAX_VALUE + " but was " + value);
        }
        return value;
    }
}
