package com.cardemo.service.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.DateValidationService;

/**
 * Transaction-add service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the
 * online CICS program <strong>{@code app/cbl/COTRN02C.cbl}</strong> (CICS transaction {@code CT02},
 * BMS mapset {@code COTRN02}). Adding a transaction is feature <strong>F-???</strong>'s add path of
 * the preserved CardDemo estate: it resolves a card cross-reference (by account <em>or</em> card),
 * runs an ordered cascade of field validations, auto-generates the next transaction id and writes a
 * new row to the {@code TRANSACT} VSAM KSDS (now the PostgreSQL {@code transaction} table).
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces the observable behavior of {@code COTRN02C}'s {@code PROCESS-ENTER-KEY}
 * paragraph and its file-I/O paragraphs ({@code VALIDATE-INPUT-KEY-FIELDS},
 * {@code VALIDATE-INPUT-DATA-FIELDS}, {@code READ-CXACAIX-FILE}, {@code READ-CCXREF-FILE}, the
 * {@code STARTBR}/{@code READPREV}/{@code ENDBR} browse-to-end and {@code WRITE-TRANSACT-FILE})
 * <em>exactly</em> &mdash; the same validation order, the same verbatim messages and the same
 * auto-id arithmetic. Per the Minimal Change Clause (AAP &sect;0.7.1) nothing is added, enhanced or
 * optimized beyond the technology transition. The COBOL is read-only reference material at the frozen
 * baseline commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only its
 * behavior is reproduced.</p>
 *
 * <h2>THE governing control-flow fact &mdash; fail-fast / first-error-wins</h2>
 * <p>In {@code COTRN02C} the {@code SEND-TRNADD-SCREEN} paragraph (L516-538) performs <em>both</em>
 * {@code EXEC CICS SEND} <em>and</em> {@code EXEC CICS RETURN}, so every error path
 * ({@code MOVE 'Y' TO WS-ERR-FLG; ... PERFORM SEND-TRNADD-SCREEN}) terminates the pseudo-conversational
 * task immediately. The program therefore reports the <strong>first</strong> failure it encounters, in
 * a fixed order. This service maps that to a chain of ordered guard clauses: the first failed check
 * throws a {@link ValidationException} with the exact COBOL message; errors are <strong>never</strong>
 * accumulated (accumulating them would change observable behavior). The {@code PROCESS-ENTER-KEY}
 * order (L164-188) is preserved exactly: (1) {@link #resolveCrossReference(TransactionDto)} &rarr;
 * (2) {@link #validateDataFields(TransactionDto)} &rarr; (3) {@link #evaluateConfirm(TransactionDto)}
 * &rarr; (4) add.</p>
 *
 * <h2>Service / controller boundary (layered architecture, AAP &sect;0.3.3)</h2>
 * <p>{@code COTRN02C}'s {@code MAIN-PARA} CICS pseudo-conversational plumbing is deliberately
 * <strong>excluded</strong> from this service: the {@code EIBCALEN = 0} sign-on redirect is a Spring
 * Security concern; the {@code EVALUATE EIBAID} PF-key dispatch (PF3 back, PF4 clear, PF5 copy-last)
 * and {@code XCTL} navigation are controller routing; and {@code SEND}/{@code RECEIVE MAP}, the
 * {@code COTRN2AI}/{@code COTRN2AO} symbolic-map fields, cursor positioning and {@code COMMAREA}
 * threading are controller + DTO concerns. This service receives an already-bound
 * {@link TransactionDto} from {@code TransactionController} ({@code POST /api/transactions}) and
 * returns the same DTO populated with the generated {@link TransactionDto#getTransactionId()
 * transactionId}; it performs only the data-centric business logic.</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1, &sect;0.4.2)</h2>
 * <ul>
 *   <li><strong>{@code CXACAIX} alternate-index read {@code READ-CXACAIX-FILE} &rarr;
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}.</strong> The non-unique index
 *       returns a {@link List}; the COBOL used the first matching record, so this takes
 *       {@code get(0)}. An empty result is the {@code DFHRESP(NOTFND)} path.</li>
 *   <li><strong>{@code CCXREF} primary-key read {@code READ-CCXREF-FILE} &rarr;
 *       {@link CardCrossReferenceRepository#findById(Object)}.</strong> An empty {@code Optional} is
 *       the {@code DFHRESP(NOTFND)} path.</li>
 *   <li><strong>{@code MOVE HIGH-VALUES TO TRAN-ID} + {@code STARTBR}/{@code READPREV}/{@code ENDBR}
 *       + {@code ADD 1} &rarr; {@link TransactionRepository#findMaxTransactionId()}{@code  + 1}.</strong>
 *       The browse-to-end-of-{@code TRANSACT} id discovery becomes a single max-key query; the
 *       {@code +1} increment and 16-digit zero-pad ({@code PIC 9(16)}) are performed here, not in the
 *       repository. An empty table ({@code ENDFILE} &rarr; zeros) is {@code Optional.empty()} &rarr;
 *       first id {@code 1}.</li>
 *   <li><strong>{@code CALL 'CSUTLDTC'} &rarr; {@link DateValidationService}.</strong> The LE
 *       {@code CEEDAYS} real-date validation becomes a {@code java.time} strict parse. See
 *       {@link #requireValidCalendarDate(LocalDate, String)} for the mandatory dash-stripping
 *       substitution.</li>
 *   <li><strong>VSAM {@code WRITE} / {@code DFHRESP(DUPKEY)} &rarr; {@code saveAndFlush} /
 *       {@link DuplicateRecordException}.</strong> The flush forces the {@code INSERT} (and any
 *       duplicate-key violation) to surface synchronously inside the {@link Transactional @Transactional}
 *       unit, mirroring the COBOL's immediate {@code DFHRESP(DUPKEY)} check right after the
 *       {@code WRITE}; {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)} &rarr;
 *       {@link DuplicateRecordException} (HTTP&nbsp;409).</li>
 *   <li><strong>{@code FUNCTION NUMVAL-C} &rarr; {@link BigDecimal}.</strong> The signed amount is a
 *       {@link BigDecimal} of scale 2 &mdash; <strong>never</strong> {@code float}/{@code double}; it
 *       is compared with {@link BigDecimal#compareTo(BigDecimal)}, never the scale-sensitive
 *       {@link BigDecimal#equals(Object)} (AAP &sect;0.7.3).</li>
 * </ul>
 *
 * <h2>{@code @Transactional} boundary (AAP &sect;0.7.5)</h2>
 * <p>{@code COTRN02C} writes a single record via one CICS {@code WRITE}; that implicit unit of work is
 * mapped faithfully by annotating {@link #addTransaction(TransactionDto)} {@link Transactional
 * &#64;Transactional} so the insert commits or rolls back atomically (a duplicate-key violation rolls
 * the write back). Because {@link ValidationException} and {@link DuplicateRecordException} are
 * unchecked, a failed validation or duplicate also triggers the default rollback.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>Stateless and singleton-safe: the only instance fields are the three injected, immutable
 * collaborators; every constant is {@code static final} and the {@link Pattern} is immutable.</p>
 *
 * @see TransactionDto
 * @see TransactionRepository
 * @see CardCrossReferenceRepository
 * @see DateValidationService
 */
