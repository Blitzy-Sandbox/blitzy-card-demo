package com.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.TransactionAddRequest;
import com.carddemo.dto.TransactionAddResponse;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

/**
 * Application service that adds a new financial transaction — the Java&nbsp;25 /
 * Spring&nbsp;Boot translation of the online CICS program {@code COTRN02C}
 * (transaction {@code CT02}, "Transaction Add"; {@code app/cbl/COTRN02C.cbl},
 * frozen COBOL reference commit SHA {@code 27d6c6f}).
 *
 * <h2>What it does</h2>
 * <p>{@link #addTransaction(TransactionAddRequest)} reproduces the
 * {@code PROCESS-ENTER-KEY} &rarr; {@code ADD-TRANSACTION} flow of the legacy
 * program: it requires an explicit confirmation, validates the key and data
 * fields in the exact order the COBOL {@code EVALUATE} statements imposed,
 * resolves the card number, auto-generates the next 16-digit transaction id,
 * builds the {@link Transaction} record and persists it. On success it returns a
 * confirmation carrying the newly assigned id, mirroring the
 * {@code WRITE-TRANSACT-FILE} success message.</p>
 *
 * <h2>COBOL&nbsp;&rarr;&nbsp;Java behavioural mapping</h2>
 * <ul>
 *   <li><b>{@code PROCESS-ENTER-KEY} / {@code EVALUATE CONFIRMI}</b> &mdash; the
 *       {@code Y}/{@code N} confirm gate. In the stateless REST model the
 *       pseudo-conversational {@code COMMAREA} confirm state becomes the request
 *       {@link TransactionAddRequest#confirm() confirm} flag: an absent or
 *       {@code false} flag returns the legacy prompt
 *       <em>"Confirm to add this transaction..."</em> without writing; a
 *       {@code true} flag proceeds to commit.</li>
 *   <li><b>{@code VALIDATE-INPUT-KEY-FIELDS}</b> ({@code COTRN02C} L193&ndash;230)
 *       &mdash; the {@code EVALUATE TRUE} that requires an account <em>or</em> a
 *       card number and rejects a non-numeric value, preserving the branch order
 *       (account first, then card, then the "must be entered" default).</li>
 *   <li><b>{@code VALIDATE-INPUT-DATA-FIELDS}</b> ({@code COTRN02C} L235&ndash;437)
 *       &mdash; the mandatory-presence edits for every data field (type code,
 *       category code, source, description, amount, original date, processed date,
 *       merchant id, merchant name, merchant city and merchant zip), the numeric
 *       edits (type / category / merchant id), the {@code -99999999.99} amount mask,
 *       the {@code YYYY-MM-DD} original/processed date-format edits and the
 *       {@code CSUTLDTC} calendar-validity edits &mdash; each surfacing the verbatim
 *       legacy operator message and accumulated in {@code COTRN02C} field order.</li>
 *   <li><b>{@code ADD-TRANSACTION}</b> ({@code COTRN02C} L442&ndash;466) &mdash;
 *       the 16-digit id allocation (delegated to {@link CrossReferenceService})
 *       and the field-by-field population of {@code TRAN-RECORD}.</li>
 *   <li><b>{@code WRITE-TRANSACT-FILE}</b> ({@code COTRN02C} L711&ndash;749)
 *       &mdash; the persist and the success message
 *       <em>"Transaction added successfully. "</em> (the trailing space is part
 *       of the legacy literal and is preserved for interface-contract parity).</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>Collaborators are supplied by <em>constructor injection</em>, replacing
 *       the static COBOL {@code CALL} / VSAM linkage with Spring dependency
 *       injection. A single constructor means no {@code @Autowired} is required,
 *       and both fields are {@code final}.</li>
 *   <li>The monetary amount is a {@link java.math.BigDecimal} normalised to scale
 *       {@value #AMOUNT_SCALE} with {@link java.math.RoundingMode#HALF_UP},
 *       matching the COBOL {@code TRAN-AMT PIC S9(09)V99}; no {@code float} or
 *       {@code double} is used for money.</li>
 *   <li>The original and processed dates are supplied by the caller as
 *       {@code YYYY-MM-DD} text (the legacy {@code TORIGDT} / {@code TPROCDT}
 *       screen fields, {@code PIC X(10)}), validated for presence, format and
 *       calendar validity, and stored verbatim in the {@code TRAN-ORIG-TS} /
 *       {@code TRAN-PROC-TS} record fields ({@code PIC X(26)}); they are never
 *       defaulted to the current time.</li>
 *   <li>The transaction id is <em>always</em> server-generated; a client-supplied
 *       id is never accepted.</li>
 *   <li>The whole operation runs in a single declarative transaction
 *       ({@code @Transactional(rollbackFor = Exception.class)}), reproducing the
 *       CICS unit-of-work / {@code SYNCPOINT} boundary so any failure rolls the
 *       write back.</li>
 *   <li>Structured logs are correlated through the MDC {@code correlationId}
 *       injected by the request/batch correlation filter; the card number (PAN)
 *       is never written to a log line.</li>
 * </ul>
 *
 * <p>The service holds no mutable state beyond its injected, immutable
 * collaborators, so a single shared instance is safe for concurrent request
 * threads.</p>
 */
