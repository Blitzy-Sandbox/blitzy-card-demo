package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;

import com.cardemo.model.key.TransactionCategoryId;

/**
 * JPA entity mapping the legacy AWS CardDemo transaction-category reference record
 * onto the PostgreSQL {@code transaction_categories} table.
 *
 * <p>This entity is the Java&nbsp;25 / Spring Data JPA replacement for the VSAM
 * KSDS dataset {@code TRANCATG}, whose fixed 60-byte record layout is defined by
 * the COBOL copybook {@code app/cpy/CVTRA04Y.cpy} ({@code 01 TRAN-CAT-RECORD},
 * record length&nbsp;60). {@code TRANCATG} is small, static
 * <em>reference&nbsp;/&nbsp;lookup</em> data: it maps a
 * {@code (transaction-type code, transaction-category code)} pair to a
 * human-readable category description. On the mainframe it was consulted by the
 * batch programs {@code CBTRN02C} (daily transaction posting) and
 * {@code CBTRN03C} (transaction-detail report) to resolve a stored type/category
 * combination into its description.</p>
 *
 * <p>The legacy access path was a single keyed (KSDS) read on the contiguous
 * byte string formed by the two {@code TRAN-CAT-KEY} sub-fields, so this entity
 * carries a <strong>two-part composite primary key</strong> ({@link #id}); the
 * downstream repository is therefore
 * {@code JpaRepository<TransactionCategory, TransactionCategoryId>}. This is the
 * reason it differs from its sibling {@code TransactionType}: {@code TransactionType}
 * (dataset {@code TRANTYPE}, copybook {@code CVTRA03Y.cpy}) keys on the type code
 * alone and uses a plain {@code @Id}, whereas {@code TransactionCategory} keys on
 * the type code <em>plus</em> a four-digit category code and therefore uses an
 * {@code @EmbeddedId} composite key ({@link TransactionCategoryId}). It is in turn
 * narrower than {@code TransactionCategoryBalance} (dataset {@code TCATBAL}), whose
 * key prepends an additional account-id component.</p>
 *
 * <h2>Original COBOL layout (CVTRA04Y.cpy &mdash; RECLN 60)</h2>
 * <pre>{@code
 * 01  TRAN-CAT-RECORD.
 *     05  TRAN-CAT-KEY.
 *         10  TRAN-TYPE-CD         PIC X(02).
 *         10  TRAN-CAT-CD          PIC 9(04).
 *     05  TRAN-CAT-TYPE-DESC       PIC X(50).
 *     05  FILLER                   PIC X(04).
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA composite key.</strong> The
 *       physically keyed {@code TRANCATG} cluster was addressed by the contiguous
 *       byte string formed from the two {@code TRAN-CAT-KEY} sub-fields
 *       ({@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}). That concatenated physical
 *       key is replaced by an explicit, typed JPA composite key: the
 *       {@code @EmbeddedId} {@link #id} of type {@link TransactionCategoryId}. The
 *       two key components are carried <strong>solely</strong> by that embedded id
 *       and are deliberately <strong>not</strong> redeclared as separate columns on
 *       this entity, so there is no duplication of the key fields. Keyed reads
 *       served by batch file control on the mainframe are replaced by a Spring
 *       Data {@code TransactionCategoryRepository}
 *       ({@code JpaRepository<TransactionCategory, TransactionCategoryId>}).</li>
 *   <li><strong>Type/category codes &rarr; embedded id components.</strong> Within
 *       {@link TransactionCategoryId}, {@code TRAN-TYPE-CD PIC X(02)} maps to a
 *       2-character {@link String} ({@code type_code VARCHAR(2)}) so the exact
 *       two-byte width and any leading characters are preserved, and
 *       {@code TRAN-CAT-CD PIC 9(04)} maps to an {@link Integer}
 *       ({@code category_code INTEGER}); a 4-digit value (max 9999) carries no
 *       decimal positions, so no {@code BigDecimal} is involved.</li>
 *   <li><strong>Description &rarr; {@link String}.</strong>
 *       {@code TRAN-CAT-TYPE-DESC PIC X(50)} is a 50-character descriptive text,
 *       mapped to {@link String} (PostgreSQL {@code VARCHAR(50)}) with its length
 *       preserved exactly. It is the entity's only non-key field.</li>
 *   <li><strong>No decimal fields.</strong> Neither the key components nor the
 *       description originate from a COBOL {@code PIC} clause with decimal
 *       positions, so this entity intentionally contains <strong>no</strong>
 *       {@code float}, {@code double} or {@code BigDecimal} (AAP §0.7.3).</li>
 *   <li><strong>No optimistic locking.</strong> Per AAP §0.7.5, JPA
 *       {@code @Version} is applied <strong>only</strong> to {@code Account} and
 *       {@code Card} (the read-update programs {@code COACTUPC} and
 *       {@code COCRDUPC}). {@code TRANCATG} is static reference data that is never
 *       updated through a read-update snapshot comparison, so this entity carries
 *       <strong>no</strong> {@code @Version} column.</li>
 *   <li><strong>No associations / no Bean Validation.</strong> Per the Minimal
 *       Change Clause this is a pure persistence type: it declares exactly the
 *       embedded key and the single description field, models no JPA associations
 *       (the type/category linkage is expressed only through the key components),
 *       and carries no Jakarta Bean Validation annotations (input validation lives
 *       in the request-DTO layer, AAP §0.4.2).</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(04)} is reserved padding that pads the record to its
 *       60-byte length ({@code 2 + 4 + 50 + 4 = 60}); it carries no business data
 *       and is intentionally <strong>not</strong> mapped to a column. The 60-byte
 *       record length is documented here for external-contract reference only
 *       (AAP §0.7.2).</li>
 * </ul>
 *
 * <h2>Composite-key &amp; column-name contract</h2>
 * <p>The composite primary key is supplied verbatim by
 * {@link TransactionCategoryId}, which declares the authoritative physical column
 * names {@code type_code} ({@code VARCHAR(2)}) and {@code category_code}
 * ({@code INTEGER}). This entity adds the single non-key column
 * {@code category_description} ({@code VARCHAR(50)}). These names and types match
 * the authoritative Flyway {@code V1__create_schema.sql} {@code transaction_category}
 * table, which declares the two-part composite primary key plus the
 * {@code category_description} column exactly as named here, and the {@code V3} seed
 * (loaded from {@code app/data/ASCII/trancatg.txt}, 60-byte records) must align
 * with these names, types and lengths. The two-character {@code type_code} width
 * is shared across the schema: it matches {@code transaction_types.type_code} and
 * the leading type-code component of the {@code transaction_category_balances}
 * key.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material and
 * is never copied into this repository.</p>
 *
 * @see TransactionCategoryId
 * @see TransactionType
 */