@Service
public class TransactionAddService {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COBOL message literals (COTRN02C). These strings are part of the preserved external
    // contract (AAP §0.7.2) and must remain byte-identical to the source program — do not alter them.
    // -----------------------------------------------------------------------------------------------

    /** {@code 'Account ID must be Numeric...'} &mdash; COTRN02C L199. */
    private static final String MSG_ACCT_NOT_NUMERIC = "Account ID must be Numeric...";

    /** {@code 'Account ID NOT found...'} &mdash; COTRN02C L593 (READ-CXACAIX DFHRESP(NOTFND)). */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    /** {@code 'Card Number must be Numeric...'} &mdash; COTRN02C L213. */
    private static final String MSG_CARD_NOT_NUMERIC = "Card Number must be Numeric...";

    /** {@code 'Card Number NOT found...'} &mdash; COTRN02C L626 (READ-CCXREF DFHRESP(NOTFND)). */
    private static final String MSG_CARD_NOT_FOUND = "Card Number NOT found...";

    /** {@code 'Account or Card Number must be entered...'} &mdash; COTRN02C L226. */
    private static final String MSG_ACCT_OR_CARD_REQUIRED = "Account or Card Number must be entered...";

    /** {@code 'Type CD can NOT be empty...'} &mdash; COTRN02C L254. */
    private static final String MSG_TYPE_EMPTY = "Type CD can NOT be empty...";

    /** {@code 'Category CD can NOT be empty...'} &mdash; COTRN02C L260. */
    private static final String MSG_CAT_EMPTY = "Category CD can NOT be empty...";

    /** {@code 'Source can NOT be empty...'} &mdash; COTRN02C L266. */
    private static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";

    /** {@code 'Description can NOT be empty...'} &mdash; COTRN02C L272. */
    private static final String MSG_DESC_EMPTY = "Description can NOT be empty...";

    /** {@code 'Amount can NOT be empty...'} &mdash; COTRN02C L278. */
    private static final String MSG_AMOUNT_EMPTY = "Amount can NOT be empty...";

    /** {@code 'Orig Date can NOT be empty...'} &mdash; COTRN02C L284. */
    private static final String MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty...";

    /** {@code 'Proc Date can NOT be empty...'} &mdash; COTRN02C L290. */
    private static final String MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty...";

    /** {@code 'Merchant ID can NOT be empty...'} &mdash; COTRN02C L296. */
    private static final String MSG_MERCH_ID_EMPTY = "Merchant ID can NOT be empty...";

    /** {@code 'Merchant Name can NOT be empty...'} &mdash; COTRN02C L302. */
    private static final String MSG_MERCH_NAME_EMPTY = "Merchant Name can NOT be empty...";

    /** {@code 'Merchant City can NOT be empty...'} &mdash; COTRN02C L308. */
    private static final String MSG_MERCH_CITY_EMPTY = "Merchant City can NOT be empty...";

    /** {@code 'Merchant Zip can NOT be empty...'} &mdash; COTRN02C L314. */
    private static final String MSG_MERCH_ZIP_EMPTY = "Merchant Zip can NOT be empty...";

    /** {@code 'Type CD must be Numeric...'} &mdash; COTRN02C L325. */
    private static final String MSG_TYPE_NOT_NUMERIC = "Type CD must be Numeric...";