@Service
public class TransactionAddService {

    /** SLF4J logger; log lines are correlated through the MDC {@code correlationId}. */
    private static final Logger log = LoggerFactory.getLogger(TransactionAddService.class);

    /**
     * Fixed decimal scale for the monetary amount, matching the COBOL
     * {@code TRAN-AMT PIC S9(09)V99} picture (two fractional digits).
     */
    private static final int AMOUNT_SCALE = 2;

    /**
     * Largest magnitude accepted for the transaction amount, matching the COBOL
     * input edit mask {@code +99999999.99} ({@code COTRN02C} L340&ndash;343):
     * at most eight integer digits and two fractional digits. Amounts whose
     * absolute value exceeds this bound fail the format check.
     */
    private static final BigDecimal AMOUNT_LIMIT = new BigDecimal("99999999.99");

    /**
     * Micrometer (dot-delimited) name for the transaction-amount distribution summary. Prometheus
     * renders it as the {@code carddemo_transaction_amount_count} / {@code _sum} / {@code _max}
     * series that the operations dashboard graphs (average ticket size = {@code _sum} / {@code _count}).
     */
    private static final String METRIC_TRANSACTION_AMOUNT = "carddemo.transaction.amount";

    /**
     * Verbatim {@code COTRN02C} prompt emitted when the transaction has not been
     * confirmed ({@code EVALUATE CONFIRMI}, L178). Returned unchanged so the
     * external contract stays byte-identical to the legacy system.
     */
    private static final String CONFIRM_PROMPT_MESSAGE = "Confirm to add this transaction...";

    /**
     * Verbatim {@code COTRN02C} success message ({@code WRITE-TRANSACT-FILE},
     * L728). The trailing space is part of the legacy literal and is intentionally
     * preserved.
     */
    private static final String SUCCESS_MESSAGE = "Transaction added successfully. ";

    /**
     * Compiled {@code YYYY-MM-DD} date-format pattern, reproducing the positional
     * edit {@code COTRN02C} applies to the {@code TORIGDT} / {@code TPROCDT} screen
     * fields ({@code (1:4)} numeric, {@code (5:1) = '-'}, {@code (6:2)} numeric,
     * {@code (8:1) = '-'}, {@code (9:2)} numeric). A value must match this pattern
     * in full &mdash; four year digits, a hyphen, two month digits, a hyphen and two
     * day digits &mdash; before its calendar validity is checked through
     * {@link DateValidationService}.
     */
    private static final Pattern DATE_FORMAT_YYYY_MM_DD = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * Transaction repository backing the {@code TRANSACT} KSDS; its inherited
     * {@code save(..)} performs the {@code WRITE-TRANSACT-FILE} insert.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Shared cross-reference / id-generation service. Supplies the next
     * transaction id ({@code ADD-TRANSACTION} browse logic) and resolves the
     * primary card number for an account when the caller supplies only an
     * account id.
     */
    private final CrossReferenceService crossReferenceService;

