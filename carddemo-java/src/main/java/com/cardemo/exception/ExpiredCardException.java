package com.cardemo.exception;

import java.io.Serial;
import java.time.LocalDate;

/**
 * Concrete domain exception signalling that a daily transaction was received
 * <em>after</em> its owning account had already expired &mdash; the Java&nbsp;25
 * mapping of CardDemo batch <strong>reject code {@code 103}</strong>
 * ({@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}) raised by the daily
 * transaction posting program.
 *
 * <p>On the legacy mainframe the batch posting program
 * {@code app/cbl/CBTRN02C.cbl} validated every incoming daily transaction
 * against the owning account inside paragraph {@code 1500-B-LOOKUP-ACCT}
 * (lines 414-420). Immediately after the credit-limit test it compared the
 * account expiration date with the date portion of the transaction origination
 * timestamp:</p>
 *
 * <pre>{@code
 * IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
 *   CONTINUE
 * ELSE
 *   MOVE 103 TO WS-VALIDATION-FAIL-REASON
 *   MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
 *     TO WS-VALIDATION-FAIL-REASON-DESC
 * END-IF
 * }</pre>
 *
 * <p>The reference substring {@code DALYTRAN-ORIG-TS (1:10)} takes the first ten
 * characters of the origination timestamp ({@code YYYY-MM-DD}), so the guard is
 * a pure <em>date</em> comparison: the transaction is accepted only while the
 * account expiration date is greater than or equal to the transaction date.
 * When the account had already expired (equivalently, when
 * {@code ACCT-EXPIRAION-DATE} was strictly earlier than the transaction date),
 * the program moved reject code {@code 103} and the fixed description
 * {@code 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'} into working storage; the
 * offending transaction was then routed to the reject file rather than posted to
 * the account. This exception is the single, idiomatic Java replacement for that
 * rejection branch.</p>
 *
 * <h2>"Card" in the name, but the guard is on the ACCOUNT</h2>
 * <p>The class is named {@code ExpiredCardException} to match the authoritative
 * target tree in the migration blueprint
 * ({@code docs/technical-specifications.md} L456 &mdash;
 * {@code ExpiredCardException.java (\u2190 Reject code 103)}). The underlying
 * COBOL guard, however, tests the <em>account</em> expiration date
 * ({@code ACCT-EXPIRAION-DATE}, read from the account record in
 * {@code 1500-B-LOOKUP-ACCT}), not a card expiration date. To stay faithful to
 * the COBOL origin the diagnostic fields are therefore named with
 * <em>account</em> semantics ({@link #getAccountId() accountId},
 * {@link #getExpirationDate() expirationDate}); this Javadoc records the naming
 * mismatch deliberately so the distinction is never lost. (The COBOL field name
 * is itself misspelled {@code ACCT-EXPIRAION-DATE} in the baseline; the spelling
 * is preserved verbatim here for traceability.)</p>
 *
 * <h2>Technology substitution (documented per the Minimal Change Clause)</h2>
 * <p>The behaviour (a transaction whose date falls after the account expiration
 * date is rejected) is preserved exactly; only the mechanism changes. The batch
 * validation processor that replaces the {@code 1500-B-LOOKUP-ACCT} expiration
 * check performs the date comparison and, when it fails, throws this exception
 * instead of moving a numeric reason into working storage. The COBOL/LE date
 * handling is replaced by the {@code java.time} API: the dates are modelled as
 * {@link LocalDate} (the project-wide {@code CEEDAYS} &rarr;
 * {@code java.time.LocalDate} substitution), never {@code java.util.Date} and
 * never a floating-point type. Because this exception is unchecked (inherited
 * from {@link CardDemoException} &rarr; {@link RuntimeException}), it also
 * triggers a Spring {@code @Transactional} rollback by default, so a reject
 * discovered mid-step unwinds any partial posting. HTTP-status mapping is
 * deliberately <em>not</em> performed here: a centralized
 * {@code @RestControllerAdvice} in the web layer translates this exception to an
 * HTTP&nbsp;<strong>422 Unprocessable Entity</strong> response &mdash; the
 * correct status for a well-formed request that violates a business rule &mdash;
 * keeping this class free of any web-framework coupling so it stays a
 * foundational, low-dependency type (its only imports are {@code java.*}).</p>
 *
 * <h2>Diagnostic context</h2>
 * <p>The optional {@link #getAccountId() accountId},
 * {@link #getExpirationDate() expirationDate} and
 * {@link #getTransactionDate() transactionDate} fields capture exactly the
 * figures the COBOL program compared, so the rejection can be explained and
 * audited without re-deriving them. The two date fields are {@link LocalDate}
 * (the {@code java.time} replacement for the LE {@code CEEDAYS} date logic):
 * {@code expirationDate} corresponds to {@code ACCT-EXPIRAION-DATE} and
 * {@code transactionDate} to the {@code YYYY-MM-DD} prefix of
 * {@code DALYTRAN-ORIG-TS}. This exception merely <em>carries</em> those values;
 * the date comparison itself is performed in the batch validation processor.
 * All three fields are nullable: the message-only constructors leave them
 * {@code null}, while the structured
 * {@link #ExpiredCardException(String, LocalDate, LocalDate)} constructor
 * &mdash; the one the batch processor normally calls &mdash; populates all three
 * and derives a deterministic detail message.</p>
 *
 * <p><strong>Traceability.</strong> Mapped from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f} (authoritative blueprint
 * {@code docs/technical-specifications.md} L456 &mdash;
 * {@code ExpiredCardException.java (\u2190 Reject code 103)}). The reject-code
 * value originates in {@code app/cbl/CBTRN02C.cbl}
 * ({@code 1500-B-LOOKUP-ACCT}, {@code MOVE 103 TO WS-VALIDATION-FAIL-REASON}).
 * The COBOL source is read-only reference and is never copied into this
 * repository.</p>
 *
 * @see CardDemoException
 * @see #REJECT_CODE
 */
