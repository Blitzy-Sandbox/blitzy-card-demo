package com.carddemo.transaction.web;

import com.carddemo.transaction.api.TransactionsApi;
import com.carddemo.transaction.model.Transaction;
import com.carddemo.transaction.model.TransactionCreateRequest;
import com.carddemo.transaction.model.TransactionListResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.UUID;

/**
 * CardDemo Transaction Service — web/REST layer.
 *
 * <p>REST entrypoint for the {@code transaction-svc} bounded context of the CardDemo
 * walking skeleton (Spring Boot 3.5.16 / Java 21). This is the single hand-written
 * class in the {@code com.carddemo.transaction.web} package; it {@code implements} the
 * contract-first, OpenAPI-generated {@link TransactionsApi} interface (emitted under
 * {@code target/generated-sources/openapi} from the frozen single-source-of-truth
 * contract {@code contracts/transaction-svc.openapi.yaml}).</p>
 *
 * <p><strong>All three operations are {@code [DEFERRED]} typed stubs.</strong> Each
 * endpoint returns a well-formed, typed placeholder DTO only — there is no transaction
 * persistence, no repository, no service, no JPA entity and no business logic in the
 * walking skeleton. Returning a typed placeholder is the intended, correct outcome; a
 * hallucinated real implementation would be a failure. Request/response validation is
 * enforced by the bean-validation annotations that the generated interface already
 * carries (size / pattern / not-null); this controller re-declares none of them and
 * carries only the override annotation on each method. The service is request-serving
 * and health-gated — the health endpoint is served by Spring Boot Actuator, never by a
 * controller here — so this class deliberately implements the generated Transactions API
 * interface only and never the generated Health API interface.</p>
 *
 * <p>The {@code X-Correlation-ID} header is accepted (as generated) and intentionally
 * ignored: correlation-ID propagation onto the SLF4J logging context is owned by the
 * sibling {@code config/CorrelationIdFilter}, not by this controller.</p>
 *
 * <p>Placeholder field values mirror the legacy {@code TRAN-RECORD} layout
 * [app/cpy/CVTRA05Y.cpy] and respect every contract constraint (max length / pattern).</p>
 *
 * <p>Provenance: {@code [SRC: COTRN00C/COTRN01C/COTRN02C | TRANSACT]} — the legacy CICS
 * Transaction List ({@code CT00} → {@code COTRN00C}, "List Transactions from TRANSACT
 * file"), Transaction View ({@code CT01} → {@code COTRN01C}, "View a Transaction from
 * TRANSACT file") and Transaction Add ({@code CT02} → {@code COTRN02C}, "Add a new
 * Transaction to TRANSACT file") programs over the {@code TRANSACT} VSAM KSDS
 * [app/csd/CARDDEMO.CSD].</p>
 */
@RestController
public class TransactionController implements TransactionsApi {

    /** Default zero-based page index when the caller omits {@code page}. */
    private static final int DEFAULT_PAGE = 0;

    /** Default page size when the caller omits {@code size} (mirrors the contract default). */
    private static final int DEFAULT_SIZE = 20;

    // ---------------------------------------------------------------------------------
    // Typed placeholder field values, sourced from the legacy TRAN-RECORD copybook
    // [app/cpy/CVTRA05Y.cpy]. Compile-time constants only — no computation, no I/O.
    // Every value respects the frozen contract's maxLength / pattern constraints.
    // ---------------------------------------------------------------------------------

    /** TRAN-ID PIC X(16) → transactionId (required; server-assigned in a real add). */
    private static final String PLACEHOLDER_TRANSACTION_ID = "0000000000000001";

    /** TRAN-CARD-NUM PIC X(16) → cardNumber (required; pattern ^[0-9]{16}$). */
    private static final String PLACEHOLDER_CARD_NUMBER = "4000000000000001";

    /** TRAN-AMT PIC S9(09)V99 → amount (required; decimal string ^-?\d{1,9}(\.\d{1,2})?$). */
    private static final String PLACEHOLDER_AMOUNT = "100.00";

    /** TRAN-TYPE-CD PIC X(02) → typeCode (maxLength 2). */
    private static final String PLACEHOLDER_TYPE_CODE = "01";

    /** TRAN-CAT-CD PIC 9(04) → categoryCode (pattern ^[0-9]{1,4}$). */
    private static final String PLACEHOLDER_CATEGORY_CODE = "0001";

    /** TRAN-SOURCE PIC X(10) → source (maxLength 10). */
    private static final String PLACEHOLDER_SOURCE = "POS";

    /** TRAN-DESC PIC X(100) → description (maxLength 100). */
    private static final String PLACEHOLDER_DESCRIPTION = "[DEFERRED] placeholder transaction";

    /** TRAN-MERCHANT-ID PIC 9(09) → merchantId (pattern ^[0-9]{1,9}$). */
    private static final String PLACEHOLDER_MERCHANT_ID = "000000001";

    /** TRAN-MERCHANT-NAME PIC X(50) → merchantName (maxLength 50). */
    private static final String PLACEHOLDER_MERCHANT_NAME = "PLACEHOLDER MERCHANT";

