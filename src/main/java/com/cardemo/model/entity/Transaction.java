package com.cardemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity mapping the COBOL TRAN-RECORD (350 bytes) from CVTRA05Y.cpy
 * to the PostgreSQL 'transaction' table.
 *
 * <p>This is the core financial transaction record used by:
 * <ul>
 *   <li>COTRN00C — Transaction list (paginated browse, 10 rows/page)</li>
 *   <li>COTRN01C — Transaction detail (single read by TRAN-ID)</li>
 *   <li>COTRN02C — Transaction add (auto-ID generation, cross-reference resolution)</li>
 *   <li>CBTRN02C — Batch daily transaction posting (4-stage validation cascade)</li>
 *   <li>CBTRN03C — Transaction report (date-filtered reporting, enrichment)</li>
 *   <li>CBSTM03A — Statement generation (reads transactions for output)</li>
 *   <li>CBACT04C — Interest calculation (category balance updates)</li>
 * </ul>
 *
 * <p>The COBOL VSAM dataset TRANSACT has an alternate index (AIX) on the card
 * number field, modeled here as a database index on {@code tran_card_num}.
 *
 * <p>COBOL source reference: {@code app/cpy/CVTRA05Y.cpy} (commit 27d6c6f)
 *
 * <pre>
 * 01  TRAN-RECORD.
 *     05  TRAN-ID                PIC X(16).
 *     05  TRAN-TYPE-CD           PIC X(02).
 *     05  TRAN-CAT-CD            PIC 9(04).
 *     05  TRAN-SOURCE            PIC X(10).
 *     05  TRAN-DESC              PIC X(100).
 *     05  TRAN-AMT               PIC S9(09)V99.
 *     05  TRAN-MERCHANT-ID       PIC 9(09).
 *     05  TRAN-MERCHANT-NAME     PIC X(50).
 *     05  TRAN-MERCHANT-CITY     PIC X(50).
 *     05  TRAN-MERCHANT-ZIP      PIC X(10).
 *     05  TRAN-CARD-NUM          PIC X(16).
 *     05  TRAN-ORIG-TS           PIC X(26).
 *     05  TRAN-PROC-TS           PIC X(26).
 *     05  FILLER                 PIC X(20).
 * </pre>
 */
@Entity
@Table(
    name = "transactions",
    indexes = {
        @Index(name = "idx_tran_card_num", columnList = "card_num")
    }
)
public class Transaction {

    // -----------------------------------------------------------------------
    // Primary Key — TRAN-ID PIC X(16)
    // -----------------------------------------------------------------------

    /**
     * Transaction identifier. Maps COBOL TRAN-ID PIC X(16).
     * Auto-generated via browse-to-end + increment pattern in TransactionAddService.
     */
    @Id
    @Column(name = "tran_id", length = 16, nullable = false)
    private String tranId;

    // -----------------------------------------------------------------------
    // Type and Category Fields
    // -----------------------------------------------------------------------

    /**
     * Transaction type code. Maps COBOL TRAN-TYPE-CD PIC X(02).
     * Logical FK reference to TransactionType.tranType.
     */
    @Column(name = "type_cd", length = 2)
    private String tranTypeCd;

    /**
     * Transaction category code. Maps COBOL TRAN-CAT-CD PIC 9(04).
     * Stored as {@link Short} to match the DDL {@code SMALLINT} column type.
     */
    @Column(name = "cat_cd")
    private Short tranCatCd;

    // -----------------------------------------------------------------------
    // Source and Description Fields
    // -----------------------------------------------------------------------

    /**
     * Transaction source identifier. Maps COBOL TRAN-SOURCE PIC X(10).
     * Values include "POS TERM", "OPERATOR", "ONLINE", "ATM", etc.
     */
    @Column(name = "source", length = 10)
    private String tranSource;

    /**
     * Transaction description text. Maps COBOL TRAN-DESC PIC X(100).
     */
    @Column(name = "description", length = 100)
    private String tranDesc;

    // -----------------------------------------------------------------------
    // Monetary Amount — CRITICAL: BigDecimal only, ZERO float/double
    // -----------------------------------------------------------------------

    /**
     * Transaction amount. Maps COBOL TRAN-AMT PIC S9(09)V99.
     *
     * <p>CRITICAL: Uses {@link BigDecimal} with precision=11, scale=2 to guarantee
     * identical precision semantics to COBOL COMP-3 packed decimal. The sign (S)
     * means values can be negative (credits vs debits).
     *
     * <p>For comparisons, always use {@code compareTo()}, NEVER {@code equals()}
     * (which is scale-sensitive in BigDecimal).
     */
    @Column(name = "amount", precision = 11, scale = 2)
    private BigDecimal tranAmt;

    // -----------------------------------------------------------------------
    // Merchant Fields
    // -----------------------------------------------------------------------

    /**
     * Merchant identifier. Maps COBOL TRAN-MERCHANT-ID PIC 9(09).
     * Stored as String to preserve leading zeros.
     */
    @Column(name = "merchant_id", length = 9)
    private String tranMerchantId;

    /**
     * Merchant name. Maps COBOL TRAN-MERCHANT-NAME PIC X(50).
     */
    @Column(name = "merchant_name", length = 50)
    private String tranMerchantName;

    /**
     * Merchant city. Maps COBOL TRAN-MERCHANT-CITY PIC X(50).
     */
    @Column(name = "merchant_city", length = 50)
    private String tranMerchantCity;

    /**
     * Merchant ZIP code. Maps COBOL TRAN-MERCHANT-ZIP PIC X(10).
     */
    @Column(name = "merchant_zip", length = 10)
    private String tranMerchantZip;

    // -----------------------------------------------------------------------
    // Card Reference — Indexed (VSAM AIX equivalent)
    // -----------------------------------------------------------------------

