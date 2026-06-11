package com.cardemo.model.enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Type-safe catalogue of the batch daily-transaction validation reject codes
 * emitted by the CardDemo posting program.
 *
 * <p>This enum is the Java 25 replacement for the COBOL working-storage pair
 * {@code WS-VALIDATION-FAIL-REASON} / {@code WS-VALIDATION-FAIL-REASON-DESC}
 * declared in the batch posting program {@code app/cbl/CBTRN02C.cbl}
 * (lines 181-182: {@code 05 WS-VALIDATION-FAIL-REASON PIC 9(04)} and
 * {@code 05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}). In the legacy program
 * the numeric reason code and its 76-byte description are written into the
 * {@code VALIDATION-TRAILER} of every {@code REJECT-RECORD}; this enum binds
 * each numeric code to that exact description so the reject-file output remains
 * byte-faithful and parity-validates against the nine ASCII fixtures.</p>
 *
 * <p><strong>Authoritative, exhaustive value set.</strong> A full sweep of the
 * entire {@code app/cbl/} estate confirms that {@code CBTRN02C.cbl} is the only
 * program that references {@code WS-VALIDATION-FAIL-REASON}, and that it ever
 * moves exactly five non-zero reason codes &mdash; {@code 100}, {@code 101},
 * {@code 102}, {@code 103} and {@code 109} &mdash; plus the zero sentinel that
 * marks "no failure". Per the migration's Minimal Change Clause the value set is
 * reproduced <strong>exactly</strong>:</p>
 *
 * <ul>
 *   <li>{@code 100} &mdash; {@code 1500-A-LOOKUP-XREF}, card cross-reference not
 *       found on the keyed {@code READ XREF-FILE ... INVALID KEY}
 *       (lines 385-387).</li>
 *   <li>{@code 101} &mdash; {@code 1500-B-LOOKUP-ACCT}, account not found on the
 *       keyed {@code READ ACCOUNT-FILE ... INVALID KEY} (lines 397-399).</li>
 *   <li>{@code 102} &mdash; {@code 1500-B-LOOKUP-ACCT}, over-limit transaction
 *       when {@code ACCT-CREDIT-LIMIT < (ACCT-CURR-CYC-CREDIT -
 *       ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)} (lines 410-412).</li>
 *   <li>{@code 103} &mdash; {@code 1500-B-LOOKUP-ACCT}, transaction received
 *       after account expiration when {@code ACCT-EXPIRAION-DATE <
 *       DALYTRAN-ORIG-TS(1:10)} (lines 417-419).</li>
 *   <li>{@code 109} &mdash; {@code 2800-UPDATE-ACCOUNT-REC}, account not found
 *       on the balance {@code REWRITE ... INVALID KEY} (lines 556-558).</li>
 * </ul>
 *
 * <p><strong>Codes 104, 105, 106, 107 and 108 are intentionally omitted</strong>
 * because they do not exist anywhere in the COBOL source. Although the target
 * tree's folder note labels this enum "Batch rejection codes 100-109", that is
 * merely the nominal numeric range of the {@code PIC 9(04)} field, not the
 * implemented set; inventing the missing codes would break the reject-file
 * parity contract, since {@code CBTRN02C.cbl} never emits them.</p>
 *
 * <p><strong>Distinct codes sharing a description.</strong> Codes {@code 101}
 * and {@code 109} deliberately carry the identical description text
 * {@code "ACCOUNT RECORD NOT FOUND"} yet originate from different paragraphs and
 * I/O operations (a keyed {@code READ} versus a balance {@code REWRITE}). They
 * are preserved as two distinct constants; consequently this enum is keyed only
 * by the integer reason code and deliberately offers no reverse lookup by
 * description, which would be ambiguous.</p>
 *
 * <p><strong>Role in the architecture.</strong> This enum is a deliberately
 * dependency-free leaf consumed by the batch validation cascade (the
 * transaction-posting {@code ItemProcessor}) and the reject-record
 * {@code ItemWriter}, and it is the value catalogue that the typed
 * {@code com.cardemo.exception.*} hierarchy mirrors &mdash;
 * {@code CreditLimitExceededException} carries reject code {@code 102} and
 * {@code ExpiredCardException} carries reject code {@code 103}. That
 * relationship is intentionally by value (matching integer constants enforced by
 * tests), <em>not</em> by code coupling: this enum imports nothing beyond
 * {@code java.lang} and {@code java.util}, holds no mutable state and performs
 * no I/O.</p>
 *
 * <p><strong>Traceability:</strong> derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference and is
 * never copied into this repository.</p>
 */