    /** TRAN-MERCHANT-CITY PIC X(50) → merchantCity (maxLength 50). */
    private static final String PLACEHOLDER_MERCHANT_CITY = "PLACEHOLDER CITY";

    /** TRAN-MERCHANT-ZIP PIC X(10) → merchantZip (maxLength 10). */
    private static final String PLACEHOLDER_MERCHANT_ZIP = "00000";

    /** TRAN-ORIG-TS / TRAN-PROC-TS PIC X(26) → origin/process timestamp (legacy CICS ABSTIME, 26 chars). */
    private static final String PLACEHOLDER_TIMESTAMP = "2024-01-01-00.00.00.000000";

    /**
     * List transactions — {@code GET /transactions} ({@code [DEFERRED]}, {@code [SRC: COTRN00C]}).
     *
     * <p>Returns a well-formed, empty page. The requested {@code page}/{@code size} are
     * echoed back (defaulting to {@value #DEFAULT_PAGE}/{@value #DEFAULT_SIZE} when null,
     * which can happen only when the method is invoked directly in a test since the
     * generated interface applies {@code defaultValue}s at the HTTP boundary). No filter
     * arguments are consulted — this is a stub, not a query.</p>
     */
    @Override
    public ResponseEntity<TransactionListResponse> listTransactions(
            UUID xCorrelationID, String cardNumber, String accountId, Integer page, Integer size) {
        // [DEFERRED] typed stub — no TRANSACT read (see COTRN00C); returns an empty, well-formed page.
        int effectivePage = (page != null) ? page : DEFAULT_PAGE;
        int effectiveSize = (size != null) ? size : DEFAULT_SIZE;
        TransactionListResponse body = new TransactionListResponse()
                .items(Collections.emptyList())
                .page(effectivePage)
                .size(effectiveSize)
                .totalItems(0L)
                .totalPages(0);
        return ResponseEntity.ok(body);
    }

    /**
     * View a transaction — {@code GET /transactions/{transactionId}}
     * ({@code [DEFERRED]}, {@code [SRC: COTRN01C]}).
     *
     * <p>Returns a typed placeholder {@link Transaction} with HTTP 200 (never 404 — a
     * typed placeholder is the correct stub outcome). The requested {@code transactionId}
     * is echoed into the placeholder for realism when present; no lookup is performed.</p>
     */
    @Override
    public ResponseEntity<Transaction> getTransaction(String transactionId, UUID xCorrelationID) {
        // [DEFERRED] typed stub — no TRANSACT read (see COTRN01C); returns a placeholder Transaction.
        Transaction placeholder = placeholderTransaction();
        if (transactionId != null) {
            placeholder.transactionId(transactionId);
        }
        return ResponseEntity.ok(placeholder);
    }

    /**
     * Add a transaction — {@code POST /transactions} ({@code [DEFERRED]}, {@code [SRC: COTRN02C]}).
     *
     * <p>Returns a typed placeholder {@link Transaction} with a server-assigned identifier
     * and HTTP 201 Created. Malformed request bodies are rejected with HTTP 400 by the
     * generated interface's bean validation before this method is reached; the body is
     * otherwise not persisted. A couple of submitted fields ({@code cardNumber},
     * {@code amount}) are echoed into the placeholder for realism when present.</p>
     */
    @Override
    public ResponseEntity<Transaction> createTransaction(
            TransactionCreateRequest transactionCreateRequest, UUID xCorrelationID) {
        // [DEFERRED] typed stub — no TRANSACT write (see COTRN02C); echoes a placeholder Transaction with a server-assigned id.
        Transaction placeholder = placeholderTransaction();
        if (transactionCreateRequest != null) {
            if (transactionCreateRequest.getCardNumber() != null) {
                placeholder.cardNumber(transactionCreateRequest.getCardNumber());
            }
            if (transactionCreateRequest.getAmount() != null) {
                placeholder.amount(transactionCreateRequest.getAmount());
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(placeholder);
    }

    /**
     * Assemble the fully-populated, typed placeholder {@link Transaction} from compile-time
     * constants. Contains zero business logic, no I/O and no computation — it merely wires
     * the constant field values through the generated model's fluent setters.
     *
     * @return a new, constraint-respecting placeholder {@link Transaction}
     */
    private static Transaction placeholderTransaction() {
        return new Transaction()
                .transactionId(PLACEHOLDER_TRANSACTION_ID)
                .cardNumber(PLACEHOLDER_CARD_NUMBER)
                .amount(PLACEHOLDER_AMOUNT)
                .typeCode(PLACEHOLDER_TYPE_CODE)
                .categoryCode(PLACEHOLDER_CATEGORY_CODE)
                .source(PLACEHOLDER_SOURCE)
                .description(PLACEHOLDER_DESCRIPTION)
                .merchantId(PLACEHOLDER_MERCHANT_ID)
                .merchantName(PLACEHOLDER_MERCHANT_NAME)
                .merchantCity(PLACEHOLDER_MERCHANT_CITY)
                .merchantZip(PLACEHOLDER_MERCHANT_ZIP)
                .originTimestamp(PLACEHOLDER_TIMESTAMP)
                .processTimestamp(PLACEHOLDER_TIMESTAMP);
    }
}
