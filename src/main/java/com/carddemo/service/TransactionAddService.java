/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.service;

import com.carddemo.dto.TransactionDto;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction add service backing {@code POST /api/transactions} (CICS
 * transaction {@code CT02} / program {@code COTRN02C}) at source commit
 * {@code 27d6c6f}.
 *
 * <p>This service translates {@code app/cbl/COTRN02C.cbl} into an idiomatic,
 * layered Spring service. It validates the submitted fields in the legacy
 * order, generates the next sequential transaction identifier, and persists a
 * single {@link Transaction} ({@code TRAN-RECORD}, copybook {@code CVTRA05Y},
 * RECLN 350) as one atomic unit of work.</p>
 *
 * <p>The work is organized into the same clusters as the COBOL procedure
 * division, decomposed into focused private methods:</p>
 * <ul>
 *   <li><strong>Confirm</strong> &mdash; the {@code PROCESS-ENTER-KEY}
 *       confirmation gate ({@code EVALUATE CONFIRMI}).</li>
 *   <li><strong>Validate</strong> &mdash; the ordered
 *       {@code VALIDATE-INPUT-KEY-FIELDS} and
 *       {@code VALIDATE-INPUT-DATA-FIELDS} edits, delegating calendar checks to
 *       {@link DateValidationService}; field failures are collected into an
 *       insertion-ordered map and raised as a single
 *       {@link ValidationException}.</li>
 *   <li><strong>Generate</strong> &mdash; the {@code ADD-TRANSACTION}
 *       {@code STARTBR}/{@code READPREV} "find highest existing id" step,
 *       reproduced by {@link TransactionRepository#findTopByOrderByTranIdDesc()}
 *       plus one, formatted as a sixteen-character zero-padded identifier.</li>
 *   <li><strong>Persist</strong> &mdash; the {@code WRITE-TRANSACT-FILE} insert
 *       within one transaction (CICS implicit commit parity); a duplicate
 *       identifier maps to {@link DuplicateRecordException} and any other
 *       data-access failure maps to {@link FileAccessException}.</li>
 * </ul>
 *
 * <p>Monetary amounts are held as {@link BigDecimal} scaled to two fraction
 * digits using {@link RoundingMode#HALF_EVEN}. The component is stateless and
 * therefore thread-safe; all collaborators are supplied through constructor
 * injection.</p>
 */
@Service
public class TransactionAddService {

    /** Scale applied to the monetary amount, matching COBOL {@code PIC S9(09)V99}. */
    private static final int MONEY_SCALE = 2;

    /** Inclusive upper bound of the {@code -99999999.99} screen amount format (eight integer digits). */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    /** Format producing the sixteen-character zero-padded {@code TRAN-ID}. */
    private static final String ID_FORMAT = "%016d";

    /** Identifier value used before the first transaction exists ({@code ENDFILE} branch). */
    private static final long EMPTY_HIGHEST_ID = 0L;

    /** Exact length of a hyphenated {@code YYYY-MM-DD} date. */
    private static final int ISO_DATE_LENGTH = 10;

    private static final int ISO_YEAR_BEGIN = 0;
    private static final int ISO_YEAR_END = 4;
    private static final int ISO_FIRST_HYPHEN = 4;
    private static final int ISO_MONTH_BEGIN = 5;
    private static final int ISO_MONTH_END = 7;
    private static final int ISO_SECOND_HYPHEN = 7;
    private static final int ISO_DAY_BEGIN = 8;
    private static final int ISO_DAY_END = 10;

    private static final String CONFIRM_YES = "Y";
    private static final String CONFIRM_NO = "N";

    private static final String MSG_CONFIRM_TO_ADD = "Confirm to add this transaction...";
    private static final String MSG_INVALID_YN = "Invalid value. Valid values are (Y/N)...";
    private static final String MSG_ACCT_OR_CARD_REQUIRED = "Account or Card Number must be entered...";
    private static final String MSG_ACCT_ID_NUMERIC = "Account ID must be Numeric...";
    private static final String MSG_CARD_NUM_NUMERIC = "Card Number must be Numeric...";
    private static final String MSG_TYPE_EMPTY = "Type CD can NOT be empty...";
    private static final String MSG_CAT_EMPTY = "Category CD can NOT be empty...";
    private static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";
    private static final String MSG_DESC_EMPTY = "Description can NOT be empty...";
    private static final String MSG_AMOUNT_EMPTY = "Amount can NOT be empty...";
    private static final String MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty...";
    private static final String MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty...";
    private static final String MSG_MID_EMPTY = "Merchant ID can NOT be empty...";
    private static final String MSG_MNAME_EMPTY = "Merchant Name can NOT be empty...";
    private static final String MSG_MCITY_EMPTY = "Merchant City can NOT be empty...";
    private static final String MSG_MZIP_EMPTY = "Merchant Zip can NOT be empty...";
    private static final String MSG_TYPE_NUMERIC = "Type CD must be Numeric...";
    private static final String MSG_CAT_NUMERIC = "Category CD must be Numeric...";
    private static final String MSG_MID_NUMERIC = "Merchant ID must be Numeric...";
    private static final String MSG_AMOUNT_FORMAT = "Amount should be in format -99999999.99";
    private static final String MSG_ORIG_DATE_FORMAT = "Orig Date should be in format YYYY-MM-DD";
    private static final String MSG_PROC_DATE_FORMAT = "Proc Date should be in format YYYY-MM-DD";
    private static final String MSG_ORIG_DATE_INVALID = "Orig Date - Not a valid date...";
    private static final String MSG_PROC_DATE_INVALID = "Proc Date - Not a valid date...";
    private static final String MSG_ACCT_ID_NOT_FOUND = "Account ID NOT found...";
    private static final String MSG_DUP_TRAN_ID = "Tran ID already exist...";
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add Transaction...";

    private static final String KEY_ACCOUNT_ID = "accountId";
    private static final String KEY_CARD_NUMBER = "cardNumber";
    private static final String KEY_TYPE_CODE = "typeCode";
    private static final String KEY_CATEGORY_CODE = "categoryCode";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_AMOUNT = "amount";
    private static final String KEY_ORIG_DATE = "originDate";
    private static final String KEY_PROC_DATE = "processDate";
    private static final String KEY_MERCHANT_ID = "merchantId";
    private static final String KEY_MERCHANT_NAME = "merchantName";
    private static final String KEY_MERCHANT_CITY = "merchantCity";
    private static final String KEY_MERCHANT_ZIP = "merchantZip";

    private final TransactionRepository transactionRepository;
    private final DateValidationService dateValidationService;

    /**
     * Creates the service with its mandatory collaborators.
     *
     * @param transactionRepository repository for the {@link Transaction} fact table,
     *                              providing the highest-identifier finder and the insert
     * @param dateValidationService service validating the origination and processing dates
     */
    public TransactionAddService(TransactionRepository transactionRepository,
                                 DateValidationService dateValidationService) {
        this.transactionRepository = transactionRepository;
        this.dateValidationService = dateValidationService;
    }

    /**
     * Adds a new transaction as one atomic unit of work.
     *
     * <p>The flow mirrors {@code COTRN02C}: the confirmation flag is checked
     * first; the key and data fields are edited in the legacy order; the next
     * sequential identifier is generated from the current highest identifier;
     * and the assembled record is inserted within the same transaction so that
     * the read-highest-then-write sequence commits atomically.</p>
     *
     * @param request the submitted add payload
     * @return the created transaction rendered as a {@link TransactionDto.Detail}
     * @throws ValidationException     if the confirmation flag is missing or declined,
     *                                 the confirmation flag is not {@code Y}/{@code N},
     *                                 or any field edit fails
     * @throws RecordNotFoundException if no card number is available to link the transaction
     * @throws DuplicateRecordException if the generated identifier already exists
     * @throws FileAccessException     if the insert fails for any other data-access reason
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionDto.Detail addTransaction(TransactionDto.AddRequest request) {
        validateConfirmation(request.confirm());

        Map<String, String> fieldErrors = validateFields(request);
        if (!fieldErrors.isEmpty()) {
            throw new ValidationException(firstMessage(fieldErrors), fieldErrors);
        }

        String cardNumber = resolveCardNumber(request);

        String tranId = generateNextTransactionId();
        if (transactionRepository.existsById(tranId)) {
            throw new DuplicateRecordException(MSG_DUP_TRAN_ID);
        }

        TransactionTypeCode transactionType = TransactionTypeCode.fromCode(request.typeCode().trim());
        BigDecimal amount = request.amount().setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);

        Transaction transaction = buildTransaction(tranId, request, cardNumber, transactionType, amount);
        persist(transaction);

        return toDetail(tranId, request, cardNumber, transactionType, amount);
    }

    /**
     * Reproduces the {@code EVALUATE CONFIRMI} gate: {@code Y}/{@code y}
     * proceeds, a blank or {@code N}/{@code n} value re-prompts without writing,
     * and any other value is rejected.
     *
     * @param confirm the submitted confirmation flag
     * @throws ValidationException if the flag is blank, {@code N}/{@code n}, or not {@code Y}/{@code N}
     */
    private static void validateConfirmation(String confirm) {
        String value = trimToEmpty(confirm);
        if (value.equalsIgnoreCase(CONFIRM_YES)) {
            return;
        }
        if (value.isEmpty() || value.equalsIgnoreCase(CONFIRM_NO)) {
            throw new ValidationException(MSG_CONFIRM_TO_ADD);
        }
        throw new ValidationException(MSG_INVALID_YN);
    }

    /**
     * Runs the {@code VALIDATE-INPUT-KEY-FIELDS} and
     * {@code VALIDATE-INPUT-DATA-FIELDS} edits in their COBOL order and collects
     * per-field failures into an insertion-ordered map.
     *
     * @param request the submitted add payload
     * @return a map keyed by request field name; empty when every edit passes
     */
    private Map<String, String> validateFields(TransactionDto.AddRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        validateKeyFields(request, errors);
        validateDataFields(request, errors);
        return errors;
    }

    /**
     * Edits the account / card search key ({@code VALIDATE-INPUT-KEY-FIELDS}):
     * an account identifier (when supplied) or a card number must be numeric,
     * and at least one of the two must be present.
     *
     * @param request the submitted add payload
     * @param errors  the accumulating per-field error map
     */
    private static void validateKeyFields(TransactionDto.AddRequest request, Map<String, String> errors) {
        boolean hasAccount = !isBlank(request.accountId());
        boolean hasCard = !isBlank(request.cardNumber());
        if (hasAccount) {
            if (!isNumeric(request.accountId())) {
                errors.put(KEY_ACCOUNT_ID, MSG_ACCT_ID_NUMERIC);
            }
        } else if (hasCard) {
            if (!isNumeric(request.cardNumber())) {
                errors.put(KEY_CARD_NUMBER, MSG_CARD_NUM_NUMERIC);
            }
        } else {
            errors.put(KEY_ACCOUNT_ID, MSG_ACCT_OR_CARD_REQUIRED);
        }
    }

    /**
     * Edits the transaction data fields ({@code VALIDATE-INPUT-DATA-FIELDS}) in
     * the COBOL order: required-presence, then numeric, amount format, date
     * format, and date validity.
     *
     * @param request the submitted add payload
     * @param errors  the accumulating per-field error map
     */
    private void validateDataFields(TransactionDto.AddRequest request, Map<String, String> errors) {
        if (isBlank(request.typeCode())) {
            errors.put(KEY_TYPE_CODE, MSG_TYPE_EMPTY);
        } else if (!isNumeric(request.typeCode())) {
            errors.put(KEY_TYPE_CODE, MSG_TYPE_NUMERIC);
        }
        if (isBlank(request.categoryCode())) {
            errors.put(KEY_CATEGORY_CODE, MSG_CAT_EMPTY);
        } else if (!isNumeric(request.categoryCode())) {
            errors.put(KEY_CATEGORY_CODE, MSG_CAT_NUMERIC);
        }
        if (isBlank(request.source())) {
            errors.put(KEY_SOURCE, MSG_SOURCE_EMPTY);
        }
        if (isBlank(request.description())) {
            errors.put(KEY_DESCRIPTION, MSG_DESC_EMPTY);
        }
        if (request.amount() == null) {
            errors.put(KEY_AMOUNT, MSG_AMOUNT_EMPTY);
        } else if (request.amount().abs().compareTo(MAX_AMOUNT) > 0) {
            errors.put(KEY_AMOUNT, MSG_AMOUNT_FORMAT);
        }
        validateDateField(request.originDate(), KEY_ORIG_DATE, MSG_ORIG_DATE_EMPTY,
                MSG_ORIG_DATE_FORMAT, MSG_ORIG_DATE_INVALID, errors);
        validateDateField(request.processDate(), KEY_PROC_DATE, MSG_PROC_DATE_EMPTY,
                MSG_PROC_DATE_FORMAT, MSG_PROC_DATE_INVALID, errors);
        if (isBlank(request.merchantId())) {
            errors.put(KEY_MERCHANT_ID, MSG_MID_EMPTY);
        } else if (!isNumeric(request.merchantId())) {
            errors.put(KEY_MERCHANT_ID, MSG_MID_NUMERIC);
        }
        if (isBlank(request.merchantName())) {
            errors.put(KEY_MERCHANT_NAME, MSG_MNAME_EMPTY);
        }
        if (isBlank(request.merchantCity())) {
            errors.put(KEY_MERCHANT_CITY, MSG_MCITY_EMPTY);
        }
        if (isBlank(request.merchantZip())) {
            errors.put(KEY_MERCHANT_ZIP, MSG_MZIP_EMPTY);
        }
    }

    /**
     * Edits a single date field for required-presence, {@code YYYY-MM-DD}
     * format, and calendar validity, recording at most one failure.
     *
     * @param value          the submitted date value
     * @param key            the request field name used as the map key
     * @param emptyMessage   message when the value is absent
     * @param formatMessage  message when the value is not {@code YYYY-MM-DD}
     * @param invalidMessage message when the value is well formed but not a real date
     * @param errors         the accumulating per-field error map
     */
    private void validateDateField(String value, String key, String emptyMessage,
            String formatMessage, String invalidMessage, Map<String, String> errors) {
        if (isBlank(value)) {
            errors.put(key, emptyMessage);
        } else if (!isIsoDateFormat(value)) {
            errors.put(key, formatMessage);
        } else if (!dateValidationService.isValidDate(toCcyymmdd(value))) {
            errors.put(key, invalidMessage);
        }
    }

    /**
     * Resolves the card number that links the transaction to its card
     * ({@code TRAN-CARD-NUM}). The COBOL derives this value through the card /
     * account cross-reference; when no card number is available the lookup is
     * surfaced with the legacy not-found message.
     *
     * @param request the submitted add payload
     * @return the non-blank card number
     * @throws RecordNotFoundException if no card number is available
     */
    private static String resolveCardNumber(TransactionDto.AddRequest request) {
        String cardNumber = trimToEmpty(request.cardNumber());
        if (cardNumber.isEmpty()) {
            throw new RecordNotFoundException(MSG_ACCT_ID_NOT_FOUND);
        }
        return cardNumber;
    }

    /**
     * Generates the next sequential sixteen-character {@code TRAN-ID}.
     *
     * <p>Reproduces {@code ADD-TRANSACTION}: the current highest identifier is
     * read ({@code STARTBR}/{@code READPREV}); an empty file yields zero
     * ({@code ENDFILE}); the value is incremented by one and formatted with
     * leading zeros.</p>
     *
     * @return the sixteen-character zero-padded next identifier
     */
    private String generateNextTransactionId() {
        long highest = transactionRepository.findTopByOrderByTranIdDesc()
                .map(Transaction::getTranId)
                .map(TransactionAddService::parseTranId)
                .orElse(EMPTY_HIGHEST_ID);
        return String.format(ID_FORMAT, highest + 1L);
    }

    /**
     * Inserts the assembled transaction, translating data-access failures to the
     * legacy {@code WRITE-TRANSACT-FILE} outcomes.
     *
     * @param transaction the transaction to insert
     * @throws DuplicateRecordException if the identifier already exists ({@code DUPKEY}/{@code DUPREC})
     * @throws FileAccessException      if the insert fails for any other reason
     */
    private void persist(Transaction transaction) {
        try {
            transactionRepository.save(transaction);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateRecordException(MSG_DUP_TRAN_ID, ex);
        } catch (DataAccessException ex) {
            throw new FileAccessException(MSG_UNABLE_TO_ADD, ex);
        }
    }

    /**
     * Assembles the {@link Transaction} record from the validated request,
     * mirroring the {@code ADD-TRANSACTION} field moves.
     *
     * @param tranId          the generated identifier
     * @param request         the validated add payload
     * @param cardNumber      the resolved card number
     * @param transactionType the resolved transaction type
     * @param amount          the normalized monetary amount
     * @return the populated, unsaved transaction
     */
    private static Transaction buildTransaction(String tranId, TransactionDto.AddRequest request,
            String cardNumber, TransactionTypeCode transactionType, BigDecimal amount) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTransactionType(transactionType);
        transaction.setTranCatCd(Integer.valueOf(request.categoryCode().trim()));
        transaction.setTranSource(request.source());
        transaction.setTranDesc(request.description());
        transaction.setTranAmt(amount);
        transaction.setMerchantId(Long.valueOf(request.merchantId().trim()));
        transaction.setMerchantName(request.merchantName());
        transaction.setMerchantCity(request.merchantCity());
        transaction.setMerchantZip(request.merchantZip());
        transaction.setCardNum(cardNumber);
        transaction.setOrigTs(request.originDate());
        transaction.setProcTs(request.processDate());
        return transaction;
    }

    /**
     * Renders the saved transaction as a detail view.
     *
     * @param tranId          the generated identifier
     * @param request         the validated add payload
     * @param cardNumber      the resolved card number
     * @param transactionType the resolved transaction type
     * @param amount          the normalized monetary amount
     * @return the detail view of the created transaction
     */
    private static TransactionDto.Detail toDetail(String tranId, TransactionDto.AddRequest request,
            String cardNumber, TransactionTypeCode transactionType, BigDecimal amount) {
        return new TransactionDto.Detail(
                tranId,
                cardNumber,
                transactionType.getCode(),
                request.categoryCode().trim(),
                request.source(),
                request.description(),
                amount,
                request.originDate(),
                request.processDate(),
                request.merchantId().trim(),
                request.merchantName(),
                request.merchantCity(),
                request.merchantZip());
    }

    /**
     * Parses a fixed-width numeric identifier to a {@code long}, treating a
     * blank value as zero.
     *
     * @param tranId the stored identifier
     * @return the numeric value of the identifier, or zero when blank
     */
    private static long parseTranId(String tranId) {
        String trimmed = trimToEmpty(tranId);
        return trimmed.isEmpty() ? EMPTY_HIGHEST_ID : Long.parseLong(trimmed);
    }

    /**
     * Returns the first message in insertion order from a non-empty error map.
     *
     * @param errors the per-field error map
     * @return the first error message
     */
    private static String firstMessage(Map<String, String> errors) {
        return errors.values().iterator().next();
    }

    /**
     * Reports whether a hyphenated value matches the {@code YYYY-MM-DD} layout:
     * four digits, a hyphen, two digits, a hyphen, then two digits.
     *
     * @param value the candidate date value
     * @return {@code true} when the value matches the layout
     */
    private static boolean isIsoDateFormat(String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.length() == ISO_DATE_LENGTH
                && isAllDigits(trimmed.substring(ISO_YEAR_BEGIN, ISO_YEAR_END))
                && trimmed.charAt(ISO_FIRST_HYPHEN) == '-'
                && isAllDigits(trimmed.substring(ISO_MONTH_BEGIN, ISO_MONTH_END))
                && trimmed.charAt(ISO_SECOND_HYPHEN) == '-'
                && isAllDigits(trimmed.substring(ISO_DAY_BEGIN, ISO_DAY_END));
    }

    /**
     * Converts a {@code YYYY-MM-DD} value to the eight-digit {@code CCYYMMDD}
     * form expected by {@link DateValidationService#isValidDate(String)}.
     *
     * @param isoDate the {@code YYYY-MM-DD} value, already format-checked
     * @return the eight-digit {@code CCYYMMDD} value
     */
    private static String toCcyymmdd(String isoDate) {
        String trimmed = trimToEmpty(isoDate);
        return trimmed.substring(ISO_YEAR_BEGIN, ISO_YEAR_END)
                + trimmed.substring(ISO_MONTH_BEGIN, ISO_MONTH_END)
                + trimmed.substring(ISO_DAY_BEGIN, ISO_DAY_END);
    }

    /**
     * Reports whether the trimmed value is non-empty and entirely numeric,
     * reproducing the COBOL {@code IS NUMERIC} class test.
     *
     * @param value the candidate value
     * @return {@code true} when the trimmed value is all digits
     */
    private static boolean isNumeric(String value) {
        return isAllDigits(trimToEmpty(value));
    }

    /**
     * Reports whether a non-empty string contains only ASCII digits.
     *
     * @param value the candidate value
     * @return {@code true} when every character is a digit and the value is non-empty
     */
    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a value is {@code null} or contains only whitespace.
     *
     * @param value the candidate value
     * @return {@code true} when the value is {@code null} or blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Returns the trimmed value, or an empty string when {@code null}.
     *
     * @param value the candidate value
     * @return the trimmed value, never {@code null}
     */
    private static String trimToEmpty(String value) {
        return (value == null) ? "" : value.trim();
    }
}