public enum RejectCode {

    /**
     * No validation failure &mdash; the transaction is accepted for posting.
     *
     * <p>Models the COBOL zero/clear sentinel: {@code CBTRN02C.cbl} performs
     * {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} (line 208) before validating
     * each record, then routes on {@code IF WS-VALIDATION-FAIL-REASON = 0}
     * (line 211) to {@code 2000-POST-TRANSACTION}. Representing the cleared
     * state as a first-class constant lets the validation processor express
     * "accepted" without resorting to {@code null}.</p>
     */
    // CBTRN02C L208 (MOVE 0 TO WS-VALIDATION-FAIL-REASON) / L211
    // (IF WS-VALIDATION-FAIL-REASON = 0 -> 2000-POST-TRANSACTION): the cleared
    // "no failure" sentinel that precedes every transaction validation.
    NONE(0, "No validation failure"),

    /**
     * Card cross-reference not found.
     *
     * <p>Maps the COBOL {@code MOVE 100 TO WS-VALIDATION-FAIL-REASON} in
     * paragraph {@code 1500-A-LOOKUP-XREF}, taken on the
     * {@code READ XREF-FILE ... INVALID KEY} path when the daily-transaction
     * card number has no entry in the card cross-reference dataset.</p>
     */
    // CBTRN02C L385-387 (1500-A-LOOKUP-XREF; READ XREF-FILE INVALID KEY).
    INVALID_CARD_NUMBER(100, "INVALID CARD NUMBER FOUND"),

