package com.carddemo.model.dto;

/**
 * Immutable response payload for the card-detail (single keyed read) screen.
 *
 * <p>Returned as JSON by {@code controller.CardController} for
 * {@code GET /api/cards/{cardNumber}} and produced by
 * {@code service.card.CardDetailService}. Each component mirrors a display
 * field of the COBOL {@code COCRDSL} BMS symbolic map (input fields of the
 * {@code CCRDSLAI} structure); screen chrome, attribute/flag fields, and
 * PF-key legends are intentionally excluded because they have no REST
 * equivalent.</p>
 *
 * <p>This is a stateless, serializable transport object: it carries no
 * persistence concerns (no JPA), no bean-validation constraints (responses
 * are server-produced), and no behavior beyond the record's generated
 * accessors. All components are {@link String} since the card-detail screen
 * exposes no monetary fields.</p>
 *
 * <p>Traceability: lineage is preserved via reference to the original AWS
 * CardDemo source commit {@code 27d6c6f}; the COBOL source is never copied
 * into this project.</p>
 *
 * @param accountId       account identifier ({@code ACCTSID}, {@code PIC X(11)})
 * @param cardNumber      card number ({@code CARDSID}, {@code PIC X(16)})
 * @param nameOnCard      embossed cardholder name ({@code CRDNAME}, {@code PIC X(50)})
 * @param cardStatus      single-character card status code ({@code CRDSTCD}, {@code PIC X(1)})
 * @param expirationMonth two-digit expiration month ({@code EXPMON}, {@code PIC X(2)})
 * @param expirationYear  four-digit expiration year ({@code EXPYEAR}, {@code PIC X(4)})
 * @param infoMessage     informational message line ({@code INFOMSG}, {@code PIC X(40)})
 * @param errorMessage    error message line ({@code ERRMSG}, {@code PIC X(80)})
 */
public record CardDetailResponse(
        String accountId,
        String cardNumber,
        String nameOnCard,
        String cardStatus,
        String expirationMonth,
        String expirationYear,
        String infoMessage,
        String errorMessage) {
}
