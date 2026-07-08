package com.carddemo.service;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.TransactionViewResponse;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

/**
 * Application service for the single-transaction detail view — online
 * transaction {@code CT01} ("Txn View").
 *
 * <p>This is the Java&nbsp;25 / Spring&nbsp;Boot translation of the CICS/COBOL
 * program {@code COTRN01C} ({@code app/cbl/COTRN01C.cbl}, 330&nbsp;LOC), whose
 * frozen source is referenced — never copied — at commit SHA {@code 27d6c6f}
 * (full {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}). It reproduces the
 * program's read-only lookup behaviour: given a transaction identifier, it
 * returns that transaction's full detail with the card number (PAN) masked to
 * its last four characters.</p>
 *
 * <h2>Control-flow parity with {@code COTRN01C}</h2>
 * <ul>
 *   <li><b>{@code PROCESS-ENTER-KEY}</b> (source L144–L192) — rejects an empty
 *       transaction id with the verbatim message
 *       {@value #MSG_TRANID_EMPTY}. Here that maps to a {@link ValidationException}
 *       (HTTP&nbsp;400) raised before any repository access.</li>
 *   <li><b>{@code READ-TRANSACT-FILE}</b> (source L267–L296) — issues
 *       {@code EXEC CICS READ} on the {@code TRANSACT} KSDS keyed by
 *       {@code TRAN-ID}. The {@code DFHRESP(NOTFND)} branch reports the verbatim
 *       message {@value #MSG_TRAN_NOT_FOUND}; here that maps to a
 *       {@link ResourceNotFoundException} (HTTP&nbsp;404) via
 *       {@link java.util.Optional#orElseThrow(java.util.function.Supplier)
 *       Optional.orElseThrow(..)}. The keyed read is served by the base
 *       {@code TRANSACT} KSDS key, i.e. {@link TransactionRepository}'s inherited
 *       {@code findById(String)}.</li>
 *   <li><b>Field projection</b> (source L176–L192) — the record fields moved to
 *       the {@code COTRN1A} map are projected onto {@link TransactionViewResponse}
 *       in the same order.</li>
 * </ul>
 *
 * <h2>Fidelity and safety</h2>
 * <ul>
 *   <li><b>Decimal fidelity.</b> {@code TRAN-AMT} ({@code PIC S9(09)V99}) flows
 *       through as {@link java.math.BigDecimal}; the {@link TransactionViewResponse}
 *       canonical constructor normalizes it to scale&nbsp;2. Floating-point types
 *       are never used for monetary values (AAP&nbsp;§0.8.2).</li>
 *   <li><b>Timestamp preservation.</b> {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS}
 *       ({@code PIC X(26)}) are passed through unchanged as 26-character strings,
 *       preserving the exact on-file {@code yyyy-mm-dd-hh.mm.ss.ffffff} text.</li>
 *   <li><b>Numeric identifier fidelity.</b> {@code TRAN-CAT-CD} ({@code PIC 9(04)})
 *       and {@code TRAN-MERCHANT-ID} ({@code PIC 9(09)}) are persisted as
 *       {@link Integer}/{@link Long} on {@link Transaction} but are exposed on the
 *       response DTO as fixed-width, zero-padded digit strings — matching the
 *       codebase convention for numeric COBOL identifier fields and the
 *       {@link TransactionViewResponse} field contract. See
 *       {@link #formatCategoryCode(Integer)} and {@link #formatMerchantId(Long)}.</li>
 *   <li><b>PAN masking.</b> The card number is masked by {@link #maskPan(String)}
 *       before it leaves this service, so the full PAN is never emitted in a
 *       response or written to a log. Masking is idempotent and is also enforced by
 *       the DTO's canonical constructor as defence in depth.</li>
 * </ul>
 *
 * <p>The lookup is executed inside a read-only transaction boundary
 * ({@code @Transactional(readOnly = true)}), reflecting that {@code COTRN01C} only
 * reads the {@code TRANSACT} file. The service is stateless and therefore
 * thread-safe; its single collaborator is injected via the constructor.</p>
 *
 * @see TransactionRepository
 * @see Transaction
 * @see TransactionViewResponse
 */
@Service
public class TransactionViewService {

    /**
     * SLF4J logger. Only non-sensitive business identifiers (e.g. the transaction
     * id) are ever logged; the card number (PAN) is never logged in any form.
     */
    private static final Logger log = LoggerFactory.getLogger(TransactionViewService.class);

    /**
     * Verbatim validation message for an empty transaction id, transcribed from
     * {@code COTRN01C} paragraph {@code PROCESS-ENTER-KEY} (source L149,
     * SHA {@code 27d6c6f}). Package-private so unit tests can assert on it without
     * duplicating the literal.
     */
    static final String MSG_TRANID_EMPTY = "Tran ID can NOT be empty...";

    /**
     * Verbatim not-found message for a missing transaction, transcribed from
     * {@code COTRN01C} paragraph {@code READ-TRANSACT-FILE} {@code DFHRESP(NOTFND)}
     * branch (source L285, SHA {@code 27d6c6f}). Package-private so unit tests can
     * assert on it without duplicating the literal.
     */
    static final String MSG_TRAN_NOT_FOUND = "Transaction ID NOT found...";

    /** Number of trailing PAN characters left visible when masking a card number. */
    private static final int VISIBLE_PAN_DIGITS = 4;

    /** Character substituted for each masked (hidden) card-number position. */
    private static final char MASK_CHARACTER = '*';

