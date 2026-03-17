package com.cardemo.model.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.Size;

/**
 * Transaction CRUD API payload DTO.
 *
 * <p>Consolidates fields from three BMS symbolic maps:</p>
 * <ul>
 *   <li>{@code COTRN00.CPY} (COTRN0AI) — transaction list (10 rows/page)</li>
 *   <li>{@code COTRN01.CPY} (COTRN1AI) — transaction detail</li>
 *   <li>{@code COTRN02.CPY} (COTRN2AI) — transaction add</li>
 * </ul>
 *
 * <p>Maps to the {@code TRAN-RECORD} layout defined in {@code CVTRA05Y.cpy}
 * (350 bytes: 16+2+4+10+100+6+9+50+50+10+16+26+26+20 filler+5 filler).</p>
 *
 * <p>Used by {@code TransactionController} for GET/POST {@code /api/transactions/*}
 * endpoints. The COBOL programs COTRN00C.cbl (list), COTRN01C.cbl (detail), and
 * COTRN02C.cbl (add) interact with the TRANSACT VSAM KSDS dataset.</p>
 *
 * <p><strong>Precision Rule:</strong> The {@code tranAmt} field uses
 * {@link BigDecimal} to preserve exact decimal precision from COBOL
 * {@code PIC S9(09)V99 COMP-3} packed decimal. Zero {@code float}/{@code double}
 * substitution per AAP decimal precision rules.</p>
 *
 * <p><strong>PCI Security:</strong> The {@link #toString()} method masks
 * {@code tranCardNum} to show only the last 4 digits, preventing accidental
 * exposure of full card numbers in logs or debug output.</p>
 */
public class TransactionDto {

    // -----------------------------------------------------------------------
    // Fields — mapping CVTRA05Y.cpy TRAN-RECORD layout (350 bytes)
    // -----------------------------------------------------------------------

    /**
     * Transaction ID — primary key.
     * <p>Maps {@code TRAN-ID PIC X(16)} from CVTRA05Y.cpy and
     * {@code TRNIDI PIC X(16)} from COTRN01.CPY line 66.</p>
     */
    @Size(max = 16, message = "Transaction ID must not exceed 16 characters")
    private String tranId;

    /**
     * Transaction type code.
     * <p>Maps {@code TRAN-TYPE-CD PIC X(02)} from CVTRA05Y.cpy and
     * {@code TTYPCDI PIC X(2)} from COTRN01.CPY line 78.</p>
     */
    @Size(max = 2, message = "Transaction type code must not exceed 2 characters")
    private String tranTypeCd;

    /**
     * Transaction category code.
     * <p>Maps {@code TRAN-CAT-CD PIC 9(04)} from CVTRA05Y.cpy and
     * {@code TCATCDI PIC X(4)} from COTRN01.CPY line 84.</p>
     */
    @Size(max = 4, message = "Transaction category code must not exceed 4 characters")
    private String tranCatCd;

    /**
     * Transaction source identifier.
     * <p>Maps {@code TRAN-SOURCE PIC X(10)} from CVTRA05Y.cpy and
     * {@code TRNSRCI PIC X(10)} from COTRN01.CPY line 90.</p>
     */
    @Size(max = 10, message = "Transaction source must not exceed 10 characters")
    private String tranSource;

    /**
     * Transaction description.
     * <p>Maps {@code TRAN-DESC PIC X(100)} from CVTRA05Y.cpy. On the BMS detail
     * screen (COTRN01.CPY) displayed as {@code TDESCI PIC X(60)}; on the list
     * screen (COTRN00.CPY) truncated to 26 characters. Uses the larger record
     * length (100) as the constraint maximum.</p>
     */
    @Size(max = 100, message = "Transaction description must not exceed 100 characters")
    private String tranDesc;

    /**
     * Transaction amount.
     * <p>Maps {@code TRAN-AMT PIC S9(09)V99 COMP-3} from CVTRA05Y.cpy.
     * CRITICAL: Uses {@link BigDecimal} for exact decimal precision — zero
     * {@code float}/{@code double} substitution. Scale 2 for two decimal
     * positions matching COBOL {@code V99}.</p>
     */
    private BigDecimal tranAmt;

    /**
     * Card number (foreign key to card entity).
     * <p>Maps {@code TRAN-CARD-NUM PIC X(16)} from CVTRA05Y.cpy and
     * {@code CARDNUMI PIC X(16)} from COTRN01.CPY line 72.
     * Uses {@link String} to preserve leading zeros in the 16-digit card number.</p>
     */
    @Size(max = 16, message = "Card number must not exceed 16 characters")
    private String tranCardNum;

    /**
     * Merchant ID.
     * <p>Maps {@code TRAN-MERCHANT-ID PIC 9(09)} from CVTRA05Y.cpy and
     * {@code MIDI PIC X(9)} from COTRN01.CPY line 120.
     * Uses {@link String} to preserve leading zeros in the 9-digit identifier.</p>
     */
    @Size(max = 9, message = "Merchant ID must not exceed 9 characters")
    private String tranMerchId;

