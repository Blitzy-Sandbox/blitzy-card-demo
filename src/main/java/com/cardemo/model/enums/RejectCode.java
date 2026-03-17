package com.cardemo.model.enums;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Batch transaction rejection codes from COBOL program CBTRN02C.cbl
 * (Daily Transaction Posting Engine).
 *
 * <p>These rejection codes are emitted during the 4-stage validation cascade
 * in the batch posting flow and written to the daily rejects file (DALYREJS)
 * with a validation trailer containing a numeric reason code (PIC 9(04))
 * and a description (PIC X(76)).</p>
 *
 * <p>COBOL source reference: {@code app/cbl/CBTRN02C.cbl}
 * (commit SHA 27d6c6f), paragraphs 1500-VALIDATE-TRAN and
 * 2800-UPDATE-ACCOUNT-REC.</p>
 *
 * <p>Validation cascade stages:</p>
 * <ol>
 *   <li>1500-A-LOOKUP-XREF — Cross-reference file lookup by card number (code 100)</li>
 *   <li>1500-B-LOOKUP-ACCT — Account file lookup by account ID (code 101)</li>
 *   <li>1500-B-LOOKUP-ACCT — Credit limit check (code 102)</li>
 *   <li>1500-B-LOOKUP-ACCT — Account expiration date check (code 103)</li>
 *   <li>2800-UPDATE-ACCOUNT-REC — Account rewrite failure (code 109)</li>
 * </ol>
 *
 * <p>Note: Codes 101 and 109 intentionally share the same description text
 * {@code "ACCOUNT RECORD NOT FOUND"} — this matches the original COBOL source exactly.</p>
 */
public enum RejectCode {

    /**
     * Cross-reference file lookup failed — card number not found in XREF-FILE.
     * COBOL paragraph: 1500-A-LOOKUP-XREF (lines 380-392).
     * WS-VALIDATION-FAIL-REASON = 100.
     */
    XREF_NOT_FOUND(100, "INVALID CARD NUMBER FOUND"),

    /**
     * Account file lookup failed — account ID from cross-reference not found in ACCOUNT-FILE.
     * COBOL paragraph: 1500-B-LOOKUP-ACCT (lines 393-399).
     * WS-VALIDATION-FAIL-REASON = 101.
     */
    ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),

    /**
     * Credit limit exceeded — computed balance (current cycle credit - debit + transaction amount)
     * exceeds account credit limit (ACCT-CREDIT-LIMIT).
     * COBOL paragraph: 1500-B-LOOKUP-ACCT (lines 407-413).
     * WS-VALIDATION-FAIL-REASON = 102.
     */
    CREDIT_LIMIT_EXCEEDED(102, "OVERLIMIT TRANSACTION"),

    /**
     * Card/account expired — account expiration date is earlier than the transaction
     * origination timestamp (DALYTRAN-ORIG-TS first 10 characters).
     * COBOL paragraph: 1500-B-LOOKUP-ACCT (lines 414-420).
     * WS-VALIDATION-FAIL-REASON = 103.
     */
    CARD_EXPIRED(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),

    /**
     * Account record rewrite failure during the posting phase — REWRITE of ACCOUNT-FILE
     * returned INVALID KEY after the record was successfully read earlier.
     * COBOL paragraph: 2800-UPDATE-ACCOUNT-REC (lines 554-558).
     * WS-VALIDATION-FAIL-REASON = 109.
     *
     * <p>Note: This shares the same description as {@link #ACCOUNT_NOT_FOUND} (code 101),
     * which is intentional per the COBOL source.</p>
     */
    ACCOUNT_REWRITE_ERROR(109, "ACCOUNT RECORD NOT FOUND");

    /**
     * Static lookup map for O(1) retrieval of {@code RejectCode} constants by their
     * numeric COBOL rejection code. Built once from {@link #values()} using
     * {@link Arrays#stream(Object[])} and {@link Collectors#toMap(Function, Function)}.
     */
    private static final Map<Integer, RejectCode> CODE_MAP =
            Arrays.stream(values())
                    .collect(Collectors.toMap(RejectCode::getCode, Function.identity()));

    /**
     * The numeric rejection code matching COBOL WS-VALIDATION-FAIL-REASON (PIC 9(04)).
     * Valid values: 100, 101, 102, 103, 109.
     */
    private final int code;

    /**
     * The human-readable rejection description matching COBOL
     * WS-VALIDATION-FAIL-REASON-DESC (PIC X(76)).
     * Strings are exact copies of the COBOL source literals (all uppercase).
     */
    private final String description;

    /**
     * Constructs a {@code RejectCode} enum constant with the specified COBOL
     * numeric rejection code and description.
     *
     * @param code        the numeric rejection code (PIC 9(04), values 100-109)
     * @param description the rejection description (PIC X(76), exact COBOL literal)
     */
    RejectCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the {@code RejectCode} constant matching the given numeric COBOL
     * rejection code. Uses O(1) map lookup.
     *
     * @param code the numeric rejection code (e.g., 100, 101, 102, 103, 109)
     * @return the matching {@code RejectCode} constant
     * @throws IllegalArgumentException if the code does not correspond to any
     *                                  defined rejection code
     */
    public static RejectCode fromCode(int code) {
        RejectCode rejectCode = CODE_MAP.get(code);
        if (rejectCode == null) {
            throw new IllegalArgumentException("Unknown reject code: " + code);
        }
        return rejectCode;
    }

    /**
     * Returns the numeric rejection code matching the COBOL
     * WS-VALIDATION-FAIL-REASON field (PIC 9(04)).
     *
     * @return the numeric code (100, 101, 102, 103, or 109)
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the human-readable rejection description matching the COBOL
     * WS-VALIDATION-FAIL-REASON-DESC field (PIC X(76)).
     *
     * @return the description string in uppercase, exactly as defined in COBOL source
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the numeric code as a zero-padded 4-character string to match
     * the COBOL PIC 9(04) format used in the validation trailer record.
     *
     * <p>Examples: {@code "0100"}, {@code "0101"}, {@code "0102"},
     * {@code "0103"}, {@code "0109"}.</p>
     *
     * @return the zero-padded 4-digit string representation of the rejection code
     */
    @Override
    public String toString() {
        return String.format("%04d", code);
    }
}
