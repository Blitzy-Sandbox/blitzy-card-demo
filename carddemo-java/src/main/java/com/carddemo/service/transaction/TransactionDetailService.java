package com.carddemo.service.transaction;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionDetailResponse;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for the online transaction-detail view (CICS transaction {@code CT01},
 * "View Transaction").
 *
 * <p>Implements a single keyed read: given a transaction identifier, it validates that the
 * key is present, looks up the matching {@link Transaction} by its primary key, and returns a
 * fully populated {@link TransactionDetailResponse}. The lookup performs no key
 * transformation (no trimming, padding, or normalization), so retrieval semantics match the
 * original direct keyed read exactly.</p>
 *
 * <p>Failure conditions are surfaced as exceptions rather than embedded in the response: an
 * absent or blank key raises {@link ValidationException}, and a key with no matching record
 * raises {@link RecordNotFoundException}. The success-path response therefore always carries a
 * {@code null} error message; the controller / exception-handling layer is responsible for
 * translating the two distinct failure conditions into HTTP error responses.</p>
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
     * <p>Processing order is preserved from the source program: the key is validated for
     * emptiness first, the keyed read is performed second, and the record is mapped to the
     * response last.</p>
     *
     * @param transactionId the transaction identifier to look up; echoed back on the response
     * @return the populated transaction-detail response on a successful read
     * @throws ValidationException     if {@code transactionId} is {@code null} or blank
     * @throws RecordNotFoundException if no transaction exists for the supplied identifier
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