    /**
     * Calendar-date validator, the Java replacement for the {@code CSUTLDTC}
     * ({@code CEEDAYS}) date-validity call {@code COTRN02C} makes for the original
     * and processed dates. Supplied so the service &mdash; not the DTO layer
     * &mdash; owns the {@code YYYY-MM-DD} validity edit.
     */
    private final DateValidationService dateValidationService;

    /**
     * Records the magnitude of every successfully added transaction &mdash; Prometheus series
     * {@code carddemo_transaction_amount_count} / {@code _sum} / {@code _max}. Pre-registered at
     * construction so the series exist (at&nbsp;0) from startup, mirroring the pre-registered
     * POSTTRAN counters in {@code MetricsConfig}; the dashboard therefore never shows a spurious
     * "No data" before the first {@code CT02} add.
     */
    private final DistributionSummary transactionAmountSummary;

    /**
     * Creates the service with its collaborating beans.
     *
     * <p>Spring injects all dependencies through this single constructor, so no
     * {@code @Autowired} annotation is needed; every field is {@code final},
     * making the service effectively immutable and thread-safe.</p>
     *
     * @param transactionRepository the transaction repository; must not be {@code null}
     * @param crossReferenceService the cross-reference / id-generation service; must not be {@code null}
     * @param dateValidationService the calendar-date validator ({@code CSUTLDTC}
     *                              replacement); must not be {@code null}
     * @param meterRegistry         the auto-configured Micrometer registry used to register the
     *                              transaction-amount distribution summary; must not be {@code null}
     */
    public TransactionAddService(TransactionRepository transactionRepository,
            CrossReferenceService crossReferenceService,
            DateValidationService dateValidationService,
            MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.crossReferenceService = crossReferenceService;
        this.dateValidationService = dateValidationService;
        // Pre-register the summary so carddemo_transaction_amount_{count,sum,max} exist (at 0) from
        // startup, mirroring the pre-registered POSTTRAN counters in MetricsConfig. No Micrometer
        // base unit is set so the scraped series names are exactly carddemo_transaction_amount_count
        // / _sum / _max (a base unit would be appended as a suffix — see MetricsConfig for rationale).
        this.transactionAmountSummary = DistributionSummary.builder(METRIC_TRANSACTION_AMOUNT)
                .description("Magnitude of transactions added online via CT02 (COTRN02C "
                        + "WRITE-TRANSACT-FILE); sum/count is the average ticket size")
                .register(meterRegistry);
    }

