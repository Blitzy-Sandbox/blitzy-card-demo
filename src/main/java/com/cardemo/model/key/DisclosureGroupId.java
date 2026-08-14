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
 * Composite primary key of the {@code DISCGRP} disclosure group (interest rate) reference file, reproducing the
 * COBOL group {@code DIS-ACCT-GROUP-ID} plus {@code DIS-TRAN-TYPE-CD} plus {@code DIS-TRAN-CAT-CD} of
 * {@code app/cpy/CVTRA02Y.cpy} field for field and in source order.
 *
 * <p>The three components sum to {@code 10 + 2 + 4 = 16} bytes, which is the catalogued key length of the
 * cluster. Component order is load-bearing: the interest job's fallback lookup substitutes only the leading
 * group identifier and re-reads with the same trailing pair, which works only while that pair keeps its
 * position.
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
 *   </ol>
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
 * <p>{@code V1__create_schema.sql} declares the {@code disclosure_group} table with its
 * three-column composite primary key in COBOL field order, summing to the catalogued key length of 16,
 * and the sibling entity {@code com.cardemo.model.entity.DisclosureGroup} maps this class through
 * {@code @EmbeddedId}. That agreement is asserted mechanically by {@code SchemaStructureTest} and
 * {@code CompositeKeyContractTest} rather than by inspection, and no SQL type beyond the contract above
 * has been invented here.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li><em>Schema validation rejects {@code tran_cat_cd}.</em> On Hibernate ORM 6.6.42.Final and
 *       PostgreSQL 16.10, an {@code Integer} component validates against an
 *       {@code INTEGER} column, and against {@code BIGINT}, but fails against both {@code NUMERIC(4)} and
 *       {@code SMALLINT}. That {@code SMALLINT} also fails is worth stating plainly, because it is the
 *       intuitive narrow choice for a four-digit code and it does not work. Declare
 *       {@code tran_cat_cd} as {@code INTEGER} in {@code V1__create_schema.sql}. Anyone who deviates must
 *       change this component in step and say so, so that entity, key and migration stay paired.</li>
 *   <li><em>The DEFAULT group lookup finds nothing.</em> Check whether the group id was supplied
 *       unpadded against a {@code VARCHAR} column; against {@code CHAR(10)} it matches either way, as measured
 *       above. The legacy behaviour when the default row is genuinely missing is an abend, per
 *       {@code app/cbl/CBACT04C.cbl:L443-L460}.</li>
 *   <li><em>An entity appears not to be found although the row exists.</em> Confirm all three components
 *       are populated: a partially populated key is a distinct value and never compares equal to a complete
 *       one, by design.</li>
 *   <li><em>{@code IllegalArgumentException} from the all-components constructor.</em> The message names
 *       the offending component and the limit it broke; the input violated the source field width or the
 *       unsigned four-digit domain of {@code PIC 9(04)}.</li>
 *   </ul>
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
 * <p>{@code ./mvnw -B clean compile} compiles this class under {@code --release 25} with {@code -Xlint:all}
 * and {@code -Werror}, so any warning is a build failure. {@code ./mvnw -B clean test} runs the unit suite, and
 * {@code ./mvnw -B clean verify} additionally enforces the JaCoCo line-coverage floor. Verified on
 * OpenJDK 25.0.3 with Maven 3.9.11: all three goals succeed and the compiler reports no warning against this
 * source.</p>
 * <p><strong>Package-level documentation.</strong> {@code com.cardemo.model.key} carries its own
 * {@code package-info.java}, which holds the package-wide banner, the composite-key inventory, the build and
 * test instructions and the shared failure modes. This class documentation stays self-contained on the
 * points specific to the disclosure-group key rather than duplicating that file.</p>
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
     * Serialization version identifier. Declared explicitly because this type implements {@link Serializable}
     * for JPA identifier purposes.
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
     *
     * <p>This width is an exact requirement rather than a ceiling, which is why the component is checked with
     * {@link #requireExactWidth(String, String, String, int)} and the account group id is not. The component
     * occupies bytes 11 and 12 of the sixteen-byte {@code DIS-GROUP-KEY}, so a one-character value does not
     * merely under-fill its own field - it moves {@code DIS-TRAN-CAT-CD} to the wrong offset and therefore
     * denotes a different row. Both sibling identifiers in this package take the same position on the same
     * picture clause ({@code TransactionCategoryId.requireValidTranTypeCd} and
     * {@code TransactionCategoryBalanceId.requireValidTypeCd}), and every one of the fifty-one rows in
     * {@code app/data/ASCII/discgrp.txt} carries exactly two characters there.</p>
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
     * see the padding discussion on the class documentation. The {@code bpchar} column definition is what makes
     * this mapping validate against a {@code CHAR(10)} column; the measured evidence for that choice is also on
     * the class documentation.
     */
    @Column(name = "acct_group_id", nullable = false, length = ACCOUNT_GROUP_ID_LENGTH,
            columnDefinition = "bpchar(10)")
    private String accountGroupId;

    /**
     * {@code DIS-TRAN-TYPE-CD}, {@code PIC X(02)}, bytes 11 to 12 of the sixteen-byte key
     * ({@code app/cpy/CVTRA02Y.cpy:L7}). Stored verbatim as fixed-width {@code bpchar} so the blank padding
     * survives the round trip.
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
     */
    protected DisclosureGroupId() {
        // Intentionally empty: Jakarta Persistence populates the fields directly after instantiation. No
        // component is defaulted, because a synthetic default would be indistinguishable from a key actually
        // read from the database. This is a required Jakarta Persistence hook, not an unfinished
        // implementation.
    }

    /**
     * Creates an identifier from the three components of {@code DIS-GROUP-KEY}, in the declaration order of
     * {@code app/cpy/CVTRA02Y.cpy:L6-L8}.
     *
     * @param accountGroupId {@code DIS-ACCT-GROUP-ID}, {@code PIC X(10)}.
     * @param tranTypeCd {@code DIS-TRAN-TYPE-CD}, {@code PIC X(02)}.
     * @param tranCatCd {@code DIS-TRAN-CAT-CD}, {@code PIC 9(04)}.
     * @throws IllegalArgumentException if any component is {@code null}, if either character component is
     * longer than its source field width, or if {@code tranCatCd} falls outside the unsigned four-digit domain.
     */
    public DisclosureGroupId(final String accountGroupId, final String tranTypeCd, final Integer tranCatCd) {
        this.accountGroupId = requireWidth(
                "accountGroupId", "DIS-ACCT-GROUP-ID", accountGroupId, ACCOUNT_GROUP_ID_LENGTH);
        this.tranTypeCd = requireExactWidth(
                "tranTypeCd", "DIS-TRAN-TYPE-CD", tranTypeCd, TRAN_TYPE_CD_LENGTH);
        this.tranCatCd = requireCategoryCode(tranCatCd);
    }

    /**
     * Returns {@code DIS-ACCT-GROUP-ID}, the first component of the key.
     *
     * @return the ten-character account group id, or {@code null} on an instance that was created by the
     * no-argument constructor and has not been populated
     */
    public String getAccountGroupId() {
        return accountGroupId;
    }

    /**
     * Returns {@code DIS-TRAN-TYPE-CD}, the second component of the key.
     *
     * @return the two-character transaction type code, or {@code null} on an instance that was created by the
     * no-argument constructor and has not been populated
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Returns {@code DIS-TRAN-CAT-CD}, the third component of the key.
     *
     * @return the transaction category code within 0 to 9999, or {@code null} on an instance that was created
     * by the no-argument constructor and has not been populated
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Compares this identifier with another object for value equality over all three key components.
     *
     * @param obj the object to compare with, possibly {@code null} or of a foreign type
     * @return {@code true} if {@code obj} is a {@code DisclosureGroupId} whose three components all equal this
     * identifier's, {@code false} otherwise
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
     * @return a hash code over the account group id, the transaction type code and the transaction category
     * code
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountGroupId, tranTypeCd, tranCatCd);
    }

    /**
     * Returns a diagnostic rendering of the three key components in COBOL key order.
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
     * <p>The width is a count of Unicode code points rather than of {@code char} values, and the unit is
     * load-bearing. A {@code PIC X(n)} clause declares n character positions and the {@code CHAR(n)} column
     * it maps to pads to n characters, while a Java {@code String} measures itself in UTF-16 code units; the
     * two disagree for any supplementary-plane character. Counting code units let a value that had been
     * accepted, stored and padded fail when it was read back and offered here again. A code point count never
     * exceeds a code unit count, so this is the same bound the write path applies rather than a looser one.
     * Held as {@code DL-MS-05} in {@code DECISION_LOG.md}.
     *
     * @param javaName the Java component name, reported in the failure message
     * @param cobolName the COBOL field name, reported in the failure message so a failure is traceable straight
     * back to {@code app/cpy/CVTRA02Y.cpy}
     * @param value the candidate value, which may be {@code null}
     * @param maxLength the source field width in characters
     * @return {@code value} unchanged when it satisfies the contract
     * @throws IllegalArgumentException if {@code value} is {@code null} or longer than {@code maxLength}
     */
    private static String requireWidth(final String javaName, final String cobolName, final String value,
            final int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException(javaName + " (" + cobolName + ") must not be null");
        }
        final int characterPositions = value.codePointCount(0, value.length());
        if (characterPositions > maxLength) {
            throw new IllegalArgumentException(javaName + " (" + cobolName + ") must not exceed " + maxLength
                    + " characters but was " + characterPositions + ": '" + value + "'");
        }
        return value;
    }

    /**
     * Validates a fixed-width key component that must occupy its source field completely.
     *
     * <p>This is the stricter sibling of {@link #requireWidth(String, String, String, int)} and exists because
     * the two rules are genuinely different, not because one is a tidier spelling of the other. A ceiling is
     * right for {@code DIS-ACCT-GROUP-ID}, whose value the interest calculation supplies as the bare literal
     * {@code DEFAULT} rather than as ten padded bytes. An exact width is right for {@code DIS-TRAN-TYPE-CD},
     * for the offset reason recorded on {@link #TRAN_TYPE_CD_LENGTH}.</p>
     *
     * <p>Nothing is padded to reach the width and nothing is trimmed to fit it. Padding here would accept a
     * caller's mistake and silently turn it into a different key, which is the outcome the check exists to
     * prevent; the value is returned exactly as supplied or not at all. Declared {@code private static} for
     * the same reason as {@link #requireWidth(String, String, String, int)}: it is called from a constructor,
     * and an overridable method called from a constructor publishes {@code this} before construction has
     * finished, which {@code -Xlint:all -Werror} reports as {@code this-escape} and fails the build over.</p>
     *
     * <p>The width is a count of Unicode code points rather than of {@code char} values, and the unit is
     * load-bearing. A {@code PIC X(n)} clause declares n character positions and the {@code CHAR(n)} column
     * it maps to pads to n characters, while a Java {@code String} measures itself in UTF-16 code units; the
     * two disagree for any supplementary-plane character. Counting code units let a value that had been
     * accepted, stored and padded fail when it was read back and offered here again. A code point count never
     * exceeds a code unit count, so this is the same bound the write path applies rather than a looser one.
     * Held as {@code DL-MS-05} in {@code DECISION_LOG.md}.
     *
     * @param javaName  the Java property name, named in the failure message so a caller can find the argument
     * @param cobolName the source field name, named so a reader can find the picture clause
     * @param value     the candidate value exactly as supplied by the caller, which may be {@code null}
     * @param length    the exact required width, taken from the picture clause
     * @return {@code value} unchanged when it is exactly {@code length} characters long
     * @throws IllegalArgumentException if {@code value} is {@code null} or is any length other than
     *                                  {@code length}. The message reports both the expected and the received
     *                                  length; quoting the value is safe here because a transaction type code
     *                                  is a two-character classification and carries nothing sensitive
     */
    private static String requireExactWidth(final String javaName, final String cobolName, final String value,
            final int length) {
        if (value == null) {
            throw new IllegalArgumentException(javaName + " (" + cobolName + ") must not be null");
        }
        final int characterPositions = value.codePointCount(0, value.length());
        if (characterPositions != length) {
            throw new IllegalArgumentException(javaName + " (" + cobolName + ") must be exactly " + length
                    + " characters but was " + characterPositions + ": '" + value + "'");
        }
        return value;
    }

    /**
     * Validates {@code DIS-TRAN-CAT-CD} against the unsigned four-digit display domain of {@code PIC 9(04)}.
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
