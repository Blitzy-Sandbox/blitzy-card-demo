package com.carddemo.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * REST data-transfer objects for the card list, detail, and update flows.
 *
 * <p>Field contracts are derived byte-accurately from the BMS symbolic maps
 * {@code COCRDLI}, {@code COCRDSL}, and {@code COCRDUP} (AWS CardDemo) at source
 * commit {@code 27d6c6f}. The maximum sizes mirror the {@code PIC X(n)} widths
 * of the underlying symbolic map fields, and the digit patterns mirror the
 * numeric-only screen fields.</p>
 *
 * <ul>
 *   <li>{@link ListResponse}/{@link CardSummary} &larr; {@code COCRDLI} (CCLI / {@code COCRDLIC}),
 *       consumed by {@code GET /api/cards}.</li>
 *   <li>{@link Detail} &larr; {@code COCRDSL} (CCDL / {@code COCRDSLC}),
 *       consumed by {@code GET /api/cards/{cardNum}}.</li>
 *   <li>{@link UpdateRequest} &larr; {@code COCRDUP} (CCUP / {@code COCRDUPC}),
 *       consumed by {@code PUT /api/cards/{cardNum}}.</li>
 * </ul>
 */
public final class CardDto {

    private CardDto() {
    }

    /**
     * One row of the card list. Derived from the {@code COCRDLI} repeated row
     * fields {@code ACCTNOn X(11)}, {@code CRDNUMn X(16)}, {@code CRDSTSn X(1)}
     * at commit {@code 27d6c6f}.
     *
     * @param accountId  the account identifier ({@code ACCTNOn}, max 11 digits)
     * @param cardNumber the card number ({@code CRDNUMn}, max 16 digits)
     * @param cardStatus the single-character card status code ({@code CRDSTSn})
     */
    public record CardSummary(
            @Size(max = 11) @Pattern(regexp = "\\d{0,11}") String accountId,
            @Size(max = 16) @Pattern(regexp = "\\d{0,16}") String cardNumber,
            @Size(max = 1) String cardStatus
    ) {
    }

    /**
     * Card list response. Derived from the {@code COCRDLI} screen fields
     * {@code PAGENO X(3)}, {@code ACCTSID X(11)}, {@code CARDSID X(16)} and the
     * seven repeated rows at commit {@code 27d6c6f}.
     *
     * @param pageNumber       the current page number ({@code PAGENO}, max 3 digits)
     * @param accountIdFilter  the account-id filter ({@code ACCTSID}, max 11 digits)
     * @param cardNumberFilter the card-number filter ({@code CARDSID}, max 16 digits)
     * @param cards            the rows on the current page (up to seven)
     */
    public record ListResponse(
            @Size(max = 3) @Pattern(regexp = "\\d{0,3}") String pageNumber,
            @Size(max = 11) @Pattern(regexp = "\\d{0,11}") String accountIdFilter,
            @Size(max = 16) @Pattern(regexp = "\\d{0,16}") String cardNumberFilter,
            List<@Valid CardSummary> cards
    ) {
    }

    /**
     * Card detail response. Derived from the {@code COCRDSL} screen fields
     * {@code ACCTSID X(11)}, {@code CARDSID X(16)}, {@code CRDNAME X(50)},
     * {@code CRDSTCD X(1)}, {@code EXPMON X(2)}, {@code EXPYEAR X(4)} at commit
     * {@code 27d6c6f}. Expiry is kept as separate month and year segments.
     *
     * @param accountId      the account identifier ({@code ACCTSID}, max 11 digits)
     * @param cardNumber     the card number ({@code CARDSID}, max 16 digits)
     * @param cardholderName the embossed cardholder name ({@code CRDNAME}, max 50)
     * @param cardStatus     the single-character card status code ({@code CRDSTCD})
     * @param expiryMonth    the expiry month segment ({@code EXPMON}, max 2 digits)
     * @param expiryYear     the expiry year segment ({@code EXPYEAR}, max 4 digits)
     */
    public record Detail(
            @Size(max = 11) @Pattern(regexp = "\\d{1,11}") String accountId,
            @Size(max = 16) @Pattern(regexp = "\\d{1,16}") String cardNumber,
            @Size(max = 50) String cardholderName,
            @Size(max = 1) String cardStatus,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String expiryMonth,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String expiryYear
    ) {
    }

    /**
     * Card update request body. Derived from the {@code COCRDUP} screen fields,
     * which add {@code EXPDAY X(2)} to the {@code COCRDSL} detail field set at
     * commit {@code 27d6c6f}. Expiry is kept as separate month, year, and day
     * segments.
     *
     * @param accountId      the account identifier ({@code ACCTSID}, max 11 digits, required)
     * @param cardNumber     the card number ({@code CARDSID}, max 16 digits, required)
     * @param cardholderName the embossed cardholder name ({@code CRDNAME}, max 50)
     * @param cardStatus     the single-character card status code ({@code CRDSTCD})
     * @param expiryMonth    the expiry month segment ({@code EXPMON}, max 2 digits)
     * @param expiryYear     the expiry year segment ({@code EXPYEAR}, max 4 digits)
     * @param expiryDay      the expiry day segment ({@code EXPDAY}, max 2 digits)
     */
    public record UpdateRequest(
            @NotBlank @Size(max = 11) @Pattern(regexp = "\\d{1,11}") String accountId,
            @NotBlank @Size(max = 16) @Pattern(regexp = "\\d{1,16}") String cardNumber,
            @Size(max = 50) String cardholderName,
            @Size(max = 1) String cardStatus,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String expiryMonth,
            @Size(max = 4) @Pattern(regexp = "\\d{0,4}") String expiryYear,
            @Size(max = 2) @Pattern(regexp = "\\d{0,2}") String expiryDay
    ) {
    }
}
