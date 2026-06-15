package com.cardemo.exception;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * Concrete domain exception signalling that posting a transaction would push an
 * account past its assigned credit limit &mdash; the Java&nbsp;25 mapping of
 * CardDemo batch <strong>reject code {@code 102}</strong>
 * ({@code "OVERLIMIT TRANSACTION"}) raised by the daily transaction posting
 * program.
 *
 * <p>On the legacy mainframe the batch posting program
 * {@code app/cbl/CBTRN02C.cbl} validated every incoming daily transaction
 * against the owning account inside paragraph {@code 1500-B-LOOKUP-ACCT}
 * (lines 403-413). After reading the account record it computed a projected
 * post-transaction balance and compared it to the account credit limit:</p>
 *
 * <pre>{@code
 * COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
 *                     - ACCT-CURR-CYC-DEBIT
 *                     + DALYTRAN-AMT
 *
 * IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
 *   CONTINUE
 * ELSE
 *   MOVE 102 TO WS-VALIDATION-FAIL-REASON
 *   MOVE 'OVERLIMIT TRANSACTION'
 *     TO WS-VALIDATION-FAIL-REASON-DESC
 * END-IF
 * }</pre>
 *
 * <p>When the projected balance {@code WS-TEMP-BAL} exceeded
 * {@code ACCT-CREDIT-LIMIT} (equivalently, when {@code ACCT-CREDIT-LIMIT} was
 * <em>not</em> greater than or equal to it), the program moved reject code
 * {@code 102} and the fixed description {@code 'OVERLIMIT TRANSACTION'} into
 * working storage; the offending transaction was then routed to the reject file
 * rather than posted to the account. This exception is the single, idiomatic
 * Java replacement for that rejection branch.</p>
 *
 * <h2>Technology substitution (documented per the Minimal Change Clause)</h2>
 * <p>The behaviour (a transaction whose projected balance breaches the credit
 * limit is rejected) is preserved exactly; only the mechanism changes. The
 * batch validation processor that replaces {@code 2000-VALIDATE-TXN} performs
 * the over-limit comparison and, when it fails, throws this exception instead of
 * moving a numeric reason into working storage. Because the type is unchecked
 * (inherited from {@link CardDemoException} &rarr; {@link RuntimeException}), it
 * also triggers a Spring {@code @Transactional} rollback by default, so a reject
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
 * {@link #getCreditLimit() creditLimit} and
 * {@link #getAttemptedBalance() attemptedBalance} fields capture exactly the
 * three figures the COBOL program compared, so the rejection can be explained
 * and audited without re-deriving them. The two monetary fields are
 * {@link BigDecimal} rather than {@code float}/{@code double}: they originate
 * from COBOL {@code PIC S9(n)V99} packed-decimal money fields
 * ({@code ACCT-CREDIT-LIMIT} is {@code PIC S9(10)V99}; the projected balance is
 * derived from {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT} and
 * {@code DALYTRAN-AMT}), and exact decimal fidelity is mandatory across the
 * migration. This exception merely <em>carries</em> those figures; the over-limit
 * comparison itself is performed in the batch validation processor using
 * {@link BigDecimal#compareTo(BigDecimal)} (never {@code equals}, which is
 * scale-sensitive). All three fields are nullable: the message-only constructors
 * leave them {@code null}, while the structured
 * {@link #CreditLimitExceededException(String, BigDecimal, BigDecimal)}
 * constructor &mdash; the one the batch processor normally calls &mdash;
 * populates all three and derives a deterministic detail message.</p>
 *
 * <p><strong>Traceability.</strong> Mapped from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f} (authoritative blueprint
 * {@code docs/technical-specifications.md} L455 &mdash;
 * {@code CreditLimitExceededException.java (\u2190 Reject code 102)}). The
 * reject-code value originates in {@code app/cbl/CBTRN02C.cbl}
 * ({@code 1500-B-LOOKUP-ACCT}, {@code MOVE 102 TO WS-VALIDATION-FAIL-REASON}).
 * The COBOL source is read-only reference and is never copied into this
 * repository.</p>
 *
 * @see CardDemoException
 * @see #REJECT_CODE
 */
public class CreditLimitExceededException extends CardDemoException {

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
     * The CardDemo batch reject code this exception represents: {@code 102}
     * ({@code "OVERLIMIT TRANSACTION"}).
     *
     * <p>Preserved exactly because it is part of the external batch
     * <em>reject-file</em> contract: the daily transaction posting pipeline
     * writes rejected transactions together with their numeric reason code, and
     * that output is parity-validated against the canonical ASCII fixtures. The
     * value mirrors the COBOL {@code MOVE 102 TO WS-VALIDATION-FAIL-REASON} in
     * {@code app/cbl/CBTRN02C.cbl} ({@code 1500-B-LOOKUP-ACCT}), where
     * {@code WS-VALIDATION-FAIL-REASON} is {@code PIC 9(04)} &mdash; a numeric
     * field, hence this constant is an {@code int} rather than a string. It is
     * the contract anchor consumed by the batch reject writer when it stamps the
     * reject record with its reason code.</p>
     */
    public static final int REJECT_CODE = 102;

    /**
     * The identifier of the account whose credit limit would be breached, or
     * {@code null} when this exception was created through a message-only
     * constructor.
     *
     * <p>Corresponds to {@code ACCT-ID} of the account record the COBOL program
     * had just read in {@code 1500-B-LOOKUP-ACCT} before the over-limit test.
     * Captured for diagnostics so a rejected transaction can be tied back to its
     * account without re-querying.</p>
     */
    private final String accountId;

    /**
     * The account's credit limit that the projected balance breached, or
     * {@code null} when this exception was created through a message-only
     * constructor.
     *
     * <p>Corresponds to COBOL {@code ACCT-CREDIT-LIMIT} ({@code PIC S9(10)V99}).
     * Held as {@link BigDecimal} to preserve exact decimal scale; never
     * {@code float}/{@code double}.</p>
     */
    private final BigDecimal creditLimit;

    /**
     * The projected post-transaction balance that exceeded the credit limit, or
     * {@code null} when this exception was created through a message-only
     * constructor.
     *
     * <p>Corresponds to COBOL {@code WS-TEMP-BAL}, computed as
     * {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}. Held as
     * {@link BigDecimal} to preserve exact decimal scale; the rejection fired
     * because this value was strictly greater than {@link #creditLimit}.</p>
     */
    private final BigDecimal attemptedBalance;

    /**
     * Constructs the exception with an explicit detail message, leaving the
     * structured {@link #getAccountId() accountId},
     * {@link #getCreditLimit() creditLimit} and
     * {@link #getAttemptedBalance() attemptedBalance} context unset
     * ({@code null}).
     *
     * <p>Use this overload when a caller has already composed a complete,
     * human-readable message and does not need the structured fields
     * populated.</p>
     *
     * @param message the human-readable detail message describing the over-limit
     *                rejection, retained for retrieval via {@link #getMessage()}
     */
    public CreditLimitExceededException(String message) {
        super(message);
        this.accountId = null;
        this.creditLimit = null;
        this.attemptedBalance = null;
    }

    /**
     * Constructs the exception with an explicit detail message and an underlying
     * cause, leaving the structured {@link #getAccountId() accountId},
     * {@link #getCreditLimit() creditLimit} and
     * {@link #getAttemptedBalance() attemptedBalance} context unset
     * ({@code null}).
     *
     * <p>Use this overload to wrap a lower-level failure that surfaced while the
     * over-limit check was being evaluated &mdash; for example a data-access
     * exception raised while reading the account record &mdash; while preserving
     * its stack trace.</p>
     *
     * @param message the human-readable detail message describing the over-limit
     *                rejection, retained for retrieval via {@link #getMessage()}
     * @param cause   the underlying cause, retained for retrieval via
     *                {@link #getCause()}; a {@code null} value indicates the
     *                cause is nonexistent or unknown
     */
    public CreditLimitExceededException(String message, Throwable cause) {
        super(message, cause);
        this.accountId = null;
        this.creditLimit = null;
        this.attemptedBalance = null;
    }

    /**
     * Constructs the exception from the structured account context, deriving a
     * deterministic detail message of the form
     * {@code "Transaction exceeds credit limit for account <accountId> (limit=<creditLimit>, attempted=<attemptedBalance>)"}.
     *
     * <p>This is the primary, recommended constructor for the batch validation
     * processor: it captures the exact three figures the COBOL
     * {@code 1500-B-LOOKUP-ACCT} branch compared &mdash; the account identifier,
     * its credit limit and the projected balance &mdash; reproducing the
     * diagnostic intent of the {@code 'OVERLIMIT TRANSACTION'} reject description
     * in a uniform, structured way without copying any COBOL text. The two
     * monetary arguments are {@link BigDecimal} to preserve exact decimal
     * fidelity.</p>
     *
     * @param accountId        the identifier of the account whose credit limit
     *                         would be breached; retrievable via
     *                         {@link #getAccountId()}
     * @param creditLimit      the account credit limit ({@code ACCT-CREDIT-LIMIT})
     *                         that was breached; retrievable via
     *                         {@link #getCreditLimit()}
     * @param attemptedBalance the projected post-transaction balance
     *                         ({@code WS-TEMP-BAL}) that exceeded the limit;
     *                         retrievable via {@link #getAttemptedBalance()}
     */
    public CreditLimitExceededException(String accountId, BigDecimal creditLimit, BigDecimal attemptedBalance) {
        super("Transaction exceeds credit limit for account " + accountId
                + " (limit=" + creditLimit + ", attempted=" + attemptedBalance + ")");
        this.accountId = accountId;
        this.creditLimit = creditLimit;
        this.attemptedBalance = attemptedBalance;
    }

    /**
     * Returns the identifier of the account whose credit limit would be
     * breached, or {@code null} when this exception was created through a
     * message-only constructor.
     *
     * @return the account identifier, or {@code null} if unset
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the account credit limit ({@code ACCT-CREDIT-LIMIT}) that the
     * projected balance breached, or {@code null} when this exception was
     * created through a message-only constructor.
     *
     * @return the credit limit as a {@link BigDecimal}, or {@code null} if unset
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Returns the projected post-transaction balance ({@code WS-TEMP-BAL}) that
     * exceeded the credit limit, or {@code null} when this exception was created
     * through a message-only constructor.
     *
     * @return the attempted balance as a {@link BigDecimal}, or {@code null} if
     *         unset
     */
    public BigDecimal getAttemptedBalance() {
        return attemptedBalance;
    }
}