    /** {@code 'Category CD must be Numeric...'} &mdash; COTRN02C L331. */
    private static final String MSG_CAT_NOT_NUMERIC = "Category CD must be Numeric...";

    /** {@code 'Amount should be in format -99999999.99'} &mdash; COTRN02C L345. */
    private static final String MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99";

    /** {@code 'Orig Date should be in format YYYY-MM-DD'} &mdash; COTRN02C L360. */
    private static final String MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";

    /** {@code 'Proc Date should be in format YYYY-MM-DD'} &mdash; COTRN02C L375. */
    private static final String MSG_PROC_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";

    /** {@code 'Orig Date - Not a valid date...'} &mdash; COTRN02C L401 (CSUTLDTC failure). */
    private static final String MSG_ORIG_DATE_INVALID = "Orig Date - Not a valid date...";

    /** {@code 'Proc Date - Not a valid date...'} &mdash; COTRN02C L421 (CSUTLDTC failure). */
    private static final String MSG_PROC_DATE_INVALID = "Proc Date - Not a valid date...";

    /** {@code 'Merchant ID must be Numeric...'} &mdash; COTRN02C L432. */
    private static final String MSG_MERCH_ID_NOT_NUMERIC = "Merchant ID must be Numeric...";

    /** {@code 'Confirm to add this transaction...'} &mdash; COTRN02C L178. */
    private static final String MSG_CONFIRM_PROMPT = "Confirm to add this transaction...";

    /** {@code 'Invalid value. Valid values are (Y/N)...'} &mdash; COTRN02C L184. */
    private static final String MSG_CONFIRM_INVALID = "Invalid value. Valid values are (Y/N)...";

    /** {@code 'Tran ID already exist...'} &mdash; COTRN02C L738 (DFHRESP(DUPKEY)/DFHRESP(DUPREC)). */
    private static final String MSG_TRAN_DUPLICATE = "Tran ID already exist...";

    // -----------------------------------------------------------------------------------------------
    // Success-message fragments (COTRN02C WRITE-TRANSACT-FILE NORMAL branch, L728-733).
    // The COBOL STRING concatenates 'Transaction added successfully. ' (trailing space) + ' Your Tran
    // ID is ' (leading space) + TRAN-ID + '.', producing TWO spaces between "successfully." and "Your".
    // The double space is part of the external contract and is preserved verbatim.
    // -----------------------------------------------------------------------------------------------

    /** Prefix of the success message; note the two spaces after {@code "successfully."}. */
    private static final String SUCCESS_MESSAGE_PREFIX = "Transaction added successfully.  Your Tran ID is ";

    /** Suffix of the success message (the trailing period). */
    private static final String SUCCESS_MESSAGE_SUFFIX = ".";

    /**
     * Format for the 16-digit zero-padded transaction id. COBOL substitution: {@code TRAN-ID PIC X(16)}
     * holding a zero-filled numeric key ({@code WS-TRAN-ID-N PIC 9(16)}) &rarr; {@code String.format}
     * {@code "%016d"}.
     */
    private static final String TRAN_ID_FORMAT = "%016d";

    /**
     * Exclusive upper bound on the absolute transaction amount. COBOL substitution: the positional
     * amount edit {@code -99999999.99} (COTRN02C L339-351) allows at most <strong>eight</strong>
     * integer digits (screen positions 2-9), i.e. {@code |amount| < 10^8}. Note this screen edit is
     * narrower than the {@code TRAN-AMT PIC S9(09)V99} storage field (nine integer digits); the screen
     * constraint is the one COTRN02C enforces, so it is reproduced here.
     */
    private static final BigDecimal AMOUNT_ABS_LIMIT = new BigDecimal("100000000");