    /**
     * Card number associated with this transaction.
     * Maps COBOL TRAN-CARD-NUM PIC X(16).
     *
     * <p>This field has a VSAM alternate index (AIX) in the original COBOL system,
     * modeled as {@code @Index(name = "idx_tran_card_num")} on the table definition.
     * Logical FK reference to Card.cardNum.
     */
    @Column(name = "card_num", length = 16)
    private String tranCardNum;

    // -----------------------------------------------------------------------
    // Timestamp Fields — LocalDateTime replacing COBOL PIC X(26)
    // -----------------------------------------------------------------------

    /**
     * Transaction origination timestamp.
     * Maps COBOL TRAN-ORIG-TS PIC X(26) (format: YYYY-MM-DD-HH.MM.SS.NNNNNN).
     * Java uses proper {@link LocalDateTime} type instead of a 26-character string.
     */
    @Column(name = "orig_ts")
    private LocalDateTime tranOrigTs;

    /**
     * Transaction processing timestamp — when the transaction was processed.
     * Maps COBOL TRAN-PROC-TS PIC X(26) (format: YYYY-MM-DD-HH.MM.SS.NNNNNN).
     * Java uses proper {@link LocalDateTime} type instead of a 26-character string.
     */
    @Column(name = "proc_ts")
    private LocalDateTime tranProcTs;

    // Note: COBOL FILLER PIC X(20) is NOT mapped — padding only.

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * No-args constructor required by JPA specification.
     */
    public Transaction() {
        // JPA requires a no-arg constructor
    }

    /**
     * All-args constructor for programmatic creation of Transaction instances.
     *
     * @param tranId           transaction identifier (16 chars)
     * @param tranTypeCd       transaction type code (2 chars)
     * @param tranCatCd        transaction category code (SMALLINT)
     * @param tranSource       transaction source (10 chars)
     * @param tranDesc         transaction description (100 chars)
     * @param tranAmt          transaction amount (BigDecimal, precision=11, scale=2)
     * @param tranMerchantId   merchant identifier (9 chars, leading zeros preserved)
     * @param tranMerchantName merchant name (50 chars)
     * @param tranMerchantCity merchant city (50 chars)
     * @param tranMerchantZip  merchant ZIP code (10 chars)
     * @param tranCardNum      card number (16 chars, indexed for AIX)
     * @param tranOrigTs       origination timestamp
     * @param tranProcTs       processing timestamp
     */
    public Transaction(String tranId, String tranTypeCd, Short tranCatCd,
                       String tranSource, String tranDesc, BigDecimal tranAmt,
                       String tranMerchantId, String tranMerchantName,
                       String tranMerchantCity, String tranMerchantZip,
                       String tranCardNum, LocalDateTime tranOrigTs,
                       LocalDateTime tranProcTs) {
        this.tranId = tranId;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranSource = tranSource;
        this.tranDesc = tranDesc;
        this.tranAmt = tranAmt;
        this.tranMerchantId = tranMerchantId;
        this.tranMerchantName = tranMerchantName;
        this.tranMerchantCity = tranMerchantCity;
        this.tranMerchantZip = tranMerchantZip;
        this.tranCardNum = tranCardNum;
        this.tranOrigTs = tranOrigTs;
        this.tranProcTs = tranProcTs;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public String getTranId() {
        return tranId;
    }

    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    public String getTranTypeCd() {
        return tranTypeCd;
    }

    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    public Short getTranCatCd() {
        return tranCatCd;
    }

    public void setTranCatCd(Short tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    public String getTranSource() {
        return tranSource;
    }

    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    public String getTranDesc() {
        return tranDesc;
    }

    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    public String getTranMerchantId() {
        return tranMerchantId;
    }

    public void setTranMerchantId(String tranMerchantId) {
        this.tranMerchantId = tranMerchantId;
    }

    public String getTranMerchantName() {
        return tranMerchantName;
    }

    public void setTranMerchantName(String tranMerchantName) {
        this.tranMerchantName = tranMerchantName;
    }

    public String getTranMerchantCity() {
        return tranMerchantCity;
    }

    public void setTranMerchantCity(String tranMerchantCity) {
        this.tranMerchantCity = tranMerchantCity;
    }

    public String getTranMerchantZip() {
        return tranMerchantZip;
    }

    public void setTranMerchantZip(String tranMerchantZip) {
        this.tranMerchantZip = tranMerchantZip;
    }

    public String getTranCardNum() {
        return tranCardNum;
    }

    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    public LocalDateTime getTranOrigTs() {
        return tranOrigTs;
    }

    public void setTranOrigTs(LocalDateTime tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    public LocalDateTime getTranProcTs() {
        return tranProcTs;
    }

    public void setTranProcTs(LocalDateTime tranProcTs) {
        this.tranProcTs = tranProcTs;
    }

    // -----------------------------------------------------------------------
    // equals() and hashCode() — based on primary key (tranId)
    // -----------------------------------------------------------------------

    /**
     * Equality comparison based on the {@code tranId} primary key field.
     * Follows JPA entity identity best practices.
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
     * Hash code based on the {@code tranId} primary key field.
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }

    // -----------------------------------------------------------------------
    // toString() — key fields for debugging and logging
    // -----------------------------------------------------------------------

    /**
     * String representation including key fields for debugging.
     * Includes tranId, tranTypeCd, tranAmt, tranCardNum, and tranOrigTs.
     */
    @Override
    public String toString() {
        return "Transaction{" +
                "tranId='" + tranId + '\'' +
                ", tranTypeCd='" + tranTypeCd + '\'' +
                ", tranAmt=" + tranAmt +
                ", tranCardNum='" + tranCardNum + '\'' +
                ", tranOrigTs=" + tranOrigTs +
                '}';
    }
}
