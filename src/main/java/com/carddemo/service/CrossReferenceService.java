package com.carddemo.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.carddemo.entity.CardXref;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Support service centralising the two cross-cutting behaviours that the legacy
 * AWS CardDemo online programs open-coded through VSAM browses: <strong>card
 * cross-reference navigation</strong> (account&nbsp;&rarr;&nbsp;card /
 * account&nbsp;&rarr;&nbsp;customer) and <strong>next transaction-id
 * generation</strong>. Frozen COBOL reference commit SHA {@code 27d6c6f}
 * (read-only source, not copied into this repository).
 *
 * <h2>Why this service exists</h2>
 * <p>In the mainframe design the {@code Account} record carries no customer
 * identifier; the linkage between an account, its card(s) and its owning
 * customer lives exclusively in the {@code CARDXREF} VSAM cluster (copybook
 * {@code CVACT03Y}). Consequently every online flow that needs the customer or
 * primary card for an account must first resolve it through the cross-reference,
 * exactly as {@code COACTVWC} does in paragraph {@code 9200-GETCARDXREF-BYACCT}.
 * Extracting that navigation here (rather than duplicating it in each caller)
 * mirrors the single, shared COBOL paragraph and gives {@code AccountViewService},
 * {@code AccountUpdateService}, {@code BillPaymentService},
 * {@code TransactionAddService} and {@code StatementFileService} one authoritative
 * implementation to reuse.</p>
 *
 * <h2>COBOL&nbsp;&rarr;&nbsp;Java behavioural mapping</h2>
 * <ul>
 *   <li><b>{@code COACTVWC} paragraph {@code 9200-GETCARDXREF-BYACCT}</b> &mdash;
 *       reads {@code CARDXREF} through the account alternate-index path
 *       ({@code CXACAIX}); on {@code DFHRESP(NORMAL)} it moves
 *       {@code XREF-CUST-ID} and {@code XREF-CARD-NUM} into the communication
 *       area, and on {@code DFHRESP(NOTFND)} it raises the operator message
 *       <em>"Did not find this account in account card xref file"</em> (the
 *       {@code DID-NOT-FIND-ACCT-IN-CARDXREF} 88-level). This maps to
 *       {@link #findByAccount(Long)}, {@link #resolveCustomerId(Long)} and
 *       {@link #resolvePrimaryCardNumber(Long)}; the not-found path becomes a
 *       {@link ResourceNotFoundException} carrying the identical message text so
 *       the external contract (Gate&nbsp;5) is preserved.</li>
 *   <li><b>{@code COTRN02C} paragraph {@code ADD-TRANSACTION} and {@code COBIL00C}</b>
 *       &mdash; both allocate the next transaction id by seeding {@code TRAN-ID}
 *       with {@code HIGH-VALUES}, browsing the {@code TRANSACT} KSDS backwards
 *       ({@code STARTBR}&nbsp;/&nbsp;{@code READPREV}&nbsp;/&nbsp;{@code ENDBR}) to
 *       obtain the highest existing key, and adding one to the 16-digit numeric
 *       working field ({@code WS-TRAN-ID-N}&nbsp;/&nbsp;{@code WS-TRAN-ID-NUM},
 *       {@code PIC 9(16)}). When the file is empty the {@code READPREV}
 *       {@code DFHRESP(ENDFILE)} branch moves {@code ZEROS} into {@code TRAN-ID}
 *       so the first allocated id is {@code 1}. This maps to
 *       {@link #generateNextTransactionId()}.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>The two repositories are supplied by <em>constructor injection</em>,
 *       replacing the static COBOL {@code CALL} / VSAM linkage with Spring
 *       dependency injection (AAP&nbsp;&sect;0.4.3). A single constructor means no
 *       {@code @Autowired} annotation is required.</li>
 *   <li>The service holds no mutable state and performs only read operations, so
 *       a single shared instance is safe for concurrent request and batch
 *       threads.</li>
 *   <li>Transaction identifiers are handled purely as {@link String} values (the
 *       16-digit numeric key), and no monetary arithmetic occurs here, so &mdash;
 *       consistent with the migration's decimal-fidelity rules &mdash; no
 *       {@code float} or {@code double} appears anywhere in this class.</li>
 * </ul>
 */
@Service
public class CrossReferenceService {

    /**
     * Verbatim operator message emitted by {@code COACTVWC} paragraph
     * {@code 9200-GETCARDXREF-BYACCT} on the {@code DFHRESP(NOTFND)} branch
     * (the {@code DID-NOT-FIND-ACCT-IN-CARDXREF} 88-level condition, SHA
     * {@code 27d6c6f}). Preserved character-for-character so the not-found
     * contract remains byte-identical to the legacy system (Gate&nbsp;5).
     */
    private static final String XREF_NOT_FOUND_MESSAGE =
            "Did not find this account in account card xref file";

    /**
     * Field width of the {@code TRAN-ID} key. The COBOL working fields
     * {@code WS-TRAN-ID-N} ({@code COTRN02C}) and {@code WS-TRAN-ID-NUM}
     * ({@code COBIL00C}) are both {@code PIC 9(16)}; generated ids are therefore
     * left-zero-padded to this width via {@link String#format(String, Object...)}.
     */
    private static final String TRAN_ID_FORMAT = "%016d";

    /**
     * Cross-reference repository backing the {@code CARDXREF} VSAM cluster; the
     * derived query {@link CardXrefRepository#findByXrefAcctId(Long)} reproduces
     * the account alternate-index ({@code CXACAIX}) read.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Transaction repository backing the {@code TRANSACT} KSDS; its inherited
     * {@code findAll(Pageable)} reproduces the descending key browse used to find
     * the highest existing transaction id.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its two collaborating repositories.
     *
     * <p>Spring injects both beans through this single constructor, so no
     * {@code @Autowired} annotation is needed. Both arguments are stored in
     * {@code final} fields, making the service effectively immutable and
     * thread-safe.</p>
     *
     * @param cardXrefRepository    the card cross-reference repository; must not
     *                              be {@code null}
     * @param transactionRepository the transaction repository; must not be
     *                              {@code null}
     */
    public CrossReferenceService(CardXrefRepository cardXrefRepository,
            TransactionRepository transactionRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns every card cross-reference row linked to the supplied account,
     * reproducing the account alternate-index read of {@code COACTVWC} paragraph
     * {@code 9200-GETCARDXREF-BYACCT}.
     *
     * <p>Because the legacy alternate index {@code CXACAIX} is declared
     * {@code NONUNIQUEKEY}, a single account may be linked to more than one card,
     * so the result is a {@link List}. It is never {@code null}; an account with
     * no cross-reference rows yields an empty list, which the callers interpret as
     * the COBOL {@code DFHRESP(NOTFND)} condition.</p>
     *
     * @param acctId the account identifier to resolve (maps to {@code XREF-ACCT-ID})
     * @return all cross-reference rows for the account, in no guaranteed order;
     *         empty when the account has no linked cards
     */
    public List<CardXref> findByAccount(Long acctId) {
        return cardXrefRepository.findByXrefAcctId(acctId);
    }

    /**
     * Resolves the customer identifier that owns the supplied account by reading
     * it from the account's first cross-reference row &mdash; the Java equivalent
     * of {@code COACTVWC} moving {@code XREF-CUST-ID} into {@code CDEMO-CUST-ID}
     * within paragraph {@code 9200-GETCARDXREF-BYACCT}.
     *
     * <p>When the account has no cross-reference row this reproduces the COBOL
     * {@code DFHRESP(NOTFND)} branch by throwing a {@link ResourceNotFoundException}
     * whose message is the exact legacy text
     * <em>"Did not find this account in account card xref file"</em>.</p>
     *
     * @param acctId the account identifier whose owning customer is required
     * @return the owning customer identifier ({@code XREF-CUST-ID}) of the first
     *         matching cross-reference row
     * @throws ResourceNotFoundException if the account has no cross-reference row
     */
    public Long resolveCustomerId(Long acctId) {
        List<CardXref> xrefs = findByAccount(acctId);
        if (xrefs.isEmpty()) {
            throw new ResourceNotFoundException(XREF_NOT_FOUND_MESSAGE);
        }
        return xrefs.get(0).getXrefCustId();
    }

    /**
     * Resolves the primary card number for the supplied account by reading it
     * from the account's first cross-reference row &mdash; the Java equivalent of
     * {@code COACTVWC} moving {@code XREF-CARD-NUM} into {@code CDEMO-CARD-NUM}
     * within paragraph {@code 9200-GETCARDXREF-BYACCT}.
     *
     * <p>When the account has no cross-reference row this reproduces the COBOL
     * {@code DFHRESP(NOTFND)} branch by throwing a {@link ResourceNotFoundException}
     * whose message is the exact legacy text
     * <em>"Did not find this account in account card xref file"</em>.</p>
     *
     * @param acctId the account identifier whose primary card number is required
     * @return the 16-character card number ({@code XREF-CARD-NUM}) of the first
     *         matching cross-reference row
     * @throws ResourceNotFoundException if the account has no cross-reference row
     */
    public String resolvePrimaryCardNumber(Long acctId) {
        List<CardXref> xrefs = findByAccount(acctId);
        if (xrefs.isEmpty()) {
            throw new ResourceNotFoundException(XREF_NOT_FOUND_MESSAGE);
        }
        return xrefs.get(0).getXrefCardNum();
    }

    /**
     * Generates the next transaction identifier, reproducing the
     * {@code ADD-TRANSACTION} browse logic shared by {@code COTRN02C} and
     * {@code COBIL00C}.
     *
     * <p>The legacy programs seed {@code TRAN-ID} with {@code HIGH-VALUES} and
     * browse the {@code TRANSACT} KSDS backwards ({@code STARTBR} /
     * {@code READPREV} / {@code ENDBR}) to obtain the highest existing key, then
     * add one. This method reproduces that by requesting a single record ordered
     * by {@code tranId} descending &mdash; the highest key &mdash; via the
     * repository's inherited {@code findAll(Pageable)}.</p>
     *
     * <ul>
     *   <li>When the transaction table is empty the COBOL {@code READPREV}
     *       {@code DFHRESP(ENDFILE)} branch moves {@code ZEROS} into
     *       {@code TRAN-ID}; adding one yields {@code 1}, so this method returns
     *       {@code "0000000000000001"}.</li>
     *   <li>Otherwise the highest key is parsed as a {@code long} &mdash; guarded
     *       with {@link String#trim()} to tolerate fixed-width space padding &mdash;
     *       incremented, and re-formatted to the 16-digit, left-zero-padded width
     *       of the {@code PIC 9(16)} working field.</li>
     * </ul>
     *
     * @return the next transaction id as a 16-character, zero-padded numeric
     *         string
     * @throws NumberFormatException if the highest stored {@code tranId} is not a
     *         valid integer, which would violate the 16-digit numeric invariant of
     *         the {@code TRAN-ID} key
     */
    public String generateNextTransactionId() {
        var page = transactionRepository.findAll(
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "tranId")));
        long next = page.isEmpty()
                ? 1L
                : Long.parseLong(page.getContent().get(0).getTranId().trim()) + 1L;
        return String.format(TRAN_ID_FORMAT, next);
    }
}
