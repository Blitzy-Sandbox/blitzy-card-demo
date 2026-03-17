package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL DALYTRAN-RECORD (350 bytes) from CVTRA06Y.cpy
 * to the PostgreSQL {@code daily_transaction} staging table.
 *
 * <p>This entity mirrors the {@code Transaction} entity layout but serves as
 * the batch staging table for the {@code DailyTransactionPostingJob}. Records
 * are read from S3 (replacing the COBOL sequential DALYTRAN file), validated
 * through a 4-stage cascade (rejection codes 100–109), and upon passing
 * validation, posted to the {@code Transaction} table.</p>
 *
 * <h3>COBOL Usage Context</h3>
 * <ul>
 *   <li>CBTRN01C.cbl — Daily Transaction Validation Driver: reads DALYTRAN sequential file</li>
 *   <li>CBTRN02C.cbl — Daily Transaction Posting Engine: 4-stage validation cascade
 *       (100=card not found, 101=account not found, 102=over credit limit, 103=expired card)</li>
 *   <li>POSTTRAN.jcl — JCL job executing the CBTRN02C batch program</li>
 * </ul>
 *
 * <h3>COBOL Source Reference</h3>
 * <pre>
 * 01  DALYTRAN-RECORD.                          (RECLN = 350)
 *     05  DALYTRAN-ID                PIC X(16).
 *     05  DALYTRAN-TYPE-CD           PIC X(02).
 *     05  DALYTRAN-CAT-CD            PIC 9(04).
 *     05  DALYTRAN-SOURCE            PIC X(10).
 *     05  DALYTRAN-DESC              PIC X(100).
 *     05  DALYTRAN-AMT               PIC S9(09)V99.
 *     05  DALYTRAN-MERCHANT-ID       PIC 9(09).
 *     05  DALYTRAN-MERCHANT-NAME     PIC X(50).
 *     05  DALYTRAN-MERCHANT-CITY     PIC X(50).
 *     05  DALYTRAN-MERCHANT-ZIP      PIC X(10).
 *     05  DALYTRAN-CARD-NUM          PIC X(16).
 *     05  DALYTRAN-ORIG-TS           PIC X(26).
 *     05  DALYTRAN-PROC-TS           PIC X(26).
 *     05  FILLER                     PIC X(20).  (not mapped)
 * </pre>
 *
 * @see com.cardemo.model.entity.Transaction
 */
@Entity
@Table(name = "daily_transaction")
public class DailyTransaction {

    // -------------------------------------------------------------------------
    // Primary Key — DALYTRAN-ID PIC X(16)
    // -------------------------------------------------------------------------

    /**
     * Daily transaction identifier (16 characters).
     * Maps COBOL field {@code DALYTRAN-ID PIC X(16)}.
     */
    @Id
    @Column(name = "dalytran_id", length = 16, nullable = false)
    private String dalytranId;

    // -------------------------------------------------------------------------
    // Type and Category Fields
    // -------------------------------------------------------------------------

    /**
     * Transaction type code (2 characters).
     * Maps COBOL field {@code DALYTRAN-TYPE-CD PIC X(02)}.
     * Examples: "SA" (Sale), "RE" (Return).
     */
    @Column(name = "dalytran_type_cd", length = 2)
    private String dalytranTypeCd;

    /**
     * Transaction category code (4 characters, numeric with leading zeros).
     * Maps COBOL field {@code DALYTRAN-CAT-CD PIC 9(04)}.
     * Stored as String to preserve leading zeros (e.g., "0001").
     */
    @Column(name = "dalytran_cat_cd", length = 4)
    private String dalytranCatCd;

    // -------------------------------------------------------------------------
    // Source and Description Fields
    // -------------------------------------------------------------------------

    /**
     * Transaction source identifier (10 characters).
     * Maps COBOL field {@code DALYTRAN-SOURCE PIC X(10)}.
     * Examples: "POS TERM", "OPERATOR", "ONLINE", "ATM".
     */
    @Column(name = "dalytran_source", length = 10)
    private String dalytranSource;

    /**
     * Transaction description (100 characters).
     * Maps COBOL field {@code DALYTRAN-DESC PIC X(100)}.
     */
    @Column(name = "dalytran_desc", length = 100)
    private String dalytranDesc;

    // -------------------------------------------------------------------------
    // Monetary Amount — BigDecimal ONLY (COBOL PIC S9(09)V99)
    // -------------------------------------------------------------------------

    /**
     * Transaction amount with exact decimal precision.
     * Maps COBOL field {@code DALYTRAN-AMT PIC S9(09)V99}.
     *
     * <p><strong>CRITICAL</strong>: Uses {@link BigDecimal} exclusively — never
     * {@code float} or {@code double}. Precision 11 = 9 integer digits + 2
     * decimal digits. Signed (S) — values can be negative for credits.</p>
     */
    @Column(name = "dalytran_amt", precision = 11, scale = 2)
    private BigDecimal dalytranAmt;

