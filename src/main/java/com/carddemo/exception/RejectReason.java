package com.carddemo.exception;

import java.util.Optional;

/**
 * Transaction-posting reject reasons migrated from the COBOL batch posting engine
 * ({@code CBTRN02C}).
 *
 * <p>Each constant mirrors a {@code WS-VALIDATION-FAIL-REASON} value set during daily
 * transaction validation together with its parallel {@code WS-VALIDATION-FAIL-REASON-DESC}
 * text. The numeric {@link #getCode() code} and the {@link #getDescription() description}
 * form the fixed-width reject-record trailer contract ({@code WS-VALIDATION-TRAILER},
 * {@code PIC 9(04)} + {@code PIC X(76)} = 80 bytes). The description literals are preserved
 * verbatim so the migrated reject writer emits a byte-equivalent trailer.</p>
 */
public enum RejectReason {

    /** Code {@code 0}: transaction is valid; no failure. Description is blank (COBOL {@code SPACES}). */
    VALID(0, ""),

    /** Code {@code 100}: card number not found in the cross-reference file. */
    INVALID_CARD_NUMBER(100, "INVALID CARD NUMBER FOUND"),

    /** Code {@code 101}: account record not found during account lookup. */
    ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),

    /** Code {@code 102}: transaction would exceed the account credit limit. */
    OVERLIMIT(102, "OVERLIMIT TRANSACTION"),

    /** Code {@code 103}: transaction received after the account expiration date. */
    ACCOUNT_EXPIRED(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),

    /** Code {@code 109}: account record not found while rewriting the account during posting. */
    ACCOUNT_NOT_FOUND_ON_UPDATE(109, "ACCOUNT RECORD NOT FOUND");

    /** Fixed width of the reject-trailer description field ({@code PIC X(76)}). */
    private static final int DESCRIPTION_WIDTH = 76;

    /** Numeric reject code ({@code WS-VALIDATION-FAIL-REASON}, {@code PIC 9(04)}). */
    private final int code;

    /** Reject description exactly as emitted by the COBOL posting engine. */
    private final String description;

    RejectReason(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the numeric reject code ({@code WS-VALIDATION-FAIL-REASON}, {@code PIC 9(04)}).
     *
     * @return the reject code
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the reject description exactly as written by the COBOL posting engine.
     *
     * @return the description; an empty string for {@link #VALID} (COBOL {@code SPACES})
     */
    public String getDescription() {
        return description;
    }

    /**
     * Indicates whether this reason represents a valid transaction (no rejection).
     *
     * @return {@code true} only for {@link #VALID} (code {@code 0}); {@code false} otherwise
     */
    public boolean isValid() {
        return this == VALID;
    }

    /**
     * Renders the description into the fixed-width reject-trailer description field
     * ({@code WS-VALIDATION-FAIL-REASON-DESC}, {@code PIC X(76)}): left-justified and
     * space-padded on the right to exactly {@value #DESCRIPTION_WIDTH} characters, and
     * truncated to that width if the description is longer.
     *
     * <p>For {@link #VALID} the empty description renders as {@value #DESCRIPTION_WIDTH}
     * spaces, reproducing the COBOL {@code MOVE SPACES} behaviour.</p>
     *
     * @return the description as a {@value #DESCRIPTION_WIDTH}-character field
     */
    public String formattedDescription() {
        if (description.length() >= DESCRIPTION_WIDTH) {
            return description.substring(0, DESCRIPTION_WIDTH);
        }
        return description + " ".repeat(DESCRIPTION_WIDTH - description.length());
    }

    /**
     * Resolves a {@code RejectReason} from its numeric code.
     *
     * @param code the numeric reject code to look up
     * @return the matching reason, or {@link Optional#empty()} if no constant matches
     */
    public static Optional<RejectReason> fromCode(int code) {
        for (RejectReason reason : values()) {
            if (reason.code == code) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