    /**
     * Adds a new transaction, reproducing the {@code COTRN02C}
     * {@code PROCESS-ENTER-KEY} &rarr; {@code ADD-TRANSACTION} &rarr;
     * {@code WRITE-TRANSACT-FILE} sequence.
     *
     * <p>Processing order (preserving the legacy control flow):</p>
     * <ol>
     *   <li><b>Confirm gate.</b> When {@link TransactionAddRequest#confirm()} is
     *       {@code null} or {@code false} the method returns the legacy
     *       <em>"Confirm to add this transaction..."</em> prompt <strong>without
     *       writing</strong> — the stateless equivalent of the COBOL
     *       {@code EVALUATE CONFIRMI} "N / spaces" branch.</li>
     *   <li><b>Validation.</b> The key and data fields are validated in the exact
     *       {@code COTRN02C} order; all failures are accumulated (preserving
     *       order) and, if any exist, raised together as a
     *       {@link ValidationException} (HTTP&nbsp;400) carrying a
     *       field&rarr;message map.</li>
     *   <li><b>Card resolution.</b> The supplied card number is used when present;
     *       otherwise the account's primary card is resolved through
     *       {@link CrossReferenceService#resolvePrimaryCardNumber(Long)}, which
     *       raises a not-found error when the account has no cross-reference.</li>
     *   <li><b>Id allocation.</b> The next 16-digit id is generated by
     *       {@link CrossReferenceService#generateNextTransactionId()}; a
     *       client-supplied id is never accepted.</li>
     *   <li><b>Build &amp; persist.</b> The {@link Transaction} record is populated
     *       (amount normalised to scale {@value #AMOUNT_SCALE}; the validated
     *       original / processed dates stored verbatim) and saved.</li>
     *   <li><b>Confirmation.</b> A {@link TransactionAddResponse} is returned with
     *       the new id, the target account, the posted amount and the verbatim
     *       success message.</li>
     * </ol>
     *
     * <p>The method is declaratively transactional and rolls back on any
     * exception, reproducing the CICS unit-of-work boundary.</p>
     *
     * @param request the transaction-add request; must not be {@code null}
     * @return a confirmation response — the "confirm" prompt when the request is
     *         not yet confirmed, otherwise the success confirmation carrying the
     *         newly assigned transaction id
     * @throws ValidationException if any field fails the {@code COTRN02C} edits
     *         (HTTP&nbsp;400)
     * @throws com.carddemo.exception.ResourceNotFoundException if only an account
     *         id is supplied and it has no card cross-reference (HTTP&nbsp;404)
     */
    @Transactional(rollbackFor = Exception.class)
    @Observed(name = "carddemo.service", contextualName = "transaction-add")
    public TransactionAddResponse addTransaction(TransactionAddRequest request) {
        // Input-contract guard: a null request body is a broken contract, surfaced
        // as a typed HTTP-400 validation failure rather than an unhandled
        // NullPointerException / HTTP 500 on the request.confirm() dereference below.
        if (request == null) {
            throw new ValidationException("Transaction add validation failed");
        }

        // Confirm gate first: an unconfirmed request is a no-op preview that
        // echoes the legacy "Confirm to add this transaction..." prompt.
        if (request.confirm() == null || !request.confirm()) {
            log.info("Transaction add (CT02) not confirmed; returning confirmation prompt without writing");
            return new TransactionAddResponse(
                    null, request.accountId(), request.amount(), CONFIRM_PROMPT_MESSAGE);
        }

        // Validate key and data fields in COTRN02C order; raise all failures at once.
        Map<String, String> errors = validate(request);
        if (!errors.isEmpty()) {
            log.warn("Transaction add (CT02) rejected: {} field validation error(s)", errors.size());
            throw new ValidationException("Transaction add validation failed", errors);
        }

        // Resolve the card number (supplied value, or the account's primary card).
        String cardNum = resolveCardNumber(request);

        // Allocate the next 16-digit transaction id (never client-supplied).
        String tranId = crossReferenceService.generateNextTransactionId();

        // Build and persist the transaction record.
        Transaction txn = buildTransaction(request, tranId, cardNum);
        transactionRepository.save(txn);

        // Record the posted amount's magnitude into carddemo_transaction_amount_{count,sum,max}.
        // The absolute value is used because a Micrometer DistributionSummary silently ignores
        // negative samples, and the COTRN02C amount edit mask (-99999999.99) admits negatives
        // (credits/refunds); recording the magnitude keeps _count equal to the number of successful
        // adds and makes _sum/_count a meaningful average ticket size.
        transactionAmountSummary.record(txn.getTranAmt().abs().doubleValue());

        // PAN is deliberately excluded from this log line.
        log.info("Transaction added successfully (CT02): tranId={}, accountId={}",
                tranId, request.accountId());

        return new TransactionAddResponse(
                tranId, request.accountId(), txn.getTranAmt(), SUCCESS_MESSAGE);
    }

