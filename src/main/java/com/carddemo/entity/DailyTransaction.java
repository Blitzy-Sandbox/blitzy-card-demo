package com.carddemo.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity representing a single <strong>daily (unposted) transaction</strong>
 * staging record.
 *
 * <p>This entity is a field-by-field translation of the legacy COBOL copybook
 * {@code app/cpy/CVTRA06Y.cpy} ({@code DALYTRAN-RECORD}, fixed record length
 * <strong>350</strong> bytes), referenced at source commit SHA {@code 27d6c6f}.
 * Daily transactions are the {@code DALYTRAN} input consumed by the batch
 * posting pipeline (legacy program {@code CBTRN02C}) before they are promoted to
 * posted {@code Transaction} rows.</p>
 *
 * <p>Although the physical layout is byte-identical to {@code CVTRA05Y} (the
 * posted-transaction copybook), the staging record uses a distinct
 * {@code DALYTRAN-} field prefix and is therefore persisted to its own separate
 * table ({@code daily_transaction}) with its own entity — staging versus posted
 * are modelled independently.</p>
 *
 * <p><strong>Decimal fidelity:</strong> the monetary field {@code DALYTRAN-AMT}
 * ({@code PIC S9(09)V99}) is mapped to {@link java.math.BigDecimal} with
 * precision 11 and scale 2 — never {@code float} or {@code double} — preserving
 * the signed packed-decimal semantics of the COBOL source through to the
 * {@code NUMERIC(11,2)} database column.</p>
 *
 * <p><strong>Timestamps:</strong> {@code DALYTRAN-ORIG-TS} and
 * {@code DALYTRAN-PROC-TS} ({@code PIC X(26)}) are retained as fixed 26-character
 * {@link String} values to preserve the exact on-file textual format; they are
 * intentionally not converted to {@code java.time} types.</p>
 *
 * <p>The trailing {@code FILLER PIC X(20)} of the copybook is intentionally not
 * mapped; it is pure record padding that carries no business data (the 13 mapped
 * fields plus the 20-byte filler account for the full 350-byte record).</p>
 *
 * <p>This is a pure persistence record: it declares no entity relationships, no
 * optimistic-lock {@code @Version}, and no business logic, in keeping with the
 * migration's no-feature-expansion constraint. The primary key is the natural
 * key {@code dalytranId}; it is assigned by the caller (no {@code @GeneratedValue}).
 * The sibling Spring Data repository declares
 * {@code JpaRepository<DailyTransaction, String>}.</p>
 */
@Entity
@Table(name = "daily_transaction")
public class DailyTransaction {

    /**
     * Unique transaction identifier and primary key of the staging record.
     * <p>COBOL: {@code DALYTRAN-ID PIC X(16)}.</p>
     */
    @Id
    @Column(name = "dalytran_id", length = 16, nullable = false)
    private String dalytranId;

    /**
     * Transaction type code (references the transaction-type reference data).
     * <p>COBOL: {@code DALYTRAN-TYPE-CD PIC X(02)}.</p>
     */
    @Column(name = "dalytran_type_cd", length = 2)
    private String dalytranTypeCd;

    /**
     * Transaction category code (references the transaction-category reference data).
     * <p>COBOL: {@code DALYTRAN-CAT-CD PIC 9(04)} — a four-digit numeric code
     * mapped to {@link Integer}.</p>
     */
    @Column(name = "dalytran_cat_cd")
    private Integer dalytranCatCd;

    /**
     * Origin/source channel of the transaction.
     * <p>COBOL: {@code DALYTRAN-SOURCE PIC X(10)}.</p>
     */
    @Column(name = "dalytran_source", length = 10)
    private String dalytranSource;

    /**
     * Free-form transaction description.
     * <p>COBOL: {@code DALYTRAN-DESC PIC X(100)}.</p>
     */
    @Column(name = "dalytran_desc", length = 100)
    private String dalytranDesc;

    /**
     * Signed transaction amount with two decimal places.
     * <p>COBOL: {@code DALYTRAN-AMT PIC S9(09)V99} → {@link BigDecimal} with
     * precision 11 and scale 2. This is a monetary value and must never be held
     * as {@code float}/{@code double}; the sign is preserved.</p>
     */
    @Column(name = "dalytran_amt", precision = 11, scale = 2)
    private BigDecimal dalytranAmt;