@Entity
@Table(name = "transaction_category")
public class TransactionCategory {

    /**
     * Composite primary key &mdash; the two-part
     * {@code (transaction-type code, transaction-category code)} identifier.
     *
     * <p>Migrated from the COBOL {@code TRAN-CAT-KEY} group
     * ({@code TRAN-TYPE-CD PIC X(02)} + {@code TRAN-CAT-CD PIC 9(04)}). The two
     * components are carried by the embedded {@link TransactionCategoryId} and
     * mapped to the {@code type_code} and {@code category_code} columns; they are
     * deliberately not redeclared here. This is the entity's sole identifier, so
     * the downstream repository is
     * {@code JpaRepository<TransactionCategory, TransactionCategoryId>}.</p>
     */
    // TRAN-CAT-KEY group (type X(02) + cat 9(04)) -> composite key @EmbeddedId
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Human-readable description of the transaction category.
     *
     * <p>Migrated from {@code TRAN-CAT-TYPE-DESC PIC X(50)}: a 50-character
     * descriptive text mapped to {@link String} (PostgreSQL {@code VARCHAR(50)})
     * with its length preserved exactly. It is this entity's only non-key
     * field.</p>
     */
    // TRAN-CAT-TYPE-DESC PIC X(50) -> 50-char description -> String(50) (VARCHAR(50))
    @Column(name = "category_description", length = 50)
    private String tranCatTypeDesc;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate) to
     * instantiate the entity reflectively before populating its fields.
     */
    public TransactionCategory() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Convenience constructor that fully populates the entity, useful for tests
     * and for seeding reference data programmatically.
     *
     * @param id              the composite key ({@code TRAN-CAT-KEY})
     * @param tranCatTypeDesc the category description ({@code TRAN-CAT-TYPE-DESC},
     *                        up to 50 characters)
     */
    public TransactionCategory(TransactionCategoryId id, String tranCatTypeDesc) {
        this.id = id;
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Returns the composite primary key ({@code TRAN-CAT-KEY}).
     *
     * @return the composite key, or {@code null} if unset
     */
    public TransactionCategoryId getId() {
        return id;
    }

    /**
     * Sets the composite primary key ({@code TRAN-CAT-KEY}).
     *
     * @param id the composite key to set
     */
    public void setId(TransactionCategoryId id) {
        this.id = id;
    }

    /**
     * Returns the category description ({@code TRAN-CAT-TYPE-DESC}).
     *
     * @return the description, or {@code null} if unset
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * Sets the category description ({@code TRAN-CAT-TYPE-DESC}).
     *
     * @param tranCatTypeDesc the description to set (up to 50 characters)
     */
    public void setTranCatTypeDesc(String tranCatTypeDesc) {
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Identity-based equality keyed on the composite primary key ({@link #id}).
     *
     * <p>Two {@code TransactionCategory} instances are equal when they are of the
     * exact same class and share the same {@link #id}. The primary key alone
     * defines entity identity; the mutable description is deliberately excluded so
     * equality stays stable as the description is updated. Exact-class comparison
     * (rather than {@code instanceof}) is used so a proxy/subclass is not treated
     * as equal to a different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code TransactionCategory} with an
     *         equal composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategory that = (TransactionCategory) o;
        return Objects.equals(id, that.id);
    }

    /**
     * Hash code derived solely from the composite key ({@link #id}), consistent
     * with {@link #equals(Object)}.
     *
     * @return the hash code of the composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * Diagnostic representation including the composite key and the description.
     * Neither component is sensitive, so both are emitted verbatim.
     *
     * @return a human-readable description of this transaction category
     */
    @Override
    public String toString() {
        return "TransactionCategory{"
                + "id=" + id
                + ", tranCatTypeDesc='" + tranCatTypeDesc + '\''
                + '}';
    }
}