    // -------------------------------------------------------------------------
    // Merchant Fields
    // -------------------------------------------------------------------------

    /**
     * Merchant identifier (9 characters, numeric with leading zeros).
     * Maps COBOL field {@code DALYTRAN-MERCHANT-ID PIC 9(09)}.
     * Stored as String to preserve leading zeros.
     */
    @Column(name = "dalytran_merchant_id", length = 9)
    private String dalytranMerchantId;

    /**
     * Merchant name (50 characters).
     * Maps COBOL field {@code DALYTRAN-MERCHANT-NAME PIC X(50)}.
     */
    @Column(name = "dalytran_merchant_name", length = 50)
    private String dalytranMerchantName;

    /**
     * Merchant city (50 characters).
     * Maps COBOL field {@code DALYTRAN-MERCHANT-CITY PIC X(50)}.
     */
    @Column(name = "dalytran_merchant_city", length = 50)
    private String dalytranMerchantCity;

    /**
     * Merchant ZIP code (10 characters).
     * Maps COBOL field {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}.
     */
    @Column(name = "dalytran_merchant_zip", length = 10)
    private String dalytranMerchantZip;

    // -------------------------------------------------------------------------
    // Card Reference
    // -------------------------------------------------------------------------

    /**
     * Card number used for cross-reference validation (16 characters).
     * Maps COBOL field {@code DALYTRAN-CARD-NUM PIC X(16)}.
     *
     * <p>During batch posting, this field is used to perform XREF lookup
     * to resolve the card-to-account mapping. Reject code 100 if lookup fails.</p>
     */
    @Column(name = "dalytran_card_num", length = 16)
    private String dalytranCardNum;

    // -------------------------------------------------------------------------
    // Timestamp Fields
    // -------------------------------------------------------------------------

    /**
     * Origination timestamp of the daily transaction.
     * Maps COBOL field {@code DALYTRAN-ORIG-TS PIC X(26)}.
     * COBOL stores as 26-character timestamp string; Java uses proper
     * {@link LocalDateTime} temporal type.
     */
    @Column(name = "dalytran_orig_ts")
    private LocalDateTime dalytranOrigTs;

    /**
     * Processing timestamp of the daily transaction.
     * Maps COBOL field {@code DALYTRAN-PROC-TS PIC X(26)}.
     * COBOL stores as 26-character timestamp string; Java uses proper
     * {@link LocalDateTime} temporal type.
     */
    @Column(name = "dalytran_proc_ts")
    private LocalDateTime dalytranProcTs;

    // FILLER PIC X(20) — not mapped (COBOL record padding)

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * No-args constructor required by JPA specification.
     */
    public DailyTransaction() {
        // JPA requires a no-args constructor
    }

    /**
     * All-args constructor for programmatic entity creation.
     *
     * @param dalytranId           transaction identifier (16 chars)
     * @param dalytranTypeCd       transaction type code (2 chars)
     * @param dalytranCatCd        transaction category code (4 chars, leading zeros)
     * @param dalytranSource       transaction source (10 chars)
     * @param dalytranDesc         transaction description (100 chars)
     * @param dalytranAmt          transaction amount (BigDecimal, precision 11, scale 2)
     * @param dalytranMerchantId   merchant identifier (9 chars, leading zeros)
     * @param dalytranMerchantName merchant name (50 chars)
     * @param dalytranMerchantCity merchant city (50 chars)
     * @param dalytranMerchantZip  merchant ZIP code (10 chars)
     * @param dalytranCardNum      card number for XREF lookup (16 chars)
     * @param dalytranOrigTs       origination timestamp
     * @param dalytranProcTs       processing timestamp
     */
    public DailyTransaction(String dalytranId,
                            String dalytranTypeCd,
                            String dalytranCatCd,
                            String dalytranSource,
                            String dalytranDesc,
                            BigDecimal dalytranAmt,
                            String dalytranMerchantId,
                            String dalytranMerchantName,
                            String dalytranMerchantCity,
                            String dalytranMerchantZip,
                            String dalytranCardNum,
                            LocalDateTime dalytranOrigTs,
                            LocalDateTime dalytranProcTs) {
        this.dalytranId = dalytranId;
        this.dalytranTypeCd = dalytranTypeCd;
        this.dalytranCatCd = dalytranCatCd;
        this.dalytranSource = dalytranSource;
        this.dalytranDesc = dalytranDesc;
        this.dalytranAmt = dalytranAmt;
        this.dalytranMerchantId = dalytranMerchantId;
        this.dalytranMerchantName = dalytranMerchantName;
        this.dalytranMerchantCity = dalytranMerchantCity;
        this.dalytranMerchantZip = dalytranMerchantZip;
        this.dalytranCardNum = dalytranCardNum;
        this.dalytranOrigTs = dalytranOrigTs;
        this.dalytranProcTs = dalytranProcTs;
    }