    /**
     * Merchant name.
     * <p>Maps {@code TRAN-MERCHANT-NAME PIC X(50)} from CVTRA05Y.cpy. On the
     * BMS screen (COTRN01.CPY) displayed as {@code MNAMEI PIC X(30)}.
     * Uses the larger record length (50) as the constraint maximum.</p>
     */
    @Size(max = 50, message = "Merchant name must not exceed 50 characters")
    private String tranMerchName;

    /**
     * Merchant city.
     * <p>Maps {@code TRAN-MERCHANT-CITY PIC X(50)} from CVTRA05Y.cpy. On the
     * BMS screen (COTRN01.CPY) displayed as {@code MCITYI PIC X(25)}.
     * Uses the larger record length (50) as the constraint maximum.</p>
     */
    @Size(max = 50, message = "Merchant city must not exceed 50 characters")
    private String tranMerchCity;

    /**
     * Merchant ZIP code.
     * <p>Maps {@code TRAN-MERCHANT-ZIP PIC X(10)} from CVTRA05Y.cpy and
     * {@code MZIPI PIC X(10)} from COTRN01.CPY line 138.</p>
     */
    @Size(max = 10, message = "Merchant ZIP must not exceed 10 characters")
    private String tranMerchZip;

    /**
     * Origination timestamp.
     * <p>Maps {@code TRAN-ORIG-TS PIC X(26)} from CVTRA05Y.cpy and
     * {@code TORIGDTI PIC X(10)} from COTRN01.CPY line 108.
     * Uses {@link LocalDateTime} to replace the 26-byte COBOL string timestamp
     * with a proper Java temporal type.</p>
     */
    private LocalDateTime tranOrigTs;

    /**
     * Processing timestamp.
     * <p>Maps {@code TRAN-PROC-TS PIC X(26)} from CVTRA05Y.cpy and
     * {@code TPROCDTI PIC X(10)} from COTRN01.CPY line 114.
     * Uses {@link LocalDateTime} to replace the 26-byte COBOL string timestamp
     * with a proper Java temporal type.</p>
     */
    private LocalDateTime tranProcTs;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * No-args constructor for framework instantiation (Jackson deserialization,
     * Spring MVC request binding).
     */
    public TransactionDto() {
    }

