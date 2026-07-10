package com.carddemo.dto;

/**
 * Immutable response payload returned after a <strong>successful</strong> account
 * update (CICS transaction {@code CAUP}), served by {@code AccountController} and
 * produced by {@code AccountUpdateService}.
 *
 * <p>This DTO is the headless-REST translation of the post-update redisplay that
 * the legacy CICS program {@code COACTUPC} rendered on the {@code COACTUP} BMS
 * map once a change was committed: the screen was re-shown populated with the
 * <em>refreshed</em> account and customer values together with a confirmation
 * message in the {@code INFOMSG} field (AAP &sect;0.5.1 program {@code COACTUPC},
 * &sect;0.8.4). Rather than duplicate the ~30 account/customer attributes, it
 * <strong>composes</strong> {@link AccountViewResponse} — the exact same
 * denormalized snapshot returned by the Account View use case — so the update
 * confirmation and the view share a single, authoritative shape.</p>
 *
 * <h2>Source of truth (COBOL, referenced by commit SHA {@code 27d6c6f})</h2>
 * <ul>
 *   <li>Screen layout / confirmation field: {@code app/cpy-bms/COACTUP.CPY}
 *       ({@code INFOMSG PIC X(45)}).</li>
 *   <li>Account record: {@code app/cpy/CVACT01Y.cpy} (ACCOUNT-RECORD, RECLN 300).</li>
 *   <li>Customer record: {@code app/cpy/CVCUS01Y.cpy} (CUSTOMER-RECORD, RECLN 500).</li>
 * </ul>
 *
 * <h2>Optimistic concurrency (AAP &sect;0.8.4)</h2>
 * <p>The COACTUPC read-then-rewrite pattern is preserved with JPA
 * {@code @Version} optimistic locking. On success this response carries the
 * <em>new</em> version token in {@link #version()}; a client that wishes to make
 * a further change echoes that value back on its next update request. The value
 * is the post-commit version of the underlying account and therefore mirrors the
 * {@link AccountViewResponse#version()} of the composed {@link #account()}
 * snapshot; it is surfaced at the top level as well so callers have a single,
 * unambiguous "next update" token without having to reach into the nested
 * object. The optimistic-lock <em>conflict</em> path (COBOL "record changed") is
 * deliberately <strong>not</strong> represented by this DTO: it is signalled by a
 * typed exception that is mapped to a {@code 409} {@link ErrorResponse}.</p>
 *
 * <h2>Fidelity and safety</h2>
 * <ul>
 *   <li><strong>Decimal / date fidelity (AAP &sect;0.8.2):</strong> all monetary
 *       ({@code java.math.BigDecimal} at scale 2) and date
 *       ({@code java.time.LocalDate}) fidelity is <em>inherited</em> from the
 *       composed {@link AccountViewResponse}, whose canonical constructor
 *       normalizes every {@code PIC S9(10)V99} amount to scale 2. This DTO adds
 *       no monetary fields of its own and never uses {@code float}/{@code double}.</li>
 *   <li><strong>Confirmation message:</strong> {@link #message()} is the modern,
 *       user-facing equivalent of the COACTUP {@code INFOMSG} (whose success
 *       text was the {@code CONFIRM-UPDATE-SUCCESS} 88-level
 *       "Changes committed to database"). It conveys only a human-readable
 *       outcome and must never leak internals, credentials, or PII.</li>
 *   <li><strong>PII:</strong> any Social Security Number is carried by the nested
 *       {@link AccountViewResponse}, which offers masking via
 *       {@link AccountViewResponse#withMaskedSsn()}; the producing service decides
 *       whether to redact before composing this response.</li>
 * </ul>
 *
 * <p>Instances are immutable, thread-safe, stateless holders (Java record) and
 * serialize as {@code {"account": { ... }, "message": ..., "version": ...}}.</p>
 *
 * @param account the refreshed account&#43;customer snapshot after the update, in
 *                the same shape as the Account View response
 * @param message a human-readable confirmation message (for example
 *                {@value #SUCCESS_MESSAGE}); never contains sensitive data
 * @param version the new JPA {@code @Version} optimistic-lock token to send on a
 *                subsequent update; mirrors {@code account.version()}
 */
public record AccountUpdateResponse(

        AccountViewResponse account,

        String message,

        Long version) {

    /**
     * Standard user-facing confirmation message for a successful account update.
     *
     * <p>This is the modern equivalent of the COACTUPC {@code INFOMSG} success
     * text (the {@code CONFIRM-UPDATE-SUCCESS} 88-level
     * "Changes committed to database"); it is phrased for a REST/API consumer and
     * exposes no implementation detail.</p>
     */
    public static final String SUCCESS_MESSAGE = "Account updated successfully";

    /**
     * Builds a success response from the refreshed account snapshot using the
     * standard {@link #SUCCESS_MESSAGE} confirmation.
     *
     * <p>The optimistic-lock {@link #version()} is derived from the supplied
     * snapshot's {@link AccountViewResponse#version()}, so the caller always
     * receives the post-update token to use on any subsequent change. A
     * {@code null} snapshot yields a {@code null} version (and a {@code null}
     * {@link #account()}), allowing the record to represent an unpopulated result
     * without throwing.</p>
     *
     * @param account the refreshed account&#43;customer snapshot after the update
     *                (may be {@code null})
     * @return a fully populated {@code AccountUpdateResponse} carrying the standard
     *         success confirmation
     */
    public static AccountUpdateResponse updated(AccountViewResponse account) {
        return updated(account, SUCCESS_MESSAGE);
    }

    /**
     * Builds a success response from the refreshed account snapshot with a
     * caller-supplied confirmation message.
     *
     * <p>Use this overload when the producing service needs to tailor the
     * confirmation wording (for example, to echo a context-specific note) while
     * still deriving the optimistic-lock {@link #version()} from the snapshot's
     * {@link AccountViewResponse#version()}. The supplied {@code message} must be
     * a user-facing string that exposes no internals, credentials, or PII.</p>
     *
     * @param account the refreshed account&#43;customer snapshot after the update
     *                (may be {@code null})
     * @param message the confirmation message to convey to the caller
     * @return a fully populated {@code AccountUpdateResponse} carrying the supplied
     *         confirmation
     */
    public static AccountUpdateResponse updated(AccountViewResponse account, String message) {
        return new AccountUpdateResponse(
                account,
                message,
                account == null ? null : account.version());
    }
}
