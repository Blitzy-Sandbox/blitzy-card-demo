package com.carddemo.model.dto;

/**
 * Immutable response payload for the card-detail (single keyed read) screen.
 *
 * <p>Returned as JSON by {@code controller.CardController} on
 * {@code GET /api/cards/{cardNumber}} and produced by
 * {@code service.card.CardDetailService}. It mirrors the display fields of the
 * COBOL {@code COCRDSL} BMS symbolic map (the modernized equivalent of the
 * online program {@code COCRDSLC}); screen chrome, attribute bytes, and PF-key
 * legends from the original map are intentionally excluded.
 *
 * <p>Every component is a {@code String}, faithfully reflecting the source map's
 * fixed-width alphanumeric ({@code PIC X}) fields. Lengths are recorded here for
 * fidelity but are not enforced on this response contract: {@code infoMessage}
 * originates from {@code INFOMSG PIC X(40)} and {@code errorMessage} from
 * {@code ERRMSG PIC X(80)} — distinct lengths from the other CardDemo maps.
 *
 * <p>Lineage is preserved by reference to the source repository commit
 * {@code 27d6c6f}; the original COBOL is not copied into this project.
 *
 * @param accountId       account identifier ({@code COCRDSL} field {@code ACCTSID}, {@code PIC X(11)})
 * @param cardNumber      card number ({@code COCRDSL} field {@code CARDSID}, {@code PIC X(16)})
 * @param nameOnCard      embossed name on the card ({@code COCRDSL} field {@code CRDNAME}, {@code PIC X(50)})
 * @param cardStatus      active/inactive status code ({@code COCRDSL} field {@code CRDSTCD}, {@code PIC X(1)})
 * @param expirationMonth two-digit expiration month ({@code COCRDSL} field {@code EXPMON}, {@code PIC X(2)})
 * @param expirationYear  four-digit expiration year ({@code COCRDSL} field {@code EXPYEAR}, {@code PIC X(4)})
 * @param infoMessage     informational message line ({@code COCRDSL} field {@code INFOMSG}, {@code PIC X(40)})
 * @param errorMessage    error message line ({@code COCRDSL} field {@code ERRMSG}, {@code PIC X(80)})
 * @param version         JPA {@code @Version} optimistic-lock token of the card record,
 *                        echoed from the last read (the Java equivalent of the CICS
 *                        {@code READ UPDATE} before-image). Enables the documented
 *                        read&rarr;modify&rarr;write cycle and {@code 409 Conflict}
 *                        recovery via re-fetch (see {@code api-contracts.md} &sect;1.8).
 *                        Carried only on this read response; per &sect;452 the card-update
 *                        response intentionally does not echo {@code version}.
 */
public record CardDetailResponse(
        String accountId,
        String cardNumber,
        String nameOnCard,
        String cardStatus,
        String expirationMonth,
        String expirationYear,
        String infoMessage,
        String errorMessage,
        Long version) {
}
