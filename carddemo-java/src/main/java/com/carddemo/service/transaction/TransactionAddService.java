package com.carddemo.service.transaction;

import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionAddRequest;
import com.carddemo.model.dto.TransactionAddResponse;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.shared.DateValidationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Application service that adds a new financial transaction.
 *
 * <p>The single public operation, {@link #addTransaction(TransactionAddRequest)}, runs the full
 * input-field validation cascade, enforces an explicit confirmation gate, generates the next
 * sixteen-digit transaction identifier, persists the record, and returns the stored values to the
 * caller.</p>
 *
 * <p>Field-level rejections are reported as {@link ValidationException}. The sixteen-digit
 * identifier is allocated by browsing to the highest existing key and adding one; because that
 * allocation is not atomic, a concurrent add that derives the same identifier is detected on the
 * immediate flush and the write is transparently retried against the new maximum, so simultaneous
 * adds each receive a distinct identifier instead of surfacing a persistence failure. A collision
 * that cannot be resolved within the retry bound is reported as a {@link DuplicateRecordException}.
 * The monetary amount is handled exclusively as {@link BigDecimal} at scale two so that the exact
 * fixed-point precision of the stored money value is preserved; no binary floating-point type is
 * used.</p>
 */
@Service
public class TransactionAddService {

    /** Fixed-format mask producing a sixteen-digit, zero-padded transaction identifier. */
    private static final String TRAN_ID_FORMAT = "%016d";

    /**
     * Upper bound on identifier-allocation attempts. The browse-to-end identifier allocation is not
     * atomic, so a concurrent add can derive the same identifier; each collision re-derives the
     * identifier from the new maximum and retries. The bound is generous relative to any realistic
     * number of simultaneous adders and guards against an unbounded loop under pathological
     * contention, after which the collision is reported as a duplicate.
     */
    private static final int MAX_ID_GENERATION_ATTEMPTS = 25;

    /** Fixed-format mask producing a four-digit, zero-padded category code for the response. */
    private static final String CATEGORY_CODE_FORMAT = "%04d";

    /** Fixed-format mask producing a nine-digit, zero-padded merchant identifier for the response. */
    private static final String MERCHANT_ID_FORMAT = "%09d";

    /** COBOL picture string passed to the date-validation service for both transaction dates. */
    private static final String DATE_FORMAT = "YYYY-MM-DD";

    /** Key-edit message: the account id was supplied but is not numeric ({@code COTRN02C}). */
    private static final String MSG_ACCT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

    /** Key-edit message: the card number was supplied but is not numeric ({@code COTRN02C}). */
    private static final String MSG_CARD_NUM_NOT_NUMERIC = "Card Number must be Numeric...";

    /** Key-edit message: neither an account id nor a card number was supplied ({@code COTRN02C}). */
    private static final String MSG_KEY_REQUIRED = "Account or Card Number must be entered...";

    /** Not-found message for an account id with no cross-reference ({@code COTRN02C} {@code READ-CXACAIX-FILE} NOTFND). */
    private static final String MSG_ACCT_ID_NOT_FOUND = "Account ID NOT found...";

    /** Not-found message for a card number with no cross-reference ({@code COTRN02C} {@code READ-CCXREF-FILE} NOTFND). */
    private static final String MSG_CARD_NUM_NOT_FOUND = "Card Number NOT found...";

    private final TransactionRepository transactionRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final DateValidationService dateValidationService;
    private final MeterRegistry meterRegistry;

    /**
     * Creates the service with its collaborators supplied by the Spring container.
     *
     * @param transactionRepository        repository used for identifier lookup and persistence
     * @param cardCrossReferenceRepository  CARDXREF / CXACAIX access used to validate and resolve
     *                                      the account/card key pairing ({@code COTRN02C}
     *                                      {@code READ-CXACAIX-FILE} / {@code READ-CCXREF-FILE})
     * @param dateValidationService         service that validates the origination and processing dates
     * @param meterRegistry                 registry for the {@code carddemo.transaction.amount.total} counter
     */
    public TransactionAddService(TransactionRepository transactionRepository,
                                 CardCrossReferenceRepository cardCrossReferenceRepository,
                                 DateValidationService dateValidationService,
                                 MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.dateValidationService = dateValidationService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Validates, confirms, and persists a new transaction.
     *
     * <p>Processing reproduces the {@code COTRN02C.PROCESS-ENTER-KEY} ordering exactly: the
     * <em>key-field</em> validation/resolution stage runs first, then the data-field validation
     * cascade, then the confirmation gate, and finally identifier generation plus persistence. The
     * first failing check short-circuits the operation with the corresponding exception.</p>
     *
     * <p>The key stage ({@code VALIDATE-INPUT-KEY-FIELDS}) accepts <em>either</em> an account id or
     * a card number with account-id-first precedence: a supplied account id is validated numeric and
     * resolved against the cross-reference ({@code READ-CXACAIX-FILE}) to obtain the authoritative
     * card number; otherwise a supplied card number is validated numeric and resolved
     * ({@code READ-CCXREF-FILE}) to obtain its account id. The transaction is therefore always
     * persisted with the resolved, mutually consistent account/card pairing &mdash; never an
     * arbitrary client-supplied card &mdash; and a key that does not exist in the cross-reference is
     * rejected before any data-field validation.</p>
     *
     * @param request the transaction-add request payload
     * @return the persisted transaction, including its service-generated identifier and the resolved
     *         account id / card number, with no error message on the success path
     * @throws ValidationException      if neither key is supplied, a supplied key is non-numeric, any
     *                                  field fails the validation cascade, or the confirmation flag is
     *                                  absent, {@code N}/{@code n}, or any value other than {@code Y}/{@code y}
     * @throws RecordNotFoundException  if the supplied account id or card number has no cross-reference
     * @throws DuplicateRecordException if a unique identifier cannot be allocated within the retry
     *                                  bound under sustained concurrent contention
     */
    public TransactionAddResponse addTransaction(TransactionAddRequest request) {
        // VALIDATE-INPUT-KEY-FIELDS: resolve the account/card pairing before any data-field edits.
        ResolvedKey resolvedKey = validateAndResolveKeyFields(request);

        // VALIDATE-INPUT-DATA-FIELDS: the data-field cascade (runs only after the key is resolved).
        validateDataFields(request);
        requireConfirmation(request.confirm());

        BigDecimal normalizedAmount = request.amount().setScale(2, RoundingMode.HALF_EVEN);

        // Allocate the next browse-to-end identifier and insert, retrying on a concurrent collision.
        Transaction saved = persistWithGeneratedId(request, resolvedKey, normalizedAmount);

        // Observability: running total of posted transaction amounts (telemetry only; see MetricsConfig).
        MetricsConfig.transactionAmountTotal(meterRegistry).increment(normalizedAmount.doubleValue());

        return new TransactionAddResponse(
                saved.getTranId(),
                resolvedKey.accountId(),
                saved.getTranCardNum(),
                saved.getTranTypeCd(),
                String.format(CATEGORY_CODE_FORMAT, saved.getTranCatCd()),
                saved.getTranSource(),
                saved.getTranDesc(),
                saved.getTranAmt(),
                saved.getTranOrigTs(),
                saved.getTranProcTs(),
                String.format(MERCHANT_ID_FORMAT, saved.getTranMerchantId()),
                saved.getTranMerchantName(),
                saved.getTranMerchantCity(),
                saved.getTranMerchantZip(),
                request.confirm(),
                null);
    }

    /**
     * Resolves and validates the account/card key fields, reproducing
     * {@code COTRN02C.VALIDATE-INPUT-KEY-FIELDS} ({@code EVALUATE TRUE} with account-id-first
     * precedence).
     *
     * <ul>
     *   <li>If an account id is supplied, it must be numeric ({@link #MSG_ACCT_ID_NOT_NUMERIC}); it
     *       is then resolved through {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
     *       ({@code READ-CXACAIX-FILE}) and the cross-reference card number becomes the
     *       authoritative card. A missing cross-reference yields {@link #MSG_ACCT_ID_NOT_FOUND}.</li>
     *   <li>Otherwise, if a card number is supplied, it must be numeric
     *       ({@link #MSG_CARD_NUM_NOT_NUMERIC}); it is resolved through the keyed
     *       {@code findById} read ({@code READ-CCXREF-FILE}) and the cross-reference account id is
     *       adopted. A missing cross-reference yields {@link #MSG_CARD_NUM_NOT_FOUND}.</li>
     *   <li>If neither is supplied, {@link #MSG_KEY_REQUIRED} is raised.</li>
     * </ul>
     *
     * @param request the request whose {@code accountId} / {@code cardNumber} keys are resolved
     * @return the resolved, mutually consistent account id and card number
     * @throws ValidationException     if neither key is supplied or a supplied key is non-numeric
     * @throws RecordNotFoundException if a supplied key has no cross-reference record
     */
    private ResolvedKey validateAndResolveKeyFields(TransactionAddRequest request) {
        String accountId = request.accountId();
        String cardNumber = request.cardNumber();

        // WHEN ACTIDINI NOT = SPACES: account id supplied -> resolve the complementary card number.
        if (!isBlank(accountId)) {
            if (!isDigits(accountId)) {
                throw new ValidationException(MSG_ACCT_ID_NOT_NUMERIC);
            }
            Long accountKey = Long.parseLong(accountId);
            List<CardCrossReference> matches =
                    cardCrossReferenceRepository.findByXrefAcctId(accountKey);
            if (matches == null || matches.isEmpty()) {
                throw new RecordNotFoundException(MSG_ACCT_ID_NOT_FOUND);
            }
            // MOVE XREF-CARD-NUM TO CARDNINI: the cross-reference card is authoritative.
            return new ResolvedKey(accountId, matches.get(0).getXrefCardNum());
        }

        // WHEN CARDNINI NOT = SPACES: only a card number supplied -> resolve the complementary acct id.
        if (!isBlank(cardNumber)) {
            if (!isDigits(cardNumber)) {
                throw new ValidationException(MSG_CARD_NUM_NOT_NUMERIC);
            }
            CardCrossReference xref = cardCrossReferenceRepository.findById(cardNumber)
                    .orElseThrow(() -> new RecordNotFoundException(MSG_CARD_NUM_NOT_FOUND));
            // MOVE XREF-ACCT-ID TO ACTIDINI: adopt the cross-reference account id.
            return new ResolvedKey(String.valueOf(xref.getXrefAcctId()), cardNumber);
        }

        // WHEN OTHER: neither key entered.
        throw new ValidationException(MSG_KEY_REQUIRED);
    }

    /**
     * The resolved, mutually consistent account/card key pairing produced by
     * {@link #validateAndResolveKeyFields(TransactionAddRequest)}.
     *
     * @param accountId  the resolved account id (supplied, or adopted from the cross-reference)
     * @param cardNumber the resolved card number (supplied, or adopted from the cross-reference)
     */
    private record ResolvedKey(String accountId, String cardNumber) {
    }

    /**
     * Runs the ordered data-field validation cascade, throwing on the first failure.
     *
     * @param request the request whose fields are validated
     * @throws ValidationException describing the first field that fails a check
     */
    private void validateDataFields(TransactionAddRequest request) {
        // Mandatory-presence checks, evaluated in field order.
        if (isBlank(request.typeCode())) {
            throw new ValidationException("Type CD can NOT be empty...");
        }
        if (isBlank(request.categoryCode())) {
            throw new ValidationException("Category CD can NOT be empty...");
        }
        if (isBlank(request.source())) {
            throw new ValidationException("Source can NOT be empty...");
        }
        if (isBlank(request.description())) {
            throw new ValidationException("Description can NOT be empty...");
        }
        if (request.amount() == null) {
            throw new ValidationException("Amount can NOT be empty...");
        }
        if (isBlank(request.originDate())) {
            throw new ValidationException("Orig Date can NOT be empty...");
        }
        if (isBlank(request.processDate())) {
            throw new ValidationException("Proc Date can NOT be empty...");
        }
        if (isBlank(request.merchantId())) {
            throw new ValidationException("Merchant ID can NOT be empty...");
        }
        if (isBlank(request.merchantName())) {
            throw new ValidationException("Merchant Name can NOT be empty...");
        }
        if (isBlank(request.merchantCity())) {
            throw new ValidationException("Merchant City can NOT be empty...");
        }
        if (isBlank(request.merchantZip())) {
            throw new ValidationException("Merchant Zip can NOT be empty...");
        }

        // Numeric checks for the type and category codes.
        if (!isDigits(request.typeCode())) {
            throw new ValidationException("Type CD must be Numeric...");
        }
        if (!isDigits(request.categoryCode())) {
            throw new ValidationException("Category CD must be Numeric...");
        }

        // Positional format checks for both dates (YYYY-MM-DD).
        if (!isYyyyMmDd(request.originDate())) {
            throw new ValidationException("Orig Date should be in format YYYY-MM-DD");
        }
        if (!isYyyyMmDd(request.processDate())) {
            throw new ValidationException("Proc Date should be in format YYYY-MM-DD");
        }

        // Calendar-validity checks delegated to the shared date-validation service.
        if (!dateValidationService.validateDate(request.originDate(), DATE_FORMAT).isAcceptable()) {
            throw new ValidationException("Orig Date - Not a valid date...");
        }
        if (!dateValidationService.validateDate(request.processDate(), DATE_FORMAT).isAcceptable()) {
            throw new ValidationException("Proc Date - Not a valid date...");
        }

        // Merchant identifier numeric check, evaluated last.
        if (!isDigits(request.merchantId())) {
            throw new ValidationException("Merchant ID must be Numeric...");
        }
    }

    /**
     * Enforces the confirmation gate after a successful validation cascade.
     *
     * @param confirm the confirmation flag supplied on the request
     * @throws ValidationException with the confirmation prompt when the flag is absent,
     *                             {@code N}/{@code n}; with the invalid-value message for any other
     *                             non-{@code Y}/{@code y} value
     */
    private static void requireConfirmation(String confirm) {
        boolean confirmed = "Y".equals(confirm) || "y".equals(confirm);
        if (confirmed) {
            return;
        }
        if (confirm == null || confirm.isBlank() || "N".equals(confirm) || "n".equals(confirm)) {
            throw new ValidationException("Confirm to add this transaction...");
        }
        throw new ValidationException("Invalid value. Valid values are (Y/N)...");
    }

    /**
     * Assigns the next browse-to-end identifier and inserts the transaction, retrying on a
     * concurrent identifier collision.
     *
     * <p>Reproduces the {@code COTRN02C} {@code STARTBR}/{@code READPREV}/{@code ENDBR} "browse to
     * the last key and add one" allocation ({@link #generateNextTransactionId()}). That allocation
     * is not atomic, so two simultaneous adds can derive the same identifier. Each attempt builds a
     * fresh transient entity and performs an explicit INSERT with an immediate flush (the repository
     * {@code insertNew} operation), so a colliding identifier is rejected by the primary key and
     * surfaces synchronously as a {@link DataIntegrityViolationException} &mdash; an INSERT, never a
     * merge that could silently overwrite the winning row. The identifier is then re-derived from the
     * now-higher maximum and the insert is retried. The operation runs outside any surrounding
     * transaction, so each attempt executes in its own short transaction and a failed attempt never
     * marks the caller's context rollback-only.</p>
     *
     * <p>If no free identifier is obtained within {@link #MAX_ID_GENERATION_ATTEMPTS} attempts the
     * collision is reported as a {@link DuplicateRecordException} (COBOL {@code WRITE-TRANSACT-FILE}
     * {@code DUPKEY}/{@code DUPREC} &rarr; {@code "Tran ID already exist..."}).</p>
     *
     * @param request          the originating add request supplying the data fields
     * @param resolvedKey      the resolved, cross-reference-backed account/card pairing
     * @param normalizedAmount the scale-two transaction amount
     * @return the persisted transaction, including its assigned identifier
     * @throws DuplicateRecordException if a unique identifier cannot be allocated within the bound
     */
    private Transaction persistWithGeneratedId(TransactionAddRequest request, ResolvedKey resolvedKey,
                                               BigDecimal normalizedAmount) {
        DataIntegrityViolationException lastCollision = null;
        for (int attempt = 0; attempt < MAX_ID_GENERATION_ATTEMPTS; attempt++) {
            // A fresh, transient entity per attempt guarantees an INSERT (never a merge-driven update)
            // and avoids reusing an instance left detached by a prior failed attempt.
            Transaction transaction =
                    buildTransaction(request, resolvedKey, normalizedAmount, generateNextTransactionId());
            try {
                return transactionRepository.insertNew(transaction);
            } catch (DataIntegrityViolationException ex) {
                // A concurrent add committed this identifier first; re-browse to the new max and retry.
                lastCollision = ex;
            }
        }
        throw new DuplicateRecordException("Tran ID already exist...", lastCollision);
    }

    /**
     * Builds a fully-populated, transient {@link Transaction} ready to be inserted. The resolved
     * cross-reference card number is stored (COBOL {@code MOVE CARDNINI TO TRAN-CARD-NUM}); the
     * account id is never written to the record. A new instance is produced on every call so each
     * persistence attempt operates on a transient entity.
     *
     * @param request          the originating add request
     * @param resolvedKey      the resolved account/card pairing
     * @param normalizedAmount the scale-two transaction amount
     * @param tranId           the generated sixteen-digit identifier to assign
     * @return a new transient transaction entity
     */
    private static Transaction buildTransaction(TransactionAddRequest request, ResolvedKey resolvedKey,
                                                BigDecimal normalizedAmount, String tranId) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd(request.typeCode());
        transaction.setTranCatCd(Integer.parseInt(request.categoryCode()));
        transaction.setTranSource(request.source());
        transaction.setTranDesc(request.description());
        transaction.setTranAmt(normalizedAmount);
        // MOVE CARDNINI TO TRAN-CARD-NUM: the resolved (cross-reference-backed) card number.
        transaction.setTranCardNum(resolvedKey.cardNumber());
        transaction.setTranMerchantId(Long.parseLong(request.merchantId()));
        transaction.setTranMerchantName(request.merchantName());
        transaction.setTranMerchantCity(request.merchantCity());
        transaction.setTranMerchantZip(request.merchantZip());
        transaction.setTranOrigTs(request.originDate());
        transaction.setTranProcTs(request.processDate());
        return transaction;
    }

    /**
     * Produces the next sixteen-digit transaction identifier.
     *
     * <p>The highest existing identifier is looked up; when no transactions exist the sequence
     * starts from zero. The result is incremented by one and rendered as a fixed-width, zero-padded
     * sixteen-character string so that lexicographic ordering matches numeric ordering.</p>
     *
     * @return the zero-padded sixteen-digit identifier for the new transaction
     */
    private String generateNextTransactionId() {
        String maxId = transactionRepository.findMaxTranId();
        long next = (maxId == null ? 0L : Long.parseLong(maxId.trim())) + 1L;
        return String.format(TRAN_ID_FORMAT, next);
    }

    /**
     * Determines whether a string is {@code null} or contains only whitespace.
     *
     * @param value the value to test
     * @return {@code true} when the value is {@code null} or blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Determines whether a string consists solely of one or more decimal digits.
     *
     * @param value the value to test
     * @return {@code true} when the value is non-{@code null} and entirely numeric
     */
    private static boolean isDigits(String value) {
        return value != null && value.matches("\\d+");
    }

    /**
     * Determines whether a string matches the fixed {@code YYYY-MM-DD} positional layout: four
     * digits, a hyphen, two digits, a hyphen, and two digits, for a total length of ten.
     *
     * @param value the value to test
     * @return {@code true} when the value conforms to the positional layout
     */
    private static boolean isYyyyMmDd(String value) {
        if (value == null || value.length() != 10) {
            return false;
        }
        return isDigits(value.substring(0, 4))
                && value.charAt(4) == '-'
                && isDigits(value.substring(5, 7))
                && value.charAt(7) == '-'
                && isDigits(value.substring(8, 10));
    }
}