    /**
     * All-digits matcher mirroring the COBOL {@code IS NUMERIC} class test on the unsigned id/code
     * fields ({@code ACTIDINI}, {@code CARDNINI}, {@code TTYPCDI}, {@code TCATCDI}, {@code MIDI}). A
     * {@link Pattern} is immutable and therefore safe to share from this singleton bean.
     */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * Positional {@code YYYY-MM-DD} matcher mirroring the COBOL date-format edit
     * ({@code COTRN02C} L353-381): four digits, {@code '-'}, two digits, {@code '-'}, two digits. See
     * {@link #requireYyyymmddFormat(LocalDate, String)} for how this is applied to the {@link LocalDate}
     * DTO fields.
     */
    private static final Pattern DATE_FORMAT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    // -----------------------------------------------------------------------------------------------
    // Injected collaborators (constructor injection; all final / singleton-safe).
    // -----------------------------------------------------------------------------------------------

    /** Repository for the {@code TRANSACT} dataset: max-id discovery and the transaction write. */
    private final TransactionRepository transactionRepository;

    /** Repository for the {@code CARDXREF} dataset / {@code CXACAIX} alternate index: xref resolution. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * The Java replacement for {@code CALL 'CSUTLDTC'} (AAP &sect;0.4.2). This is the only
     * {@code service.shared} collaborator this service imports, which is the evidence for
     * {@code depends_on_folders=[carddemo-java/src/main/java/com/cardemo/service/shared]}.
     */
    private final DateValidationService dateValidationService;

    /**
     * Constructs the service with its three collaborators. Spring selects this constructor for
     * autowiring (a single constructor needs no {@code @Autowired} annotation).
     *
     * @param transactionRepository         repository for the {@code TRANSACT} table
     * @param cardCrossReferenceRepository  repository for the {@code CARDXREF} table / {@code CXACAIX}
     *                                      alternate index
     * @param dateValidationService         the {@code CSUTLDTC} real-date validation replacement
     */
    public TransactionAddService(TransactionRepository transactionRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            DateValidationService dateValidationService) {
        this.transactionRepository = transactionRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.dateValidationService = dateValidationService;
    }

    // -----------------------------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------------------------

    /**
     * Adds a new transaction &mdash; the Java realization of {@code COTRN02C}'s {@code PROCESS-ENTER-KEY}
     * paragraph (L164-188) followed by {@code ADD-TRANSACTION} (L442-466) and
     * {@code WRITE-TRANSACT-FILE} (L711-749).
     *
     * <p>The steps run in the exact COBOL order, fail-fast on the first error (see the class Javadoc):</p>
     * <ol>
     *   <li>{@link #resolveCrossReference(TransactionDto)} &mdash; {@code VALIDATE-INPUT-KEY-FIELDS}:
     *       resolve the card cross-reference by account (precedence) or card.</li>
     *   <li>{@link #validateDataFields(TransactionDto)} &mdash; {@code VALIDATE-INPUT-DATA-FIELDS}:
     *       the ordered empty &rarr; numeric &rarr; amount &rarr; date-format &rarr; calendar-validity
     *       &rarr; merchant-id cascade.</li>
     *   <li>{@link #evaluateConfirm(TransactionDto)} &mdash; {@code EVALUATE CONFIRMI}: require a
     *       {@code 'Y'}/{@code 'y'} confirmation.</li>
     *   <li>auto-generate the id, build the {@link Transaction} and persist it.</li>
     * </ol>
     *
     * <p>On success the request DTO is populated with the generated
     * {@link TransactionDto#setTransactionId(String) transactionId} and returned. The COBOL success
     * message (built by {@link #buildSuccessMessage(String)}) embeds that same id; because the
     * {@link TransactionDto} has no message field, the generated id is the canonical response and the
     * verbatim message is available to the controller via {@link #buildSuccessMessage(String)}.</p>
     *
     * <p>Annotated {@link Transactional @Transactional} (AAP &sect;0.7.5): the insert commits or rolls
     * back atomically; a duplicate-key violation, a failed validation or any other runtime exception
     * rolls the write back.</p>
     *
     * @param request the inbound add request, already bound and structurally validated by the
     *                controller (Jakarta Bean Validation)
     * @return the {@code request} DTO with its {@link TransactionDto#getTransactionId() transactionId}
     *         set to the newly generated 16-digit id
     * @throws ValidationException      on the first failed key/data/confirm check (HTTP&nbsp;400),
     *                                  carrying the verbatim COBOL message
     * @throws DuplicateRecordException when the generated id collides with an existing row
     *                                  (the {@code DFHRESP(DUPKEY)} path, HTTP&nbsp;409)
     */
    @Transactional
    public TransactionDto addTransaction(TransactionDto request) {
        // PROCESS-ENTER-KEY (L164-188): key fields -> data fields -> confirm, in this exact order.
        resolveCrossReference(request);   // (1) VALIDATE-INPUT-KEY-FIELDS
        validateDataFields(request);      // (2) VALIDATE-INPUT-DATA-FIELDS
        evaluateConfirm(request);         // (3) EVALUATE CONFIRMI ('Y'/'y' required to proceed)

        // (4) ADD-TRANSACTION (L442-466): generate the next id, build the record and write it.
        String newTranId = generateNextTransactionId();
        Transaction newTransaction = buildTransaction(request, newTranId);

        // COBOL substitution: WRITE-TRANSACT-FILE (L711-749). VSAM WRITE DATASET('TRANSACT') ->
        // saveAndFlush. The flush forces the INSERT (and any duplicate-key violation) to surface NOW,
        // inside this @Transactional unit, mirroring the COBOL's immediate DFHRESP(DUPKEY) check right
        // after the WRITE; a plain save() could defer the flush to commit, escaping this catch.
        // DFHRESP(DUPKEY)/DFHRESP(DUPREC) -> DuplicateRecordException (HTTP 409). The COBOL WHEN OTHER
        // ("Unable to Add Transaction...") is a CICS infra/IO failure; in Java the corresponding
        // unexpected DataAccessException is left to propagate (no domain exception is invented for it).
        try {
            transactionRepository.saveAndFlush(newTransaction);
        } catch (DataIntegrityViolationException duplicate) {
            throw new DuplicateRecordException(MSG_TRAN_DUPLICATE, duplicate);
        }

        // Success (COBOL NORMAL branch): the generated id is the canonical machine-readable result.
        request.setTransactionId(newTranId);
        return request;
    }

    /**
     * Builds the verbatim COTRN02C success message for a generated transaction id
     * ({@code WRITE-TRANSACT-FILE} NORMAL branch, L728-733).
     *
     * <p>The COBOL {@code STRING} statement concatenates {@code 'Transaction added successfully. '}
     * (trailing space) {@code + ' Your Tran ID is '} (leading space) {@code + TRAN-ID + '.'}, which
     * yields <strong>two</strong> spaces between {@code "successfully."} and {@code "Your"} &mdash;
     * preserved here byte-for-byte. The {@link TransactionDto} carries no message field, so this method
     * is the single source of truth for the on-success message (consumed by the controller / verified
     * by tests). It is a pure function of the id and holds no state.</p>
     *
     * @param transactionId the generated 16-digit transaction id
     * @return the success message, for example
     *         {@code "Transaction added successfully.  Your Tran ID is 0000000000000042."}
     */
    public String buildSuccessMessage(String transactionId) {
        return SUCCESS_MESSAGE_PREFIX + transactionId + SUCCESS_MESSAGE_SUFFIX;
    }

    // -----------------------------------------------------------------------------------------------
    // Step 1 — VALIDATE-INPUT-KEY-FIELDS (COTRN02C L193-230): cross-reference resolution.
    // -----------------------------------------------------------------------------------------------

    /**
     * Resolves the card cross-reference from the account id (checked first &mdash; account precedence)
     * or, failing that, the card number &mdash; the Java realization of {@code VALIDATE-INPUT-KEY-FIELDS}
     * ({@code EVALUATE TRUE}, L193-230).
     *
     * <p>When an account id is supplied it takes precedence (the COBOL evaluates {@code ACTIDINI}
     * first): the resolved {@code XREF-CARD-NUM} is <strong>written back</strong> onto
     * {@link TransactionDto#setCardNumber(String)} and becomes the stored {@code tranCardNum}
     * ({@code MOVE XREF-CARD-NUM TO CARDNINI}, L209). When only a card number is supplied, the resolved
     * account id is written back ({@code MOVE XREF-ACCT-ID TO ACTIDINI}, L223) for information only and
     * the entered card number is the stored value. When both are supplied the account path runs and the
     * card number is overwritten by the resolved value. When neither is supplied the request is
     * rejected.</p>
     *
     * @param dto the request whose {@code accountId}/{@code cardNumber} drive the lookup and which is
     *            mutated with the resolved counterpart
     * @throws ValidationException on a non-numeric id, a not-found xref, or neither field supplied
     */
    private void resolveCrossReference(TransactionDto dto) {
        String accountId = dto.getAccountId();
        String cardNumber = dto.getCardNumber();

        // EVALUATE TRUE — WHEN ACTIDINI NOT = SPACES AND LOW-VALUES (account id present, checked first).
        if (hasValue(accountId)) {
            if (!isNumeric(accountId)) {
                throw new ValidationException(MSG_ACCT_NOT_NUMERIC);
            }
            // COBOL substitution: COMPUTE WS-ACCT-ID-N = FUNCTION NUMVAL(ACTIDINI) -> Long.parseLong
            // (the value is all-digits after the IS NUMERIC check above).
            long accountIdNumeric = Long.parseLong(accountId.trim());
            // COBOL substitution: READ-CXACAIX-FILE (CXACAIX alternate index, RIDFLD(XREF-ACCT-ID))
            // -> findByXrefAcctId. The index is NONUNIQUE so it returns a List; the COBOL READ took the
            // single record on the key, which here is the first element.
            List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(accountIdNumeric);
            if (xrefs.isEmpty()) {
                // DFHRESP(NOTFND) (L591-595).
                throw new ValidationException(MSG_ACCT_NOT_FOUND);
            }
            // MOVE XREF-CARD-NUM TO CARDNINI (L209): write the resolved card number back so the record
            // build (buildTransaction) stores it as TRAN-CARD-NUM.
            dto.setCardNumber(xrefs.get(0).getXrefCardNum());
            return;
        }

        // WHEN CARDNINI NOT = SPACES AND LOW-VALUES (card number present).
        if (hasValue(cardNumber)) {
            if (!isNumeric(cardNumber)) {
                throw new ValidationException(MSG_CARD_NOT_NUMERIC);
            }
            // COBOL substitution: READ-CCXREF-FILE (CCXREF primary key, RIDFLD(XREF-CARD-NUM))
            // -> findById. An empty Optional is the DFHRESP(NOTFND) path (L624-628).
            CardCrossReference xref = cardCrossReferenceRepository.findById(cardNumber.trim())
                    .orElseThrow(() -> new ValidationException(MSG_CARD_NOT_FOUND));
            // MOVE XREF-ACCT-ID TO ACTIDINI (L223): informational write-back; the stored card number is
            // the entered cardNumber, not a resolved value.
            dto.setAccountId(String.valueOf(xref.getXrefAcctId()));
            return;
        }

        // WHEN OTHER — neither account nor card supplied (L224-229).
        throw new ValidationException(MSG_ACCT_OR_CARD_REQUIRED);
    }

    // -----------------------------------------------------------------------------------------------
    // Step 2 — VALIDATE-INPUT-DATA-FIELDS (COTRN02C L235-436): the ordered field-edit cascade.
    // -----------------------------------------------------------------------------------------------

    /**
     * Runs the data-field validation cascade in the exact COBOL order, throwing a
     * {@link ValidationException} with the verbatim message on the <strong>first</strong> failure
     * (fail-fast) &mdash; the Java realization of {@code VALIDATE-INPUT-DATA-FIELDS} (L235-436).
     *
     * <p>Order: (2a) non-empty edits for all eleven fields (L251-320); (2b) numeric edits for the
     * type and category codes (L322-337); (2c) the amount-format edit (L339-351) and scale-2
     * normalization; (2d) the date-format edits (L353-381); (2e) the {@code CSUTLDTC} calendar-validity
     * edits (L389-427); (2f) the merchant-id numeric edit (L430).</p>
     *
     * @param dto the request to validate
     * @throws ValidationException on the first failed edit, carrying the verbatim COBOL message
     */
    private void validateDataFields(TransactionDto dto) {
        // (2a) Empty edits (L251-320), in COBOL field order. For the text fields "empty" is the COBOL
        // "= SPACES OR LOW-VALUES" (null/blank); for the amount (a BigDecimal) and the dates (LocalDate)
        // "empty" is a null value (Jackson leaves an absent/null JSON value null).
        if (!hasValue(dto.getTypeCode())) {
            throw new ValidationException(MSG_TYPE_EMPTY);
        }
        if (!hasValue(dto.getCategoryCode())) {
            throw new ValidationException(MSG_CAT_EMPTY);
        }
        if (!hasValue(dto.getSource())) {
            throw new ValidationException(MSG_SOURCE_EMPTY);
        }
        if (!hasValue(dto.getDescription())) {
            throw new ValidationException(MSG_DESC_EMPTY);
        }
        if (dto.getAmount() == null) {
            throw new ValidationException(MSG_AMOUNT_EMPTY);
        }
        if (dto.getOriginationDate() == null) {
            throw new ValidationException(MSG_ORIG_DATE_EMPTY);
        }
        if (dto.getProcessingDate() == null) {
            throw new ValidationException(MSG_PROC_DATE_EMPTY);
        }
        if (!hasValue(dto.getMerchantId())) {
            throw new ValidationException(MSG_MERCH_ID_EMPTY);
        }
        if (!hasValue(dto.getMerchantName())) {
            throw new ValidationException(MSG_MERCH_NAME_EMPTY);
        }
        if (!hasValue(dto.getMerchantCity())) {
            throw new ValidationException(MSG_MERCH_CITY_EMPTY);
        }
        if (!hasValue(dto.getMerchantZip())) {
            throw new ValidationException(MSG_MERCH_ZIP_EMPTY);
        }

        // (2b) Numeric edits (L322-337): type code then category code.
        if (!isNumeric(dto.getTypeCode())) {
            throw new ValidationException(MSG_TYPE_NOT_NUMERIC);
        }
        if (!isNumeric(dto.getCategoryCode())) {
            throw new ValidationException(MSG_CAT_NOT_NUMERIC);
        }

        // (2c) Amount-format edit (L339-351). COBOL substitution: the positional edit of the 12-char
        // display field `-99999999.99` (sign, 8 integer digits, '.', 2 decimals) is reproduced as the
        // BigDecimal range |amount| < 10^8 AND scale <= 2. The comparison uses compareTo (NEVER equals,
        // which is scale-sensitive); the value is NEVER a float/double (AAP §0.7.3).
        BigDecimal amount = dto.getAmount();
        if (amount.abs().compareTo(AMOUNT_ABS_LIMIT) >= 0 || amount.scale() > 2) {
            throw new ValidationException(MSG_AMOUNT_FORMAT);
        }

        // (2d) Date-format edits (L353-381): origination THEN processing, mirroring the COBOL order
        // exactly (both format edits run before either CSUTLDTC calendar edit). DTO date type:
        // LocalDate — Jackson already parsed the ISO `yyyy-MM-dd` wire form (@JsonFormat), so these
        // edits always pass; they are retained as a one-for-one structural mirror of the COBOL control
        // flow (§0.7.4) and to anchor the verbatim format messages.
        requireYyyymmddFormat(dto.getOriginationDate(), MSG_ORIG_DATE_FORMAT);
        requireYyyymmddFormat(dto.getProcessingDate(), MSG_PROC_DATE_FORMAT);

        // (2e) CSUTLDTC calendar-validity edits (L389-427): origination THEN processing.
        requireValidCalendarDate(dto.getOriginationDate(), MSG_ORIG_DATE_INVALID);
        requireValidCalendarDate(dto.getProcessingDate(), MSG_PROC_DATE_INVALID);

        // (2f) Merchant-id numeric edit (L430).
        if (!isNumeric(dto.getMerchantId())) {
            throw new ValidationException(MSG_MERCH_ID_NOT_NUMERIC);
        }
    }

    /**
     * Calendar-validity edit for a single date &mdash; the Java realization of the COTRN02C
     * {@code CALL 'CSUTLDTC'} blocks (L389-407 for origination, L409-427 for processing).
     *
     * <p>COBOL substitution: {@code CALL 'CSUTLDTC' USING <date>, 'YYYY-MM-DD', <result>} &rarr;
     * {@link DateValidationService#validateDate(String)}. {@link DateValidationService} understands only
     * the {@code YYYYMMDD} picture (a strict {@code uuuuMMdd} parse) and defaults any other picture
     * &mdash; including {@code "YYYY-MM-DD"} &mdash; to it, so a dashed string would fail to parse. The
     * dashes are therefore <strong>stripped</strong> before the call. {@link
     * DateValidationService.DateValidationResult#valid() result.valid()} (severity 0) is the faithful
     * equivalent of the COBOL {@code SEV-CD = '0000'} pass; the COBOL {@code '2513'} message-number
     * tolerance is subsumed by {@code java.time} strict resolution (the result exposes no message
     * number). Only this real-date check is performed &mdash; the century-window
     * {@code validateCcyymmdd} cascade is intentionally <strong>not</strong> called, because COTRN02C
     * invokes only the {@code CSUTLDTC} real-date validation.</p>
     *
     * <p>Because the inbound value is an already-parsed {@link LocalDate}, this check is structurally
     * faithful but always passes; it is invoked anyway so the migrated control flow mirrors the COBOL
     * one-for-one.</p>
     *
     * @param date           the date to validate (never {@code null} here — the empty edit ran first)
     * @param failureMessage the verbatim COBOL message to throw when the date is rejected
     * @throws ValidationException when {@link DateValidationService} reports the date invalid
     */
    private void requireValidCalendarDate(LocalDate date, String failureMessage) {
        // Strip the dashes from the ISO yyyy-MM-dd rendering to obtain the 8-char YYYYMMDD picture
        // that DateValidationService parses (see method Javadoc for why this is mandatory).
        String yyyymmdd = date.toString().replace("-", "");
        DateValidationService.DateValidationResult result = dateValidationService.validateDate(yyyymmdd);
        if (!result.valid()) {
            throw new ValidationException(failureMessage);
        }
    }

    /**
     * Positional date-format edit for a single date &mdash; the Java realization of the COTRN02C
     * date-format checks (L353-360 for origination, L362-381 for processing).
     *
     * <p>DTO date type: {@link LocalDate}. Jackson already parsed the value from the ISO
     * {@code yyyy-MM-dd} wire form ({@code @JsonFormat} on the DTO), so reconstructing
     * {@link LocalDate#toString()} and re-checking the positional {@code YYYY-MM-DD} shape with
     * {@link #DATE_FORMAT} is structurally faithful to the COBOL edit yet always passes for an
     * already-parsed {@link LocalDate}. The method is retained as a one-for-one mirror of the COBOL
     * control flow (&sect;0.7.4) and to anchor the verbatim format message; a {@code String}-typed DTO
     * would instead test the raw inbound string here and could legitimately fail. The empty/{@code null}
     * case was already handled by the (2a) empty edit.</p>
     *
     * @param date           the date to check (never {@code null} here — the empty edit ran first)
     * @param failureMessage the verbatim COBOL message to throw on a malformed value
     * @throws ValidationException when the date does not match the {@code YYYY-MM-DD} format
     */
    private void requireYyyymmddFormat(LocalDate date, String failureMessage) {
        if (!DATE_FORMAT.matcher(date.toString()).matches()) {
            throw new ValidationException(failureMessage);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step 3 — EVALUATE CONFIRMI (COTRN02C L169-188): the Y/N add confirmation.
    // -----------------------------------------------------------------------------------------------

    /**
     * Evaluates the add-confirmation flag &mdash; the Java realization of {@code EVALUATE CONFIRMI}
     * (L169-188). A {@code 'Y'}/{@code 'y'} proceeds; {@code 'N'}/{@code 'n'} or a blank/absent value
     * (the COBOL {@code SPACES}/{@code LOW-VALUES}) re-prompts; any other value is rejected.
     *
     * @param dto the request whose {@link TransactionDto#getConfirm() confirm} flag is evaluated
     * @throws ValidationException with the confirmation prompt for {@code N}/blank, or the
     *                             invalid-value message for any other input
     */
    private void evaluateConfirm(TransactionDto dto) {
        String confirm = dto.getConfirm();

        // WHEN 'Y' WHEN 'y' -> proceed to ADD-TRANSACTION.
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            return;
        }

        // WHEN 'N' WHEN 'n' WHEN SPACES WHEN LOW-VALUES -> "Confirm to add this transaction...".
        // null / "" / blank are the Java equivalents of SPACES / LOW-VALUES.
        if (confirm == null || confirm.isBlank() || "N".equals(confirm) || "n".equals(confirm)) {
            throw new ValidationException(MSG_CONFIRM_PROMPT);
        }

        // WHEN OTHER -> "Invalid value. Valid values are (Y/N)...".
        throw new ValidationException(MSG_CONFIRM_INVALID);
    }

    // -----------------------------------------------------------------------------------------------
    // Step 4 — ADD-TRANSACTION (COTRN02C L442-466): id generation and record build.
    // -----------------------------------------------------------------------------------------------

    /**
     * Generates the next transaction id &mdash; the Java realization of the COTRN02C browse-to-end id
     * discovery (L444-451).
     *
     * <p>COBOL substitution: {@code MOVE HIGH-VALUES TO TRAN-ID} then {@code STARTBR}/{@code READPREV}/
     * {@code ENDBR} to read the highest existing key, then {@code ADD 1} &rarr;
     * {@link TransactionRepository#findMaxTransactionId()}{@code  + 1}. Because {@code TRAN-ID} is a
     * 16-character zero-padded numeric key, its lexicographic maximum equals its numeric maximum. An
     * empty table is the {@code READPREV} {@code ENDFILE} branch ({@code MOVE ZEROS TO TRAN-ID}, L689),
     * which here is {@link java.util.Optional#empty()} &rarr; first id {@code 1}. The result is
     * zero-padded back to 16 digits ({@code PIC 9(16)}).</p>
     *
     * @return the next 16-digit transaction id (for example {@code "0000000000000042"})
     */
    private String generateNextTransactionId() {
        long nextId = transactionRepository.findMaxTransactionId()
                .map(maxId -> Long.parseLong(maxId.trim()) + 1L)
                .orElse(1L);
        return String.format(TRAN_ID_FORMAT, nextId);
    }

    /**
     * Builds the new {@link Transaction} entity from the validated request &mdash; the Java realization
     * of the COTRN02C {@code MOVE}s into {@code TRAN-RECORD} (L450-465).
     *
     * <p>Type conversions performed here mirror the COBOL {@code MOVE}s into the typed record fields and
     * are documented at each seam. The amount is normalized to scale 2 ({@code FUNCTION NUMVAL-C}); the
     * category code and merchant id (validated numeric earlier) are converted from {@link String} to the
     * entity's {@link Integer}/{@link Long} types; and the two dates are converted from {@link LocalDate}
     * to the entity's {@link java.time.LocalDateTime} timestamp columns at start-of-day.</p>
     *
     * @param dto       the validated request (its {@code cardNumber} already holds the resolved card)
     * @param newTranId the generated 16-digit transaction id
     * @return the populated {@link Transaction} ready to persist
     */
    private Transaction buildTransaction(TransactionDto dto, String newTranId) {
        // INITIALIZE TRAN-RECORD (L450) -> a fresh entity.
        Transaction transaction = new Transaction();

        transaction.setTranId(newTranId);                                  // MOVE WS-TRAN-ID-N TO TRAN-ID
        transaction.setTranTypeCd(dto.getTypeCode());                      // MOVE TTYPCDI TO TRAN-TYPE-CD
        // COBOL substitution: MOVE TCATCDI (X4, validated numeric) TO TRAN-CAT-CD (9(4)) -> String->Integer.
        transaction.setTranCatCd(Integer.valueOf(dto.getCategoryCode().trim()));
        // MOVE TRNSRCI TO TRAN-SOURCE — free text; NOT validated against the TransactionSource enum
        // (COTRN02C only checks non-empty; enforcing the enum would change behavior — Minimal Change Clause).
        transaction.setTranSource(dto.getSource());
        transaction.setTranDesc(dto.getDescription());                     // MOVE TDESCI TO TRAN-DESC
        // COBOL substitution: COMPUTE WS-TRAN-AMT-N = FUNCTION NUMVAL-C(TRNAMTI) -> BigDecimal scale 2
        // (HALF_EVEN banker's rounding; the value is already within scale 2 after the (2c) edit, so this
        // is an exact rescale, never a rounding change). NEVER float/double.
        transaction.setTranAmt(dto.getAmount().setScale(2, RoundingMode.HALF_EVEN));
        // MOVE CARDNINI TO TRAN-CARD-NUM — the resolved card number from step 1 (account path wrote the
        // XREF-CARD-NUM back; card path used the entered value).
        transaction.setTranCardNum(dto.getCardNumber());
        // COBOL substitution: MOVE MIDI (X9, validated numeric) TO TRAN-MERCHANT-ID (9(9)) -> String->Long.
        transaction.setTranMerchantId(Long.valueOf(dto.getMerchantId().trim()));
        transaction.setTranMerchantName(dto.getMerchantName());            // MOVE MNAMEI TO TRAN-MERCHANT-NAME
        transaction.setTranMerchantCity(dto.getMerchantCity());            // MOVE MCITYI TO TRAN-MERCHANT-CITY
        transaction.setTranMerchantZip(dto.getMerchantZip());              // MOVE MZIPI TO TRAN-MERCHANT-ZIP
        // COBOL substitution: MOVE TORIGDTI (X10 date) TO TRAN-ORIG-TS (X26) / MOVE TPROCDTI TO TRAN-PROC-TS.
        // The entity maps these timestamps to LocalDateTime (TIMESTAMP columns); the screen carried only
        // the date portion, so each date is stored at start-of-day (00:00:00). The date is preserved
        // exactly; the COBOL space-padding of the 10-char date into the 26-char field has no relational
        // analogue and is intentionally not reproduced.
        transaction.setTranOrigTs(dto.getOriginationDate().atStartOfDay());
        transaction.setTranProcTs(dto.getProcessingDate().atStartOfDay());

        return transaction;
    }

    // -----------------------------------------------------------------------------------------------
    // Shared field predicates (COBOL class tests).
    // -----------------------------------------------------------------------------------------------

    /**
     * Tests whether a field carries a value &mdash; the Java equivalent of the COBOL
     * {@code field NOT = SPACES AND LOW-VALUES} presence test. {@code null}, empty and all-whitespace
     * values are treated as absent.
     *
     * @param value the field value
     * @return {@code true} when {@code value} is non-{@code null} and not blank
     */
    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Tests whether a field is all digits &mdash; the Java equivalent of the COBOL {@code IS NUMERIC}
     * class test on the unsigned id/code fields. A {@code null}, empty or non-digit value (including any
     * embedded space or sign) is not numeric, matching {@code IS NUMERIC} on an unsigned {@code PIC 9}
     * field.
     *
     * @param value the field value
     * @return {@code true} when {@code value} is non-empty and composed solely of ASCII digits
     */
    private static boolean isNumeric(String value) {
        return value != null && DIGITS.matcher(value).matches();
    }
}