    /** Zero-padded format for {@code TRAN-CAT-CD} ({@code PIC 9(04)}) — four digits. */
    private static final String CATEGORY_CODE_FORMAT = "%04d";

    /** Zero-padded format for {@code TRAN-MERCHANT-ID} ({@code PIC 9(09)}) — nine digits. */
    private static final String MERCHANT_ID_FORMAT = "%09d";

    /** Repository for keyed access to the migrated {@code TRANSACT} KSDS. */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param transactionRepository repository providing keyed access to
     *                              {@link Transaction} records; must not be {@code null}
     */
    public TransactionViewService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns the detail of a single transaction by its identifier, reproducing the
     * {@code COTRN01C} view flow.
     *
     * <p>Processing order mirrors the COBOL program:</p>
     * <ol>
     *   <li>If {@code transactionId} is {@code null} or blank, a
     *       {@link ValidationException} carrying {@value #MSG_TRANID_EMPTY} is thrown
     *       (the {@code PROCESS-ENTER-KEY} empty-id edit).</li>
     *   <li>Otherwise the transaction is read by key; if no record exists a
     *       {@link ResourceNotFoundException} carrying {@value #MSG_TRAN_NOT_FOUND} is
     *       thrown (the {@code READ-TRANSACT-FILE} {@code DFHRESP(NOTFND)} branch).</li>
     *   <li>On success the record is projected onto a {@link TransactionViewResponse}
     *       with the PAN masked, the amount at scale&nbsp;2, the 26-character
     *       timestamps preserved verbatim, and the numeric category/merchant
     *       identifiers rendered as fixed-width zero-padded strings.</li>
     * </ol>
     *
     * @param transactionId the transaction id to look up ({@code TRAN-ID},
     *                      {@code X(16)}); must be non-{@code null} and non-blank
     * @return the transaction detail with the card number masked; never {@code null}
     * @throws ValidationException       if {@code transactionId} is {@code null} or blank
     * @throws ResourceNotFoundException if no transaction exists for the given id
     */
    @Transactional(readOnly = true)
    public TransactionViewResponse viewTransaction(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new ValidationException(MSG_TRANID_EMPTY);
        }

        log.debug("Viewing transaction detail for transaction id [{}]", transactionId);

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_TRAN_NOT_FOUND));

        return new TransactionViewResponse(
                transaction.getTranId(),
                maskPan(transaction.getTranCardNum()),
                transaction.getTranTypeCd(),
                formatCategoryCode(transaction.getTranCatCd()),
                transaction.getTranSource(),
                transaction.getTranDesc(),
                transaction.getTranAmt(),
                transaction.getTranOrigTs(),
                transaction.getTranProcTs(),
                formatMerchantId(transaction.getTranMerchantId()),
                transaction.getTranMerchantName(),
                transaction.getTranMerchantCity(),
                transaction.getTranMerchantZip());
    }

    /**
     * Masks a card number so that only the last {@value #VISIBLE_PAN_DIGITS}
     * characters remain visible, replacing every preceding character with
     * {@value #MASK_CHARACTER}. The full PAN is never returned, logged, or emitted.
     *
     * <p>The operation is null- and short-value safe and idempotent: a {@code null}
     * value returns {@code null}; a value no longer than
     * {@value #VISIBLE_PAN_DIGITS} characters is returned unchanged (nothing to
     * hide); and re-masking an already-masked value yields the same result, because
     * only the trailing characters are ever retained. For example
     * {@code "1234567890123456"} becomes {@code "************3456"}.</p>
     *
     * @param pan the raw (or already-masked) card number; may be {@code null}
     * @return the masked card number, or the original value when masking does not apply
     */
    private String maskPan(String pan) {
        if (pan == null) {
            return null;
        }
        int length = pan.length();
        if (length <= VISIBLE_PAN_DIGITS) {
            return pan;
        }
        int maskedCount = length - VISIBLE_PAN_DIGITS;
        StringBuilder masked = new StringBuilder(length);
        for (int i = 0; i < maskedCount; i++) {
            masked.append(MASK_CHARACTER);
        }
        masked.append(pan, maskedCount, length);
        return masked.toString();
    }

    /**
     * Renders {@code TRAN-CAT-CD} ({@code PIC 9(04)}) as a fixed-width, zero-padded
     * four-digit string, preserving the COBOL numeric-display representation on the
     * response DTO (for example {@code 5} becomes {@code "0005"}).
     *
     * @param categoryCode the numeric category code; may be {@code null}
     * @return the zero-padded four-digit string, or {@code null} when the input is
     *         {@code null} (absence preserved rather than defaulted)
     */
    private String formatCategoryCode(Integer categoryCode) {
        if (categoryCode == null) {
            return null;
        }
        return String.format(Locale.ROOT, CATEGORY_CODE_FORMAT, categoryCode);
    }

    /**
     * Renders {@code TRAN-MERCHANT-ID} ({@code PIC 9(09)}) as a fixed-width,
     * zero-padded nine-digit string, preserving the COBOL numeric-display
     * representation on the response DTO (for example {@code 123456789} becomes
     * {@code "123456789"}, and {@code 42} becomes {@code "000000042"}).
     *
     * @param merchantId the numeric merchant identifier; may be {@code null}
     * @return the zero-padded nine-digit string, or {@code null} when the input is
     *         {@code null} (absence preserved rather than defaulted)
     */
    private String formatMerchantId(Long merchantId) {
        if (merchantId == null) {
            return null;
        }
        return String.format(Locale.ROOT, MERCHANT_ID_FORMAT, merchantId);
    }
}
