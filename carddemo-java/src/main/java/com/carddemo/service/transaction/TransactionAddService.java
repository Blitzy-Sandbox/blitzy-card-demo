package com.carddemo.service.transaction;

import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionAddRequest;
import com.carddemo.model.dto.TransactionAddResponse;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.shared.DateValidationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Application service for adding a new transaction.
 *
 * <p>For each submitted {@link TransactionAddRequest} the service runs the complete
 * input-validation cascade (field presence, numeric edits, and date format/validity edits),
 * enforces the two-step confirmation gate, generates the next sixteen-digit zero-padded
 * transaction identifier, persists the resulting {@link Transaction}, and returns a fully
 * populated {@link TransactionAddResponse}.</p>
 *
 * <p>Validation failures, an unconfirmed flag, and an invalid confirmation value are surfaced as
 * {@link ValidationException}; a duplicate identifier detected on insert is surfaced as
 * {@link DuplicateRecordException}. The whole operation executes within a single write
 * transaction, so any thrown runtime exception rolls the unit of work back.</p>
 *
 * <p>The card number (not the account identifier) is stored on the persisted record; the account
 * identifier is echoed back on the response only. Monetary amounts are handled as
 * {@link BigDecimal} normalized to scale two to preserve exact fixed-point precision.</p>
 */
@Service
public class TransactionAddService {

    /** Picture format supplied to {@link DateValidationService} for the origin and process dates. */
    private static final String DATE_FORMAT = "YYYY-MM-DD";

    /** Fixed width of the zero-padded numeric transaction identifier. */
    private static final String TRAN_ID_FORMAT = "%016d";

    /** Fixed width of the zero-padded numeric category code (four digits). */
    private static final String CATEGORY_CODE_FORMAT = "%04d";

    /** Fixed width of the zero-padded numeric merchant identifier (nine digits). */
    private static final String MERCHANT_ID_FORMAT = "%09d";

    /** Scale applied to the persisted transaction amount. */
    private static final int AMOUNT_SCALE = 2;

    private final TransactionRepository transactionRepository;
    private final DateValidationService dateValidationService;

    /**
     * Creates the service with its collaborating beans.
     *
     * @param transactionRepository repository providing the maximum-identifier lookup and save
     * @param dateValidationService strict date-validation collaborator for the origin/process dates
     */
    public TransactionAddService(TransactionRepository transactionRepository,
                                 DateValidationService dateValidationService) {
        this.transactionRepository = transactionRepository;
        this.dateValidationService = dateValidationService;
    }

    /**
     * Validates, confirms, creates, and persists a new transaction.
     *
     * @param request the submitted transaction-add input
     * @return the persisted transaction echoed back with its generated identifier and a {@code null}
     *         error message on success
     * @throws ValidationException      when any field edit fails, when confirmation has not been
     *                                  given, or when the confirmation value is neither {@code Y} nor
     *                                  {@code N}
     * @throws DuplicateRecordException when the generated identifier collides with an existing record
     */
    @Transactional
    public TransactionAddResponse addTransaction(TransactionAddRequest request) {
        validateDataFields(request);
        enforceConfirmation(request.confirm());

        String maxId = transactionRepository.findMaxTranId();
        long next = (maxId == null ? 0L : Long.parseLong(maxId.trim())) + 1L;
        String newTranId = String.format(TRAN_ID_FORMAT, next);

        BigDecimal normalizedAmount = request.amount().setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);

        Transaction tx = new Transaction();
        tx.setTranId(newTranId);
        tx.setTranTypeCd(request.typeCode());
        tx.setTranCatCd(Integer.parseInt(request.categoryCode()));
        tx.setTranSource(request.source());
        tx.setTranDesc(request.description());
        tx.setTranAmt(normalizedAmount);
        tx.setTranCardNum(request.cardNumber());
        tx.setTranMerchantId(Long.parseLong(request.merchantId()));
        tx.setTranMerchantName(request.merchantName());
        tx.setTranMerchantCity(request.merchantCity());
        tx.setTranMerchantZip(request.merchantZip());
        tx.setTranOrigTs(request.originDate());
        tx.setTranProcTs(request.processDate());

        Transaction saved;
        try {
            saved = transactionRepository.save(tx);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateRecordException("Tran ID already exist...", ex);
        }

        return new TransactionAddResponse(
                saved.getTranId(),
                request.accountId(),
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
     * Runs the field-edit cascade in the precise order required for message parity, throwing on the
     * first failed edit.
     *
     * @param request the submitted transaction-add input
     * @throws ValidationException on the first failing edit
     */
    private void validateDataFields(TransactionAddRequest request) {
        // Presence checks.
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

        // Amount positional editing is covered by the request type and bean-validation constraints;
        // scale normalization is applied when the entity is built.

        // Date format checks (positional YYYY-MM-DD).
        if (!isYyyyMmDd(request.originDate())) {
            throw new ValidationException("Orig Date should be in format YYYY-MM-DD");
        }
        if (!isYyyyMmDd(request.processDate())) {
            throw new ValidationException("Proc Date should be in format YYYY-MM-DD");
        }

        // Calendar-validity checks (strict parse, with the unsupported-range tolerance).
        if (!dateValidationService.validateDate(request.originDate(), DATE_FORMAT).isAcceptable()) {
            throw new ValidationException("Orig Date - Not a valid date...");
        }
        if (!dateValidationService.validateDate(request.processDate(), DATE_FORMAT).isAcceptable()) {
            throw new ValidationException("Proc Date - Not a valid date...");
        }

        // Merchant identifier numeric check (performed last).
        if (!isDigits(request.merchantId())) {
            throw new ValidationException("Merchant ID must be Numeric...");
        }
    }

    /**
     * Enforces the two-step confirmation gate.
     *
     * @param confirm the confirmation flag; {@code Y}/{@code y} proceeds, an empty/{@code N}/{@code n}
     *                value re-prompts, and any other value is rejected
     * @throws ValidationException when confirmation has not been given or is invalid
     */
    private void enforceConfirmation(String confirm) {
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            return;
        }
        if (confirm == null || confirm.isBlank() || "N".equals(confirm) || "n".equals(confirm)) {
            throw new ValidationException("Confirm to add this transaction...");
        }
        throw new ValidationException("Invalid value. Valid values are (Y/N)...");
    }

    /**
     * Reports whether a string is {@code null} or contains only whitespace.
     *
     * @param s the value to test
     * @return {@code true} when {@code s} is {@code null} or blank
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Reports whether a string is non-{@code null} and composed solely of decimal digits.
     *
     * @param s the value to test
     * @return {@code true} when {@code s} contains at least one character and all are digits
     */
    private static boolean isDigits(String s) {
        return s != null && s.matches("\\d+");
    }

    /**
     * Reports whether a string matches the fixed-width {@code YYYY-MM-DD} layout positionally: ten
     * characters with four leading digits, a hyphen, two digits, a hyphen, and two trailing digits.
     *
     * @param d the value to test
     * @return {@code true} when {@code d} satisfies the positional layout
     */
    private static boolean isYyyyMmDd(String d) {
        if (d == null || d.length() != 10) {
            return false;
        }
        return isDigits(d.substring(0, 4))
                && d.charAt(4) == '-'
                && isDigits(d.substring(5, 7))
                && d.charAt(7) == '-'
                && isDigits(d.substring(8, 10));
    }
}
