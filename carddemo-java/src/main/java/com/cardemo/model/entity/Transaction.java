package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the legacy AWS CardDemo transaction record onto the
 * PostgreSQL {@code transactions} table.
 *
 * <p>This entity is the Java 25 / Spring Data JPA replacement for the VSAM KSDS
 * dataset {@code TRANSACT}, whose fixed 350-byte record layout is defined by the
 * COBOL copybook {@code app/cpy/CVTRA05Y.cpy} ({@code 01 TRAN-RECORD}, record
 * length 350). The {@code TRANSACT} cluster is keyed on the 16-byte transaction
 * id and carries an alternate index keyed on the card number. On the mainframe
 * this record was read and written by the online programs {@code COTRN00C}
 * (transaction list), {@code COTRN01C} (transaction detail) and {@code COTRN02C}
 * (add transaction), and by the batch programs {@code CBTRN02C} (daily posting),
 * {@code CBTRN03C} (transaction report) and {@code CBSTM03A} (statement
 * generation). It is the highest-traffic record in the system and the single
 * decimal-critical entity in the data model.</p>
 *
 * <h2>Original COBOL layout (CVTRA05Y.cpy &mdash; RECLN 350)</h2>
 * <pre>{@code
 * 01  TRAN-RECORD.
 *     05  TRAN-ID                  PIC X(16).
 *     05  TRAN-TYPE-CD             PIC X(02).
 *     05  TRAN-CAT-CD              PIC 9(04).
 *     05  TRAN-SOURCE              PIC X(10).
 *     05  TRAN-DESC                PIC X(100).
 *     05  TRAN-AMT                 PIC S9(09)V99.
 *     05  TRAN-MERCHANT-ID         PIC 9(09).
 *     05  TRAN-MERCHANT-NAME       PIC X(50).
 *     05  TRAN-MERCHANT-CITY       PIC X(50).
 *     05  TRAN-MERCHANT-ZIP        PIC X(10).
 *     05  TRAN-CARD-NUM            PIC X(16).
 *     05  TRAN-ORIG-TS             PIC X(26).
 *     05  TRAN-PROC-TS             PIC X(26).
 *     05  FILLER                   PIC X(20).
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed access &rarr; JPA.</strong> The physically keyed
 *       {@code TRANSACT} cluster (16-byte key) becomes a relational table whose
 *       primary key is {@link #tranId}. Keyed reads/writes are served by a Spring
 *       Data {@code TransactionRepository} (a {@code JpaRepository<Transaction,
 *       String>}, because the primary key is the 16-character {@code TRAN-ID})
 *       rather than CICS file control. The {@code TRANSACT} alternate index on
 *       card number is reproduced by a repository query over {@link #tranCardNum}
 *       (created in the Flyway {@code V2} index migration); no JPA association is
 *       declared (see below).</li>
 *   <li><strong>Signed packed/zoned decimal &rarr; {@link BigDecimal}.</strong>
 *       {@code TRAN-AMT PIC S9(09)V99} is the transaction monetary amount: nine
 *       integer digits plus two fractional digits, signed. It maps to a
 *       {@link BigDecimal} with {@code precision = 11, scale = 2}
 *       (9&nbsp;+&nbsp;2&nbsp;=&nbsp;11 total digits). <strong>No {@code float}
 *       or {@code double} is used anywhere</strong> (AAP §0.7.3): this amount is
 *       summed into statement totals ({@code CBSTM03A}) and feeds interest and
 *       report processing, where penny-level parity is non-negotiable.</li>
 *   <li><strong>{@code TRAN-SOURCE} kept as raw {@link String}.</strong> Although
 *       a {@code model.enums.TransactionSource} enum exists, the confirmed
 *       cross-folder contract is that this entity maps {@code TRAN-SOURCE PIC
 *       X(10)} as a raw {@link String} of length 10 to preserve exact byte and
 *       space-padding parity with the legacy field (for example
 *       {@code "POS TERM  "}). Any enum interpretation is a service-layer concern
 *       and is intentionally <strong>not</strong> applied at the persistence
 *       boundary.</li>
 *   <li><strong>Fixed-text timestamps &rarr; {@link String}.</strong> The two
 *       {@code PIC X(26)} timestamp fields ({@code TRAN-ORIG-TS},
 *       {@code TRAN-PROC-TS}) hold 26-character text in the form
 *       {@code YYYY-MM-DD HH:MM:SS.ffffff}. They are kept as {@link String} (not
 *       converted to {@code LocalDateTime}/{@code Instant}) to preserve the exact
 *       26-byte external-interface contract (AAP §0.7.2) and avoid parse failures
 *       on legacy rows that may be spaces or low-values.</li>
 *   <li><strong>No optimistic-locking column.</strong> Unlike {@code Account} and
 *       {@code Card}, the transaction record has no read-update snapshot
 *       comparison in the COBOL estate, so this entity declares no
 *       {@code @Version} field (AAP §0.7.5). Transactions are inserted (and read)
 *       rather than concurrently rewritten.</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(20)} is reserved padding that pads the record to its
 *       350-byte length; it carries no business data and is intentionally
 *       <strong>not</strong> mapped to a column. The 350-byte record length is
 *       documented here for external-contract reference only.</li>
 * </ul>
 *
 * <h2>Decimal-fidelity contract (AAP §0.7.3)</h2>
 * <p>{@link #tranAmt} is persisted with {@code precision = 11, scale = 2}.
 * Callers MUST compare amounts with {@link BigDecimal#compareTo(BigDecimal)}
 * rather than {@link BigDecimal#equals(Object)}, because {@code equals} is
 * scale-sensitive (for example {@code 1.0} is not {@code equals} to
 * {@code 1.00}). This entity neither rounds nor rescales values; the persisted
 * scale is fixed by the {@code @Column(scale = 2)} mapping.</p>
 *
 * <h2>Foreign keys modelled as scalar columns</h2>
 * <p>{@link #tranTypeCd}, {@link #tranCatCd}, {@link #tranCardNum} and
 * {@link #tranMerchantId} are logical foreign keys (to
 * {@code transaction_types.type_code}, the {@code transaction_categories}
 * type+category pair, {@code cards.card_number} and the merchant respectively).
 * Mirroring the original VSAM keyed-access pattern and the Minimal Change
 * Clause, they are kept as plain scalar columns &mdash; this entity declares
 * <strong>no</strong> JPA associations ({@code @ManyToOne}/{@code @OneToMany}).</p>
 *
 * <h2>Primary-key &amp; column-name contract</h2>
 * <p>The {@link Column} names declared below &mdash; {@code transaction_id},
 * {@code type_code}, {@code category_code}, {@code source}, {@code description},
 * {@code amount}, {@code merchant_id}, {@code merchant_name},
 * {@code merchant_city}, {@code merchant_zip}, {@code card_number},
 * {@code original_timestamp} and {@code processed_timestamp} &mdash; are
 * authoritative. The Flyway {@code V1__create_schema.sql} {@code transactions}
 * DDL must align with them ({@code transaction_id} PK {@code VARCHAR(16)};
 * {@code amount} {@code NUMERIC(11,2)}; {@code merchant_id} {@code BIGINT};
 * {@code card_number} {@code VARCHAR(16)}; the two timestamps {@code VARCHAR(26)}
 * to match the 26-character text contract), the {@code V2} migration adds the
 * alternate index on {@code card_number}, and the {@code transactions} table is
 * populated at runtime by the daily-posting batch job (no static seed row).</p>
 *
 * <p>Per the Minimal Change Clause this entity is a pure persistence type: it
 * declares exactly the thirteen mapped fields, carries no Jakarta Bean
 * Validation annotations (input validation lives in the request DTO layer, AAP
 * §0.4.2) and models no JPA associations.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material
 * and is never copied into this repository.</p>
 *
 * @see java.math.BigDecimal
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    /**
     * Primary key &mdash; the unique transaction identifier.
     *
     * <p>Migrated from {@code TRAN-ID PIC X(16)}: a 16-character key used for the
     * keyed VSAM read in {@code COTRN01C}. Kept as a fixed-length {@link String}
     * to preserve the exact 16-character external format; the downstream
     * repository is therefore {@code JpaRepository<Transaction, String>}.</p>
     */
    // TRAN-ID PIC X(16) -> 16-char transaction key -> String (sole @Id)
    @Id
    @Column(name = "transaction_id", length = 16, nullable = false)
    private String tranId;

    /**
     * Transaction type code.
     *
     * <p>Migrated from {@code TRAN-TYPE-CD PIC X(02)}: a two-character code that
     * is a logical foreign key to {@code transaction_types.type_code}. Modelled
     * as a scalar {@link String} of length two (no JPA association).</p>
     */
    // TRAN-TYPE-CD PIC X(02) -> 2-char type code -> String (logical FK, scalar)
    @Column(name = "type_code", length = 2)
    private String tranTypeCd;

    /**
     * Transaction category code.
     *
     * <p>Migrated from {@code TRAN-CAT-CD PIC 9(04)}: a four-digit numeric
     * category code that pairs with {@link #tranTypeCd} in the
     * {@code transaction_categories} reference data. Modelled as an
     * {@link Integer} (four digits fit comfortably within {@code Integer}).</p>
     */
    // TRAN-CAT-CD PIC 9(04) -> 4-digit numeric category code -> Integer
    @Column(name = "category_code")
    private Integer tranCatCd;

    /**
     * Transaction origination source.
     *
     * <p>Migrated from {@code TRAN-SOURCE PIC X(10)}: a ten-character provenance
     * token (for example {@code "POS TERM  "} or {@code "OPERATOR  "}). Kept as a
     * raw {@link String} of length ten &mdash; <strong>not</strong> the
     * {@code TransactionSource} enum &mdash; to preserve exact byte and
     * space-padding parity at the persistence boundary.</p>
     */
    // TRAN-SOURCE PIC X(10) -> 10-char source token -> String (raw, NOT enum)
    @Column(name = "source", length = 10)
    private String tranSource;

    /**
     * Free-text transaction description.
     *
     * <p>Migrated from {@code TRAN-DESC PIC X(100)}: a 100-character description.
     * Modelled as a {@link String} of length 100.</p>
     */
    // TRAN-DESC PIC X(100) -> 100-char description -> String
    @Column(name = "description", length = 100)
    private String tranDesc;

    /**
     * Transaction monetary amount.
     *
     * <p>Migrated from {@code TRAN-AMT PIC S9(09)V99}: a signed amount with nine
     * integer and two fractional digits. Mapped to {@link BigDecimal} with
     * {@code precision = 11, scale = 2}. <strong>No {@code float}/{@code double}
     * substitution</strong> (AAP §0.7.3): this is the value summed into statement
     * totals and used by interest and report processing. Compare with
     * {@link BigDecimal#compareTo(BigDecimal)}, never
     * {@link BigDecimal#equals(Object)}.</p>
     */
    // TRAN-AMT PIC S9(09)V99 -> signed money 9+2 digits -> BigDecimal(11,2), NO float/double
    @Column(name = "amount", precision = 11, scale = 2)
    private BigDecimal tranAmt;

    /**
     * Merchant identifier.
     *
     * <p>Migrated from {@code TRAN-MERCHANT-ID PIC 9(09)}: a nine-digit numeric
     * merchant id. Modelled as a {@link Long} (a nine-digit value fits within
     * {@code Integer}, but {@link Long} is used for headroom and to keep numeric
     * id types consistent across the model). It is a logical, scalar reference to
     * the merchant; no association is declared.</p>
     */
    // TRAN-MERCHANT-ID PIC 9(09) -> 9-digit numeric merchant id -> Long
    @Column(name = "merchant_id")
    private Long tranMerchantId;

    /**
     * Merchant name.
     *
     * <p>Migrated from {@code TRAN-MERCHANT-NAME PIC X(50)}: a 50-character
     * merchant name. Modelled as a {@link String} of length 50.</p>
     */
    // TRAN-MERCHANT-NAME PIC X(50) -> 50-char merchant name -> String
    @Column(name = "merchant_name", length = 50)
    private String tranMerchantName;

    /**
     * Merchant city.
     *
     * <p>Migrated from {@code TRAN-MERCHANT-CITY PIC X(50)}: a 50-character
     * merchant city. Modelled as a {@link String} of length 50.</p>
     */
    // TRAN-MERCHANT-CITY PIC X(50) -> 50-char merchant city -> String
    @Column(name = "merchant_city", length = 50)
    private String tranMerchantCity;

    /**
     * Merchant ZIP/postal code.
     *
     * <p>Migrated from {@code TRAN-MERCHANT-ZIP PIC X(10)}: a 10-character
     * merchant ZIP code. Modelled as a {@link String} of length 10.</p>
     */
    // TRAN-MERCHANT-ZIP PIC X(10) -> 10-char merchant ZIP -> String
    @Column(name = "merchant_zip", length = 10)
    private String tranMerchantZip;

    /**
     * Card number associated with the transaction.
     *
     * <p>Migrated from {@code TRAN-CARD-NUM PIC X(16)}: the 16-character card
     * number (PAN). This is the {@code TRANSACT} alternate-index field and a
     * logical foreign key to {@code cards.card_number}; it is kept as a scalar
     * {@link String} of length 16 (no JPA association). It is masked in
     * {@link #toString()} so the full PAN is never emitted to logs.</p>
     */
    // TRAN-CARD-NUM PIC X(16) -> 16-char card number (AIX field) -> String (logical FK, scalar)
    @Column(name = "card_number", length = 16)
    private String tranCardNum;

    /**
     * Original transaction timestamp, as 26-character text.
     *
     * <p>Migrated from {@code TRAN-ORIG-TS PIC X(26)}: text in the form
     * {@code YYYY-MM-DD HH:MM:SS.ffffff}. Kept as a {@link String} of length 26
     * (not {@code LocalDateTime}) to preserve the exact 26-byte external
     * contract.</p>
     */
    // TRAN-ORIG-TS PIC X(26) -> 26-char timestamp text -> String (NOT LocalDateTime)
    @Column(name = "original_timestamp", length = 26)
    private String tranOrigTs;

    /**
     * Processed transaction timestamp, as 26-character text.
     *
     * <p>Migrated from {@code TRAN-PROC-TS PIC X(26)}: text in the form
     * {@code YYYY-MM-DD HH:MM:SS.ffffff}. Kept as a {@link String} of length 26
     * (not {@code LocalDateTime}) to preserve the exact 26-byte external
     * contract.</p>
     */
    // TRAN-PROC-TS PIC X(26) -> 26-char timestamp text -> String (NOT LocalDateTime)
    @Column(name = "processed_timestamp", length = 26)
    private String tranProcTs;

    /**
     * Creates an empty {@code Transaction}.
     *
     * <p>Required by JPA: the provider instantiates the entity with this no-arg
     * constructor and then populates the fields via reflection.</p>
     */
    public Transaction() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Returns the transaction identifier ({@code TRAN-ID}).
     *
     * @return the 16-character transaction id, or {@code null} if unset
     */
    public String getTranId() {
        return tranId;
    }

    /**
     * Sets the transaction identifier ({@code TRAN-ID}).
     *
     * @param tranId the 16-character transaction id to set
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * Returns the transaction type code ({@code TRAN-TYPE-CD}).
     *
     * @return the two-character type code, or {@code null} if unset
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Sets the transaction type code ({@code TRAN-TYPE-CD}).
     *
     * @param tranTypeCd the two-character type code to set
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the transaction category code ({@code TRAN-CAT-CD}).
     *
     * @return the numeric category code, or {@code null} if unset
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Sets the transaction category code ({@code TRAN-CAT-CD}).
     *
     * @param tranCatCd the numeric category code to set
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the transaction origination source ({@code TRAN-SOURCE}).
     *
     * <p>The value is the raw, space-padded ten-character token (for example
     * {@code "POS TERM  "}); any enum interpretation is a service-layer
     * concern.</p>
     *
     * @return the ten-character source token, or {@code null} if unset
     */
    public String getTranSource() {
        return tranSource;
    }

    /**
     * Sets the transaction origination source ({@code TRAN-SOURCE}).
     *
     * @param tranSource the ten-character source token to set
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * Returns the transaction description ({@code TRAN-DESC}).
     *
     * @return the description, or {@code null} if unset
     */
    public String getTranDesc() {
        return tranDesc;
    }

    /**
     * Sets the transaction description ({@code TRAN-DESC}).
     *
     * @param tranDesc the description to set
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * Returns the transaction amount ({@code TRAN-AMT}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)},
     * never {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the transaction amount, or {@code null} if unset
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * Sets the transaction amount ({@code TRAN-AMT}). The value is persisted at
     * scale 2; this setter neither rounds nor rescales.
     *
     * @param tranAmt the transaction amount to set
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * Returns the merchant identifier ({@code TRAN-MERCHANT-ID}).
     *
     * @return the merchant id, or {@code null} if unset
     */
    public Long getTranMerchantId() {
        return tranMerchantId;
    }

    /**
     * Sets the merchant identifier ({@code TRAN-MERCHANT-ID}).
     *
     * @param tranMerchantId the merchant id to set
     */
    public void setTranMerchantId(Long tranMerchantId) {
        this.tranMerchantId = tranMerchantId;
    }

    /**
     * Returns the merchant name ({@code TRAN-MERCHANT-NAME}).
     *
     * @return the 50-character merchant name, or {@code null} if unset
     */
    public String getTranMerchantName() {
        return tranMerchantName;
    }

    /**
     * Sets the merchant name ({@code TRAN-MERCHANT-NAME}).
     *
     * @param tranMerchantName the 50-character merchant name to set
     */
    public void setTranMerchantName(String tranMerchantName) {
        this.tranMerchantName = tranMerchantName;
    }

    /**
     * Returns the merchant city ({@code TRAN-MERCHANT-CITY}).
     *
     * @return the 50-character merchant city, or {@code null} if unset
     */
    public String getTranMerchantCity() {
        return tranMerchantCity;
    }

    /**
     * Sets the merchant city ({@code TRAN-MERCHANT-CITY}).
     *
     * @param tranMerchantCity the 50-character merchant city to set
     */
    public void setTranMerchantCity(String tranMerchantCity) {
        this.tranMerchantCity = tranMerchantCity;
    }

    /**
     * Returns the merchant ZIP/postal code ({@code TRAN-MERCHANT-ZIP}).
     *
     * @return the 10-character merchant ZIP code, or {@code null} if unset
     */
    public String getTranMerchantZip() {
        return tranMerchantZip;
    }

    /**
     * Sets the merchant ZIP/postal code ({@code TRAN-MERCHANT-ZIP}).
     *
     * @param tranMerchantZip the 10-character merchant ZIP code to set
     */
    public void setTranMerchantZip(String tranMerchantZip) {
        this.tranMerchantZip = tranMerchantZip;
    }

    /**
     * Returns the card number ({@code TRAN-CARD-NUM}).
     *
     * <p>This returns the full, unmasked 16-character card number; callers are
     * responsible for masking it before display or logging (see
     * {@link #toString()}, which masks it).</p>
     *
     * @return the 16-character card number, or {@code null} if unset
     */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /**
     * Sets the card number ({@code TRAN-CARD-NUM}).
     *
     * @param tranCardNum the 16-character card number to set
     */
    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    /**
     * Returns the original transaction timestamp ({@code TRAN-ORIG-TS}) as text.
     *
     * @return the 26-character original timestamp, or {@code null} if unset
     */
    public String getTranOrigTs() {
        return tranOrigTs;
    }

    /**
     * Sets the original transaction timestamp ({@code TRAN-ORIG-TS}) as text.
     *
     * @param tranOrigTs the 26-character original timestamp to set
     */
    public void setTranOrigTs(String tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    /**
     * Returns the processed transaction timestamp ({@code TRAN-PROC-TS}) as text.
     *
     * @return the 26-character processed timestamp, or {@code null} if unset
     */
    public String getTranProcTs() {
        return tranProcTs;
    }

    /**
     * Sets the processed transaction timestamp ({@code TRAN-PROC-TS}) as text.
     *
     * @param tranProcTs the 26-character processed timestamp to set
     */
    public void setTranProcTs(String tranProcTs) {
        this.tranProcTs = tranProcTs;
    }

    /**
     * Identity-based equality keyed on the transaction identifier
     * ({@link #tranId}).
     *
     * <p>Two {@code Transaction} instances are equal when they are of the exact
     * same class and share the same {@link #tranId}. The primary key alone
     * defines entity identity; mutable business columns are deliberately excluded
     * so equality stays stable across updates. Exact-class comparison (rather
     * than {@code instanceof}) is used so a proxy/subclass is not treated as
     * equal to a different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Transaction} with an equal id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Transaction that = (Transaction) o;
        return Objects.equals(tranId, that.tranId);
    }

    /**
     * Hash code derived solely from {@link #tranId}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the transaction identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }

    /**
     * Masks the card number for safe inclusion in diagnostic output, revealing
     * at most the last four characters.
     *
     * @return the masked card number, or {@code "null"} when no card number is set
     */
    private String maskedCardNumber() {
        if (tranCardNum == null) {
            return "null";
        }
        int length = tranCardNum.length();
        if (length <= 4) {
            // Too short to reveal a last-four suffix safely; mask completely.
            return "*".repeat(length);
        }
        return "*".repeat(length - 4) + tranCardNum.substring(length - 4);
    }

    /**
     * Diagnostic representation that intentionally <strong>excludes</strong> the
     * monetary amount ({@link #tranAmt}) to avoid emitting sensitive financial
     * data into logs, and <strong>masks</strong> the card number
     * ({@link #tranCardNum}) so the full PAN is never logged. The identifier,
     * type/category/source, description, merchant fields and timestamps are
     * included.
     *
     * @return a human-readable, non-sensitive description of this transaction
     */
    @Override
    public String toString() {
        return "Transaction{"
                + "tranId='" + tranId + '\''
                + ", tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd=" + tranCatCd
                + ", tranSource='" + tranSource + '\''
                + ", tranDesc='" + tranDesc + '\''
                + ", tranMerchantId=" + tranMerchantId
                + ", tranMerchantName='" + tranMerchantName + '\''
                + ", tranMerchantCity='" + tranMerchantCity + '\''
                + ", tranMerchantZip='" + tranMerchantZip + '\''
                + ", tranCardNum='" + maskedCardNumber() + '\''
                + ", tranOrigTs='" + tranOrigTs + '\''
                + ", tranProcTs='" + tranProcTs + '\''
                + '}';
    }
}