public class ExpiredCardException extends CardDemoException {

    /**
     * Serialization version identifier.
     *
     * <p>{@link Throwable}, and therefore every exception, is
     * {@link java.io.Serializable}. Declaring an explicit
     * {@code serialVersionUID} (annotated with {@link Serial}) pins the
     * serialized form and keeps the Java&nbsp;25 build free of the
     * {@code serial} compiler lint warning, matching the convention of the
     * {@link CardDemoException} base type.</p>
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The CardDemo batch reject code this exception represents: {@code 103}
     * ({@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}).
     *
     * <p>Preserved exactly because it is part of the external batch
     * <em>reject-file</em> contract: the daily transaction posting pipeline
     * writes rejected transactions together with their numeric reason code, and
     * that output is parity-validated against the canonical ASCII fixtures. The
     * value mirrors the COBOL {@code MOVE 103 TO WS-VALIDATION-FAIL-REASON} in
     * {@code app/cbl/CBTRN02C.cbl} ({@code 1500-B-LOOKUP-ACCT}), where
     * {@code WS-VALIDATION-FAIL-REASON} is {@code PIC 9(04)} &mdash; a numeric
     * field, hence this constant is an {@code int} rather than a string. It is
     * the contract anchor consumed by the batch reject writer when it stamps the
     * reject record with its reason code, and it is the by-value counterpart of
     * {@code com.cardemo.model.enums.RejectCode.TRANSACTION_AFTER_EXPIRATION}
     * (code {@code 103}).</p>
     */
    public static final int REJECT_CODE = 103;

    /**
     * The identifier of the account that had expired, or {@code null} when this
     * exception was created through a message-only constructor.
     *
     * <p>Corresponds to {@code ACCT-ID} of the account record the COBOL program
     * had just read in {@code 1500-B-LOOKUP-ACCT} before the expiration test.
     * Captured for diagnostics so a rejected transaction can be tied back to its
     * account without re-querying. Named with account semantics even though the
     * class name says "Card" (see the class-level Javadoc).</p>
     */
    private final String accountId;

    /**
     * The account expiration date the transaction date breached, or {@code null}
     * when this exception was created through a message-only constructor.
     *
     * <p>Corresponds to COBOL {@code ACCT-EXPIRAION-DATE} (spelling preserved
     * verbatim from the baseline). Held as {@link LocalDate} &mdash; the
     * {@code java.time} replacement for the LE {@code CEEDAYS} date logic
     * &mdash; never {@code java.util.Date}. The rejection fired because this
     * value was strictly earlier than {@link #transactionDate}.</p>
     */
    private final LocalDate expirationDate;

    /**
     * The transaction origination date that fell after the account expiration
     * date, or {@code null} when this exception was created through a
     * message-only constructor.
     *
     * <p>Corresponds to the date portion ({@code YYYY-MM-DD}, the first ten
     * characters) of COBOL {@code DALYTRAN-ORIG-TS}, exactly the
     * {@code DALYTRAN-ORIG-TS (1:10)} reference modification the
     * {@code 1500-B-LOOKUP-ACCT} guard compared. Held as {@link LocalDate} to
     * reflect that the COBOL guard is a date-only comparison.</p>
     */
    private final LocalDate transactionDate;

