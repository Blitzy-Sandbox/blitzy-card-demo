package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Response body for the bill-payment screen, returned by the billing REST
 * controller from {@code POST /api/billing/pay} and on the initial balance
 * inquiry that precedes a payment, after the bill-payment service has read the
 * account and (when confirmed) posted the balance-clearing payment.
 *
 * <p>This DTO mirrors the <em>output / redisplay</em> view of the
 * {@code COBIL00} BMS symbolic map (COBOL lineage preserved by reference to
 * source commit {@code 27d6c6f}; the original copybook is never copied into this
 * project). It exposes only the four operator-facing data fields of that map:
 * the entered account identifier, the account's current balance, the
 * confirmation flag, and the operator-message line. The map's screen chrome
 * (transaction name, titles, current date/time, and program name), its
 * PF-key (function-key) legends, and every BMS attribute sub-field are
 * deliberately omitted because they have no equivalent in a stateless JSON
 * contract.</p>
 *
 * <p>The {@code currentBalance} component is modelled with
 * {@link java.math.BigDecimal} (scale 2) to preserve the exact fixed-point
 * precision of the originating COBOL {@code PIC S9(10)V99} account-balance
 * clause; floating-point types ({@code float}/{@code double}) are deliberately
 * avoided. Every other component is a {@link String}, reflecting the fixed-width
 * character fields of the symbolic map. As a server-produced response, this
 * record declares no bean-validation constraints, no JPA/persistence mapping,
 * and no screen-chrome or PF-key fields.</p>
 *
 * <p>Being a Java {@code record}, the type is immutable and value-based: it
 * provides a canonical constructor, public accessor methods for every component,
 * and structural {@code equals}, {@code hashCode}, and {@code toString}
 * implementations.</p>
 *
 * @param accountId      entered/echoed account identifier (&larr; {@code ACTIDIN})
 * @param currentBalance account current balance, scale 2 (&larr; {@code CURBAL})
 * @param confirm        payment confirmation flag (&larr; {@code CONFIRM})
 * @param errorMessage   informational/validation message line (&larr; {@code ERRMSG})
 */
public record BillPaymentResponse(
        String accountId,
        BigDecimal currentBalance,
        String confirm,
        String errorMessage) {
}