    /**
     * Validates the request fields in the exact {@code COTRN02C} branch order,
     * accumulating every failure into an insertion-ordered field&rarr;message map.
     * An empty result denotes a fully valid request.
     *
     * <p>The legacy program {@code COTRN02C} reports one edit at a time (each
     * failing {@code WHEN} performs {@code SEND-TRNADD-SCREEN}, which issues a CICS
     * {@code RETURN}). The stateless REST translation instead accumulates every
     * field failure so the caller receives them together in a single
     * HTTP&nbsp;400, preserving the legacy <em>message literals</em> and
     * <em>field order</em> while giving one edit per field (the first that applies,
     * matching the legacy precedence for that field).</p>
     *
     * <p>Order and messages mirror {@code VALIDATE-INPUT-KEY-FIELDS}
     * ({@code COTRN02C} L193&ndash;230) followed by {@code VALIDATE-INPUT-DATA-FIELDS}
     * ({@code COTRN02C} L235&ndash;437):</p>
     * <ol>
     *   <li><b>account/card key</b> &mdash; an account id, else a card number, else
     *       the {@code "Account or Card Number must be entered..."} default; a
     *       present value that is non-numeric is rejected with its
     *       {@code "... must be Numeric..."} message;</li>
     *   <li><b>type code</b> &mdash; {@code "Type CD can NOT be empty..."} when
     *       absent, else {@code "Type CD must be Numeric..."};</li>
     *   <li><b>category code</b> &mdash; {@code "Category CD can NOT be empty..."}
     *       when absent, else {@code "Category CD must be Numeric..."};</li>
     *   <li><b>source</b> &mdash; {@code "Source can NOT be empty..."};</li>
     *   <li><b>description</b> &mdash; {@code "Description can NOT be empty..."};</li>
     *   <li><b>amount</b> &mdash; {@code "Amount can NOT be empty..."} when absent,
     *       else {@code "Amount should be in format -99999999.99"} when it does not
     *       fit the eight-integer / two-fraction edit mask;</li>
     *   <li><b>original date</b> &mdash; {@code "Orig Date can NOT be empty..."} when
     *       absent, else {@code "Orig Date should be in format YYYY-MM-DD"} when it
     *       fails the positional format edit, else
     *       {@code "Orig Date - Not a valid date..."} when it is not a real calendar
     *       date;</li>
     *   <li><b>processed date</b> &mdash; the {@code "Proc Date ..."} equivalents of
     *       the original-date edits;</li>
     *   <li><b>merchant id</b> &mdash; {@code "Merchant ID can NOT be empty..."} when
     *       absent, else {@code "Merchant ID must be Numeric..."};</li>
     *   <li><b>merchant name</b> &mdash; {@code "Merchant Name can NOT be empty..."};</li>
     *   <li><b>merchant city</b> &mdash; {@code "Merchant City can NOT be empty..."};</li>
     *   <li><b>merchant zip</b> &mdash; {@code "Merchant Zip can NOT be empty..."}.</li>
     * </ol>
     *
     * @param request the request to validate; must not be {@code null}
     * @return an insertion-ordered map of field&rarr;message failures; empty when
     *         the request is valid
     */
    private Map<String, String> validate(TransactionAddRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        // (1) VALIDATE-INPUT-KEY-FIELDS: account first, then card, then default.
        if (isPresent(request.accountId())) {
            if (!isNumeric(request.accountId())) {
                errors.put("accountId", "Account ID must be Numeric...");
            }
        } else if (isPresent(request.cardNumber())) {
            if (!isNumeric(request.cardNumber())) {
                errors.put("cardNumber", "Card Number must be Numeric...");
            }
        } else {
            errors.put("account", "Account or Card Number must be entered...");
        }

        // (2) Type code: mandatory, then numeric.
        if (!isPresent(request.typeCode())) {
            errors.put("typeCode", "Type CD can NOT be empty...");
        } else if (!isNumeric(request.typeCode())) {
            errors.put("typeCode", "Type CD must be Numeric...");
        }

        // (3) Category code: mandatory, then numeric.
        if (!isPresent(request.categoryCode())) {
            errors.put("categoryCode", "Category CD can NOT be empty...");
        } else if (!isNumeric(request.categoryCode())) {
            errors.put("categoryCode", "Category CD must be Numeric...");
        }

        // (4) Source: mandatory.
        if (!isPresent(request.source())) {
            errors.put("source", "Source can NOT be empty...");
        }

        // (5) Description: mandatory.
        if (!isPresent(request.description())) {
            errors.put("description", "Description can NOT be empty...");
        }

        // (6) Amount: mandatory, then must fit the -99999999.99 edit mask.
        if (request.amount() == null) {
            errors.put("amount", "Amount can NOT be empty...");
        } else if (!isValidAmountFormat(request.amount())) {
            errors.put("amount", "Amount should be in format -99999999.99");
        }

        // (7) Original date: mandatory, then YYYY-MM-DD format, then calendar valid.
        validateDateField(errors, "originalTimestamp", request.originalTimestamp(),
                "Orig Date can NOT be empty...",
                "Orig Date should be in format YYYY-MM-DD",
                "Orig Date - Not a valid date...");

        // (8) Processed date: mandatory, then YYYY-MM-DD format, then calendar valid.
        validateDateField(errors, "processedTimestamp", request.processedTimestamp(),
                "Proc Date can NOT be empty...",
                "Proc Date should be in format YYYY-MM-DD",
                "Proc Date - Not a valid date...");

        // (9) Merchant id: mandatory, then numeric.
        if (!isPresent(request.merchantId())) {
            errors.put("merchantId", "Merchant ID can NOT be empty...");
        } else if (!isNumeric(request.merchantId())) {
            errors.put("merchantId", "Merchant ID must be Numeric...");
        }

        // (10) Merchant name: mandatory.
        if (!isPresent(request.merchantName())) {
            errors.put("merchantName", "Merchant Name can NOT be empty...");
        }

        // (11) Merchant city: mandatory.
        if (!isPresent(request.merchantCity())) {
            errors.put("merchantCity", "Merchant City can NOT be empty...");
        }

        // (12) Merchant zip: mandatory.
        if (!isPresent(request.merchantZip())) {
            errors.put("merchantZip", "Merchant Zip can NOT be empty...");
        }

        return errors;
    }

