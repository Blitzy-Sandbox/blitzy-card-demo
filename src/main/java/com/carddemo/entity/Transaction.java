package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * JPA entity for a posted transaction, migrated field-by-field from the COBOL
 * copybook {@code CVTRA05Y} ({@code TRAN-RECORD}, record length 350 bytes,
 * source commit SHA {@code 27d6c6f}).
 *
 * <p>This is the record persisted to the KSDS {@code TRANSACT} file in the
 * legacy system: written by the batch posting pipeline ({@code CBTRN02C}) and
 * read by the online transaction programs ({@code COTRN00C} /
 * {@code COTRN01C} / {@code COTRN02C}). It maps to the unquoted
 * {@code transaction} table (a non-reserved identifier in PostgreSQL 16),
 * whose column contract is owned by the Flyway {@code V1__schema.sql}
 * migration and validated against this mapping under
 * {@code spring.jpa.hibernate.ddl-auto: validate}.</p>
 *
 * <p>Fidelity notes carried over from the copybook:</p>
 * <ul>
 *   <li>{@code TRAN-AMT} ({@code PIC S9(09)V99}) is a signed monetary value and
 *       is represented as {@link java.math.BigDecimal} with
 *       {@code precision = 11, scale = 2} ({@code NUMERIC(11,2)}). Floating-point
 *       types are never used for financial fields, and the sign is preserved.</li>
 *   <li>{@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} ({@code PIC X(26)}) are kept
 *       as 26-character {@link String} values, not {@code LocalDateTime}, so that
 *       the legacy timestamp text ({@code yyyy-mm-dd-hh.mm.ss.ffffff}) is preserved
 *       verbatim for interface-contract parity.</li>
 *   <li>The trailing copybook {@code FILLER PIC X(20)} is intentionally not mapped;
 *       it is fixed-width padding with no domain meaning.</li>
 * </ul>
 *
 * <p>Byte-origin check (copybook field widths):
 * 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + FILLER 20 = 350.</p>
 */
@Entity
@Table(name = "transaction")
public class Transaction {

    /** {@code TRAN-ID PIC X(16)} — natural primary key of the transaction record. */
    @Id
    @Column(name = "tran_id", length = 16, nullable = false)
    private String tranId;

    /** {@code TRAN-TYPE-CD PIC X(02)} — transaction type code. */
    @Column(name = "tran_type_cd", length = 2)
    private String tranTypeCd;

    /** {@code TRAN-CAT-CD PIC 9(04)} — transaction category code. */
    @Column(name = "tran_cat_cd")
    private Integer tranCatCd;

    /** {@code TRAN-SOURCE PIC X(10)} — origination source of the transaction. */
    @Column(name = "tran_source", length = 10)
    private String tranSource;

    /** {@code TRAN-DESC PIC X(100)} — free-text transaction description. */
    @Column(name = "tran_desc", length = 100)
    private String tranDesc;

    /**
     * {@code TRAN-AMT PIC S9(09)V99} — signed transaction amount.
     * Mapped to {@code NUMERIC(11,2)}; never a floating-point type.
     */
    @Column(name = "tran_amt", precision = 11, scale = 2)
    private BigDecimal tranAmt;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} — merchant identifier. */
    @Column(name = "tran_merchant_id")
    private Long tranMerchantId;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} — merchant name. */
    @Column(name = "tran_merchant_name", length = 50)
    private String tranMerchantName;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} — merchant city. */
    @Column(name = "tran_merchant_city", length = 50)
    private String tranMerchantCity;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} — merchant postal (ZIP) code. */
    @Column(name = "tran_merchant_zip", length = 10)
    private String tranMerchantZip;

    /** {@code TRAN-CARD-NUM PIC X(16)} — card number (kept as a scalar, no relationship). */
    @Column(name = "tran_card_num", length = 16)
    private String tranCardNum;

    /**
     * {@code TRAN-ORIG-TS PIC X(26)} — origination timestamp text
     * ({@code yyyy-mm-dd-hh.mm.ss.ffffff}), preserved verbatim as a 26-char string.
     */
    @Column(name = "tran_orig_ts", length = 26)
    private String tranOrigTs;

    /**
     * {@code TRAN-PROC-TS PIC X(26)} — processing timestamp text
     * ({@code yyyy-mm-dd-hh.mm.ss.ffffff}), preserved verbatim as a 26-char string.
     */
    @Column(name = "tran_proc_ts", length = 26)
    private String tranProcTs;

    /**
     * Protected/public no-argument constructor required by the JPA provider for
     * entity instantiation and proxy generation.
     */
    public Transaction() {
        // No-arg constructor for JPA.
    }

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

    public Integer getTranCatCd() {
        return tranCatCd;
    }

    public void setTranCatCd(Integer tranCatCd) {
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

    public Long getTranMerchantId() {
        return tranMerchantId;
    }

    public void setTranMerchantId(Long tranMerchantId) {
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

    public String getTranOrigTs() {
        return tranOrigTs;
    }

    public void setTranOrigTs(String tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    public String getTranProcTs() {
        return tranProcTs;
    }

    public void setTranProcTs(String tranProcTs) {
        this.tranProcTs = tranProcTs;
    }
}
