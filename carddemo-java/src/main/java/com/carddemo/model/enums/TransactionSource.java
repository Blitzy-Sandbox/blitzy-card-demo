package com.carddemo.model.enums;

/**
 * Transaction source classification carried by a daily-transaction record.
 *
 * <p>This enum is the Java equivalent of the COBOL {@code DALYTRAN-SOURCE} field
 * (copybook {@code CVTRA06Y}, a fixed-width {@code PIC X(10)} alphanumeric) which
 * flows unchanged into the transaction record's {@code TRAN-SOURCE} field. It is
 * used while parsing the daily-transaction fixtures and mapping them onto the
 * {@code DailyTransaction} and {@code Transaction} models (source commit
 * {@code 27d6c6f}).</p>
 *
 * <p>The source data exposes exactly two classifications: {@code "POS TERM"}
 * (point-of-sale terminal) and {@code "OPERATOR"}. Each constant stores the
 * canonical business literal with the COBOL right-padding removed; the internal
 * single space in {@code "POS TERM"} is significant and preserved.</p>
 */
public enum TransactionSource {

    /**
     * Point-of-sale terminal source. Stored as the trimmed business literal
     * {@code "POS TERM"} (fixed-width source form {@code "POS TERM  "}).
     */
    POS_TERMINAL("POS TERM"),

    /**
     * Operator-entered source. Stored as the trimmed business literal
     * {@code "OPERATOR"} (fixed-width source form {@code "OPERATOR  "}).
     */
    OPERATOR("OPERATOR");

    /**
     * The canonical, trimmed source classification literal exactly as it appears
     * in the daily-transaction data (right-padding removed; internal spacing
     * preserved).
     */
    private final String value;

    /**
     * Binds a constant to its canonical (trimmed) source literal.
     *
     * @param value the canonical source classification literal
     */
    TransactionSource(String value) {
        this.value = value;
    }

    /**
     * Returns the canonical, trimmed source classification literal for this
     * constant (for example {@code "POS TERM"} or {@code "OPERATOR"}).
     *
     * @return the stored source classification literal
     */
    public String getValue() {
        return value;
    }

    /**
     * Resolves a raw {@code TRAN-SOURCE} / {@code DALYTRAN-SOURCE} value to its
     * enum constant. The input is trimmed before comparison so that the
     * fixed-width {@code PIC X(10)} form read by the daily-transaction parser
     * (for example {@code "POS TERM  "}) resolves to the same constant as the
     * trimmed literal. Matching is case-sensitive.
     *
     * @param value the source value to resolve; may be {@code null} or
     *              space-padded
     * @return the matching {@link TransactionSource}, or {@code null} if the
     *         value is {@code null} or does not map to a known constant
     */
    public static TransactionSource fromValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (TransactionSource source : values()) {
            if (source.value.equals(trimmed)) {
                return source;
            }
        }
        return null;
    }
}
