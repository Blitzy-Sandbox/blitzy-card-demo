package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity mapping the legacy AWS CardDemo <em>daily transaction</em> staging
 * record onto the PostgreSQL {@code daily_transactions} table.
 *
 * <p>This entity is the Java 25 / Spring Data JPA replacement for the sequential
 * <strong>PS staging</strong> dataset {@code DALYTRAN}
 * ({@code AWS.M2.CARDDEMO.DALYTRAN.PS}), whose fixed 350-byte record layout is
 * defined by the COBOL copybook {@code app/cpy/CVTRA06Y.cpy}
 * ({@code 01 DALYTRAN-RECORD}, record length 350). On the mainframe this file is
 * the daily, <em>pre-posting</em> transaction feed: it is read sequentially by
 * the batch programs {@code CBTRN01C} (daily-transaction reader/validator) and
 * {@code CBTRN02C} (the {@code POSTTRAN} daily posting step), which validate each
 * row (account, card and category checks) and then <strong>post</strong> the
 * accepted rows into the permanent {@code TRANSACT} dataset.</p>
 *
 * <h2>Why a separate staging entity</h2>
 * <p>{@code DailyTransaction} is intentionally a near-clone of {@link Transaction}
 * &mdash; the two records share the identical 350-byte shape, differing only by
 * the {@code DALYTRAN-} versus {@code TRAN-} field-name prefix &mdash; but it is
 * deliberately modelled as a <strong>distinct staging table</strong>
 * ({@code daily_transactions}) rather than reusing {@code transactions}. This
 * preserves the source/target separation of the COBOL posting pipeline: the
 * {@code DailyTransactionPostingJob} reads {@code daily_transactions}, runs the
 * {@code CBTRN02C} validation cascade, and writes accepted rows into
 * {@code transactions} (rejects are routed to the daily-reject sink). Keeping the
 * raw, possibly-not-yet-valid input physically separate from the posted ledger is
 * the same boundary the mainframe enforced between the {@code DALYTRAN} PS file
 * and the {@code TRANSACT} KSDS.</p>
 *
 * <h2>Original COBOL layout (CVTRA06Y.cpy &mdash; RECLN 350)</h2>
 * <pre>{@code
 * 01  DALYTRAN-RECORD.
 *     05  DALYTRAN-ID              PIC X(16).
 *     05  DALYTRAN-TYPE-CD         PIC X(02).
 *     05  DALYTRAN-CAT-CD          PIC 9(04).
 *     05  DALYTRAN-SOURCE          PIC X(10).
 *     05  DALYTRAN-DESC            PIC X(100).
 *     05  DALYTRAN-AMT             PIC S9(09)V99.
 *     05  DALYTRAN-MERCHANT-ID     PIC 9(09).
 *     05  DALYTRAN-MERCHANT-NAME   PIC X(50).
 *     05  DALYTRAN-MERCHANT-CITY   PIC X(50).
 *     05  DALYTRAN-MERCHANT-ZIP    PIC X(10).
 *     05  DALYTRAN-CARD-NUM        PIC X(16).
 *     05  DALYTRAN-ORIG-TS         PIC X(26).
 *     05  DALYTRAN-PROC-TS         PIC X(26).
 *     05  FILLER                   PIC X(20).
 * }</pre>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP §0.7.1)</h2>
 * <ul>
 *   <li><strong>Sequential PS staging file &rarr; JPA staging table.</strong> The
 *       {@code DALYTRAN} PS dataset has no VSAM key; its natural record identifier
 *       is {@code DALYTRAN-ID}, which becomes the relational primary key
 *       {@link #dalytranId}. Sequential reads/writes are served by a Spring Data
 *       {@code DailyTransactionRepository} (a {@code JpaRepository<DailyTransaction,
 *       String>}, because the primary key is the 16-character {@code DALYTRAN-ID})
 *       in place of QSAM/sequential file access. No JPA association is declared
 *       (see below).</li>
 *   <li><strong>Signed packed/zoned decimal &rarr; {@link BigDecimal}.</strong>
 *       {@code DALYTRAN-AMT PIC S9(09)V99} is the transaction monetary amount:
 *       nine integer digits plus two fractional digits, signed. It maps to a
 *       {@link BigDecimal} with {@code precision = 11, scale = 2}
 *       (9&nbsp;+&nbsp;2&nbsp;=&nbsp;11 total digits), the identical money rule
 *       applied to {@link Transaction#getTranAmt()}. <strong>No {@code float} or
 *       {@code double} is used anywhere</strong> (AAP §0.7.3): this amount is
 *       posted verbatim into {@code transactions} and feeds downstream interest,
 *       statement and report processing, where penny-level parity is
 *       non-negotiable.</li>
 *   <li><strong>{@code DALYTRAN-SOURCE} kept as raw {@link String}.</strong>
 *       Although a {@code model.enums.TransactionSource} enum exists, the confirmed
 *       cross-folder contract is that this entity maps {@code DALYTRAN-SOURCE PIC
 *       X(10)} as a raw {@link String} of length 10 to preserve exact byte and
 *       space-padding parity with the legacy field (for example
 *       {@code "POS TERM  "} or {@code "OPERATOR  "}). Any enum interpretation is a
 *       service-layer concern and is intentionally <strong>not</strong> applied at
 *       the persistence boundary.</li>
 *   <li><strong>Fixed-text timestamps &rarr; {@link LocalDateTime}.</strong> The
 *       two {@code PIC X(26)} timestamp fields ({@code DALYTRAN-ORIG-TS},
 *       {@code DALYTRAN-PROC-TS}) hold 26-character text in the form
 *       {@code YYYY-MM-DD HH:MM:SS.ffffff}. They map to {@link LocalDateTime} to
 *       match the authoritative V1 {@code TIMESTAMP} columns (AAP §0.1.2 maps LE
 *       date/time handling to {@code java.time}); the exact 26-byte
 *       external-interface rendering is reconstructed at the DTO/batch-file
 *       boundary rather than at the persistence column.</li>
 *   <li><strong>No optimistic-locking column.</strong> Only {@code Account} and
 *       {@code Card} carry a read-update snapshot comparison in the COBOL estate,
 *       so this staging entity declares no {@code @Version} field (AAP §0.7.5).
 *       Daily-transaction rows are inserted by the feed and read once by the
 *       posting job; they are never concurrently rewritten.</li>
 *   <li><strong>FILLER not materialized.</strong> The trailing
 *       {@code FILLER PIC X(20)} is reserved padding that pads the record to its
 *       350-byte length; it carries no business data and is intentionally
 *       <strong>not</strong> mapped to a column. The 350-byte record length is
 *       documented here for external-contract reference only.</li>
 * </ul>
 *
 * <h2>Decimal-fidelity contract (AAP §0.7.3)</h2>
 * <p>{@link #dalytranAmt} is persisted with {@code precision = 11, scale = 2}.
 * Callers MUST compare amounts with {@link BigDecimal#compareTo(BigDecimal)}
 * rather than {@link BigDecimal#equals(Object)}, because {@code equals} is
 * scale-sensitive (for example {@code 1.0} is not {@code equals} to
 * {@code 1.00}). This entity neither rounds nor rescales values; the persisted
 * scale is fixed by the {@code @Column(scale = 2)} mapping.</p>
 *
 * <h2>Foreign keys modelled as scalar columns</h2>
 * <p>{@link #dalytranTypeCd}, {@link #dalytranCatCd}, {@link #dalytranCardNum}
 * and {@link #dalytranMerchantId} are logical references (to transaction-type,
 * transaction-category, card and merchant data respectively). Because this is a
 * staging table that holds raw, not-yet-validated input &mdash; a card or account
 * may not yet exist when the row lands &mdash; they are kept as plain scalar
 * columns with <strong>no</strong> JPA associations
 * ({@code @ManyToOne}/{@code @OneToMany}) and no database foreign keys, exactly
 * mirroring the FK-light staging design and the COBOL access pattern.</p>
 *
 * <h2>Primary-key &amp; column-name contract</h2>
 * <p>The {@link Column} names declared below &mdash; {@code transaction_id},
 * {@code type_code}, {@code category_code}, {@code source}, {@code description},
 * {@code amount}, {@code merchant_id}, {@code merchant_name},
 * {@code merchant_city}, {@code merchant_zip}, {@code card_number},
 * {@code original_timestamp} and {@code processed_timestamp} &mdash; are
 * authoritative for the Flyway {@code V1__create_schema.sql}
 * {@code daily_transactions} DDL ({@code transaction_id} PK {@code VARCHAR(16)};
 * {@code amount} {@code NUMERIC(11,2)}; {@code merchant_id} {@code BIGINT};
 * {@code card_number} {@code VARCHAR(16)}; {@code original_timestamp} and
 * {@code processed_timestamp} {@code TIMESTAMP}). The
 * {@code daily_transactions} table is seeded by the Flyway {@code V3} migration
 * from the canonical ASCII fixture {@code app/data/ASCII/dailytran.txt}
 * (350-byte records), which is the golden input for the posting-pipeline parity
 * tests.</p>
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
 * @see Transaction
 * @see java.math.BigDecimal
 */
@Entity
@Table(name = "daily_transactions")
public class DailyTransaction {

    /**
     * Primary key &mdash; the unique daily-transaction identifier.
     *
     * <p>Migrated from {@code DALYTRAN-ID PIC X(16)}: the 16-character natural
     * record key of the {@code DALYTRAN} PS staging file (the file has no VSAM
     * key). Kept as a fixed-length {@link String} to preserve the exact
     * 16-character external format; the downstream repository is therefore
     * {@code JpaRepository<DailyTransaction, String>}.</p>
     */
    // DALYTRAN-ID PIC X(16) -> 16-char staging key -> String (sole @Id)
    @Id
    @Column(name = "transaction_id", length = 16, nullable = false)
    private String dalytranId;

    /**
     * Transaction type code.
     *
     * <p>Migrated from {@code DALYTRAN-TYPE-CD PIC X(02)}: a two-character code
     * that is a logical reference to {@code transaction_type.type_code}. Modelled
     * as a scalar {@link String} of length two (no JPA association; FK-light
     * staging).</p>
     */
    // DALYTRAN-TYPE-CD PIC X(02) -> 2-char type code -> String (logical ref, scalar)
    @Column(name = "type_code", length = 2)
    private String dalytranTypeCd;

    /**
     * Transaction category code.
     *
     * <p>Migrated from {@code DALYTRAN-CAT-CD PIC 9(04)}: a four-digit numeric
     * category code that pairs with {@link #dalytranTypeCd} in the
     * {@code transaction_category} reference data. Modelled as an
     * {@link Integer} (four digits fit comfortably within {@code Integer}).</p>
     */
    // DALYTRAN-CAT-CD PIC 9(04) -> 4-digit numeric category code -> Integer
    @Column(name = "category_code")
    private Integer dalytranCatCd;

    /**
     * Transaction origination source.
     *
     * <p>Migrated from {@code DALYTRAN-SOURCE PIC X(10)}: a ten-character
     * provenance token (for example {@code "POS TERM  "} or {@code "OPERATOR  "}).
     * Kept as a raw {@link String} of length ten &mdash; <strong>not</strong> the
     * {@code TransactionSource} enum &mdash; to preserve exact byte and
     * space-padding parity at the persistence boundary.</p>
     */
    // DALYTRAN-SOURCE PIC X(10) -> 10-char source token -> String (raw, NOT enum)
    @Column(name = "source", length = 10)
    private String dalytranSource;

    /**
     * Free-text transaction description.
     *
     * <p>Migrated from {@code DALYTRAN-DESC PIC X(100)}: a 100-character
     * description. Modelled as a {@link String} of length 100.</p>
     */
    // DALYTRAN-DESC PIC X(100) -> 100-char description -> String
    @Column(name = "description", length = 100)
    private String dalytranDesc;

    /**
     * Transaction monetary amount.
     *
     * <p>Migrated from {@code DALYTRAN-AMT PIC S9(09)V99}: a signed amount with
     * nine integer and two fractional digits. Mapped to {@link BigDecimal} with
     * {@code precision = 11, scale = 2}. <strong>No {@code float}/{@code double}
     * substitution</strong> (AAP §0.7.3): this is the value posted verbatim into
     * the {@code transactions} ledger and used by interest, statement and report
     * processing. Compare with {@link BigDecimal#compareTo(BigDecimal)}, never
     * {@link BigDecimal#equals(Object)}.</p>
     */
    // DALYTRAN-AMT PIC S9(09)V99 -> signed money 9+2 digits -> BigDecimal(11,2), NO float/double
    @Column(name = "amount", precision = 11, scale = 2)
    private BigDecimal dalytranAmt;

    /**
     * Merchant identifier.
     *
     * <p>Migrated from {@code DALYTRAN-MERCHANT-ID PIC 9(09)}: a nine-digit
     * numeric merchant id. Modelled as a {@link Long} (a nine-digit value fits
     * within {@code Integer}, but {@link Long} is used for headroom and to keep
     * numeric id types consistent across the model, matching
     * {@link Transaction#getTranMerchantId()}). It is a logical, scalar reference
     * to the merchant; no association is declared.</p>
     */
    // DALYTRAN-MERCHANT-ID PIC 9(09) -> 9-digit numeric merchant id -> Long
    @Column(name = "merchant_id")
    private Long dalytranMerchantId;

    /**
     * Merchant name.
     *
     * <p>Migrated from {@code DALYTRAN-MERCHANT-NAME PIC X(50)}: a 50-character
     * merchant name. Modelled as a {@link String} of length 50.</p>
     */
    // DALYTRAN-MERCHANT-NAME PIC X(50) -> 50-char merchant name -> String
    @Column(name = "merchant_name", length = 50)
    private String dalytranMerchantName;

    /**
     * Merchant city.
     *
     * <p>Migrated from {@code DALYTRAN-MERCHANT-CITY PIC X(50)}: a 50-character
     * merchant city. Modelled as a {@link String} of length 50.</p>
     */
    // DALYTRAN-MERCHANT-CITY PIC X(50) -> 50-char merchant city -> String
    @Column(name = "merchant_city", length = 50)
    private String dalytranMerchantCity;

    /**
     * Merchant ZIP/postal code.
     *
     * <p>Migrated from {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}: a 10-character
     * merchant ZIP code. Modelled as a {@link String} of length 10.</p>
     */
    // DALYTRAN-MERCHANT-ZIP PIC X(10) -> 10-char merchant ZIP -> String
    @Column(name = "merchant_zip", length = 10)
    private String dalytranMerchantZip;

    /**
     * Card number associated with the daily transaction.
     *
     * <p>Migrated from {@code DALYTRAN-CARD-NUM PIC X(16)}: the 16-character card
     * number (PAN). It is a logical reference to {@code card.card_number} and is
     * kept as a scalar {@link String} of length 16 (no JPA association; FK-light
     * staging). It is masked in {@link #toString()} so the full PAN is never
     * emitted to logs.</p>
     */
    // DALYTRAN-CARD-NUM PIC X(16) -> 16-char card number -> String (logical ref, scalar)
    @Column(name = "card_number", length = 16)
    private String dalytranCardNum;

    /**
     * Original transaction timestamp.
     *
     * <p>Migrated from {@code DALYTRAN-ORIG-TS PIC X(26)} (text in the form
     * {@code YYYY-MM-DD HH:MM:SS.ffffff}) to a {@link LocalDateTime}, matching the
     * authoritative {@code original_timestamp TIMESTAMP} column in
     * {@code V1__create_schema.sql}. The 26-byte external text rendering is
     * preserved at the DTO/API and batch-file boundaries, not in this column.</p>
     */
    // DALYTRAN-ORIG-TS PIC X(26) timestamp text -> LocalDateTime (V1 original_timestamp TIMESTAMP)
    @Column(name = "original_timestamp")
    private LocalDateTime dalytranOrigTs;

    /**
     * Processed transaction timestamp.
     *
     * <p>Migrated from {@code DALYTRAN-PROC-TS PIC X(26)} (text in the form
     * {@code YYYY-MM-DD HH:MM:SS.ffffff}) to a {@link LocalDateTime}, matching the
     * authoritative {@code processed_timestamp TIMESTAMP} column in
     * {@code V1__create_schema.sql}. The 26-byte external text rendering is
     * preserved at the DTO/API and batch-file boundaries, not in this column.</p>
     */
    // DALYTRAN-PROC-TS PIC X(26) timestamp text -> LocalDateTime (V1 processed_timestamp TIMESTAMP)
    @Column(name = "processed_timestamp")
    private LocalDateTime dalytranProcTs;

    /**
     * Creates an empty {@code DailyTransaction}.
     *
     * <p>Required by JPA: the provider instantiates the entity with this no-arg
     * constructor and then populates the fields via reflection.</p>
     */
    public DailyTransaction() {
        // Intentionally empty: JPA/Hibernate instantiates then sets fields.
    }

    /**
     * Returns the daily-transaction identifier ({@code DALYTRAN-ID}).
     *
     * @return the 16-character staging key, or {@code null} if unset
     */
    public String getDalytranId() {
        return dalytranId;
    }

    /**
     * Sets the daily-transaction identifier ({@code DALYTRAN-ID}).
     *
     * @param dalytranId the 16-character staging key to set
     */
    public void setDalytranId(String dalytranId) {
        this.dalytranId = dalytranId;
    }

    /**
     * Returns the transaction type code ({@code DALYTRAN-TYPE-CD}).
     *
     * @return the two-character type code, or {@code null} if unset
     */
    public String getDalytranTypeCd() {
        return dalytranTypeCd;
    }

    /**
     * Sets the transaction type code ({@code DALYTRAN-TYPE-CD}).
     *
     * @param dalytranTypeCd the two-character type code to set
     */
    public void setDalytranTypeCd(String dalytranTypeCd) {
        this.dalytranTypeCd = dalytranTypeCd;
    }

    /**
     * Returns the transaction category code ({@code DALYTRAN-CAT-CD}).
     *
     * @return the numeric category code, or {@code null} if unset
     */
    public Integer getDalytranCatCd() {
        return dalytranCatCd;
    }

    /**
     * Sets the transaction category code ({@code DALYTRAN-CAT-CD}).
     *
     * @param dalytranCatCd the numeric category code to set
     */
    public void setDalytranCatCd(Integer dalytranCatCd) {
        this.dalytranCatCd = dalytranCatCd;
    }

    /**
     * Returns the transaction origination source ({@code DALYTRAN-SOURCE}).
     *
     * <p>The value is the raw, space-padded ten-character token (for example
     * {@code "POS TERM  "}); any enum interpretation is a service-layer
     * concern.</p>
     *
     * @return the ten-character source token, or {@code null} if unset
     */
    public String getDalytranSource() {
        return dalytranSource;
    }

    /**
     * Sets the transaction origination source ({@code DALYTRAN-SOURCE}).
     *
     * @param dalytranSource the ten-character source token to set
     */
    public void setDalytranSource(String dalytranSource) {
        this.dalytranSource = dalytranSource;
    }

    /**
     * Returns the transaction description ({@code DALYTRAN-DESC}).
     *
     * @return the description, or {@code null} if unset
     */
    public String getDalytranDesc() {
        return dalytranDesc;
    }

    /**
     * Sets the transaction description ({@code DALYTRAN-DESC}).
     *
     * @param dalytranDesc the description to set
     */
    public void setDalytranDesc(String dalytranDesc) {
        this.dalytranDesc = dalytranDesc;
    }

    /**
     * Returns the transaction amount ({@code DALYTRAN-AMT}).
     *
     * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)},
     * never {@link BigDecimal#equals(Object)} (which is scale-sensitive).</p>
     *
     * @return the transaction amount, or {@code null} if unset
     */
    public BigDecimal getDalytranAmt() {
        return dalytranAmt;
    }

    /**
     * Sets the transaction amount ({@code DALYTRAN-AMT}). The value is persisted
     * at scale 2; this setter neither rounds nor rescales.
     *
     * @param dalytranAmt the transaction amount to set
     */
    public void setDalytranAmt(BigDecimal dalytranAmt) {
        this.dalytranAmt = dalytranAmt;
    }

    /**
     * Returns the merchant identifier ({@code DALYTRAN-MERCHANT-ID}).
     *
     * @return the merchant id, or {@code null} if unset
     */
    public Long getDalytranMerchantId() {
        return dalytranMerchantId;
    }

    /**
     * Sets the merchant identifier ({@code DALYTRAN-MERCHANT-ID}).
     *
     * @param dalytranMerchantId the merchant id to set
     */
    public void setDalytranMerchantId(Long dalytranMerchantId) {
        this.dalytranMerchantId = dalytranMerchantId;
    }

    /**
     * Returns the merchant name ({@code DALYTRAN-MERCHANT-NAME}).
     *
     * @return the 50-character merchant name, or {@code null} if unset
     */
    public String getDalytranMerchantName() {
        return dalytranMerchantName;
    }

    /**
     * Sets the merchant name ({@code DALYTRAN-MERCHANT-NAME}).
     *
     * @param dalytranMerchantName the 50-character merchant name to set
     */
    public void setDalytranMerchantName(String dalytranMerchantName) {
        this.dalytranMerchantName = dalytranMerchantName;
    }

    /**
     * Returns the merchant city ({@code DALYTRAN-MERCHANT-CITY}).
     *
     * @return the 50-character merchant city, or {@code null} if unset
     */
    public String getDalytranMerchantCity() {
        return dalytranMerchantCity;
    }

    /**
     * Sets the merchant city ({@code DALYTRAN-MERCHANT-CITY}).
     *
     * @param dalytranMerchantCity the 50-character merchant city to set
     */
    public void setDalytranMerchantCity(String dalytranMerchantCity) {
        this.dalytranMerchantCity = dalytranMerchantCity;
    }

    /**
     * Returns the merchant ZIP/postal code ({@code DALYTRAN-MERCHANT-ZIP}).
     *
     * @return the 10-character merchant ZIP code, or {@code null} if unset
     */
    public String getDalytranMerchantZip() {
        return dalytranMerchantZip;
    }

    /**
     * Sets the merchant ZIP/postal code ({@code DALYTRAN-MERCHANT-ZIP}).
     *
     * @param dalytranMerchantZip the 10-character merchant ZIP code to set
     */
    public void setDalytranMerchantZip(String dalytranMerchantZip) {
        this.dalytranMerchantZip = dalytranMerchantZip;
    }

    /**
     * Returns the card number ({@code DALYTRAN-CARD-NUM}).
     *
     * <p>This returns the full, unmasked 16-character card number; callers are
     * responsible for masking it before display or logging (see
     * {@link #toString()}, which masks it).</p>
     *
     * @return the 16-character card number, or {@code null} if unset
     */
    public String getDalytranCardNum() {
        return dalytranCardNum;
    }

    /**
     * Sets the card number ({@code DALYTRAN-CARD-NUM}).
     *
     * @param dalytranCardNum the 16-character card number to set
     */
    public void setDalytranCardNum(String dalytranCardNum) {
        this.dalytranCardNum = dalytranCardNum;
    }

    /**
     * Returns the original transaction timestamp ({@code DALYTRAN-ORIG-TS}).
     *
     * @return the original timestamp, or {@code null} if unset
     */
    public LocalDateTime getDalytranOrigTs() {
        return dalytranOrigTs;
    }

    /**
     * Sets the original transaction timestamp ({@code DALYTRAN-ORIG-TS}).
     *
     * @param dalytranOrigTs the original timestamp to set
     */
    public void setDalytranOrigTs(LocalDateTime dalytranOrigTs) {
        this.dalytranOrigTs = dalytranOrigTs;
    }

    /**
     * Returns the processed transaction timestamp ({@code DALYTRAN-PROC-TS}).
     *
     * @return the processed timestamp, or {@code null} if unset
     */
    public LocalDateTime getDalytranProcTs() {
        return dalytranProcTs;
    }

    /**
     * Sets the processed transaction timestamp ({@code DALYTRAN-PROC-TS}).
     *
     * @param dalytranProcTs the processed timestamp to set
     */
    public void setDalytranProcTs(LocalDateTime dalytranProcTs) {
        this.dalytranProcTs = dalytranProcTs;
    }

    /**
     * Identity-based equality keyed on the daily-transaction identifier
     * ({@link #dalytranId}).
     *
     * <p>Two {@code DailyTransaction} instances are equal when they are of the
     * exact same class and share the same {@link #dalytranId}. The primary key
     * alone defines entity identity; mutable business columns are deliberately
     * excluded so equality stays stable across updates. Exact-class comparison
     * (rather than {@code instanceof}) is used so a proxy/subclass is not treated
     * as equal to a different concrete type.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code DailyTransaction} with an
     *         equal id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DailyTransaction that = (DailyTransaction) o;
        return Objects.equals(dalytranId, that.dalytranId);
    }

    /**
     * Hash code derived solely from {@link #dalytranId}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code of the daily-transaction identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(dalytranId);
    }

    /**
     * Masks the card number for safe inclusion in diagnostic output, revealing
     * at most the last four characters.
     *
     * @return the masked card number, or {@code "null"} when no card number is set
     */
    private String maskedCardNumber() {
        if (dalytranCardNum == null) {
            return "null";
        }
        int length = dalytranCardNum.length();
        if (length <= 4) {
            // Too short to reveal a last-four suffix safely; mask completely.
            return "*".repeat(length);
        }
        return "*".repeat(length - 4) + dalytranCardNum.substring(length - 4);
    }

    /**
     * Diagnostic representation that intentionally <strong>excludes</strong> the
     * monetary amount ({@link #dalytranAmt}) to avoid emitting sensitive
     * financial data into logs, and <strong>masks</strong> the card number
     * ({@link #dalytranCardNum}) so the full PAN is never logged. The identifier,
     * type/category/source, description, merchant fields and timestamps are
     * included.
     *
     * @return a human-readable, non-sensitive description of this daily transaction
     */
    @Override
    public String toString() {
        return "DailyTransaction{"
                + "dalytranId='" + dalytranId + '\''
                + ", dalytranTypeCd='" + dalytranTypeCd + '\''
                + ", dalytranCatCd=" + dalytranCatCd
                + ", dalytranSource='" + dalytranSource + '\''
                + ", dalytranDesc='" + dalytranDesc + '\''
                + ", dalytranMerchantId=" + dalytranMerchantId
                + ", dalytranMerchantName='" + dalytranMerchantName + '\''
                + ", dalytranMerchantCity='" + dalytranMerchantCity + '\''
                + ", dalytranMerchantZip='" + dalytranMerchantZip + '\''
                + ", dalytranCardNum='" + maskedCardNumber() + '\''
                + ", dalytranOrigTs=" + dalytranOrigTs
                + ", dalytranProcTs=" + dalytranProcTs
                + '}';
    }
}
