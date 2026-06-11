package com.cardemo.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * REST request payload for the CardDemo <strong>Bill Payment</strong> screen.
 *
 * <p>This Data Transfer Object is the Java 25 / Spring Boot 3.x replacement for the
 * two <em>input</em> fields that the legacy 3270 bill-payment map received. On the
 * mainframe the screen was BMS map {@code COBIL0A} (mapset {@code COBIL00}, online
 * transaction {@code CB00}); its symbolic map is defined by the copybook
 * {@code app/cpy-bms/COBIL00.CPY}. The online program {@code app/cbl/COBIL00C.cbl}
 * issued {@code RECEIVE MAP('COBIL0A')} to read the operator's keystrokes into the
 * symbolic input structure {@code COBIL0AI}, looked up the account, painted the
 * current balance back to the screen and &mdash; on a {@code 'Y'} confirmation
 * &mdash; paid the <strong>full current balance</strong>: it generated a payment
 * transaction and decremented the account balance in a single unit of work.</p>
 *
 * <p>In the migrated system this DTO is bound by {@code controller/BillingController}
 * to {@code POST /api/billing/pay} (blueprint {@code docs/technical-specifications.md}
 * L650): Jackson deserializes the JSON request body into an instance, Jakarta Bean
 * Validation enforces the field constraints declared below (the {@code @Valid} gate
 * on the controller method), and the validated payload is handed to
 * {@code service/billing/BillPaymentService} (the translation of {@code COBIL00C},
 * blueprint L630), which performs the &quot;account balance update + transaction
 * create in a single transaction&quot; under Spring {@code @Transactional}. This DTO
 * is therefore a pure boundary type &mdash; it carries no business logic, no I/O and
 * no static state.</p>
 *
 * <h2>Original COBOL symbolic input map (COBIL00.CPY &mdash; {@code 01 COBIL0AI})</h2>
 * <p>The 3270 symbolic map declared nine on-screen fields, but only two of them were
 * operator <em>inputs</em>; the remainder were presentation chrome, the displayed
 * balance, the error line, or BMS per-field control bytes. The two modeled inputs
 * are:</p>
 * <pre>{@code
 * 01  COBIL0AI.
 *     ...
 *     02  ACTIDINI  PIC X(11).  <-- modeled here as accountId
 *     ...
 *     02  CURBALI   PIC X(14).  <-- displayed balance: RESPONSE only (see Response.currentBalance)
 *     ...
 *     02  CONFIRMI  PIC X(1).   <-- modeled here as confirm (Y/N)
 *     ...
 * }</pre>
 *
 * <h2>Scope &mdash; exactly two request fields are modeled (AAP &sect;0.4.2)</h2>
 * <p>The following symbolic-map members are <strong>deliberately excluded</strong>
 * from the request because they are 3270 presentation/control artifacts (or a
 * server-authoritative value) with no REST request equivalent:</p>
 * <ul>
 *   <li><strong>Screen chrome (output-only):</strong> {@code TRNNAMEI} ({@code X(4)}),
 *       {@code TITLE01I}/{@code TITLE02I} ({@code X(40)}), {@code CURDATEI}
 *       ({@code X(8)}), {@code CURTIMEI} ({@code X(8)}) and {@code PGMNAMEI}
 *       ({@code X(8)}) &mdash; banner, titles, date/time and program identifiers
 *       painted by the program, never typed by the user.</li>
 *   <li><strong>Displayed balance (server-authoritative):</strong> {@code CURBALI}
 *       ({@code X(14)}) &mdash; the current balance the screen <em>showed</em>. It is
 *       never accepted as an input; the server reads the authoritative balance from
 *       the account record. It is surfaced on the {@link Response} as
 *       {@link Response#currentBalance} (a {@link BigDecimal} of scale 2).</li>
 *   <li><strong>Error line (output-only):</strong> {@code ERRMSGI} ({@code X(78)})
 *       &mdash; the message the program wrote back (for example
 *       {@code 'Confirm to make a bill payment...'}); in REST this becomes the
 *       {@link Response#message} / error body, not a request field.</li>
 *   <li><strong>BMS per-field control bytes:</strong> the {@code ...L} (length),
 *       {@code ...F}/{@code ...A} (attribute/flag), and the redefined output bytes
 *       {@code ...C}/{@code ...P}/{@code ...H}/{@code ...V}/{@code ...O} in the
 *       {@code COBIL0AO} output redefinition &mdash; 3270 datastream metadata with no
 *       REST analogue.</li>
 *   <li><strong>AID / PF keys:</strong> the {@code DFHAID} attention identifiers
 *       (ENTER, PF3, &hellip;) do not map onto a request body; each AID maps to a
 *       distinct REST endpoint instead (AAP &sect;0.4.2).</li>
 * </ul>
 *
 * <h2>Technology-substitution notes (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code RECEIVE MAP} &rarr; REST request DTO.</strong> The CICS
 *       map-receive that populated {@code COBIL0AI} is replaced by Jackson binding a
 *       JSON body to this object; the two character inputs become {@link String}
 *       properties.</li>
 *   <li><strong>Byte-faithful field lengths.</strong> {@code ACTIDINI} was
 *       {@code PIC X(11)} and {@code CONFIRMI} was {@code PIC X(1)}; the
 *       {@link Size @Size} constraints preserve those external-interface widths
 *       exactly (AAP &sect;0.7.2).</li>
 *   <li><strong>Decimal fidelity (AAP &sect;0.7.3).</strong> The monetary balance is a
 *       <em>response</em> value only and is modeled on the nested {@link Response} as
 *       a {@link BigDecimal} of scale 2 &mdash; <strong>never</strong> {@code float}
 *       or {@code double}. The request itself carries no numeric/decimal field.</li>
 *   <li><strong>Bill-pay semantics.</strong> Bill payment clears the
 *       <em>full</em> current balance: {@code COBIL00C} moved {@code ACCT-CURR-BAL}
 *       into {@code TRAN-AMT}, wrote the payment transaction, then computed
 *       {@code ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}. That atomic
 *       &quot;update balance + create transaction&quot; stays in the
 *       {@code @Transactional} {@code BillPaymentService}, <strong>not</strong> in
 *       this DTO; the amount is therefore not an input.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit
 * SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is never
 * copied into this repository.</p>
 *
 * @see jakarta.validation.constraints.NotBlank
 * @see jakarta.validation.constraints.Size
 * @see jakarta.validation.constraints.Pattern
 * @see java.math.BigDecimal
 */
public class BillPaymentRequest {

    /**
     * The account id typed on the bill-payment screen.
     *
     * <p>Migrated from {@code ACTIDINI PIC X(11)} &mdash; an eleven-character numeric
     * account identifier. {@link NotBlank @NotBlank} reproduces the COBOL edit in
     * {@code COBIL00C} that rejected an empty account id, {@link Size @Size(max = 11)}
     * preserves the original field width byte-for-byte, and
     * {@link Pattern @Pattern} enforces a digit-only shape (one to eleven digits) so
     * the value is a well-formed account key. Kept as a fixed-width {@link String} so
     * any leading zeros are preserved exactly.</p>
     */
    // ACTIDIN PIC X(11) -> 11-char numeric account id -> String (1..11 digits, not blank)
    @NotBlank(message = "Account ID must be supplied")
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    @Pattern(regexp = "\\d{1,11}", message = "Account ID must be 1 to 11 digits")
    private String accountId;

    /**
     * The single-character payment confirmation flag.
     *
     * <p>Migrated from {@code CONFIRMI PIC X(1)} &mdash; the operator's confirmation
     * keystroke. In {@code COBIL00C} this drove the working-storage flag
     * {@code WS-CONF-PAY-FLG} whose condition names were
     * {@code 88 CONF-PAY-YES VALUE 'Y'} and {@code 88 CONF-PAY-NO VALUE 'N'}: a
     * {@code 'Y'} (or {@code 'y'}) authorized the payment, an {@code 'N'} (or
     * {@code 'n'}) declined it, and any other value (including blank) caused the
     * program to re-prompt with {@code 'Confirm to make a bill payment...'}.</p>
     *
     * <p>Modeled as a {@link String} of length one to remain byte-faithful to the
     * {@code PIC X(1)} field (rather than a {@code Character} or {@code Boolean}); the
     * {@code Y}/{@code N} interpretation is performed downstream by
     * {@code BillPaymentService}. Only {@link Size @Size(max = 1)} is enforced here so
     * that, exactly like the COBOL program, an unrecognized value is re-prompted by
     * the service rather than hard-rejected at the boundary.</p>
     */
    // CONFIRM PIC X(1) -> single-character Y/N confirmation -> String (max length 1); Y/y=pay, N/n=decline
    @Size(max = 1, message = "Confirmation flag must be a single character")
    private String confirm;

    /**
     * Default no-argument constructor required by the JSON binder (Jackson)
     * to instantiate this DTO reflectively before populating its properties.
     */
    public BillPaymentRequest() {
        // Intentionally empty: Jackson instantiates then sets fields via setters.
    }

    /**
     * Constructs a fully-populated bill-payment request.
     *
     * <p>Parameter order mirrors the COBOL symbolic-map field order: account id
     * ({@code ACTIDINI}) first, then the confirmation flag ({@code CONFIRMI}).</p>
     *
     * @param accountId the account id ({@code ACTIDINI}); 1 to 11 digits, not blank
     * @param confirm   the confirmation flag ({@code CONFIRMI}); a single character,
     *                  {@code "Y"}/{@code "N"} (case-insensitive)
     */
    public BillPaymentRequest(String accountId, String confirm) {
        this.accountId = accountId;
        this.confirm = confirm;
    }

    /**
     * Returns the entered account id ({@code ACTIDINI}).
     *
     * @return the account id, or {@code null} if unset
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the account id ({@code ACTIDINI}).
     *
     * @param accountId the account id to set
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the payment confirmation flag ({@code CONFIRMI}).
     *
     * @return the single-character confirmation flag ({@code "Y"}/{@code "N"}), or
     *         {@code null} if unset
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets the payment confirmation flag ({@code CONFIRMI}).
     *
     * @param confirm the single-character confirmation flag to set
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * Value-based equality across both request fields.
     *
     * <p>Two instances are equal only when {@code o} is exactly a
     * {@code BillPaymentRequest} and both {@code accountId} and {@code confirm}
     * match.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is an equal bill-payment request
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BillPaymentRequest that = (BillPaymentRequest) o;
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(confirm, that.confirm);
    }

    /**
     * Hash code derived from both request fields, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this request
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId, confirm);
    }

    /**
     * Diagnostic representation of this request.
     *
     * <p>Both fields are non-sensitive (an account id and a single-character
     * confirmation flag), so they are shown verbatim; no value is masked.</p>
     *
     * @return a human-readable description of this request
     */
    @Override
    public String toString() {
        return "BillPaymentRequest{"
                + "accountId='" + accountId + '\''
                + ", confirm='" + confirm + '\''
                + '}';
    }

    /**
     * Response payload for the CardDemo <strong>Bill Payment</strong> screen
     * ({@code COBIL00}) &mdash; the display/result contract returned by
     * {@code POST /api/billing/pay}.
     *
     * <p>This nested type carries the values the legacy {@code COBIL0A} map either
     * <em>displayed</em> to the operator or produced as the outcome of a confirmed
     * payment. It is populated by {@code service/billing/BillPaymentService} (the
     * translation of {@code COBIL00C}) and serialized back to the caller; it is a
     * pure data holder with no business logic and no I/O.</p>
     *
     * <p><strong>Bill-pay semantics.</strong> A confirmed bill payment clears the
     * <em>full</em> current balance in one transaction: {@code COBIL00C} displayed
     * {@code ACCT-CURR-BAL} in {@code CURBALI}, moved it into {@code TRAN-AMT}, wrote
     * the payment transaction (id generated by browsing {@code TRANSACT} to the end
     * and incrementing &mdash; {@code WS-TRAN-ID-NUM PIC 9(16)}), then computed
     * {@code ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}. Accordingly
     * {@link #currentBalance} is the balance <em>before</em> payment and
     * {@link #newBalance} is the balance <em>after</em> it (zero after a full
     * payment).</p>
     *
     * <p><strong>Decimal fidelity (AAP &sect;0.7.3).</strong> Every monetary field is
     * a {@link BigDecimal} of <strong>scale 2</strong> &mdash; <strong>never</strong>
     * {@code float} or {@code double}. Callers requiring penny-level parity must
     * compare amounts with {@link BigDecimal#compareTo(BigDecimal)} rather than the
     * scale-sensitive {@link BigDecimal#equals(Object)}.</p>
     */
    public static class Response {

        /**
         * The current account balance the screen displayed &mdash; the amount the
         * payment will clear.
         *
         * <p>Migrated from {@code CURBALI PIC X(14)} on screen, backed by the account
         * record's {@code ACCT-CURR-BAL PIC S9(10)V99} (working-storage edit field
         * {@code WS-CURR-BAL PIC +9999999999.99}). Modeled as a {@link BigDecimal} of
         * scale 2; {@link Digits @Digits(integer = 10, fraction = 2)} locks the
         * precision. This value is server-authoritative: it is read from the account,
         * never accepted from the request.</p>
         */
        // COBOL substitution: CURBAL PIC X(14) / ACCT-CURR-BAL PIC S9(10)V99 -> BigDecimal scale 2 (NEVER float/double);
        //                     balance displayed = the full amount a confirmed bill payment clears in one @Transactional unit
        @Digits(integer = 10, fraction = 2, message = "Current balance must have at most 10 integer and 2 fraction digits")
        private BigDecimal currentBalance;

        /**
         * The identifier of the payment transaction created by a confirmed payment.
         *
         * <p>Migrated from the generated {@code TRAN-ID} / {@code WS-TRAN-ID-NUM
         * PIC 9(16)}: {@code COBIL00C} browsed the {@code TRANSACT} file to the last
         * key and added one to obtain the next id. Kept as a fixed-width
         * {@link String} of at most sixteen characters so leading zeros are preserved
         * exactly. {@code null} when no payment was made (for example a declined or
         * unconfirmed request).</p>
         */
        // TRAN-ID / WS-TRAN-ID-NUM PIC 9(16) -> 16-char generated transaction id -> String(16)
        @Size(max = 16, message = "Transaction ID must not exceed 16 characters")
        private String transactionId;

        /**
         * The account balance after the payment was applied.
         *
         * <p>Result of {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} in
         * {@code COBIL00C}; because bill payment clears the full balance this is
         * normally zero after a confirmed payment. Modeled as a {@link BigDecimal} of
         * scale 2; {@link Digits @Digits(integer = 10, fraction = 2)} locks the
         * precision. Equal to {@link #currentBalance} when no payment was made.</p>
         */
        // COBOL substitution: ACCT-CURR-BAL after COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
        //                     -> BigDecimal scale 2 (NEVER float/double); ~0 after a full bill payment
        @Digits(integer = 10, fraction = 2, message = "New balance must have at most 10 integer and 2 fraction digits")
        private BigDecimal newBalance;

        /**
         * The human-readable result or prompt message.
         *
         * <p>Migrated from the {@code ERRMSGI PIC X(78)} error/message line that
         * {@code COBIL00C} painted back to the screen &mdash; for example
         * {@code 'Confirm to make a bill payment...'} when confirmation was required,
         * or a success/validation message. Kept at the original 78-character width.</p>
         */
        // ERRMSG PIC X(78) -> 78-char screen message line -> String(78) (prompt/success/validation text)
        @Size(max = 78, message = "Message must not exceed 78 characters")
        private String message;

        /**
         * The user-facing confirmation reference for a completed payment.
         *
         * <p>A REST convenience that surfaces the payment reference (the generated
         * payment {@link #transactionId}) as a confirmation number the caller can
         * quote. Kept at the sixteen-character transaction-id width. {@code null}
         * when no payment was made.</p>
         */
        // Confirmation reference for a completed bill payment (the generated transaction id) -> String(16)
        @Size(max = 16, message = "Confirmation number must not exceed 16 characters")
        private String confirmationNumber;

        /**
         * Default no-argument constructor required by the JSON binder (Jackson)
         * to instantiate this response reflectively before populating its properties.
         */
        public Response() {
            // Intentionally empty: Jackson instantiates then sets fields via setters.
        }

        /**
         * Constructs a fully-populated bill-payment response.
         *
         * @param currentBalance     the balance before payment ({@code CURBAL} /
         *                           {@code ACCT-CURR-BAL}); scale 2
         * @param transactionId      the generated payment transaction id
         *                           ({@code TRAN-ID}); up to 16 characters
         * @param newBalance         the balance after payment; scale 2
         * @param message            the result/prompt message ({@code ERRMSG}); up to
         *                           78 characters
         * @param confirmationNumber the user-facing confirmation reference; up to 16
         *                           characters
         */
        public Response(BigDecimal currentBalance,
                        String transactionId,
                        BigDecimal newBalance,
                        String message,
                        String confirmationNumber) {
            this.currentBalance = currentBalance;
            this.transactionId = transactionId;
            this.newBalance = newBalance;
            this.message = message;
            this.confirmationNumber = confirmationNumber;
        }

        /**
         * Returns the balance displayed before payment ({@code CURBAL} /
         * {@code ACCT-CURR-BAL}).
         *
         * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)},
         * never the scale-sensitive {@link BigDecimal#equals(Object)} (AAP
         * &sect;0.7.3).</p>
         *
         * @return the pre-payment balance as a {@link BigDecimal} of scale 2, or
         *         {@code null} if unset
         */
        public BigDecimal getCurrentBalance() {
            return currentBalance;
        }

        /**
         * Sets the balance displayed before payment.
         *
         * <p>This holder does not rescale; the caller supplies a scale-2
         * {@link BigDecimal} (AAP &sect;0.7.3).</p>
         *
         * @param currentBalance the pre-payment balance to set (scale 2)
         */
        public void setCurrentBalance(BigDecimal currentBalance) {
            this.currentBalance = currentBalance;
        }

        /**
         * Returns the generated payment transaction id ({@code TRAN-ID}).
         *
         * @return the transaction id, or {@code null} if no payment was made
         */
        public String getTransactionId() {
            return transactionId;
        }

        /**
         * Sets the generated payment transaction id ({@code TRAN-ID}).
         *
         * @param transactionId the transaction id to set
         */
        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }

        /**
         * Returns the balance after payment.
         *
         * <p>Compare returned values with {@link BigDecimal#compareTo(BigDecimal)},
         * never the scale-sensitive {@link BigDecimal#equals(Object)} (AAP
         * &sect;0.7.3).</p>
         *
         * @return the post-payment balance as a {@link BigDecimal} of scale 2, or
         *         {@code null} if unset
         */
        public BigDecimal getNewBalance() {
            return newBalance;
        }

        /**
         * Sets the balance after payment.
         *
         * <p>This holder does not rescale; the caller supplies a scale-2
         * {@link BigDecimal} (AAP &sect;0.7.3).</p>
         *
         * @param newBalance the post-payment balance to set (scale 2)
         */
        public void setNewBalance(BigDecimal newBalance) {
            this.newBalance = newBalance;
        }

        /**
         * Returns the result/prompt message ({@code ERRMSG}).
         *
         * @return the message, or {@code null} if unset
         */
        public String getMessage() {
            return message;
        }

        /**
         * Sets the result/prompt message ({@code ERRMSG}).
         *
         * @param message the message to set
         */
        public void setMessage(String message) {
            this.message = message;
        }

        /**
         * Returns the user-facing confirmation reference.
         *
         * @return the confirmation number, or {@code null} if no payment was made
         */
        public String getConfirmationNumber() {
            return confirmationNumber;
        }

        /**
         * Sets the user-facing confirmation reference.
         *
         * @param confirmationNumber the confirmation number to set
         */
        public void setConfirmationNumber(String confirmationNumber) {
            this.confirmationNumber = confirmationNumber;
        }

        /**
         * Value-based equality across all response fields.
         *
         * <p>The {@link BigDecimal} balances participate via
         * {@link Objects#equals(Object, Object)}; note this is scale-sensitive, so for
         * penny-level numeric parity callers should compare the individual amounts
         * with {@link BigDecimal#compareTo(BigDecimal)} (AAP &sect;0.7.3).</p>
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is an equal response
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Response that = (Response) o;
            return Objects.equals(currentBalance, that.currentBalance)
                    && Objects.equals(transactionId, that.transactionId)
                    && Objects.equals(newBalance, that.newBalance)
                    && Objects.equals(message, that.message)
                    && Objects.equals(confirmationNumber, that.confirmationNumber);
        }

        /**
         * Hash code derived from all response fields, consistent with
         * {@link #equals(Object)}.
         *
         * @return the hash code for this response
         */
        @Override
        public int hashCode() {
            return Objects.hash(currentBalance, transactionId, newBalance, message,
                    confirmationNumber);
        }

        /**
         * Diagnostic representation of this response. All fields are non-sensitive
         * and are shown verbatim.
         *
         * @return a human-readable description of this response
         */
        @Override
        public String toString() {
            return "BillPaymentRequest.Response{"
                    + "currentBalance=" + currentBalance
                    + ", transactionId='" + transactionId + '\''
                    + ", newBalance=" + newBalance
                    + ", message='" + message + '\''
                    + ", confirmationNumber='" + confirmationNumber + '\''
                    + '}';
        }
    }
}