    /**
     * Constructs the exception with an explicit detail message, leaving the
     * structured {@link #getAccountId() accountId},
     * {@link #getExpirationDate() expirationDate} and
     * {@link #getTransactionDate() transactionDate} context unset
     * ({@code null}).
     *
     * <p>Use this overload when a caller has already composed a complete,
     * human-readable message and does not need the structured fields
     * populated.</p>
     *
     * @param message the human-readable detail message describing the
     *                after-expiration rejection, retained for retrieval via
     *                {@link #getMessage()}
     */
    public ExpiredCardException(String message) {
        super(message);
        this.accountId = null;
        this.expirationDate = null;
        this.transactionDate = null;
    }

    /**
     * Constructs the exception with an explicit detail message and an underlying
     * cause, leaving the structured {@link #getAccountId() accountId},
     * {@link #getExpirationDate() expirationDate} and
     * {@link #getTransactionDate() transactionDate} context unset
     * ({@code null}).
     *
     * <p>Use this overload to wrap a lower-level failure that surfaced while the
     * expiration check was being evaluated &mdash; for example a data-access
     * exception raised while reading the account record, or a date-parsing
     * failure while extracting the {@code DALYTRAN-ORIG-TS} prefix &mdash; while
     * preserving its stack trace.</p>
     *
     * @param message the human-readable detail message describing the
     *                after-expiration rejection, retained for retrieval via
     *                {@link #getMessage()}
     * @param cause   the underlying cause, retained for retrieval via
     *                {@link #getCause()}; a {@code null} value indicates the
     *                cause is nonexistent or unknown
     */
    public ExpiredCardException(String message, Throwable cause) {
        super(message, cause);
        this.accountId = null;
        this.expirationDate = null;
        this.transactionDate = null;
    }

    /**
     * Constructs the exception from the structured account context, deriving a
     * deterministic detail message of the form
     * {@code "Transaction dated <transactionDate> received after account <accountId> expired on <expirationDate>"}.
     *
     * <p>This is the primary, recommended constructor for the batch validation
     * processor: it captures the exact figures the COBOL
     * {@code 1500-B-LOOKUP-ACCT} expiration branch compared &mdash; the account
     * identifier, its expiration date and the transaction date &mdash;
     * reproducing the diagnostic intent of the
     * {@code 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'} reject description in
     * a uniform, structured way without copying any COBOL text. The two date
     * arguments are {@link LocalDate} (the {@code java.time} replacement for the
     * LE {@code CEEDAYS} date logic), reflecting that the COBOL guard is a
     * date-only comparison.</p>
     *
     * @param accountId       the identifier of the expired account
     *                        ({@code ACCT-ID}); retrievable via
     *                        {@link #getAccountId()}
     * @param expirationDate  the account expiration date
     *                        ({@code ACCT-EXPIRAION-DATE}) that was breached;
     *                        retrievable via {@link #getExpirationDate()}
     * @param transactionDate the transaction origination date (the
     *                        {@code DALYTRAN-ORIG-TS (1:10)} prefix) that fell
     *                        after the expiration date; retrievable via
     *                        {@link #getTransactionDate()}
     */
    public ExpiredCardException(String accountId, LocalDate expirationDate, LocalDate transactionDate) {
        super("Transaction dated " + transactionDate + " received after account " + accountId
                + " expired on " + expirationDate);
        this.accountId = accountId;
        this.expirationDate = expirationDate;
        this.transactionDate = transactionDate;
    }

    /**
     * Returns the identifier of the expired account ({@code ACCT-ID}), or
     * {@code null} when this exception was created through a message-only
     * constructor.
     *
     * @return the account identifier, or {@code null} if unset
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the account expiration date ({@code ACCT-EXPIRAION-DATE}) that the
     * transaction date breached, or {@code null} when this exception was created
     * through a message-only constructor.
     *
     * @return the account expiration date as a {@link LocalDate}, or
     *         {@code null} if unset
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * Returns the transaction origination date (the
     * {@code DALYTRAN-ORIG-TS (1:10)} prefix) that fell after the account
     * expiration date, or {@code null} when this exception was created through a
     * message-only constructor.
     *
     * @return the transaction date as a {@link LocalDate}, or {@code null} if
     *         unset
     */
    public LocalDate getTransactionDate() {
        return transactionDate;
    }
}
