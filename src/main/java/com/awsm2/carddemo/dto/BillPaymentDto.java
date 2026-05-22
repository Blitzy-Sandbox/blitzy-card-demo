/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bill payment request / confirmation DTO &mdash; the JSON envelope exchanged
 * by the {@code POST /api/billing/pay} endpoint that replaces the legacy
 * 3270 bill-payment confirmation screen.
 *
 * <p><b>COBIL00 transaction summary.</b>  In the source mainframe stack,
 * {@code COBIL00C.cbl} (CICS transaction id {@code CB00}) implements a
 * one-step "pay the current account balance in full" flow:
 * <ol>
 *   <li>The operator keys an 11-digit account identifier into the
 *       {@code ACTIDIN} field of the BMS map {@code COBIL0A} (defined in
 *       {@code app/bms/COBIL00.bms} and projected to working storage by the
 *       symbolic-map copybook {@code app/cpy-bms/COBIL00.CPY}).</li>
 *   <li>{@code COBIL00C} reads the {@code ACCOUNT-RECORD} from the
 *       {@code ACCTDAT} VSAM cluster, displays the
 *       {@code ACCT-CURR-BAL PIC S9(10)V99} balance in the {@code CURBAL}
 *       field, and re-renders the screen with the operator's account
 *       identifier still populated.</li>
 *   <li>The operator inspects the displayed balance and confirms the
 *       transaction with a single character ({@code Y}) in the
 *       {@code CONFIRM} field, or cancels with {@code N}.</li>
 *   <li>On {@code Y} confirmation, {@code COBIL00C} resolves the card via
 *       the {@code CXACAIX} alternate index over the {@code CARDXREF}
 *       cluster, generates the next sequential {@code TRAN-ID PIC 9(16)} by
 *       browse-to-end on the {@code TRANSACT} cluster, writes a new
 *       {@code TRAN-RECORD} (transaction type {@code 02}, category
 *       {@code 2}, source {@code POS TERM}, description
 *       {@code BILL PAYMENT - ONLINE}, amount = current balance, merchant
 *       identifier {@code 999999999}, merchant name {@code BILL PAYMENT}),
 *       and updates the account balance by subtracting the just-posted
 *       transaction amount (which equals the previous balance, leaving the
 *       account at zero) &mdash; all inside the implicit CICS unit of work
 *       that wraps the conversational add flow.</li>
 *   <li>On zero or negative balance the program emits the message
 *       {@code "You have nothing to pay..."} and short-circuits without
 *       writing.  On any other unexpected {@code CONFIRM} value it emits
 *       {@code "Invalid value. Valid values are (Y/N)..."}.</li>
 * </ol>
 *
 * <p><b>Java target mapping.</b>  In the Java/Spring Boot target, the same
 * flow is delivered by {@code BillPaymentService.pay(...)} (one-service-per-
 * COBOL-program mapping per AAP &sect;0.3.3 / &sect;0.4.1):
 * <ol>
 *   <li>Validates input via Bean Validation (annotations on this record;
 *       runs from the {@code @Valid} marker on the
 *       {@code BillingController.payBill(...)} method parameter).</li>
 *   <li>Resolves the card via
 *       {@code CardCrossReferenceRepository.findByXrefAcctId(accountId)}
 *       (replaces the {@code CXACAIX} alternate index over the
 *       {@code CARDXREF} VSAM cluster &mdash; AAP &sect;0.6.2).</li>
 *   <li>Reads the {@code Account} entity, validates that the balance is
 *       strictly positive (preserving the COBOL
 *       {@code "You have nothing to pay..."} guard) and that the
 *       {@code confirm} flag is exactly {@code "Y"}.</li>
 *   <li>Persists a new {@code Transaction} entity (type {@code 02},
 *       category {@code 2}, source {@code POS TERM}, description
 *       {@code BILL PAYMENT - ONLINE}, amount = current balance, merchant
 *       identifier {@code 999999999}) and updates the {@code Account}
 *       balance to zero inside a single
 *       {@code @Transactional(rollbackFor = Exception.class)} boundary
 *       &mdash; replaces the implicit CICS {@code SYNCPOINT} wrapping
 *       the original conversational pay flow (AAP &sect;0.6.2).</li>
 *   <li>Publishes a {@code transaction.posted} Kafka event to MSK,
 *       partitioned by the 11-digit account identifier so that all events
 *       for the same account land on the same partition and remain
 *       strictly ordered for any single consumer (AAP &sect;0.6.5).</li>
 *   <li>Returns this DTO with {@link #transactionId()},
 *       {@link #postedAt()}, and {@link #amountPaid()} populated as a
 *       confirmation receipt.</li>
 * </ol>
 *
 * <p><b>Request / response duality.</b>  This DTO carries the request body
 * on the way in <i>and</i> the confirmation receipt on the way out.  The
 * caller supplies {@link #accountId()} and {@link #confirm()};
 * {@link #currentBalance()}, {@link #transactionId()}, {@link #postedAt()},
 * and {@link #amountPaid()} are server-populated.  This mirrors the
 * BMS map's behavior &mdash; the operator types {@code ACTIDIN} and
 * {@code CONFIRM}; the program populates {@code CURBAL} and (in the Java
 * target's enriched API contract) the generated transaction identifier and
 * timestamp.  Request fields are left {@code null} in the response payload
 * are server-populated; response-only fields are left {@code null} in the
 * request payload by the client.
 *
 * <p><b>BMS / record-layout field mapping (AAP &sect;0.4.1 traceability):</b>
 * <pre>{@code
 *   BMS field   Length   Mode   Source                              Java component
 *   ---------   ------   ----   ---------------------------------   ------------------
 *   ACTIDIN     X(11)    I/O    ACCT-ID PIC 9(11) on ACCOUNT-RECORD accountId
 *   CURBAL      X(14)    O      ACCT-CURR-BAL PIC S9(10)V99         currentBalance
 *   CONFIRM     X(01)    I      (transient, not persisted)          confirm
 *   (synthetic) X(16)    O      TRAN-ID PIC 9(16) on TRAN-RECORD    transactionId
 *   (synthetic) ---      O      TRAN-PROC-TS PIC X(26) on record    postedAt
 *   (synthetic) ---      O      TRAN-AMT PIC S9(09)V99 on record    amountPaid
 * }</pre>
 *
 * <p>(*) The BMS {@code CURBAL} field is 14 characters wide because the COBOL
 * {@code WS-CURR-BAL} edited-output field is declared as
 * {@code PIC +9999999999.99} (sign + 10 digits + decimal point + 2 digits =
 * 14 characters).  The underlying record-level
 * {@code ACCT-CURR-BAL PIC S9(10)V99} is mapped to a {@link BigDecimal}
 * with scale 2 and precision 12 on the {@code Account} entity per AAP
 * &sect;0.6.1.
 *
 * <p>(**) The original 3270 screen does not display the generated
 * transaction identifier or timestamp &mdash; the COBOL program writes the
 * transaction and re-sends the bill-payment screen with a blank message.
 * The REST endpoint enriches the response with {@code transactionId},
 * {@code postedAt}, and {@code amountPaid} to give API consumers a
 * machine-readable confirmation receipt.  These three components are
 * therefore "synthetic" with respect to the BMS map but anchored to
 * authoritative {@code TRAN-RECORD} columns from
 * {@code app/cpy/CVTRA05Y.cpy} ({@code TRAN-ID PIC 9(16)},
 * {@code TRAN-PROC-TS PIC X(26)}, {@code TRAN-AMT PIC S9(09)V99}).
 *
 * <p><b>COBOL Provenance (AAP &sect;0.7.3 traceability):</b>
 * <ul>
 *   <li>BMS Mapset: {@code app/bms/COBIL00.bms} (mapset {@code COBIL00},
 *       map {@code COBIL0A})</li>
 *   <li>Symbolic Map: {@code app/cpy-bms/COBIL00.CPY} (record
 *       {@code COBIL0AI} / output redefine {@code COBIL0AO})</li>
 *   <li>Program: {@code app/cbl/COBIL00C.cbl} (CICS transaction id
 *       {@code CB00}; "pay account balance in full" online flow)</li>
 *   <li>Record Layouts:
 *       {@code app/cpy/CVACT01Y.cpy} ({@code ACCOUNT-RECORD}, 300 bytes,
 *       source of {@code ACCT-CURR-BAL}) and
 *       {@code app/cpy/CVTRA05Y.cpy} ({@code TRAN-RECORD}, 350 bytes,
 *       target of the generated transaction)</li>
 *   <li>Cross-reference: {@code app/cpy/CVACT03Y.cpy} ({@code CARD-XREF-
 *       RECORD}, 50 bytes; consumed by
 *       {@code CardCrossReferenceRepository.findByXrefAcctId(...)} in the
 *       Java target)</li>
 *   <li>Consumed by: {@code BillPaymentService#pay(BillPaymentDto)}
 *       (one-service-per-COBOL-program mapping per AAP &sect;0.3.3)</li>
 *   <li>Controller endpoint: {@code POST /api/billing/pay} (see
 *       {@code BillingController})</li>
 * </ul>
 *
 * <p><b>Monetary precision (AAP &sect;0.6.1).</b>  Both monetary fields
 * &mdash; {@link #currentBalance()} and {@link #amountPaid()} &mdash; are
 * typed as {@link BigDecimal} to preserve the exact COBOL fixed-point
 * decimal-arithmetic semantics of {@code ACCT-CURR-BAL PIC S9(10)V99} and
 * {@code TRAN-AMT PIC S9(09)V99}.  No {@code float}, no {@code double}, no
 * {@code long}-cents approximation.  Service-level arithmetic always uses
 * {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding), matching
 * the COBOL {@code PIC 9} default rounding behavior.  The underlying JPA
 * {@code Account} column is mapped {@code @Column(precision = 12,
 * scale = 2)} (digits_total + 1 for sign, scale = 2 for V99) and Hibernate
 * serializes the column as PostgreSQL {@code NUMERIC(12, 2)}.
 *
 * <p><b>Timestamp handling (AAP &sect;0.6.3).</b>  The {@link #postedAt()}
 * field carries the processing timestamp recorded when the transaction is
 * written to the database.  COBOL's CICS {@code ASKTIME}/{@code FORMATTIME}
 * pair (lines 251&ndash;261 of {@code COBIL00C.cbl}) is replaced by a
 * native {@link LocalDateTime} captured by {@code BillPaymentService} just
 * before the JPA {@code save}.  Jackson serializes/deserializes against
 * the ISO-8601 {@code yyyy-MM-dd'T'HH:mm:ss} pattern declared by
 * {@link JsonFormat @JsonFormat} on the {@link #postedAt()} component.
 * The 26-byte {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} fields on the
 * underlying {@code TRAN-RECORD} are persisted as PostgreSQL
 * {@code TIMESTAMP} columns; the wire representation is the same ISO-8601
 * literal regardless of database storage.
 *
 * <p><b>PCI-DSS handling (AAP &sect;0.6.6, &sect;0.7.1).</b>  This DTO
 * carries no Primary Account Number (PAN); the BMS map exchanges only the
 * 11-digit account identifier, never the 16-digit card number.  The card
 * number is resolved server-side via the cross-reference repository and
 * is never echoed to the client in this endpoint.  Account identifiers
 * are not classified as PAN under PCI-DSS Requirement 3.4 (a card number
 * is the PAN; the account identifier is an internal record key), so the
 * default record {@link Record#toString()} is retained &mdash; every
 * component is rendered verbatim and the auto-generated
 * {@link #equals(Object)} and {@link #hashCode()} methods compare every
 * component.
 *
 * <p>This DTO contains no business logic, no AWS-SDK references, and no
 * Lombok &mdash; it is a pure validated request/response envelope per
 * AAP &sect;0.3.3 (Layered Architecture).
 *
 * @param accountId      11-digit account identifier (request and response);
 *                       maps to BMS {@code ACTIDIN PIC X(11)} on the
 *                       {@code COBIL0A} screen and to
 *                       {@code ACCT-ID PIC 9(11)} on the
 *                       {@code ACCOUNT-RECORD} layout in
 *                       {@code app/cpy/CVACT01Y.cpy}; the only mandatory
 *                       request input alongside {@link #confirm()}
 * @param currentBalance server-populated current account balance to be
 *                       paid; maps to BMS {@code CURBAL PIC X(14)} on the
 *                       screen and to {@code ACCT-CURR-BAL PIC S9(10)V99}
 *                       on the {@code ACCOUNT-RECORD}; typed as
 *                       {@link BigDecimal} per AAP &sect;0.6.1; null in
 *                       the inbound request, populated by the service in
 *                       the response
 * @param confirm        single-character payment confirmation flag
 *                       ({@code Y} to pay the current balance in full,
 *                       {@code N} to cancel); maps to BMS
 *                       {@code CONFIRM PIC X(01)}; required on the inbound
 *                       request; not persisted on any record (transient
 *                       only)
 * @param transactionId  16-digit transaction identifier generated by the
 *                       service after successful posting; maps to
 *                       {@code TRAN-ID PIC 9(16)} on the
 *                       {@code TRAN-RECORD} layout in
 *                       {@code app/cpy/CVTRA05Y.cpy}; null in the inbound
 *                       request, populated by the service in the response
 *                       (synthetic with respect to the BMS map, which did
 *                       not display the transaction id)
 * @param postedAt       server-captured timestamp of the posted
 *                       transaction; maps to
 *                       {@code TRAN-PROC-TS PIC X(26)} on the
 *                       {@code TRAN-RECORD} layout; replaces CICS
 *                       {@code ASKTIME}/{@code FORMATTIME} per AAP
 *                       &sect;0.6.3; serialized as ISO-8601
 *                       {@code yyyy-MM-dd'T'HH:mm:ss} via Jackson; null
 *                       in the inbound request, populated by the service
 *                       in the response
 * @param amountPaid     server-populated amount of the payment (mirrors
 *                       {@link #currentBalance()} on confirmation; kept
 *                       separate to expose the canonical "amount paid"
 *                       field on the receipt); maps to
 *                       {@code TRAN-AMT PIC S9(09)V99} on the
 *                       {@code TRAN-RECORD}; typed as {@link BigDecimal}
 *                       per AAP &sect;0.6.1; null in the inbound request,
 *                       populated by the service in the response
 *
 * @see <a href=
 *      "https://github.com/aws-samples/aws-mainframe-modernization-carddemo">
 *      AWS CardDemo (source COBOL)</a>
 */
@Schema(name = "BillPaymentDto",
        description = "Bill payment request / confirmation receipt. Acts as the request body "
                + "for POST /api/billing/pay (caller supplies accountId and confirm) and as "
                + "the response body (service populates currentBalance, transactionId, "
                + "postedAt, and amountPaid). COBIL00 is a one-step \"pay the current balance "
                + "in full\" transaction: the user is shown the current balance, confirms "
                + "with 'Y', and the full balance is paid (a Transaction record is written, "
                + "the Account balance is zeroed, and a transaction.posted MSK event is "
                + "published partitioned by account id). Monetary fields use arbitrary-"
                + "precision decimal (BigDecimal) and the timestamp uses ISO-8601 "
                + "(yyyy-MM-dd'T'HH:mm:ss) per AAP §0.6.1 / §0.6.3.")
public record BillPaymentDto(

        @NotBlank(message = "Account ID is required")
        @Pattern(regexp = "^\\d{11}$",
                message = "Account ID must be exactly 11 digits")
        @Schema(description = "11-digit account identifier. Maps to BMS "
                        + "ACTIDIN PIC X(11) on the COBIL0A screen and to "
                        + "ACCT-ID PIC 9(11) on the ACCOUNT-RECORD "
                        + "(app/cpy/CVACT01Y.cpy). Required on the inbound "
                        + "request; echoed back unchanged in the response.",
                example = "00000000001",
                maxLength = 11,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("accountId")
        String accountId,

        @Schema(description = "Current account balance to be paid (server-populated, "
                        + "read-only). Maps to BMS CURBAL PIC X(14) on the COBIL0A screen "
                        + "(BMS edited picture +9999999999.99 = 14 characters) and to "
                        + "ACCT-CURR-BAL PIC S9(10)V99 on the ACCOUNT-RECORD. Typed as "
                        + "BigDecimal per AAP \u00a70.6.1 -- never float/double -- to "
                        + "preserve exact COBOL decimal-arithmetic semantics; the service "
                        + "performs all arithmetic with RoundingMode.HALF_EVEN (banker's "
                        + "rounding). Null in the inbound request; populated in the "
                        + "response.",
                example = "1234.56",
                format = "decimal",
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("currentBalance")
        BigDecimal currentBalance,

        @NotBlank(message = "Confirmation is required")
        @Pattern(regexp = "^[YN]$",
                message = "Confirmation must be 'Y' or 'N'")
        @Schema(description = "Payment confirmation flag: 'Y' to pay the full current "
                        + "balance, 'N' to cancel without writing. Maps to BMS CONFIRM "
                        + "PIC X(01) on the COBIL0A screen. Transient -- never persisted on "
                        + "any record; used by the service to gate the final write. The "
                        + "original COBOL program also accepts lowercase 'y'/'n' "
                        + "(line 174-179 of COBIL00C.cbl), but the REST contract normalizes "
                        + "to uppercase to keep the wire format unambiguous; clients should "
                        + "uppercase the value before sending.",
                example = "Y",
                maxLength = 1,
                allowableValues = {"Y", "N"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("confirm")
        String confirm,

        @Schema(description = "16-digit transaction identifier generated by the service "
                        + "after a successful payment posting (response only). Maps to "
                        + "TRAN-ID PIC 9(16) on the TRAN-RECORD layout in "
                        + "app/cpy/CVTRA05Y.cpy. Synthetic with respect to the BMS map "
                        + "(the original 3270 screen did not display the transaction id); "
                        + "anchored to the authoritative TRAN-RECORD column. Null in the "
                        + "inbound request; populated in the response.",
                example = "0000000000000001",
                maxLength = 16,
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("transactionId")
        String transactionId,

        @JsonFormat(shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Server-captured timestamp of the posted transaction "
                        + "(response only). Maps to TRAN-PROC-TS PIC X(26) on the "
                        + "TRAN-RECORD layout. Java native LocalDateTime replaces the CICS "
                        + "ASKTIME/FORMATTIME pair (lines 251-261 of COBIL00C.cbl) per AAP "
                        + "\u00a70.6.3 -- no LE CEEDAYS dependency. Serialized as "
                        + "ISO-8601 yyyy-MM-dd'T'HH:mm:ss via Jackson. Null in the inbound "
                        + "request; populated in the response.",
                example = "2026-05-20T12:34:56",
                format = "date-time",
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("postedAt")
        LocalDateTime postedAt,

        @Schema(description = "Amount paid by the service (response only). Mirrors "
                        + "currentBalance on a confirmed payment but kept as a separate "
                        + "component to expose the canonical \"amount paid\" field on the "
                        + "confirmation receipt -- this is the value written to "
                        + "TRAN-AMT PIC S9(09)V99 on the TRAN-RECORD layout in "
                        + "app/cpy/CVTRA05Y.cpy. Typed as BigDecimal per AAP \u00a70.6.1. "
                        + "Null in the inbound request; populated in the response.",
                example = "1234.56",
                format = "decimal",
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("amountPaid")
        BigDecimal amountPaid

) {
}