    /**
     * Applies the {@code COTRN02C} original/processed date edits to a single date
     * field, in the legacy precedence order: mandatory presence, then the
     * {@code YYYY-MM-DD} positional format edit, then the {@code CSUTLDTC}
     * calendar-validity edit. At most one message is recorded for the field (the
     * first that applies), matching the legacy per-field behaviour.
     *
     * <p>The {@code CSUTLDTC} validity call is reproduced by stripping the two
     * hyphens from the {@code YYYY-MM-DD} value to obtain the canonical
     * {@code CCYYMMDD} string {@link DateValidationService} expects, then invoking
     * {@link DateValidationService#isValidDateCcyyMmDd(String)}.</p>
     *
     * @param errors      the accumulating field&rarr;message map
     * @param field       the field key under which any failure is recorded
     * @param value       the raw date text ({@code YYYY-MM-DD}); may be {@code null}
     * @param emptyMsg    the verbatim "can NOT be empty" message
     * @param formatMsg   the verbatim "should be in format YYYY-MM-DD" message
     * @param invalidMsg  the verbatim "Not a valid date" message
     */
    private void validateDateField(Map<String, String> errors, String field, String value,
            String emptyMsg, String formatMsg, String invalidMsg) {
        if (!isPresent(value)) {
            errors.put(field, emptyMsg);
            return;
        }
        String trimmed = value.strip();
        if (!DATE_FORMAT_YYYY_MM_DD.matcher(trimmed).matches()) {
            errors.put(field, formatMsg);
            return;
        }
        // CSUTLDTC validity: DateValidationService expects CCYYMMDD (no hyphens).
        String ccyymmdd = trimmed.replace("-", "");
        if (!dateValidationService.isValidDateCcyyMmDd(ccyymmdd)) {
            errors.put(field, invalidMsg);
        }
    }

