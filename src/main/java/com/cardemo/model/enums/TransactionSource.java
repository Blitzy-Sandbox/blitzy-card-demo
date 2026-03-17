package com.cardemo.model.enums;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enumeration of transaction origination sources in the CardDemo application.
 *
 * <p>This enum codifies the origination sources for financial transactions,
 * mapping directly from the COBOL {@code DALYTRAN-SOURCE PIC X(10)} field
 * defined in {@code CVTRA06Y.cpy} (daily transaction record, line 8) and the
 * {@code TRAN-SOURCE PIC X(10)} field defined in {@code CVTRA05Y.cpy}
 * (transaction record, line 8).</p>
 *
 * <p>Each enum constant stores the trimmed COBOL source identifier string.
 * The original COBOL field is a 10-character, left-justified, space-padded
 * alphanumeric field. The {@link #fromCode(String)} factory method trims
 * input before lookup to handle COBOL trailing-space padding.</p>
 *
 * <h3>COBOL Source Evidence</h3>
 * <ul>
 *   <li>{@code CVTRA06Y.cpy} line 8: {@code 05 DALYTRAN-SOURCE PIC X(10).}</li>
 *   <li>{@code CVTRA05Y.cpy} line 8: {@code 05 TRAN-SOURCE PIC X(10).}</li>
 *   <li>{@code dailytran.txt} fixture data positions 23–32: "POS TERM  " and "OPERATOR  "</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <p>Used by: {@code Transaction} entity, {@code DailyTransaction} entity,
 * {@code TransactionDto} for serialization/deserialization of the transaction
 * source field.</p>
 *
 * @see com.cardemo.model.entity.Transaction
 * @see com.cardemo.model.entity.DailyTransaction
 */
public enum TransactionSource {

    /**
     * Point-of-sale terminal transaction.
     *
     * <p>COBOL value: {@code "POS TERM  "} (positions 23–32 in daily transaction records).
     * This is the most common source in the CardDemo fixture data, representing
     * purchases made at physical point-of-sale terminals.</p>
     */
    POS_TERMINAL("POS TERM"),

    /**
     * Operator-initiated transaction.
     *
     * <p>COBOL value: {@code "OPERATOR  "} (positions 23–32 in daily transaction records).
     * Represents transactions initiated by an operator, including return items
     * and manual adjustment entries.</p>
     */
    OPERATOR("OPERATOR"),

    /**
     * Online (web/application) originated transaction.
     *
     * <p>COBOL value: {@code "ONLINE    "} (10-character field, right-padded).
     * Represents transactions originating from online channels such as
     * web portals or mobile applications.</p>
     */
    ONLINE("ONLINE"),

    /**
     * ATM-originated transaction.
     *
     * <p>COBOL value: {@code "ATM       "} (10-character field, right-padded).
     * Represents transactions originating from automated teller machines,
     * including cash withdrawals and balance inquiries.</p>
     */
    ATM("ATM");

    /**
     * COBOL field width for the transaction source field ({@code PIC X(10)}).
     * Used by {@link #toCobolFormat()} to produce correctly padded output.
     */
    private static final int COBOL_FIELD_WIDTH = 10;

    /**
     * Static lookup map keyed by trimmed COBOL source identifier for O(1) lookup.
     * Built once at class load time from all enum values via {@link Arrays#stream(Object[])}.
     */
    private static final Map<String, TransactionSource> CODE_MAP =
            Arrays.stream(values())
                    .collect(Collectors.toMap(TransactionSource::getCode, Function.identity()));

    /**
     * The trimmed COBOL source identifier string.
     * Corresponds to the {@code PIC X(10)} field value with trailing spaces removed.
     */
    private final String code;

    /**
     * Constructs a {@code TransactionSource} enum constant with the given
     * trimmed COBOL source identifier.
     *
     * @param code the trimmed COBOL source identifier (e.g., "POS TERM", "OPERATOR")
     */
    TransactionSource(String code) {
        this.code = code;
    }

    /**
     * Returns the {@code TransactionSource} constant matching the given source
     * identifier string. The input is trimmed before lookup to handle COBOL
     * trailing-space padding from the {@code PIC X(10)} field.
     *
     * <p>Example usage:
     * <pre>{@code
     * // From a COBOL-format record with trailing spaces
     * TransactionSource src = TransactionSource.fromCode("POS TERM  ");
     * assert src == TransactionSource.POS_TERMINAL;
     *
     * // From a trimmed database value
     * TransactionSource src2 = TransactionSource.fromCode("OPERATOR");
     * assert src2 == TransactionSource.OPERATOR;
     * }</pre>
     *
     * @param code the transaction source identifier string (may include trailing spaces)
     * @return the matching {@code TransactionSource} enum constant
     * @throws IllegalArgumentException if the trimmed code does not match any constant
     * @throws NullPointerException if {@code code} is {@code null}
     */
    public static TransactionSource fromCode(String code) {
        if (code == null) {
            throw new NullPointerException("Transaction source code must not be null");
        }
        String trimmed = code.trim();
        TransactionSource source = CODE_MAP.get(trimmed);
        if (source == null) {
            throw new IllegalArgumentException("Unknown transaction source: " + trimmed);
        }
        return source;
    }

    /**
     * Returns the trimmed COBOL source identifier for this constant.
     *
     * <p>Examples: {@code "POS TERM"}, {@code "OPERATOR"}, {@code "ONLINE"}, {@code "ATM"}.</p>
     *
     * @return the trimmed COBOL source identifier string
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the COBOL-compatible trimmed code value for this transaction source.
     *
     * <p>This method returns the same value as {@link #getCode()} — the trimmed
     * source identifier. When writing to COBOL-format fixed-width files, use
     * {@link #toCobolFormat()} instead to obtain the correctly padded 10-character
     * representation.</p>
     *
     * @return the trimmed COBOL source identifier string
     */
    @Override
    public String toString() {
        return code;
    }

    /**
     * Returns the COBOL {@code PIC X(10)} formatted string representation of this
     * transaction source — left-justified and right-padded with spaces to exactly
     * 10 characters.
     *
     * <p>This method preserves the exact COBOL field format for file output
     * compatibility with the original mainframe record layouts.</p>
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code POS_TERMINAL.toCobolFormat()} → {@code "POS TERM  "}</li>
     *   <li>{@code OPERATOR.toCobolFormat()} → {@code "OPERATOR  "}</li>
     *   <li>{@code ONLINE.toCobolFormat()} → {@code "ONLINE    "}</li>
     *   <li>{@code ATM.toCobolFormat()} → {@code "ATM       "}</li>
     * </ul>
     *
     * @return a 10-character string matching the COBOL {@code PIC X(10)} field format
     */
    public String toCobolFormat() {
        return String.format("%-" + COBOL_FIELD_WIDTH + "s", code);
    }
}
