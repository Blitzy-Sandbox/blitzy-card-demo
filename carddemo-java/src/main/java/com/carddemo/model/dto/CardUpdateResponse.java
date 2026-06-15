package com.carddemo.model.dto;

/**
 * Response payload returned for {@code PUT /api/cards/{cardNumber}} after the
 * card-update flow completes.
 *
 * <p>This DTO mirrors the <em>output / redisplay</em> fields of the
 * {@code COCRDUP} BMS symbolic map (card-update screen): the editable card
 * attributes are echoed back for confirmation, alongside an informational
 * message and an error message used to redisplay the screen state. Screen
 * chrome (titles, date/time, program name) and PF-key legends from the map are
 * intentionally excluded, as they are not part of the API contract.</p>
 *
 * <p>It is a plain, immutable record with no persistence, validation, or
 * framework coupling; JSON (de)serialization is handled by Jackson directly
 * from the record components. Lineage is preserved via the original COBOL
 * source at commit {@code 27d6c6f} (program {@code COCRDUPC}); the COBOL is
 * referenced, never copied.</p>
 *
 * @param accountId       the 11-character account identifier (COBOL {@code ACCTSID})
 * @param cardNumber      the 16-character card number (COBOL {@code CARDSID})
 * @param nameOnCard      the 50-character embossed name on the card (COBOL {@code CRDNAME})
 * @param cardStatus      the single-character active status code (COBOL {@code CRDSTCD})
 * @param expirationMonth the 2-character expiration month (COBOL {@code EXPMON})
 * @param expirationYear  the 4-character expiration year (COBOL {@code EXPYEAR})
 * @param expirationDay   the 2-character expiration day (COBOL {@code EXPDAY})
 * @param infoMessage     the informational/confirmation message text (COBOL {@code INFOMSG})
 * @param errorMessage    the error message text for redisplay, empty when none (COBOL {@code ERRMSG})
 */
public record CardUpdateResponse(
        String accountId,
        String cardNumber,
        String nameOnCard,
        String cardStatus,
        String expirationMonth,
        String expirationYear,
        String expirationDay,
        String infoMessage,
        String errorMessage) {
}