    /**
     * Resolves the card number for the transaction. When the request carries a
     * card number it is used directly; otherwise the account's primary card is
     * looked up through the cross-reference — the Java equivalent of the
     * {@code READ-CXACAIX-FILE} account&rarr;card navigation in {@code COTRN02C}.
     *
     * <p>This is only reached after validation has confirmed that, when the card
     * number is absent, the account id is present and numeric, so the parse is
     * safe.</p>
     *
     * @param request the validated request; must not be {@code null}
     * @return the 16-character card number to store on the transaction
     * @throws com.carddemo.exception.ResourceNotFoundException if the account has
     *         no card cross-reference
     */
    private String resolveCardNumber(TransactionAddRequest request) {
        if (isPresent(request.cardNumber())) {
            return request.cardNumber();
        }
        return crossReferenceService.resolvePrimaryCardNumber(Long.valueOf(request.accountId().strip()));
    }

    /**
     * Populates a new {@link Transaction} from the validated request, reproducing
     * the {@code MOVE} statements of {@code COTRN02C} paragraph
     * {@code ADD-TRANSACTION}.
     *
     * <p>The amount is normalised to scale {@value #AMOUNT_SCALE} with
     * {@link java.math.RoundingMode#HALF_UP}; the numeric category and merchant
     * codes are converted from their validated string forms; and the validated
     * original / processed dates ({@code YYYY-MM-DD}) are stored verbatim &mdash;
     * they are mandatory and are never defaulted to the current time.</p>
     *
     * @param request the validated request; must not be {@code null}
     * @param tranId  the server-generated 16-digit transaction id
     * @param cardNum the resolved card number
     * @return a fully populated, not-yet-persisted {@link Transaction}
     */
    private Transaction buildTransaction(TransactionAddRequest request, String tranId, String cardNum) {
        Transaction txn = new Transaction();
        txn.setTranId(tranId);
        txn.setTranTypeCd(request.typeCode());
        txn.setTranCatCd(Integer.valueOf(request.categoryCode().strip()));
        txn.setTranSource(request.source());
        txn.setTranDesc(request.description());
        txn.setTranAmt(request.amount().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
        txn.setTranCardNum(cardNum);
        txn.setTranMerchantId(Long.valueOf(request.merchantId().strip()));
        txn.setTranMerchantName(request.merchantName());
        txn.setTranMerchantCity(request.merchantCity());
        txn.setTranMerchantZip(request.merchantZip());
        // Orig/proc dates are validated (present, YYYY-MM-DD, calendar-valid)
        // before this point, so they are stored verbatim and never defaulted.
        txn.setTranOrigTs(request.originalTimestamp());
        txn.setTranProcTs(request.processedTimestamp());
        return txn;
    }

    /**
     * Reports whether a string is present — non-{@code null} and not blank after
     * trimming — the Java equivalent of the COBOL {@code NOT = SPACES AND
     * LOW-VALUES} test.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} when the value contains at least one non-whitespace
     *         character
     */
    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Reports whether a string is numeric — the Java equivalent of the COBOL
     * {@code IS NUMERIC} class test for the unsigned key/code fields. Surrounding
     * whitespace from fixed-width padding is stripped first; the value must then
     * be non-empty and consist solely of the ASCII digits {@code 0}&ndash;{@code 9}.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} when the trimmed value is a non-empty run of digits
     */
    private static boolean isNumeric(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether an amount fits the COBOL {@code +99999999.99} edit mask
     * ({@code COTRN02C} L340&ndash;343): at most two fractional digits and an
     * absolute value no greater than {@link #AMOUNT_LIMIT}.
     *
     * <p>A scale wider than {@value #AMOUNT_SCALE} is detected by attempting an
     * exact rescale: {@link BigDecimal#setScale(int)} without a rounding mode
     * throws {@link ArithmeticException} when it would discard non-zero
     * fractional digits, which is exactly the "more than two decimals" condition
     * the mask forbids.</p>
     *
     * @param amount the amount to test; must not be {@code null}
     * @return {@code true} when the amount fits the edit mask
     */
    private static boolean isValidAmountFormat(BigDecimal amount) {
        try {
            // Exact rescale: throws when the amount has more than two decimals.
            amount.setScale(AMOUNT_SCALE);
        } catch (ArithmeticException ex) {
            return false;
        }
        return amount.abs().compareTo(AMOUNT_LIMIT) <= 0;
    }
}