    /**
     * All-args constructor for programmatic construction.
     *
     * @param tranId        transaction ID, max 16 characters (PIC X(16))
     * @param tranTypeCd    transaction type code, max 2 characters (PIC X(02))
     * @param tranCatCd     transaction category code, max 4 characters (PIC 9(04))
     * @param tranSource    transaction source, max 10 characters (PIC X(10))
     * @param tranDesc      transaction description, max 100 characters (PIC X(100))
     * @param tranAmt       transaction amount as BigDecimal (PIC S9(09)V99 COMP-3)
     * @param tranCardNum   card number FK, max 16 characters (PIC X(16))
     * @param tranMerchId   merchant ID, max 9 characters (PIC 9(09))
     * @param tranMerchName merchant name, max 50 characters (PIC X(50))
     * @param tranMerchCity merchant city, max 50 characters (PIC X(50))
     * @param tranMerchZip  merchant ZIP code, max 10 characters (PIC X(10))
     * @param tranOrigTs    origination timestamp (PIC X(26))
     * @param tranProcTs    processing timestamp (PIC X(26))
     */
    public TransactionDto(String tranId, String tranTypeCd, String tranCatCd,
                          String tranSource, String tranDesc, BigDecimal tranAmt,
                          String tranCardNum, String tranMerchId, String tranMerchName,
                          String tranMerchCity, String tranMerchZip,
                          LocalDateTime tranOrigTs, LocalDateTime tranProcTs) {
        this.tranId = tranId;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranSource = tranSource;
        this.tranDesc = tranDesc;
        this.tranAmt = tranAmt;
        this.tranCardNum = tranCardNum;
        this.tranMerchId = tranMerchId;
        this.tranMerchName = tranMerchName;
        this.tranMerchCity = tranMerchCity;
        this.tranMerchZip = tranMerchZip;
        this.tranOrigTs = tranOrigTs;
        this.tranProcTs = tranProcTs;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the transaction ID.
     *
     * @return transaction ID string, max 16 characters
     */
    public String getTranId() {
        return tranId;
    }

    /**
     * Sets the transaction ID.
     *
     * @param tranId transaction ID, max 16 characters
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return transaction type code string, max 2 characters
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Sets the transaction type code.
     *
     * @param tranTypeCd transaction type code, max 2 characters
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the transaction category code.
     *
     * @return transaction category code string, max 4 characters
     */
    public String getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Sets the transaction category code.
     *
     * @param tranCatCd transaction category code, max 4 characters
     */
    public void setTranCatCd(String tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the transaction source identifier.
     *
     * @return transaction source string, max 10 characters
     */
    public String getTranSource() {
        return tranSource;
    }

    /**
     * Sets the transaction source identifier.
     *
     * @param tranSource transaction source, max 10 characters
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * Returns the transaction description.
     *
     * @return transaction description string, max 100 characters
     */
    public String getTranDesc() {
        return tranDesc;
    }

    /**
     * Sets the transaction description.
     *
     * @param tranDesc transaction description, max 100 characters
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * Returns the transaction amount as BigDecimal.
     * Scale 2 for two decimal positions matching COBOL V99.
     *
     * @return transaction amount with exact decimal precision
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * Sets the transaction amount.
     * Must be BigDecimal with scale 2 for COBOL COMP-3 precision preservation.
     *
     * @param tranAmt transaction amount as BigDecimal
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * Returns the card number (foreign key).
     *
     * @return card number string, max 16 characters
     */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /**
     * Sets the card number (foreign key).
     *
     * @param tranCardNum card number, max 16 characters
     */
    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    /**
     * Returns the merchant ID.
     *
     * @return merchant ID string, max 9 characters
     */
    public String getTranMerchId() {
        return tranMerchId;
    }

    /**
     * Sets the merchant ID.
     *
     * @param tranMerchId merchant ID, max 9 characters
     */
    public void setTranMerchId(String tranMerchId) {
        this.tranMerchId = tranMerchId;
    }

    /**
     * Returns the merchant name.
     *
     * @return merchant name string, max 50 characters
     */
    public String getTranMerchName() {
        return tranMerchName;
    }

    /**
     * Sets the merchant name.
     *
     * @param tranMerchName merchant name, max 50 characters
     */
    public void setTranMerchName(String tranMerchName) {
        this.tranMerchName = tranMerchName;
    }

    /**
     * Returns the merchant city.
     *
     * @return merchant city string, max 50 characters
     */
    public String getTranMerchCity() {
        return tranMerchCity;
    }

    /**
     * Sets the merchant city.
     *
     * @param tranMerchCity merchant city, max 50 characters
     */
    public void setTranMerchCity(String tranMerchCity) {
        this.tranMerchCity = tranMerchCity;
    }

    /**
     * Returns the merchant ZIP code.
     *
     * @return merchant ZIP string, max 10 characters
     */
    public String getTranMerchZip() {
        return tranMerchZip;
    }

    /**
     * Sets the merchant ZIP code.
     *
     * @param tranMerchZip merchant ZIP, max 10 characters
     */
    public void setTranMerchZip(String tranMerchZip) {
        this.tranMerchZip = tranMerchZip;
    }

    /**
     * Returns the origination timestamp.
     *
     * @return origination timestamp as LocalDateTime
     */
    public LocalDateTime getTranOrigTs() {
        return tranOrigTs;
    }

    /**
     * Sets the origination timestamp.
     *
     * @param tranOrigTs origination timestamp
     */
    public void setTranOrigTs(LocalDateTime tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    /**
     * Returns the processing timestamp.
     *
     * @return processing timestamp as LocalDateTime
     */
    public LocalDateTime getTranProcTs() {
        return tranProcTs;
    }

    /**
     * Sets the processing timestamp.
     *
     * @param tranProcTs processing timestamp
     */
    public void setTranProcTs(LocalDateTime tranProcTs) {
        this.tranProcTs = tranProcTs;
    }

    // -----------------------------------------------------------------------
    // toString — with PCI card number masking
    // -----------------------------------------------------------------------

    /**
     * Returns a string representation of this transaction DTO.
     * <p>The card number ({@code tranCardNum}) is masked for PCI security
     * compliance — only the last 4 digits are shown, with preceding digits
     * replaced by asterisks.</p>
     *
     * @return string representation with masked card number
     */
    @Override
    public String toString() {
        return "TransactionDto{"
                + "tranId='" + tranId + '\''
                + ", tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd='" + tranCatCd + '\''
                + ", tranSource='" + tranSource + '\''
                + ", tranDesc='" + tranDesc + '\''
                + ", tranAmt=" + tranAmt
                + ", tranCardNum='" + maskCardNumber(tranCardNum) + '\''
                + ", tranMerchId='" + tranMerchId + '\''
                + ", tranMerchName='" + tranMerchName + '\''
                + ", tranMerchCity='" + tranMerchCity + '\''
                + ", tranMerchZip='" + tranMerchZip + '\''
                + ", tranOrigTs=" + tranOrigTs
                + ", tranProcTs=" + tranProcTs
                + '}';
    }

    /**
     * Masks a card number for PCI compliance by replacing all but the last
     * 4 digits with asterisks.
     *
     * @param cardNumber the card number to mask; may be {@code null}
     * @return masked card number, {@code null} if input is {@code null},
     *         or the original string if 4 characters or fewer
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null) {
            return null;
        }
        int length = cardNumber.length();
        if (length <= 4) {
            return cardNumber;
        }
        return "*".repeat(length - 4) + cardNumber.substring(length - 4);
    }
}