    /**
     * Identifier of the merchant that originated the transaction.
     * <p>COBOL: {@code DALYTRAN-MERCHANT-ID PIC 9(09)} — a nine-digit numeric
     * identifier mapped to {@link Long}.</p>
     */
    @Column(name = "dalytran_merchant_id")
    private Long dalytranMerchantId;

    /**
     * Merchant name.
     * <p>COBOL: {@code DALYTRAN-MERCHANT-NAME PIC X(50)}.</p>
     */
    @Column(name = "dalytran_merchant_name", length = 50)
    private String dalytranMerchantName;

    /**
     * Merchant city.
     * <p>COBOL: {@code DALYTRAN-MERCHANT-CITY PIC X(50)}.</p>
     */
    @Column(name = "dalytran_merchant_city", length = 50)
    private String dalytranMerchantCity;

    /**
     * Merchant postal (ZIP) code.
     * <p>COBOL: {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}.</p>
     */
    @Column(name = "dalytran_merchant_zip", length = 10)
    private String dalytranMerchantZip;

    /**
     * Card number associated with the transaction.
     * <p>COBOL: {@code DALYTRAN-CARD-NUM PIC X(16)}.</p>
     */
    @Column(name = "dalytran_card_num", length = 16)
    private String dalytranCardNum;

    /**
     * Original timestamp of the transaction, retained as a fixed 26-character string.
     * <p>COBOL: {@code DALYTRAN-ORIG-TS PIC X(26)}. Kept as {@link String} to
     * preserve the exact on-file format.</p>
     */
    @Column(name = "dalytran_orig_ts", length = 26)
    private String dalytranOrigTs;

    /**
     * Processing timestamp of the transaction, retained as a fixed 26-character string.
     * <p>COBOL: {@code DALYTRAN-PROC-TS PIC X(26)}. Kept as {@link String} to
     * preserve the exact on-file format.</p>
     */
    @Column(name = "dalytran_proc_ts", length = 26)
    private String dalytranProcTs;

    /**
     * Default no-argument constructor required by the JPA specification for
     * entity instantiation and proxy creation. Intentionally empty: fields are
     * populated by the persistence provider or via the generated setters.
     */
    public DailyTransaction() {
        // Required by JPA; no initialization logic is needed for a staging record.
    }

    /**
     * Returns the transaction identifier (primary key).
     *
     * @return the daily-transaction id ({@code DALYTRAN-ID})
     */
    public String getDalytranId() {
        return dalytranId;
    }

