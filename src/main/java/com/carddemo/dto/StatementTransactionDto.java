package com.carddemo.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Flattened statement / report transaction line.
 *
 * <p>Direct migration of the legacy COBOL copybook {@code COSTM01} record
 * {@code TRNX-RECORD} — described in the copybook header as the
 * "Transaction altered Layout for use in reporting" — referenced against the
 * frozen COBOL source at commit SHA {@code 27d6c6f} (full HEAD
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}). Because the COBOL source is
 * never copied into the target repository, this class is the Java-side
 * traceability anchor for {@code COSTM01.TRNX-RECORD}.</p>
 *
 * <p>The DTO is a single denormalized transaction line assembled for output. It
 * backs two migrated flows:</p>
 * <ul>
 *   <li>the statement-generation batch job (legacy {@code CBSTM03A} /
 *       {@code CBSTM03B}); and</li>
 *   <li>the transaction-report flow (legacy {@code CBTRN03C} and the
 *       {@code CORPT00C} report bridge).</li>
 * </ul>
 *
 * <h2>Field mapping ({@code COSTM01 TRNX-RECORD})</h2>
 * <table class="striped">
 *   <caption>COBOL picture to Java component mapping</caption>
 *   <thead>
 *     <tr><th>Java component</th><th>COBOL field</th><th>COBOL picture</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code cardNumber}</td><td>{@code TRNX-CARD-NUM}</td><td>{@code X(16)}</td></tr>
 *     <tr><td>{@code transactionId}</td><td>{@code TRNX-ID}</td><td>{@code X(16)}</td></tr>
 *     <tr><td>{@code typeCode}</td><td>{@code TRNX-TYPE-CD}</td><td>{@code X(02)}</td></tr>
 *     <tr><td>{@code categoryCode}</td><td>{@code TRNX-CAT-CD}</td><td>{@code 9(04)}</td></tr>
 *     <tr><td>{@code source}</td><td>{@code TRNX-SOURCE}</td><td>{@code X(10)}</td></tr>
 *     <tr><td>{@code description}</td><td>{@code TRNX-DESC}</td><td>{@code X(100)}</td></tr>
 *     <tr><td>{@code amount}</td><td>{@code TRNX-AMT}</td><td>{@code S9(09)V99}</td></tr>
 *     <tr><td>{@code merchantId}</td><td>{@code TRNX-MERCHANT-ID}</td><td>{@code 9(09)}</td></tr>
 *     <tr><td>{@code merchantName}</td><td>{@code TRNX-MERCHANT-NAME}</td><td>{@code X(50)}</td></tr>
 *     <tr><td>{@code merchantCity}</td><td>{@code TRNX-MERCHANT-CITY}</td><td>{@code X(50)}</td></tr>
 *     <tr><td>{@code merchantZip}</td><td>{@code TRNX-MERCHANT-ZIP}</td><td>{@code X(10)}</td></tr>
 *     <tr><td>{@code originalTimestamp}</td><td>{@code TRNX-ORIG-TS}</td><td>{@code X(26)}</td></tr>
 *     <tr><td>{@code processedTimestamp}</td><td>{@code TRNX-PROC-TS}</td><td>{@code X(26)}</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>The trailing {@code FILLER PIC X(20)} of {@code TRNX-RECORD} carries no
 * business meaning and is intentionally not mapped.</p>
 *
 * <h2>Migration contracts</h2>
 * <ul>
 *   <li><strong>Decimal fidelity (AAP §0.8.2).</strong> {@code TRNX-AMT} is a
 *       signed packed-style {@code S9(09)V99} value and is mapped to
 *       {@link java.math.BigDecimal} with a fixed scale of {@value #AMOUNT_SCALE}.
 *       Floating-point types ({@code float}/{@code double}) are prohibited for
 *       monetary values. The canonical constructor normalizes the scale using
 *       {@link java.math.RoundingMode#HALF_UP}, so {@code 5} is stored as
 *       {@code 5.00}.</li>
 *   <li><strong>Timestamp contract (Gates 1 &amp; 5).</strong>
 *       {@code originalTimestamp} and {@code processedTimestamp} are preserved as
 *       raw 26-character {@link String}s. The exact {@code X(26)} textual format
 *       is an external interface contract and is deliberately <em>not</em> parsed
 *       into {@link java.time.LocalDateTime}; the string must round-trip
 *       unchanged.</li>
 *   <li><strong>Identifier fidelity.</strong> {@code transactionId},
 *       {@code categoryCode} and {@code merchantId} are kept as {@link String}s
 *       to preserve fixed width and any leading zeros, exactly as stored in the
 *       fixed-width record.</li>
 *   <li><strong>PAN masking.</strong> {@code cardNumber} holds the full Primary
 *       Account Number and is populated only inside internal batch / statement
 *       generation, where the complete PAN is required. The full PAN must
 *       <em>never</em> be emitted in a REST JSON body. Any REST-facing flow must
 *       expose the last-four masked form via {@link #maskedCardNumber()} or emit
 *       {@link #toMaskedView()} instead of this record. This is a documented
 *       usage contract rather than a serialization-level suppression, so the
 *       natural (batch) JSON representation still contains all thirteen keys.</li>
 * </ul>
 *
 * <p>This type is a stateless, immutable value holder. As a Java {@code record}
 * with only {@code final} components it is inherently thread-safe and safe to
 * share across batch worker threads.</p>
 *
 * @param cardNumber          full Primary Account Number ({@code TRNX-CARD-NUM},
 *                            {@code X(16)}); mask via {@link #maskedCardNumber()}
 *                            before any REST exposure
 * @param transactionId       transaction identifier ({@code TRNX-ID},
 *                            {@code X(16)})
 * @param typeCode            transaction type code ({@code TRNX-TYPE-CD},
 *                            {@code X(02)})
 * @param categoryCode        transaction category code ({@code TRNX-CAT-CD},
 *                            {@code 9(04)}); numeric string, width preserved
 * @param source              origination source channel ({@code TRNX-SOURCE},
 *                            {@code X(10)})
 * @param description         free-text description ({@code TRNX-DESC},
 *                            {@code X(100)})
 * @param amount              signed monetary amount ({@code TRNX-AMT},
 *                            {@code S9(09)V99}); {@link java.math.BigDecimal} of
 *                            scale {@value #AMOUNT_SCALE}
 * @param merchantId          merchant identifier ({@code TRNX-MERCHANT-ID},
 *                            {@code 9(09)}); numeric string, width preserved
 * @param merchantName        merchant name ({@code TRNX-MERCHANT-NAME},
 *                            {@code X(50)})
 * @param merchantCity        merchant city ({@code TRNX-MERCHANT-CITY},
 *                            {@code X(50)})
 * @param merchantZip         merchant postal code ({@code TRNX-MERCHANT-ZIP},
 *                            {@code X(10)})
 * @param originalTimestamp   originating 26-char timestamp string
 *                            ({@code TRNX-ORIG-TS}, {@code X(26)})
 * @param processedTimestamp  processing 26-char timestamp string
 *                            ({@code TRNX-PROC-TS}, {@code X(26)})
 */
public record StatementTransactionDto(

        @Size(max = 16) String cardNumber,

        @Size(max = 16) String transactionId,

        @Size(max = 2) String typeCode,

        @Size(max = 4) @Pattern(regexp = "\\d{1,4}") String categoryCode,

        @Size(max = 10) String source,

        @Size(max = 100) String description,

        @Digits(integer = 9, fraction = 2) BigDecimal amount,

        @Size(max = 9) @Pattern(regexp = "\\d{1,9}") String merchantId,

        @Size(max = 50) String merchantName,

        @Size(max = 50) String merchantCity,

        @Size(max = 10) String merchantZip,

        @Size(max = 26) String originalTimestamp,

        @Size(max = 26) String processedTimestamp
) {

    /**
     * Fixed decimal scale for the monetary {@code amount}, matching the
     * {@code V99} fraction of the source {@code TRNX-AMT PIC S9(09)V99}.
     */
    private static final int AMOUNT_SCALE = 2;

    /** Character used to obscure the leading digits of a masked PAN. */
    private static final String MASK_CHARACTER = "*";

    /** Number of trailing PAN digits left visible when masking. */
    private static final int VISIBLE_PAN_DIGITS = 4;

    /**
     * Canonical (compact) constructor.
     *
     * <p>Normalizes {@code amount} to the fixed scale of
     * {@value #AMOUNT_SCALE} using {@link java.math.RoundingMode#HALF_UP} so that
     * every instance carries a value with the same decimal precision as the
     * COBOL {@code V99} picture (for example {@code 5} becomes {@code 5.00}). A
     * {@code null} amount is preserved as {@code null} — absence is a distinct
     * state from a zero balance. All other components are accepted verbatim to
     * preserve the exact fixed-width contract of {@code TRNX-RECORD}.</p>
     */
    public StatementTransactionDto {
        if (amount != null) {
            amount = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * Returns the {@code cardNumber} with all but the last
     * {@value #VISIBLE_PAN_DIGITS} characters replaced by
     * {@code '*'}, for safe REST/log exposure.
     *
     * <p>Surrounding whitespace introduced by fixed-width space padding is
     * stripped before masking. When the (stripped) value is {@code null} or no
     * longer than {@value #VISIBLE_PAN_DIGITS} characters, it is returned as-is,
     * since there are no additional leading digits to obscure. Example:
     * {@code "1234567890123456"} becomes {@code "************3456"}.</p>
     *
     * @return the masked PAN, or {@code null} when {@code cardNumber} is
     *         {@code null}
     */
    public String maskedCardNumber() {
        if (cardNumber == null) {
            return null;
        }
        final String normalized = cardNumber.strip();
        final int length = normalized.length();
        if (length <= VISIBLE_PAN_DIGITS) {
            return normalized;
        }
        final String lastDigits = normalized.substring(length - VISIBLE_PAN_DIGITS);
        return MASK_CHARACTER.repeat(length - VISIBLE_PAN_DIGITS) + lastDigits;
    }

    /**
     * Produces a REST-safe copy of this line in which {@code cardNumber} is
     * replaced by its last-four masked form (see {@link #maskedCardNumber()}).
     *
     * <p>All other components — including the full-precision {@code amount} and
     * the raw timestamp strings — are carried across unchanged. Use this factory
     * whenever a statement line leaves the internal batch boundary and is about
     * to be serialized into a REST JSON body; the full PAN must never appear in
     * such a response.</p>
     *
     * @return a new {@code StatementTransactionDto} with a masked
     *         {@code cardNumber}
     */
    public StatementTransactionDto toMaskedView() {
        return new StatementTransactionDto(
                maskedCardNumber(),
                transactionId,
                typeCode,
                categoryCode,
                source,
                description,
                amount,
                merchantId,
                merchantName,
                merchantCity,
                merchantZip,
                originalTimestamp,
                processedTimestamp
        );
    }
}
