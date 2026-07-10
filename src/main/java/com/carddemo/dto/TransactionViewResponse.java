package com.carddemo.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.validation.constraints.Size;

/**
 * Immutable response payload for the transaction detail view — online transaction
 * {@code CT01} ("Txn View").
 *
 * <p>This DTO is the Java 25 / Spring Boot translation of the CICS/BMS online program
 * {@code COTRN01C} and its symbolic map {@code COTRN01}
 * (see {@code app/cpy-bms/COTRN01.CPY}). Its field set and maximum widths are derived
 * from the transaction record layout {@code TRAN-RECORD} defined in
 * {@code app/cpy/CVTRA05Y.cpy} (record length 350). The COBOL source is referenced
 * — never copied — at commit SHA {@code 27d6c6f}
 * (full {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}).</p>
 *
 * <p>The BMS map exposes narrower <em>screen</em> widths for some fields (for example
 * {@code TDESC} is {@code X(60)} on the screen), but the persisted <em>record</em>
 * widths from {@code CVTRA05Y} are authoritative for validation, so every
 * {@link Size} constraint below reflects the record width.</p>
 *
 * <h2>Translation and safety notes</h2>
 * <ul>
 *   <li><strong>Decimal fidelity:</strong> {@code amount} maps the packed-decimal
 *       {@code TRAN-AMT PIC S9(09)V99} to a {@link BigDecimal} normalized to
 *       {@link #AMOUNT_SCALE scale 2}. Floating-point types are never used for
 *       monetary values (AAP §0.8.2 — zero {@code float}/{@code double} in financial
 *       fields).</li>
 *   <li><strong>Timestamp preservation:</strong> {@code originalTimestamp} and
 *       {@code processedTimestamp} map the 26-character {@code TRAN-ORIG-TS} /
 *       {@code TRAN-PROC-TS} fields and are kept as {@link String} so the exact
 *       on-file 26-character format survives the round-trip unchanged.</li>
 *   <li><strong>PAN masking:</strong> {@code cardNumber} is masked so that only the
 *       last {@value #VISIBLE_CARD_DIGITS} characters remain visible; all preceding
 *       characters are replaced with {@value #MASK_CHARACTER} so the full card number
 *       is never exposed in an API response. Masking is applied by the canonical
 *       constructor, so callers may pass the raw card number directly.</li>
 *   <li><strong>Identifiers as text:</strong> numeric COBOL identifier fields
 *       ({@code TRAN-CAT-CD 9(04)}, {@code TRAN-MERCHANT-ID 9(09)}) are represented as
 *       {@link String} to preserve fixed-width, zero-padded formatting.</li>
 * </ul>
 *
 * <p>As a {@code record} this type is immutable and therefore inherently stateless and
 * thread-safe. {@code null} component values are preserved as {@code null} so the DTO
 * can faithfully represent absent fields.</p>
 *
 * @param transactionId      transaction identifier — {@code TRAN-ID} / {@code TRNID}, {@code X(16)}
 * @param cardNumber         masked card number (last {@value #VISIBLE_CARD_DIGITS} characters
 *                           visible) — {@code TRAN-CARD-NUM} / {@code CARDNUM}, {@code X(16)}
 * @param typeCode           transaction type code — {@code TRAN-TYPE-CD} / {@code TTYPCD}, {@code X(02)}
 * @param categoryCode       transaction category code — {@code TRAN-CAT-CD} / {@code TCATCD}, {@code 9(04)}
 * @param source             transaction source channel — {@code TRAN-SOURCE} / {@code TRNSRC}, {@code X(10)}
 * @param description        transaction description — {@code TRAN-DESC} / {@code TDESC}, {@code X(100)}
 * @param amount             transaction amount at scale 2 — {@code TRAN-AMT} / {@code TRNAMT}, {@code S9(09)V99}
 * @param originalTimestamp  original timestamp, 26-character format preserved — {@code TRAN-ORIG-TS} /
 *                           {@code TORIGDT}, {@code X(26)}
 * @param processedTimestamp processed timestamp, 26-character format preserved — {@code TRAN-PROC-TS} /
 *                           {@code TPROCDT}, {@code X(26)}
 * @param merchantId         merchant identifier — {@code TRAN-MERCHANT-ID} / {@code MID}, {@code 9(09)}
 * @param merchantName       merchant name — {@code TRAN-MERCHANT-NAME} / {@code MNAME}, {@code X(50)}
 * @param merchantCity       merchant city — {@code TRAN-MERCHANT-CITY} / {@code MCITY}, {@code X(50)}
 * @param merchantZip        merchant ZIP code — {@code TRAN-MERCHANT-ZIP} / {@code MZIP}, {@code X(10)}
 */
public record TransactionViewResponse(
        @Size(max = 16) String transactionId,
        @Size(max = 16) String cardNumber,
        @Size(max = 2) String typeCode,
        @Size(max = 4) String categoryCode,
        @Size(max = 10) String source,
        @Size(max = 100) String description,
        BigDecimal amount,
        @Size(max = 26) String originalTimestamp,
        @Size(max = 26) String processedTimestamp,
        @Size(max = 9) String merchantId,
        @Size(max = 50) String merchantName,
        @Size(max = 50) String merchantCity,
        @Size(max = 10) String merchantZip) {

    /** Number of trailing characters left visible when masking a card number. */
    private static final int VISIBLE_CARD_DIGITS = 4;

    /** Fixed scale applied to monetary amounts, matching the COBOL {@code Vnn} = 2 picture. */
    private static final int AMOUNT_SCALE = 2;

    /** Character substituted for each masked (hidden) card-number position. */
    private static final char MASK_CHARACTER = '*';

    /**
     * Canonical (compact) constructor that enforces this record's invariants:
     * <ul>
     *   <li>{@code amount} is normalized to {@link #AMOUNT_SCALE} using
     *       {@link RoundingMode#HALF_UP}, reproducing COBOL fixed-point scaling and
     *       guaranteeing a consistent {@code NUMERIC(p,2)} representation; and</li>
     *   <li>{@code cardNumber} is masked so that only the last
     *       {@value #VISIBLE_CARD_DIGITS} characters remain visible.</li>
     * </ul>
     * {@code null} values are preserved as {@code null} so absent fields are represented
     * faithfully rather than defaulted.
     */
    public TransactionViewResponse {
        if (amount != null) {
            amount = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        cardNumber = maskCardNumber(cardNumber);
    }

    /**
     * Masks a card number so that only the last {@value #VISIBLE_CARD_DIGITS}
     * characters remain visible, replacing every preceding character with
     * {@value #MASK_CHARACTER}.
     *
     * <p>The operation is <em>idempotent</em>: re-masking an already-masked value
     * yields the same result, because only the trailing characters are ever retained.
     * Values that are {@code null}, or no longer than {@value #VISIBLE_CARD_DIGITS}
     * characters, are returned unchanged because there is nothing to hide.</p>
     *
     * @param rawCardNumber the raw or previously masked card number; may be {@code null}
     * @return the masked card number, or the original value when masking does not apply
     */
    private static String maskCardNumber(String rawCardNumber) {
        if (rawCardNumber == null) {
            return null;
        }
        int length = rawCardNumber.length();
        if (length <= VISIBLE_CARD_DIGITS) {
            return rawCardNumber;
        }
        int maskedCount = length - VISIBLE_CARD_DIGITS;
        StringBuilder masked = new StringBuilder(length);
        for (int i = 0; i < maskedCount; i++) {
            masked.append(MASK_CHARACTER);
        }
        masked.append(rawCardNumber, maskedCount, length);
        return masked.toString();
    }
}
