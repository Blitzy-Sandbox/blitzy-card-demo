package com.carddemo.dto;

/**
 * Immutable response returned by the Card Update operation (online transaction
 * {@code CCUP}) after a card has been updated successfully.
 *
 * <p>This DTO is the Java 25 / Spring Boot translation of the confirmation that the
 * legacy CICS program {@code COCRDUPC} rendered on the {@code COCRDUP} BMS map
 * ({@code app/cpy-bms/COCRDUP.CPY}) once an update succeeded: the screen was repainted
 * with the freshly persisted card fields and its information line ({@code INFOMSG})
 * carried a success message. It composes the read-only card snapshot, that
 * confirmation text, and the new optimistic-lock version so the caller can render the
 * outcome without issuing a follow-up read (AAP &sect;0.5.1, program {@code COCRDUPC};
 * &sect;0.8.4).</p>
 *
 * <h2>COBOL source mapping</h2>
 * <ul>
 *   <li>{@code card} &larr; the refreshed {@code CARD-RECORD}
 *       ({@code app/cpy/CVACT02Y.cpy}) re-read after the rewrite, surfaced through
 *       {@link CardViewResponse} — the same read-only projection used by the Card View
 *       screen {@code COCRDSL}.</li>
 *   <li>{@code message} &larr; the {@code INFOMSG PIC X(40)} information line of the
 *       {@code COCRDUP} symbolic map — the on-screen success confirmation. The error
 *       line ({@code ERRMSG PIC X(80)}) is <strong>not</strong> represented here:
 *       failures, including the optimistic-lock "record changed" conflict, are surfaced
 *       as a typed {@code 409}-style error response, never as this DTO.</li>
 *   <li>{@code version} &larr; the JPA {@code @Version} value <em>after</em> the update.
 *       It reproduces the read-then-rewrite optimistic-concurrency contract of
 *       {@code COCRDUPC} (AAP &sect;0.8.4): the client echoes this value on any
 *       subsequent update so a stale write is detected and rejected rather than silently
 *       overwriting a concurrent change.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>All card security invariants are <strong>inherited</strong> from the composed
 * {@link CardViewResponse}: the Primary Account Number is already masked to its last
 * four characters by that type's canonical constructor, and the card verification value
 * ({@code CARD-CVV-CD}, {@code PIC 9(03)}) is never present in the projection. This
 * response introduces no card attributes of its own, so neither the PAN nor the CVV can
 * leak through it. The {@link #message()} is a fixed, human-readable confirmation and
 * exposes no internal implementation detail.</p>
 *
 * <p>The type is a stateless, immutable {@code record} and is safe to share across
 * threads. It carries no monetary values, so no floating-point types are used.</p>
 *
 * @param card    the refreshed, PAN-masked card snapshot as re-read after the update
 * @param message a human-readable success confirmation (see {@link #SUCCESS_MESSAGE})
 * @param version the new JPA {@code @Version} value after the update, echoed by the
 *                client on subsequent updates for optimistic-lock enforcement; may be
 *                {@code null} when no version is tracked
 */
public record CardUpdateResponse(CardViewResponse card, String message, Long version) {

    /**
     * Standard confirmation message emitted after a successful card update, mirroring the
     * success text shown on the {@code INFOMSG} information line of the legacy
     * {@code COCRDUP} screen. The text carries no sensitive data and reveals no internal
     * implementation detail.
     */
    public static final String SUCCESS_MESSAGE = "Card updated successfully";

    /**
     * Builds a response carrying the standard {@link #SUCCESS_MESSAGE} for a card that has
     * just been updated.
     *
     * <p>The new optimistic-lock {@code version} is taken from the supplied, refreshed
     * {@code card} snapshot ({@link CardViewResponse#version()}), keeping the top-level
     * {@link #version()} consistent with the version echoed inside the card. A
     * {@code null} {@code card} yields a {@code null} version so the factory never
     * dereferences a missing snapshot.</p>
     *
     * @param card the refreshed, PAN-masked card snapshot re-read after the update
     * @return a fully populated {@code CardUpdateResponse} with the standard success
     *         confirmation and the card's post-update version
     */
    public static CardUpdateResponse withConfirmation(CardViewResponse card) {
        return new CardUpdateResponse(
                card,
                SUCCESS_MESSAGE,
                card == null ? null : card.version());
    }
}