    /**
     * Sets the transaction identifier (primary key).
     *
     * @param dalytranId the daily-transaction id ({@code DALYTRAN-ID})
     */
    public void setDalytranId(String dalytranId) {
        this.dalytranId = dalytranId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return the transaction type code ({@code DALYTRAN-TYPE-CD})
     */
    public String getDalytranTypeCd() {
        return dalytranTypeCd;
    }

    /**
     * Sets the transaction type code.
     *
     * @param dalytranTypeCd the transaction type code ({@code DALYTRAN-TYPE-CD})
     */
    public void setDalytranTypeCd(String dalytranTypeCd) {
        this.dalytranTypeCd = dalytranTypeCd;
    }

    /**
     * Returns the transaction category code.
     *
     * @return the transaction category code ({@code DALYTRAN-CAT-CD})
     */
    public Integer getDalytranCatCd() {
        return dalytranCatCd;
    }

    /**
     * Sets the transaction category code.
     *
     * @param dalytranCatCd the transaction category code ({@code DALYTRAN-CAT-CD})
     */
    public void setDalytranCatCd(Integer dalytranCatCd) {
        this.dalytranCatCd = dalytranCatCd;
    }

    /**
     * Returns the origin/source channel of the transaction.
     *
     * @return the transaction source ({@code DALYTRAN-SOURCE})
     */
    public String getDalytranSource() {
        return dalytranSource;
    }

    /**
     * Sets the origin/source channel of the transaction.
     *
     * @param dalytranSource the transaction source ({@code DALYTRAN-SOURCE})
     */
    public void setDalytranSource(String dalytranSource) {
        this.dalytranSource = dalytranSource;
    }

    /**
     * Returns the free-form transaction description.
     *
     * @return the transaction description ({@code DALYTRAN-DESC})
     */
    public String getDalytranDesc() {
        return dalytranDesc;
    }

    /**
     * Sets the free-form transaction description.
     *
     * @param dalytranDesc the transaction description ({@code DALYTRAN-DESC})
     */
    public void setDalytranDesc(String dalytranDesc) {
        this.dalytranDesc = dalytranDesc;
    }

    /**
     * Returns the signed transaction amount (scale 2).
     *
     * @return the transaction amount ({@code DALYTRAN-AMT})
     */
    public BigDecimal getDalytranAmt() {
        return dalytranAmt;
    }

    /**
     * Sets the signed transaction amount (scale 2).
     *
     * @param dalytranAmt the transaction amount ({@code DALYTRAN-AMT})
     */
    public void setDalytranAmt(BigDecimal dalytranAmt) {
        this.dalytranAmt = dalytranAmt;
    }

    /**
     * Returns the originating merchant identifier.
     *
     * @return the merchant id ({@code DALYTRAN-MERCHANT-ID})
     */
    public Long getDalytranMerchantId() {
        return dalytranMerchantId;
    }

    /**
     * Sets the originating merchant identifier.
     *
     * @param dalytranMerchantId the merchant id ({@code DALYTRAN-MERCHANT-ID})
     */
    public void setDalytranMerchantId(Long dalytranMerchantId) {
        this.dalytranMerchantId = dalytranMerchantId;
    }

    /**
     * Returns the merchant name.
     *
     * @return the merchant name ({@code DALYTRAN-MERCHANT-NAME})
     */
    public String getDalytranMerchantName() {
        return dalytranMerchantName;
    }

    /**
     * Sets the merchant name.
     *
     * @param dalytranMerchantName the merchant name ({@code DALYTRAN-MERCHANT-NAME})
     */
    public void setDalytranMerchantName(String dalytranMerchantName) {
        this.dalytranMerchantName = dalytranMerchantName;
    }

    /**
     * Returns the merchant city.
     *
     * @return the merchant city ({@code DALYTRAN-MERCHANT-CITY})
     */
    public String getDalytranMerchantCity() {
        return dalytranMerchantCity;
    }

    /**
     * Sets the merchant city.
     *
     * @param dalytranMerchantCity the merchant city ({@code DALYTRAN-MERCHANT-CITY})
     */
    public void setDalytranMerchantCity(String dalytranMerchantCity) {
        this.dalytranMerchantCity = dalytranMerchantCity;
    }

    /**
     * Returns the merchant postal (ZIP) code.
     *
     * @return the merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP})
     */
    public String getDalytranMerchantZip() {
        return dalytranMerchantZip;
    }

    /**
     * Sets the merchant postal (ZIP) code.
     *
     * @param dalytranMerchantZip the merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP})
     */
    public void setDalytranMerchantZip(String dalytranMerchantZip) {
        this.dalytranMerchantZip = dalytranMerchantZip;
    }

    /**
     * Returns the card number associated with the transaction.
     *
     * @return the card number ({@code DALYTRAN-CARD-NUM})
     */
    public String getDalytranCardNum() {
        return dalytranCardNum;
    }

    /**
     * Sets the card number associated with the transaction.
     *
     * @param dalytranCardNum the card number ({@code DALYTRAN-CARD-NUM})
     */
    public void setDalytranCardNum(String dalytranCardNum) {
        this.dalytranCardNum = dalytranCardNum;
    }

    /**
     * Returns the original transaction timestamp (fixed 26-character string).
     *
     * @return the original timestamp ({@code DALYTRAN-ORIG-TS})
     */
    public String getDalytranOrigTs() {
        return dalytranOrigTs;
    }

    /**
     * Sets the original transaction timestamp (fixed 26-character string).
     *
     * @param dalytranOrigTs the original timestamp ({@code DALYTRAN-ORIG-TS})
     */
    public void setDalytranOrigTs(String dalytranOrigTs) {
        this.dalytranOrigTs = dalytranOrigTs;
    }

    /**
     * Returns the processing transaction timestamp (fixed 26-character string).
     *
     * @return the processing timestamp ({@code DALYTRAN-PROC-TS})
     */
    public String getDalytranProcTs() {
        return dalytranProcTs;
    }

    /**
     * Sets the processing transaction timestamp (fixed 26-character string).
     *
     * @param dalytranProcTs the processing timestamp ({@code DALYTRAN-PROC-TS})
     */
    public void setDalytranProcTs(String dalytranProcTs) {
        this.dalytranProcTs = dalytranProcTs;
    }
}
