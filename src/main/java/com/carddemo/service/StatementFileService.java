package com.carddemo.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.StatementTransactionDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Typed, JPA-backed data-access service for statement generation.
 *
 * <p>This service is the idiomatic Spring replacement for the legacy COBOL
 * file-I/O subprogram {@code CBSTM03B} ({@code app/cbl/CBSTM03B.CBL}, 230 LOC),
 * which the statement-create program {@code CBSTM03A}
 * ({@code app/cbl/CBSTM03A.CBL}, 924 LOC) {@code CALL}s thirteen times to read
 * the XREF, CUSTOMER, ACCOUNT and TRANSACT files while assembling an account
 * statement. The frozen COBOL source is referenced (never copied) at commit SHA
 * {@code 27d6c6f} (full HEAD {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}).</p>
 *
 * <h2>Deliberate modernization: no untyped dispatcher</h2>
 * <p>{@code CBSTM03B} is a <em>generic dispatcher</em>: callers populate a single
 * {@code LK-M03B-AREA} linkage record — an eight-byte DD name ({@code DD X(08)}),
 * an operation flag ({@code OPER}, 88-levels {@code O}/{@code C}/{@code R}/
 * {@code K}/{@code W}/{@code Z} for open/close/read/keyed-read/write/rewrite), a
 * two-byte return code ({@code RC X(02)}), a 25-byte key ({@code KEY X(25)}), a
 * signed key length ({@code KEY-LN S9(4)}) and a 1000-byte data buffer
 * ({@code FLDT X(1000)}) — and the subprogram switches on the DD name to open,
 * read or close one of four files. This untyped COMMAREA-style dispatch is
 * intentionally <strong>not</strong> reproduced. Instead this class exposes
 * strongly typed, per-aggregate query methods backed by Spring Data JPA
 * repositories. This is a deliberate, logged modernization (see
 * {@code docs/decision-log.md}); the {@code CBSTM03A}/{@code CBSTM03B} →
 * {@code StatementFileService} mapping is recorded in
 * {@code docs/traceability-matrix.md}.</p>
 *
 * <h2>Injection contract</h2>
 * <p>Per AAP §0.4.3 ("CBSTM03A's {@code CALL 'CBSTM03B'} becomes a
 * constructor-injected statement-file service bean"), the statement batch job
 * ({@code com.carddemo.batch.StatementJob}) constructor-injects this bean and
 * reuses the public API below rather than issuing raw file I/O. All five
 * repository collaborators are supplied through a single constructor
 * (constructor injection), keeping the component immutable and trivially
 * testable with mocks.</p>
 *
 * <h2>PAN (card-number) handling</h2>
 * <p>The methods here return the <strong>full, unmasked</strong> Primary Account
 * Number. Statement generation writes a fixed-width statement <em>file</em>
 * whose byte layout must remain identical to the mainframe output (migration
 * goal G4, interface-contract parity), so the complete PAN is required at this
 * layer. Masking is a concern of the REST presentation layer only — for example
 * {@link com.carddemo.dto.StatementTransactionDto#toMaskedView()} or the DTO's
 * JSON serializer — and is deliberately never applied here.</p>
 *
 * <h2>Decimal fidelity</h2>
 * <p>Monetary amounts flow through unchanged as {@link java.math.BigDecimal};
 * floating-point types are never introduced (AAP §0.8.2). The scale-2 contract
 * of {@code TRAN-AMT PIC S9(09)V99} is preserved end to end: the source
 * {@link Transaction} entity stores {@code NUMERIC(11,2)} and the target
 * {@link StatementTransactionDto} normalizes to scale 2 in its canonical
 * constructor, so this service simply passes the value through.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>The service is stateless aside from its final repository references and is
 * therefore safe to share across concurrent batch worker threads and web request
 * threads. Every read path is annotated {@link Transactional @Transactional}
 * with {@code readOnly = true}, allowing the persistence provider to skip dirty
 * checking and flushing for these queries.</p>
 *
 * @see com.carddemo.dto.StatementTransactionDto
 * @see com.carddemo.repository.TransactionRepository
 */
@Service
public class StatementFileService {

    /** Card cross-reference access (account &rarr; card(s)); replaces {@code XREFFILE} reads. */
    private final CardXrefRepository cardXrefRepository;

    /** Account master access (keyed by account id); replaces {@code ACCTFILE} reads. */
    private final AccountRepository accountRepository;

    /** Customer master access (keyed by customer id); replaces {@code CUSTFILE} reads. */
    private final CustomerRepository customerRepository;

    /** Card master access (keyed by card number); supports statement header assembly. */
    private final CardRepository cardRepository;

    /** Transaction master access (by card number); replaces {@code TRNXFILE} reads. */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its five repository collaborators.
     *
     * <p>Constructor injection is used so that every collaborator is
     * {@code final} and mandatory, mirroring the fixed set of files the legacy
     * {@code CBSTM03B} subprogram could operate on.</p>
     *
     * @param cardXrefRepository    repository for card cross-reference records; must not be {@code null}
     * @param accountRepository     repository for account records; must not be {@code null}
     * @param customerRepository    repository for customer records; must not be {@code null}
     * @param cardRepository        repository for card records; must not be {@code null}
     * @param transactionRepository repository for transaction records; must not be {@code null}
     */
    public StatementFileService(
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            CardRepository cardRepository,
            TransactionRepository transactionRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardRepository = cardRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns every card cross-reference row for an account, resolving the
     * <em>account&nbsp;&rarr;&nbsp;card(s)</em> relationship.
     *
     * <p>Replaces the legacy {@code XREFFILE} sequential read in {@code CBSTM03B}
     * and reproduces the VSAM non-unique alternate index over
     * {@code XREF-ACCT-ID}; because that index is non-unique, an account may map
     * to several cards, so a {@link List} is returned (empty, never {@code null},
     * when the account has no cards).</p>
     *
     * @param acctId the owning account identifier (maps to {@code XREF-ACCT-ID})
     * @return all matching cross-reference rows, in no guaranteed order; never {@code null}
     */
    @Transactional(readOnly = true)
    public List<CardXref> getCardXrefsForAccount(Long acctId) {
        return cardXrefRepository.findByXrefAcctId(acctId);
    }

    /**
     * Reads a single account by its primary key, replacing the keyed
     * {@code ACCTFILE} read ({@code M03B-READ-K}) in {@code CBSTM03B}.
     *
     * @param acctId the account identifier ({@code ACCT-ID})
     * @return the account if present, otherwise {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<Account> getAccount(Long acctId) {
        return accountRepository.findById(acctId);
    }

    /**
     * Reads a single customer by its primary key, replacing the keyed
     * {@code CUSTFILE} read ({@code M03B-READ-K}) in {@code CBSTM03B}.
     *
     * @param custId the customer identifier ({@code CUST-ID})
     * @return the customer if present, otherwise {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<Customer> getCustomer(Long custId) {
        return customerRepository.findById(custId);
    }

    /**
     * Reads a single card by its card number (natural key), supporting statement
     * header assembly.
     *
     * @param cardNum the 16-character card number ({@code CARD-NUM}); the full,
     *                unmasked Primary Account Number
     * @return the card if present, otherwise {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<Card> getCard(String cardNum) {
        return cardRepository.findById(cardNum);
    }

    /**
     * Returns all transactions for a single card, replacing the sequential
     * {@code TRNXFILE} read loop in {@code CBSTM03B}/{@code CBSTM03A}.
     *
     * <p>The underlying repository method is paginated for online use; statement
     * generation needs the complete set, so an {@linkplain Pageable#unpaged()
     * unpaged} request is issued and its {@code content} returned as a plain
     * {@link List}.</p>
     *
     * @param cardNum the 16-character card number to select on ({@code TRAN-CARD-NUM})
     * @return all transactions for the card, in no guaranteed order; never {@code null}
     */
    @Transactional(readOnly = true)
    public List<Transaction> getTransactionsForCard(String cardNum) {
        return transactionRepository.findByTranCardNum(cardNum, Pageable.unpaged()).getContent();
    }

    /**
     * Builds the ordered list of denormalized statement lines for a card — the
     * Java equivalent of the {@code CBSTM03A} statement-assembly loop that read
     * each transaction through {@code CBSTM03B} and flattened it into the report
     * record.
     *
     * <p>Each {@link Transaction} for the card produces exactly one
     * {@link StatementTransactionDto}, preserving the source ordering of
     * {@link #getTransactionsForCard(String)}. Field-level rules:</p>
     * <ul>
     *   <li><strong>Full PAN.</strong> {@code cardNumber} carries the complete,
     *       unmasked card number for byte-identical statement-file output (G4);
     *       masking belongs to the REST layer, not here.</li>
     *   <li><strong>Decimal pass-through.</strong> {@code amount} is the
     *       {@link java.math.BigDecimal} from {@link Transaction#getTranAmt()};
     *       the DTO normalizes it to scale 2, matching {@code TRAN-AMT
     *       PIC S9(09)V99}. No {@code float}/{@code double} is used.</li>
     *   <li><strong>Null-safe numeric&nbsp;&rarr;&nbsp;String.</strong> The
     *       numeric entity fields {@code tranCatCd} ({@link Integer}) and
     *       {@code tranMerchantId} ({@link Long}) become {@link String} DTO
     *       components; a {@code null} source stays {@code null} rather than
     *       becoming the literal {@code "null"}.</li>
     *   <li><strong>Timestamp fidelity.</strong> The 26-character
     *       {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} strings are carried across
     *       verbatim.</li>
     * </ul>
     *
     * @param cardNum the 16-character card number whose statement lines are built
     * @return an unmodifiable list with one statement line per transaction, in
     *         source order; never {@code null}, empty when the card has no
     *         transactions
     */
    @Transactional(readOnly = true)
    public List<StatementTransactionDto> buildStatementLines(String cardNum) {
        return getTransactionsForCard(cardNum).stream()
                .map(StatementFileService::toStatementLine)
                .toList();
    }

    /**
     * Maps a single {@link Transaction} entity to its flattened
     * {@link StatementTransactionDto} statement line.
     *
     * <p>Kept {@code static} because it depends only on its argument. The numeric
     * category code and merchant id are converted to {@link String}
     * null-safely, the full card number is retained, the {@link java.math.BigDecimal}
     * amount is passed through unchanged, and the raw 26-character timestamp
     * strings are preserved.</p>
     *
     * @param tran the source transaction entity (never {@code null} within the stream)
     * @return the corresponding statement line DTO
     */
    private static StatementTransactionDto toStatementLine(Transaction tran) {
        final Integer categoryCode = tran.getTranCatCd();
        final Long merchantId = tran.getTranMerchantId();
        return new StatementTransactionDto(
                tran.getTranCardNum(),
                tran.getTranId(),
                tran.getTranTypeCd(),
                categoryCode == null ? null : String.valueOf(categoryCode),
                tran.getTranSource(),
                tran.getTranDesc(),
                tran.getTranAmt(),
                merchantId == null ? null : String.valueOf(merchantId),
                tran.getTranMerchantName(),
                tran.getTranMerchantCity(),
                tran.getTranMerchantZip(),
                tran.getTranOrigTs(),
                tran.getTranProcTs());
    }
}