    /**
     * Account record not found on the keyed read.
     *
     * <p>Maps the COBOL {@code MOVE 101 TO WS-VALIDATION-FAIL-REASON} in
     * paragraph {@code 1500-B-LOOKUP-ACCT}, taken on the
     * {@code READ ACCOUNT-FILE ... INVALID KEY} path when the account referenced
     * by the resolved cross-reference does not exist.</p>
     */
    // CBTRN02C L397-399 (1500-B-LOOKUP-ACCT; READ ACCOUNT-FILE INVALID KEY).
    ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),

    /**
     * Over-limit transaction.
     *
     * <p>Maps the COBOL {@code MOVE 102 TO WS-VALIDATION-FAIL-REASON} in
     * paragraph {@code 1500-B-LOOKUP-ACCT}, taken when the account's credit
     * limit cannot absorb the transaction, i.e. when
     * {@code ACCT-CREDIT-LIMIT < (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT +
     * DALYTRAN-AMT)}.</p>
     */
    // CBTRN02C L410-412 (1500-B-LOOKUP-ACCT; credit-limit check).
    // By-value match to com.cardemo.exception.CreditLimitExceededException.REJECT_CODE.
    OVERLIMIT_TRANSACTION(102, "OVERLIMIT TRANSACTION"),

    /**
     * Transaction received after account expiration.
     *
     * <p>Maps the COBOL {@code MOVE 103 TO WS-VALIDATION-FAIL-REASON} in
     * paragraph {@code 1500-B-LOOKUP-ACCT}, taken when the transaction's
     * origination date falls after the account expiration date, i.e. when
     * {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)}.</p>
     */
    // CBTRN02C L417-419 (1500-B-LOOKUP-ACCT; expiration check).
    // By-value match to com.cardemo.exception.ExpiredCardException.REJECT_CODE.
    TRANSACTION_AFTER_EXPIRATION(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),

    /**
     * Account record not found on the balance rewrite.
     *
     * <p>Maps the COBOL {@code MOVE 109 TO WS-VALIDATION-FAIL-REASON} in
     * paragraph {@code 2800-UPDATE-ACCOUNT-REC}, taken on the
     * {@code REWRITE ... INVALID KEY} path when the account disappears between
     * the initial read and the balance-update rewrite. This shares the
     * description text of {@link #ACCOUNT_NOT_FOUND} (code {@code 101}) but is a
     * distinct code with a distinct origin (rewrite rather than read), so both
     * are preserved.</p>
     */
    // CBTRN02C L556-558 (2800-UPDATE-ACCOUNT-REC; REWRITE FD-ACCTFILE-REC INVALID KEY).
    ACCOUNT_NOT_FOUND_ON_UPDATE(109, "ACCOUNT RECORD NOT FOUND");

    /**
     * The exact numeric reject reason code.
     *
     * <p>This value preserves the integer emitted into the COBOL
     * {@code WS-VALIDATION-FAIL-REASON} {@code PIC 9(04)} field and is part of
     * the external reject-file interface contract.</p>
     */
    // COBOL substitution: the PIC 9(04) WS-VALIDATION-FAIL-REASON numeric reason
    // code is represented as an int to preserve exact fidelity with the
    // reject-file contract.
    private final int code;

    /**
     * The exact reject reason description text.
     *
     * <p>This value preserves, verbatim, the literal moved into the COBOL
     * {@code WS-VALIDATION-FAIL-REASON-DESC} {@code PIC X(76)} field and is part
     * of the external reject-file interface contract.</p>
     */
    // COBOL substitution: the PIC X(76) WS-VALIDATION-FAIL-REASON-DESC text is
    // preserved verbatim so the reject-record trailer round-trips byte-faithfully.
    private final String description;

    /**
     * Binds each constant to its byte-faithful COBOL reject code and
     * description. Enum constructors are implicitly private.
     *
     * @param code        the exact numeric reject reason code emitted into the
     *                    COBOL {@code WS-VALIDATION-FAIL-REASON} field
     * @param description the exact reject reason text moved into the COBOL
     *                    {@code WS-VALIDATION-FAIL-REASON-DESC} field
     */
    RejectCode(final int code, final String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Immutable {@code code -> RejectCode} index used for O(1) lookup by
     * {@link #fromCode(int)}.
     *
     * <p>The map is populated in a static initializer that runs after every
     * enum constant has been constructed (avoiding the enum-constructor /
     * static-field ordering pitfall), and is wrapped with
     * {@link Collections#unmodifiableMap(Map)} so no mutable static state is
     * exposed. The numeric codes therefore live in exactly one place &mdash; on
     * the enum constants themselves.</p>
     */
    private static final Map<Integer, RejectCode> BY_CODE;

    static {
        final Map<Integer, RejectCode> lookup = new HashMap<>();
        for (final RejectCode rejectCode : values()) {
            lookup.put(rejectCode.code, rejectCode);
        }
        BY_CODE = Collections.unmodifiableMap(lookup);
    }

    /**
     * Returns the exact numeric reject reason code for this constant.
     *
     * @return the reject code (for example {@code 102} for
     *         {@link #OVERLIMIT_TRANSACTION}, or {@code 0} for {@link #NONE})
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the exact reject reason description for this constant.
     *
     * @return the reject description preserved verbatim from the COBOL
     *         {@code WS-VALIDATION-FAIL-REASON-DESC} literal (for example
     *         {@code "OVERLIMIT TRANSACTION"})
     */
    public String getDescription() {
        return description;
    }

    /**
     * Resolves a {@code RejectCode} from its numeric reject reason code.
     *
     * <p>The lookup is keyed solely by the integer code, mirroring the COBOL
     * {@code WS-VALIDATION-FAIL-REASON} numeric comparison. Only the codes the
     * estate actually emits resolve &mdash; {@code 0}, {@code 100}, {@code 101},
     * {@code 102}, {@code 103} and {@code 109}; any other value (including the
     * never-emitted {@code 104}-{@code 108}) is rejected, because it indicates a
     * reason code the posting program never produces. There is intentionally no
     * reverse lookup by description, because codes {@code 101} and {@code 109}
     * share the same description text.</p>
     *
     * @param code the numeric reject reason code to resolve
     * @return the matching {@code RejectCode}
     * @throws IllegalArgumentException if {@code code} matches no known reject
     *         code
     */
    public static RejectCode fromCode(final int code) {
        final RejectCode rejectCode = BY_CODE.get(code);
        if (rejectCode == null) {
            throw new IllegalArgumentException("Unknown reject code: " + code);
        }
        return rejectCode;
    }

    /**
     * Indicates whether this constant represents a rejection (as opposed to the
     * accepted/cleared state).
     *
     * <p>Mirrors the COBOL {@code IF WS-VALIDATION-FAIL-REASON = 0} branch
     * (CBTRN02C.cbl line 211): a zero reason code routes the transaction to
     * posting, while any non-zero code routes it to the reject writer. This
     * predicate returns {@code true} for every reject reason and {@code false}
     * only for {@link #NONE}.</p>
     *
     * @return {@code true} if this is a rejection reason; {@code false} if this
     *         is {@link #NONE}
     */
    public boolean isRejection() {
        return this != NONE;
    }
}
