package com.cardemo.service.transaction;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction Detail / View business logic &mdash; the Java&nbsp;25 / Spring Boot 3.x realization of the
 * legacy AWS CardDemo CICS COBOL program {@code app/cbl/COTRN01C.cbl} (transaction id {@code CT01}).
 *
 * <p>{@code COTRN01C} is the <strong>single keyed read</strong> of the {@code TRANSACT} VSAM KSDS: an
 * operator (or a row selection forwarded from the {@code COTRN00C} transaction-list screen via
 * {@code CDEMO-CT01-TRN-SELECTED}) supplies a transaction id, the program reads that one record and paints
 * every detail field onto the {@code COTRN1A} map. This service reproduces that behavior end to end against
 * the PostgreSQL {@code transaction} table (mapped by {@link Transaction}) through
 * {@link TransactionRepository}. It is the simplest of the three transaction services &mdash; one read, two
 * possible operator-facing messages &mdash; but it carries two parity-critical concerns: the
 * <em>exact</em> error messages and the entity&rarr;DTO <em>type conversions</em>.</p>
 *
 * <h2>Technology substitution &mdash; CICS/VSAM &rarr; Spring Data JPA (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <table border="1">
 *   <caption>Legacy COTRN01C construct &rarr; Java realization</caption>
 *   <tr><th>COBOL / CICS construct</th><th>Java realization</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} blank edit (L146-156): {@code WHEN TRNIDINI = SPACES OR LOW-VALUES}</td>
 *       <td>{@code if (id == null || id.isBlank())} &rarr; {@link ValidationException}
 *           ({@link #MSG_TRAN_ID_EMPTY}, HTTP&nbsp;400)</td></tr>
 *   <tr><td>{@code READ-TRANSACT-FILE} keyed read (L267-296):
 *       {@code EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID)}</td>
 *       <td>{@link TransactionRepository#findById(Object) findById(String)}</td></tr>
 *   <tr><td>{@code DFHRESP(NORMAL)} (L281-282)</td>
 *       <td>{@link Optional#isPresent()} &rarr; map all detail fields ({@link #toDto(Transaction)})</td></tr>
 *   <tr><td>{@code DFHRESP(NOTFND)} (L283-285)</td>
 *       <td>{@link RecordNotFoundException} ({@link #MSG_TRAN_NOT_FOUND}, HTTP&nbsp;404 / FILE STATUS
 *           {@code '23'})</td></tr>
 *   <tr><td>{@code WHEN OTHER} fatal read failure (L289-295,
 *       {@code 'Unable to lookup Transaction...'})</td>
 *       <td>a Spring {@code DataAccessException} from {@code findById} is <strong>not</strong> caught here;
 *           it propagates to the centralized {@code @RestControllerAdvice} (HTTP&nbsp;500), faithfully
 *           reproducing the COBOL unrecoverable-read path</td></tr>
 *   <tr><td>{@code SEND MAP('COTRN1A')} field {@code MOVE}s (L177-190)</td>
 *       <td>population of the response {@link TransactionDto} in {@link #toDto(Transaction)}</td></tr>
 * </table>
 *
 * <h2>Read-only transaction boundary (AAP &sect;0.7.5)</h2>
 * <p>The class is annotated {@link Transactional @Transactional(readOnly = true)} so every public method runs
 * in a read-only transaction. This is faithful: {@code COTRN01C} only reads. The {@code UPDATE} keyword on
 * its {@code EXEC CICS READ ... UPDATE} (L275) is a CICS browse/record-lock artifact with <strong>no</strong>
 * accompanying {@code REWRITE} &mdash; there is no business write on this path, so a read-only boundary
 * preserves the behavior exactly while letting the JPA provider skip dirty-checking.</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>The monetary {@link TransactionDto#getAmount() amount} is a {@link BigDecimal} of scale&nbsp;2
 * ({@code TRAN-AMT PIC S9(09)V99}); {@code float}/{@code double} are never used and any comparison uses
 * {@link BigDecimal#compareTo(BigDecimal)} rather than the scale-sensitive
 * {@link BigDecimal#equals(Object)}.</p>
 *
 * <h2>Entity&rarr;DTO type conversions (verified against the generated files)</h2>
 * <ul>
 *   <li>{@code TRAN-CAT-CD} is an {@link Integer} on the entity but the DTO {@code categoryCode} is a
 *       {@link String}: the COBOL {@code MOVE TRAN-CAT-CD (PIC 9(04)) TO TCATCDI (PIC X(4))} renders the
 *       four-digit value with leading zeros, so it is formatted as a zero-padded four-character string.</li>
 *   <li>{@code TRAN-MERCHANT-ID} is a {@link Long} on the entity but the DTO {@code merchantId} is a
 *       {@link String}: the COBOL {@code MOVE TRAN-MERCHANT-ID (PIC 9(09)) TO MIDI (PIC X(9))} renders a
 *       nine-digit zero-padded value, so it is formatted as a zero-padded nine-character string.</li>
 *   <li>{@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} are {@link LocalDateTime} on the entity but the DTO
 *       {@code originationDate}/{@code processingDate} are {@link LocalDate}: the detail screen fields
 *       {@code TORIGDTI}/{@code TPROCDTI} are {@code PIC X(10)}, i.e. the {@code YYYY-MM-DD} date prefix of
 *       the 26-character timestamp, so only the date portion is carried ({@link LocalDateTime#toLocalDate()}).</li>
 *   <li>{@code TRAN-DESC X(100)}, {@code TRAN-MERCHANT-NAME X(50)} and {@code TRAN-MERCHANT-CITY X(50)} are
 *       wider than their detail-screen fields ({@code TDESCI X(60)}, {@code MNAMEI X(30)},
 *       {@code MCITYI X(25)}); the COBOL alphanumeric {@code MOVE} truncates to the screen width, reproduced
 *       by a leftmost-substring truncation.</li>
 * </ul>
 *
 * <p>Per the folder rule this service imports only the repository, the entity, the response DTO, the
 * exception types and the JDK/Spring framework &mdash; it does not reach into any other {@code service}
 * subpackage. It is stateless and singleton-safe: its only field is the injected, {@code final} repository.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at commit SHA {@code 27d6c6f}
 * ({@code app/cbl/COTRN01C.cbl}; record layout {@code app/cpy/CVTRA05Y.cpy}). The COBOL source is read-only
 * reference material and is never copied into this repository.</p>
 *
 * @see TransactionRepository
 * @see Transaction
 * @see TransactionDto
 * @see RecordNotFoundException
 * @see ValidationException
 */
@Service
@Transactional(readOnly = true)
public class TransactionDetailService {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COTRN01C messages. Copied EXACTLY (including the trailing "..." ellipsis) for 100%
    // message parity (AAP §0.7.2). These are the only two operator-facing messages this single
    // keyed-read program can raise; both surface to the client via the centralized exception advice.
    // -----------------------------------------------------------------------------------------------

    /**
     * Blank/empty transaction id on input &mdash; raised as a {@link ValidationException} (HTTP&nbsp;400).
     *
     * <p>COBOL substitution: {@code PROCESS-ENTER-KEY} {@code EVALUATE TRUE WHEN TRNIDINI = SPACES OR
     * LOW-VALUES} moved this literal into {@code WS-MESSAGE} ({@code COTRN01C} L147-150).</p>
     */
    // WHEN TRNIDINI = SPACES OR LOW-VALUES -> 'Tran ID can NOT be empty...' [COTRN01C L149]
    public static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /**
     * Keyed read found no matching transaction &mdash; raised as a {@link RecordNotFoundException}
     * (HTTP&nbsp;404 / FILE STATUS {@code '23'}).
     *
     * <p>COBOL substitution: {@code READ-TRANSACT-FILE} {@code EVALUATE WS-RESP-CD WHEN DFHRESP(NOTFND)}
     * moved this literal into {@code WS-MESSAGE} ({@code COTRN01C} L283-285).</p>
     */
    // WHEN DFHRESP(NOTFND) -> 'Transaction ID NOT found...' [COTRN01C L285]
    public static final String MSG_TRAN_NOT_FOUND = "Transaction ID NOT found...";

    // -----------------------------------------------------------------------------------------------
    // Field-width and decimal contract constants. Widths come from the BMS symbolic map
    // app/cpy-bms/COTRN01.CPY (the COTRN1A detail-screen fields) and the record layout
    // app/cpy/CVTRA05Y.cpy (TRAN-RECORD). They preserve the external-interface widths exactly.
    // -----------------------------------------------------------------------------------------------

    /**
     * The {@code TRAN-ID} key width &mdash; sixteen characters ({@code TRAN-ID PIC X(16)}). The stored
     * {@code TRANSACT} keys are sixteen-character zero-padded numerics, so a numeric lookup id is left
     * zero-padded to this width (see {@link #normalizeTransactionId(String)}).
     */
    private static final int TRAN_ID_KEY_LENGTH = 16;

    /**
     * The detail-screen description width &mdash; sixty characters ({@code TDESCI PIC X(60)}). The
     * hundred-character {@code TRAN-DESC} is truncated to this width when painted onto the detail screen.
     */
    private static final int DESC_DISPLAY_LENGTH = 60;

    /**
     * The merchant-name display width &mdash; thirty characters ({@code MNAMEI PIC X(30)}). The
     * fifty-character {@code TRAN-MERCHANT-NAME} is truncated to this width.
     */
    private static final int MERCHANT_NAME_DISPLAY_LENGTH = 30;

    /**
     * The merchant-city display width &mdash; twenty-five characters ({@code MCITYI PIC X(25)}). The
     * fifty-character {@code TRAN-MERCHANT-CITY} is truncated to this width.
     */
    private static final int MERCHANT_CITY_DISPLAY_LENGTH = 25;

    /**
     * The category-code width &mdash; four digits ({@code TCATCDI PIC X(4)} fed from
     * {@code TRAN-CAT-CD PIC 9(04)}). The {@link Integer} category code is rendered zero-padded to this
     * width to reproduce the COBOL numeric-to-display {@code MOVE}.
     */
    private static final int CATEGORY_CODE_LENGTH = 4;

    /**
     * The merchant-id width &mdash; nine digits ({@code MIDI PIC X(9)} fed from
     * {@code TRAN-MERCHANT-ID PIC 9(09)}). The {@link Long} merchant id is rendered zero-padded to this
     * width to reproduce the COBOL numeric-to-display {@code MOVE}.
     */
    private static final int MERCHANT_ID_LENGTH = 9;

    /**
     * The monetary scale &mdash; two fractional digits ({@code TRAN-AMT PIC S9(09)V99}, AAP &sect;0.7.3).
     * Amounts are {@link BigDecimal} only; never {@code float}/{@code double}.
     */
    private static final int AMOUNT_SCALE = 2;

    /** Matches an all-digits value, mirroring the COBOL {@code IS NUMERIC} test on an unsigned field. */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    // -----------------------------------------------------------------------------------------------
    // Injected collaborator. Constructor injection with a private-final field (no field @Autowired):
    // the sole state this stateless, singleton-safe service holds.
    // -----------------------------------------------------------------------------------------------

    /** Repository for the {@code TRANSACT} VSAM replacement; supplies the inherited keyed {@code findById}. */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param transactionRepository repository for the {@code TRANSACT} VSAM replacement; its inherited
     *                              {@link TransactionRepository#findById(Object) findById(String)} performs
     *                              the keyed read that reproduces {@code COTRN01C}'s
     *                              {@code EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID)}
     */
    public TransactionDetailService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Reads one transaction by its id and returns every detail field, reproducing {@code COTRN01C} end to
     * end (the {@code PROCESS-ENTER-KEY} edit followed by {@code READ-TRANSACT-FILE} and the
     * {@code SEND MAP('COTRN1A')} field population).
     *
     * <p>Processing order mirrors the COBOL dispatch exactly:</p>
     * <ol>
     *   <li><strong>Blank edit</strong> ({@code PROCESS-ENTER-KEY} {@code EVALUATE TRUE}, L146-156): a
     *       {@code null}/blank id is the verbatim empty-id error, raised <em>before</em> any database
     *       access. This is the only input validation the program performs.</li>
     *   <li><strong>Keyed read</strong> ({@code READ-TRANSACT-FILE}, L267-296): the id is normalized to the
     *       sixteen-character key form and looked up with {@code findById}. An empty result is the
     *       {@code DFHRESP(NOTFND)} not-found error; an infrastructure failure from {@code findById}
     *       propagates (the {@code WHEN OTHER} fatal path).</li>
     *   <li><strong>Field mapping</strong> ({@code DFHRESP(NORMAL)} branch, L177-190): every detail field is
     *       copied onto the response {@link TransactionDto} by {@link #toDto(Transaction)}.</li>
     * </ol>
     *
     * @param transactionId the transaction id to look up (COBOL {@code TRNIDINI} / the
     *                      {@code CDEMO-CT01-TRN-SELECTED} value forwarded from the list screen); a bare
     *                      numeric id or a full sixteen-character zero-padded key are both accepted
     * @return the populated detail {@link TransactionDto} for the requested transaction
     * @throws ValidationException     if {@code transactionId} is {@code null} or blank
     *                                 ({@link #MSG_TRAN_ID_EMPTY}, HTTP&nbsp;400)
     * @throws RecordNotFoundException if no transaction exists for the (normalized) id
     *                                 ({@link #MSG_TRAN_NOT_FOUND}, HTTP&nbsp;404 / FILE STATUS {@code '23'})
     */
    public TransactionDto getTransaction(String transactionId) {
        // PROCESS-ENTER-KEY blank edit (COTRN01C L147-150): WHEN TRNIDINI = SPACES OR LOW-VALUES.
        // null covers LOW-VALUES/absent; an all-blank value covers SPACES.
        if (transactionId == null || transactionId.trim().isEmpty()) {
            throw new ValidationException(MSG_TRAN_ID_EMPTY);
        }

        // MOVE TRNIDINI TO TRAN-ID (L172): normalize to the 16-char zero-padded key form before the read.
        String key = normalizeTransactionId(transactionId.trim());

        // COBOL substitution: EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID) (READ-TRANSACT-FILE,
        // L269-278) -> inherited JpaRepository.findById on the 16-char primary key.
        //
        // WHEN OTHER fatal path (L289-295, 'Unable to lookup Transaction...'): a Spring DataAccessException
        // raised here is intentionally NOT caught -- it propagates to the centralized @RestControllerAdvice
        // (HTTP 500), faithfully reproducing the COBOL unrecoverable-read branch (no catch-and-swallow).
        Optional<Transaction> result = transactionRepository.findById(key);

        // COBOL substitution: WHEN DFHRESP(NOTFND) (L283-285) -> RecordNotFoundException with the EXACT
        // COBOL literal (single-message constructor; the (entityType, key) constructor would derive a
        // different message and break message parity). The exception maps to HTTP 404 / FILE STATUS '23'.
        Transaction transaction =
                result.orElseThrow(() -> new RecordNotFoundException(MSG_TRAN_NOT_FOUND));

        // DFHRESP(NORMAL) branch (L177-190): paint every detail field onto the response DTO.
        return toDto(transaction);
    }

    /**
     * Reads one transaction using the id carried on an inbound request {@link TransactionDto}, for
     * controllers that bind a request body rather than a path variable.
     *
     * <p>The detail screen's lookup key is {@code TRNIDIN}, modeled on the DTO as
     * {@link TransactionDto#getTransactionIdFilter() transactionIdFilter}; some clients may instead populate
     * {@link TransactionDto#getTransactionId() transactionId}. This overload therefore prefers the documented
     * filter field and falls back to {@code transactionId}, then delegates to
     * {@link #getTransaction(String)} so the blank edit, the keyed read and the field mapping are performed
     * by a single authoritative code path.</p>
     *
     * @param request the inbound request payload carrying the lookup id; may be {@code null} (treated as a
     *                blank id, i.e. the empty-id validation error)
     * @return the populated detail {@link TransactionDto} for the requested transaction
     * @throws ValidationException     if no usable id is present on the request
     *                                 ({@link #MSG_TRAN_ID_EMPTY}, HTTP&nbsp;400)
     * @throws RecordNotFoundException if no transaction exists for the (normalized) id
     *                                 ({@link #MSG_TRAN_NOT_FOUND}, HTTP&nbsp;404 / FILE STATUS {@code '23'})
     */
    public TransactionDto getTransaction(TransactionDto request) {
        String lookupId = null;
        if (request != null) {
            // Prefer the documented detail lookup key (TRNIDIN -> transactionIdFilter); fall back to
            // transactionId when the filter field is absent/blank.
            lookupId = request.getTransactionIdFilter();
            if (lookupId == null || lookupId.trim().isEmpty()) {
                lookupId = request.getTransactionId();
            }
        }
        return getTransaction(lookupId);
    }

    /**
     * Maps a {@link Transaction} entity onto the detail {@link TransactionDto}, reproducing the
     * {@code DFHRESP(NORMAL)} field {@code MOVE}s of {@code COTRN01C} (L177-190) one for one.
     *
     * <p>The thirteen detail fields are copied in the same order the COBOL painted them onto the
     * {@code COTRN1A} map. Type conversions and width truncations (documented inline) reproduce the COBOL
     * numeric-to-display {@code MOVE}s and alphanumeric truncations exactly.</p>
     *
     * @param transaction the transaction record read by {@code findById} (never {@code null})
     * @return the populated detail response DTO
     */
    private TransactionDto toDto(Transaction transaction) {
        TransactionDto dto = new TransactionDto();

        // TRNIDI <- TRAN-ID (L178): the 16-char transaction key, carried verbatim.
        dto.setTransactionId(transaction.getTranId());

        // CARDNUMI <- TRAN-CARD-NUM (L179): the 16-char card number, carried verbatim.
        dto.setCardNumber(transaction.getTranCardNum());

        // TTYPCDI <- TRAN-TYPE-CD (L180): the 2-char type code, carried verbatim.
        dto.setTypeCode(transaction.getTranTypeCd());

        // COBOL substitution: TCATCDI <- TRAN-CAT-CD (L181). The entity stores TRAN-CAT-CD as an Integer,
        // but MOVE of PIC 9(04) into the X(4) screen field renders four digits with leading zeros; the
        // String DTO field therefore receives a zero-padded 4-character string (null-guarded).
        dto.setCategoryCode(formatNumericCode(transaction.getTranCatCd(), CATEGORY_CODE_LENGTH));

        // TRNSRCI <- TRAN-SOURCE (L182): the 10-char source token, carried verbatim.
        dto.setSource(transaction.getTranSource());

        // COBOL substitution: TDESCI <- TRAN-DESC (L184). A COBOL alphanumeric MOVE of TRAN-DESC X(100)
        // into the TDESCI X(60) detail field truncates to the leftmost 60 characters.
        dto.setDescription(truncate(transaction.getTranDesc(), DESC_DISPLAY_LENGTH));

        // COBOL substitution: TRNAMTI <- WS-TRAN-AMT (L177/L183). The COBOL edited form (+99999999.99) is
        // purely presentational; the DTO carries the raw signed value as BigDecimal at scale 2
        // (AAP §0.7.3 -- never float/double; compare with compareTo, never equals).
        dto.setAmount(scaleAmount(transaction.getTranAmt()));

        // COBOL substitution: TORIGDTI <- TRAN-ORIG-TS (L185). The entity stores TRAN-ORIG-TS as a
        // LocalDateTime; the detail field TORIGDTI is PIC X(10) -- the YYYY-MM-DD date prefix of the
        // 26-char timestamp -- so only the date portion is carried (LocalDate, null-guarded).
        dto.setOriginationDate(toDatePortion(transaction.getTranOrigTs()));

        // COBOL substitution: TPROCDTI <- TRAN-PROC-TS (L186). Same X(10) date-prefix rule as above.
        dto.setProcessingDate(toDatePortion(transaction.getTranProcTs()));

        // COBOL substitution: MIDI <- TRAN-MERCHANT-ID (L187). The entity stores TRAN-MERCHANT-ID as a
        // Long, but MOVE of PIC 9(09) into the X(9) screen field renders nine digits with leading zeros;
        // the String DTO field receives a zero-padded 9-character string (null-guarded).
        dto.setMerchantId(formatNumericCode(transaction.getTranMerchantId(), MERCHANT_ID_LENGTH));

        // COBOL substitution: MNAMEI <- TRAN-MERCHANT-NAME (L188). MOVE of X(50) into MNAMEI X(30)
        // truncates to the leftmost 30 characters.
        dto.setMerchantName(truncate(transaction.getTranMerchantName(), MERCHANT_NAME_DISPLAY_LENGTH));

        // COBOL substitution: MCITYI <- TRAN-MERCHANT-CITY (L189). MOVE of X(50) into MCITYI X(25)
        // truncates to the leftmost 25 characters.
        dto.setMerchantCity(truncate(transaction.getTranMerchantCity(), MERCHANT_CITY_DISPLAY_LENGTH));

        // MZIPI <- TRAN-MERCHANT-ZIP (L190): the 10-char ZIP, carried verbatim (entity and screen widths match).
        dto.setMerchantZip(transaction.getTranMerchantZip());

        return dto;
    }

    /**
     * Normalizes a non-blank, already-trimmed transaction id to the sixteen-character key form before the
     * keyed read.
     *
     * <p>COBOL substitution: {@code COTRN01C} performs {@code MOVE TRNIDINI TO TRAN-ID} and then a keyed
     * {@code READ} (L172-173). The stored {@code TRANSACT} keys are sixteen-character zero-padded numerics
     * ({@code TRAN-ID PIC X(16)}; see {@code TransactionRepository} and the {@code V1} schema), and the
     * sibling {@code TransactionListService} positions its browse by left zero-padding numeric input to that
     * width. To keep the detail lookup consistent with the list endpoint's id form (and with the system's
     * canonical sixteen-character key contract), an all-digit id no longer than the key width is left
     * zero-padded to sixteen characters &mdash; so a client may pass either a bare numeric id or the full
     * zero-padded key and resolve the same record. A non-numeric id (or a numeric id wider than the key) is
     * used as received; it simply will not match any stored key and yields the not-found error, preserving
     * the faithful outcome. The 3270 space-padding of a short directly-typed id is a retired
     * presentation-layer artifact, not a business rule, so it is not reproduced.</p>
     *
     * @param trimmedId the already-trimmed, non-empty inbound id
     * @return the id normalized to the sixteen-character key form (or the id unchanged when it is not a
     *         key-width numeric)
     */
    private static String normalizeTransactionId(String trimmedId) {
        if (isAllDigits(trimmedId) && trimmedId.length() <= TRAN_ID_KEY_LENGTH) {
            // IF TRNIDINI IS NUMERIC -> left zero-pad to the 16-char key width. A value of <= 16 digits
            // always fits within long range, so Long.parseLong cannot overflow here.
            return zeroPad(Long.parseLong(trimmedId), TRAN_ID_KEY_LENGTH);
        }
        return trimmedId;
    }

    /**
     * Renders a numeric entity code (the {@link Integer} {@code TRAN-CAT-CD} or the {@link Long}
     * {@code TRAN-MERCHANT-ID}) as a fixed-width, zero-padded {@link String}, reproducing the COBOL
     * numeric-to-alphanumeric {@code MOVE} that emits leading zeros.
     *
     * @param value the numeric code (may be {@code null})
     * @param width the fixed display width to zero-pad to ({@code PIC 9(width)})
     * @return the zero-padded string of the given width, or {@code null} when {@code value} is {@code null}
     */
    private static String formatNumericCode(Number value, int width) {
        if (value == null) {
            return null;
        }
        return zeroPad(value.longValue(), width);
    }

    /**
     * Left zero-pads a non-negative value to a fixed width, the shared primitive behind the
     * {@code MOVE PIC 9(n) TO PIC X(n)} rendering and the sixteen-character key normalization.
     *
     * @param value the value to render
     * @param width the fixed width to zero-pad to
     * @return the zero-padded decimal string
     */
    private static String zeroPad(long value, int width) {
        return String.format("%0" + width + "d", value);
    }

    /**
     * Truncates a textual value to a fixed display width, reproducing a COBOL alphanumeric {@code MOVE}
     * into a narrower {@code PIC X(n)} screen field (leftmost {@code width} characters).
     *
     * <p>A {@code null} value maps to an empty string &mdash; the COBOL fixed-width field would render
     * spaces &mdash; matching the sibling {@code TransactionListService} convention.</p>
     *
     * @param value the source text (may be {@code null})
     * @param width the maximum display width
     * @return the value truncated to at most {@code width} characters, or {@code ""} when {@code value} is
     *         {@code null}
     */
    private static String truncate(String value, int width) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(width, value.length()));
    }

    /**
     * Normalizes a monetary amount to scale&nbsp;2, enforcing the decimal-fidelity rule (AAP &sect;0.7.3).
     *
     * <p>Monetary values are {@link BigDecimal} of scale&nbsp;2 &mdash; never {@code float}/{@code double}
     * &mdash; and comparisons (elsewhere) use {@link BigDecimal#compareTo(BigDecimal)}, never the
     * scale-sensitive {@link BigDecimal#equals(Object)}. The persisted {@code TRAN-AMT} is already
     * {@code NUMERIC(11,2)}; this defensively normalizes the scale to two with
     * {@link RoundingMode#HALF_EVEN} (banker's rounding) without altering the value.</p>
     *
     * @param amount the raw transaction amount (may be {@code null})
     * @return the amount at scale&nbsp;2, or {@code null} when the input is {@code null}
     */
    private static BigDecimal scaleAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Extracts the date portion of a timestamp, reproducing the detail screen's {@code PIC X(10)} date
     * fields ({@code TORIGDTI}/{@code TPROCDTI}).
     *
     * <p>COBOL substitution: the entity stores {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} as
     * {@link LocalDateTime}, but the detail fields are {@code PIC X(10)} &mdash; the {@code YYYY-MM-DD} date
     * prefix of the 26-character timestamp. {@link LocalDateTime#toLocalDate()} yields exactly that date,
     * which the DTO renders as {@code yyyy-MM-dd} via its {@code @JsonFormat}. Unlike the list row's string
     * date (which used the {@code '00/00/00'} sentinel), the detail field is a typed {@link LocalDate} with
     * no sentinel value, so a {@code null} timestamp maps to a {@code null} date.</p>
     *
     * @param timestamp the entity timestamp (may be {@code null})
     * @return the date portion, or {@code null} when {@code timestamp} is {@code null}
     */
    private static LocalDate toDatePortion(LocalDateTime timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toLocalDate();
    }

    /**
     * Determines whether a value is all digits, mirroring the COBOL {@code IS NUMERIC} test on an unsigned
     * display field. Only digit composition is checked (no length constraint).
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is non-{@code null} and every character is a digit
     */
    private static boolean isAllDigits(String value) {
        return value != null && DIGITS.matcher(value).matches();
    }
}

