package com.carddemo.model.enums;

/**
 * Transaction source classification.
 *
 * <p>Java equivalent of the COBOL {@code DALYTRAN-SOURCE} / {@code TRAN-SOURCE}
 * field (copybook {@code CVTRA06Y}, {@code DALYTRAN-SOURCE PIC X(10)}; data
 * fixtures {@code app/data/ASCII/dailytran.txt}; source commit
 * {@code 27d6c6f}). The field is a 10-byte, space-padded alphanumeric source
 * classifier carried on the daily-transaction record and propagated onto the
 * posted transaction record.
 *
 * <p>The only values present in the source data are {@code "POS TERM"} and
 * {@code "OPERATOR"}. Each constant stores its canonical (trimmed) business
 * literal; the fixed-width, right-padded form (for example {@code "POS TERM  "})
 * is reconciled by {@link #fromValue(String)}, which trims its input before
 * matching.
 */
public enum TransactionSource {

    /**
     * Point-of-sale terminal source. Stored value {@code "POS TERM"} (single
     * internal space); fixed-width source form {@code "POS TERM  "}.
     */
    POS_TERMINAL("POS TERM"),

    /**
     * Operator-entered source. Stored value {@code "OPERATOR"}; fixed-width
     * source form {@code "OPERATOR  "}.
     */
    OPERATOR("OPERATOR");

    /**
     * The canonical (trimmed) source classification literal as it appears in
     * the daily-transaction data, with the COBOL fixed-width right-padding
     * removed.
     */
    private final String value;

    TransactionSource(String value) {
        this.value = value;
    }

    /**
     * Returns the canonical (trimmed) source classification literal backing
     * this constant.
     *
     * @return the stored source value, never {@code null}
     */
    public String getValue() {
        return value;
    }

    /**
     * Resolves a raw {@code TRAN-SOURCE} / {@code DALYTRAN-SOURCE} value to its
     * enum constant.
     *
     * <p>The input is trimmed before comparison so the fixed-width
     * {@code PIC X(10)} form read by the daily-transaction parser (for example
     * {@code "POS TERM  "} or {@code "OPERATOR  "}) resolves to the same
     * constant as the trimmed literal. Matching is case-sensitive, matching the
     * uppercase convention of the source data.
     *
     * @param value the source value; may be {@code null} or space-padded
     * @return the matching constant, or {@code null} if {@code value} is
     *         {@code null} or does not map to a known source
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
