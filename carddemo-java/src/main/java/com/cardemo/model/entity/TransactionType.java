package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * JPA entity mapping the legacy AWS CardDemo transaction-type reference record
 * onto the PostgreSQL {@code transaction_types} table.
 *
 * <p>This entity is the Java&nbsp;25 / Spring Data JPA replacement for the VSAM
 * KSDS dataset {@code TRANTYPE}, whose fixed 60-byte record layout is defined by
 * the COBOL copybook {@code app/cpy/CVTRA03Y.cpy} ({@code 01 TRAN-TYPE-RECORD},
 * record length&nbsp;60). {@code TRANTYPE} is small, static
 * <em>reference&nbsp;/&nbsp;lookup</em> data: it maps a two-character
 * transaction-type code to a human-readable description. On the mainframe it was
 * consulted by the batch programs {@code CBTRN02C} (daily transaction posting)
 * and {@code CBTRN03C} (transaction-detail report), and by the online
 * transaction services, to resolve a stored type code into its description.</p>
 *
 * <p>The legacy access path was a single keyed (KSDS) read on the two-byte type
 * code, so this entity carries a <strong>plain single-column primary key</strong>
 * ({@link #tranType}); the downstream repository is therefore
 * {@code JpaRepository<TransactionType, String>}. This is the entity's single
 * subtlety and the reason it differs from its sibling {@code TransactionCategory}:
 * {@code TransactionCategory} (dataset {@code TRANCATG}, copybook
 * {@code CVTRA04Y.cpy}) keys on the type code <em>plus</em> a four-digit category
 * code and therefore uses an {@code @EmbeddedId} composite key
 * ({@code TransactionCategoryId}), whereas {@code TransactionType} keys on the
 * type code alone and uses a plain {@code @Id}.</p>
 *
 * <h2>Original COBOL layout (CVTRA03Y.cpy &mdash; RECLN 60)</h2>
 * <pre>{@code
 * 01  TRAN-TYPE-RECORD.
 *     05  TRAN-TYPE                PIC X(02).
 *     05  TRAN-TYPE-DESC           PIC X(50).
 *     05  FILLER                   PIC X(08).
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA.</strong> The physically keyed
 *       {@code TRANTYPE} cluster (two-byte key) becomes a relational table whose
 *       primary key is {@link #tranType}. Keyed reads served by CICS file control
 *       (online) and by sequential/keyed batch reads are replaced by a Spring Data
 *       {@code TransactionTypeRepository}
 *       ({@code JpaRepository<TransactionType, String>}).</li>
 *   <li><strong>Type code &rarr; {@link String} primary key.</strong>
 *       {@code TRAN-TYPE PIC X(02)} is a fixed two-character (alphanumeric) code.
 *       It is kept as a {@link String} (PostgreSQL {@code VARCHAR(2)}), not a
 *       numeric type, so any leading characters and the exact two-byte width are
 *       preserved, matching the keyed VSAM access. It is this entity's sole
 *       {@code @Id}.</li>
 *   <li><strong>Description &rarr; {@link String}.</strong>
 *       {@code TRAN-TYPE-DESC PIC X(50)} is a 50-character descriptive text,
 *       mapped to {@link String} (PostgreSQL {@code VARCHAR(50)}) with its length
 *       preserved exactly.</li>
 *   <li><strong>No decimal fields.</strong> Neither field originates from a COBOL
 *       {@code PIC} clause with decimal positions, so this entity intentionally
 *       contains <strong>no</strong> {@code float}, {@code double} or
 *       {@code BigDecimal} (AAP §0.7.3).</li>
 *   <li><strong>No optimistic locking.</strong> Per AAP §0.7.5, JPA
 *       {@code @Version} is applied <strong>only</strong> to {@code Account} and
 *       {@code Card} (the read-update programs {@code COACTUPC} and
 *       {@code COCRDUPC}). {@code TRANTYPE} is static reference data that is never
 *       updated through a read-update snapshot comparison, so this entity carries
 *       <strong>no</strong> {@code @Version} column.</li>
 *   <li><strong>No composite key.</strong> Unlike {@code TransactionCategory},
 *       this record's key is the single {@code TRAN-TYPE} field; it is therefore a
 *       plain {@code @Id} and deliberately carries <strong>no</strong>
 *       {@code @EmbeddedId}.</li>
 *   <li><strong>No associations / no Bean Validation.</strong> Per the Minimal
 *       Change Clause this is a pure persistence type: it declares exactly the two
 *       mapped record fields, models no JPA associations, and carries no Jakarta
 *       Bean Validation annotations (input validation lives in the request-DTO
 *       layer, AAP §0.4.2).</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(08)} is reserved padding that pads the record to its
 *       60-byte length ({@code 2 + 50 + 8 = 60}); it carries no business data and
 *       is intentionally <strong>not</strong> mapped to a column. The 60-byte
 *       record length is documented here for external-contract reference only
 *       (AAP §0.7.2).</li>
 * </ul>
 *
 * <h2>Primary-key &amp; column-name contract</h2>
 * <p>The {@link Column} names declared below &mdash; {@code type_code} (primary
 * key) and {@code description} &mdash; are authoritative for the data layer. The
 * Flyway {@code V1__create_schema.sql} {@code transaction_types} table must
 * declare {@code type_code} as the {@code VARCHAR(2)} primary key and
 * {@code description} as {@code VARCHAR(50)}; the {@code V3} seed (loaded from
 * {@code app/data/ASCII/trantype.txt}, 60-byte records) must align with these
 * names and lengths. The two-character {@code type_code} width is shared across
 * the schema: it matches {@code transactions.type_code} (the logical foreign key
 * {@code Transaction.tranTypeCd}) and the leading type-code component of the
 * {@code transaction_categories} and {@code disclosure_groups} keys.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material and
 * is never copied into this repository.</p>
 *
 * @see Transaction
 */
@Entity
@Table(name = "transaction_types")
public class TransactionType {

    /**
     * Primary key &mdash; the two-character transaction-type code.
     *
     * <p>Migrated from {@code TRAN-TYPE PIC X(02)}. Kept as a {@link String}
     * (PostgreSQL {@code VARCHAR(2)}) rather than a numeric type so the exact
     * two-byte width and any leading characters are preserved and the value
     * matches both the keyed VSAM access and the logical foreign key
     * {@code Transaction.tranTypeCd} ({@code transactions.type_code}). It is the
     * table's sole {@code @Id}, so the downstream repository is
     * {@code JpaRepository<TransactionType, String>}.</p>
     */
    // TRAN-TYPE PIC X(02) -> 2-char type code, primary VSAM key -> String(2) (VARCHAR(2)) @Id
    @Id
    @Column(name = "type_code", length = 2, nullable = false)
    private String tranType;

    /**
     * Human-readable description of the transaction type.
     *
     * <p>Migrated from {@code TRAN-TYPE-DESC PIC X(50)}: a 50-character
     * descriptive text mapped to {@link String} (PostgreSQL {@code VARCHAR(50)})
     * with its length preserved exactly.</p>
     */
    // TRAN-TYPE-DESC PIC X(50) -> 50-char description -> String(50) (VARCHAR(50))
    @Column(name = "description", length = 50)
    private String tranTypeDesc;

    /**
     * Default no-argument constructor required by the JPA provider (Hibernate) to
     * instantiate the entity reflectively before populating its fields.
     */
    public TransactionType() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Convenience constructor that fully populates the entity, useful for tests
     * and for seeding reference data programmatically.
     *
     * @param tranType     the two-character type code (primary key)
     * @param tranTypeDesc the type description (up to 50 characters)
     */
    public TransactionType(String tranType, String tranTypeDesc) {
        this.tranType = tranType;
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Returns the primary-key type code ({@code TRAN-TYPE}).
     *
     * @return the two-character type code, or {@code null} if unset
     */
    public String getTranType() {
        return tranType;
    }

    /**
     * Sets the primary-key type code ({@code TRAN-TYPE}).
     *
     * @param tranType the two-character type code to set
     */
    public void setTranType(String tranType) {
        this.tranType = tranType;
    }

    /**
     * Returns the type description ({@code TRAN-TYPE-DESC}).
     *
     * @return the description, or {@code null} if unset
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Sets the type description ({@code TRAN-TYPE-DESC}).
     *
     * @param tranTypeDesc the description to set (up to 50 characters)
     */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Identity-based equality keyed on the type code ({@link #tranType}).
     *
     * <p>Two {@code TransactionType} instances are equal when they are of the
     * exact same class and share the same {@link #tranType}. The primary key alone
     * defines entity identity; the mutable description is deliberately excluded so
     * equality stays stable. Exact-class comparison (rather than
     * {@code instanceof}) is used so a proxy/subclass is not treated as equal to a
     * different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code TransactionType} with an equal
     *         type code
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionType that = (TransactionType) o;
        return Objects.equals(tranType, that.tranType);
    }

    /**
     * Hash code derived solely from {@link #tranType}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the type code
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranType);
    }

    /**
     * Diagnostic representation including both mapped fields. Neither the type
     * code nor its description is sensitive, so both are emitted verbatim.
     *
     * @return a human-readable description of this transaction type
     */
    @Override
    public String toString() {
        return "TransactionType{"
                + "tranType='" + tranType + '\''
                + ", tranTypeDesc='" + tranTypeDesc + '\''
                + '}';
    }
}