    // -------------------------------------------------------------------------
    // Getters and Setters
    // -------------------------------------------------------------------------

    public String getDalytranId() {
        return dalytranId;
    }

    public void setDalytranId(String dalytranId) {
        this.dalytranId = dalytranId;
    }

    public String getDalytranTypeCd() {
        return dalytranTypeCd;
    }

    public void setDalytranTypeCd(String dalytranTypeCd) {
        this.dalytranTypeCd = dalytranTypeCd;
    }

    public String getDalytranCatCd() {
        return dalytranCatCd;
    }

    public void setDalytranCatCd(String dalytranCatCd) {
        this.dalytranCatCd = dalytranCatCd;
    }

    public String getDalytranSource() {
        return dalytranSource;
    }

    public void setDalytranSource(String dalytranSource) {
        this.dalytranSource = dalytranSource;
    }

    public String getDalytranDesc() {
        return dalytranDesc;
    }

    public void setDalytranDesc(String dalytranDesc) {
        this.dalytranDesc = dalytranDesc;
    }

    public BigDecimal getDalytranAmt() {
        return dalytranAmt;
    }

    public void setDalytranAmt(BigDecimal dalytranAmt) {
        this.dalytranAmt = dalytranAmt;
    }

    public String getDalytranMerchantId() {
        return dalytranMerchantId;
    }

    public void setDalytranMerchantId(String dalytranMerchantId) {
        this.dalytranMerchantId = dalytranMerchantId;
    }

    public String getDalytranMerchantName() {
        return dalytranMerchantName;
    }

    public void setDalytranMerchantName(String dalytranMerchantName) {
        this.dalytranMerchantName = dalytranMerchantName;
    }

    public String getDalytranMerchantCity() {
        return dalytranMerchantCity;
    }

    public void setDalytranMerchantCity(String dalytranMerchantCity) {
        this.dalytranMerchantCity = dalytranMerchantCity;
    }

    public String getDalytranMerchantZip() {
        return dalytranMerchantZip;
    }

    public void setDalytranMerchantZip(String dalytranMerchantZip) {
        this.dalytranMerchantZip = dalytranMerchantZip;
    }

    public String getDalytranCardNum() {
        return dalytranCardNum;
    }

    public void setDalytranCardNum(String dalytranCardNum) {
        this.dalytranCardNum = dalytranCardNum;
    }

    public LocalDateTime getDalytranOrigTs() {
        return dalytranOrigTs;
    }

    public void setDalytranOrigTs(LocalDateTime dalytranOrigTs) {
        this.dalytranOrigTs = dalytranOrigTs;
    }

    public LocalDateTime getDalytranProcTs() {
        return dalytranProcTs;
    }

    public void setDalytranProcTs(LocalDateTime dalytranProcTs) {
        this.dalytranProcTs = dalytranProcTs;
    }

    // -------------------------------------------------------------------------
    // equals, hashCode, toString — identity based on dalytranId (primary key)
    // -------------------------------------------------------------------------

    /**
     * Compares this daily transaction entity for equality based on the primary key
     * ({@code dalytranId}). Follows JPA best practice for entity identity comparison.
     *
     * @param o the object to compare against
     * @return {@code true} if the objects have the same primary key
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
     * Returns a hash code based on the primary key ({@code dalytranId}).
     *
     * @return hash code for this entity
     */
    @Override
    public int hashCode() {
        return Objects.hash(dalytranId);
    }

    /**
     * Returns a string representation including key fields for debugging.
     * Includes transaction ID, type, amount, card number, and timestamps.
     *
     * @return string representation of this daily transaction
     */
    @Override
    public String toString() {
        return "DailyTransaction{" +
                "dalytranId='" + dalytranId + '\'' +
                ", dalytranTypeCd='" + dalytranTypeCd + '\'' +
                ", dalytranCatCd='" + dalytranCatCd + '\'' +
                ", dalytranSource='" + dalytranSource + '\'' +
                ", dalytranAmt=" + dalytranAmt +
                ", dalytranMerchantId='" + dalytranMerchantId + '\'' +
                ", dalytranCardNum='" + dalytranCardNum + '\'' +
                ", dalytranOrigTs=" + dalytranOrigTs +
                ", dalytranProcTs=" + dalytranProcTs +
                '}';
    }
}
