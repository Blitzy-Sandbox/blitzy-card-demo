package com.carddemo.service.transaction;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionDetailResponse;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for single-transaction detail retrieval (keyed read).
 *
 * <p>Exposes one read-only operation that looks up a transaction by its
 * identifier and returns its display fields. An empty/blank identifier is
 * rejected with a {@link ValidationException}; a missing record yields a
 * {@link RecordNotFoundException}. Both failure conditions surface as distinct
 * messages so the web layer can render the appropriate error response, while
 * the success path returns a fully populated {@link TransactionDetailResponse}
 * with the monetary amount preserved as a {@link java.math.BigDecimal}.</p>
 */
@Service
public class TransactionDetailService {

    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its required repository collaborator.
     *
     * @param transactionRepository repository providing keyed access to transactions
     */
    public TransactionDetailService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Retrieves the detail of a single transaction by its identifier.
     *
     * <p>Validates that the supplied identifier is non-empty, performs a keyed
     * lookup, and maps the persisted record onto the response contract. The
     * numeric category code and merchant identifier are rendered as fixed-width,
     * zero-padded strings (4 and 9 digits respectively) to preserve the original
     * display widths, and the amount is passed through unchanged to retain exact
     * decimal precision.</p>
     *
     * @param transactionId the transaction identifier to look up
     * @return the populated transaction detail response
     * @throws ValidationException     if {@code transactionId} is {@code null} or blank
     * @throws RecordNotFoundException if no transaction exists for the identifier
     */
    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransaction(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new ValidationException("Tran ID can NOT be empty...");
        }

        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RecordNotFoundException("Transaction ID NOT found..."));

        return new TransactionDetailResponse(
                transactionId,
                tx.getTranId(),
                tx.getTranCardNum(),
                tx.getTranTypeCd(),
                String.format("%04d", tx.getTranCatCd()),
                tx.getTranSource(),
                tx.getTranDesc(),
                tx.getTranAmt(),
                tx.getTranOrigTs(),
                tx.getTranProcTs(),
                String.format("%09d", tx.getTranMerchantId()),
                tx.getTranMerchantName(),
                tx.getTranMerchantCity(),
                tx.getTranMerchantZip(),
                null);
    }
}
