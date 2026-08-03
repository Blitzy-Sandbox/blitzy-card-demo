/*
 * ****************************************************************************
 * Program     : TransactionAddService.java
 * Application : CardDemo
 * Type        : Spring Service Bean
 * Function    : Transaction add. Reproduces COTRN02C (transaction CT02):
 *               ten-stage validation cascade, dual numeric parsing, and
 *               max-key-plus-one identifier generation.
 * Source      : app/cbl/COTRN02C.cbl (783 lines, 18 paragraphs) @ 7756d89
 * ****************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.service.transaction;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.config.WebConfig;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionAddRequest;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Adds a transaction, reproducing {@code app/cbl/COTRN02C.cbl} at behavioural parity.
 *
 * <h2>What it does</h2>
 * <p>
 * This bean is the Java target of CICS transaction {@code CT02}, defined as
 * {@code DEFINE TRANSACTION(CT02)} with {@code PROGRAM(COTRN02C)} at
 * {@code app/csd/CARDDEMO.CSD:439-440} over mapset {@code COTRN02} at
 * {@code app/csd/CARDDEMO.CSD:153} and program entry {@code app/csd/CARDDEMO.CSD:271}. The source program is
 * 783 lines carrying 18 paragraph labels, and every one of those labels is reproduced here as exactly one
 * private method whose Javadoc cites the label and its verified line. The traceability anchor is commit
 * {@code 7756d89}; the paragraph-to-method correspondence is what the planned {@code TRACEABILITY_MATRIX.md} will
 * assert.
 * </p>
 * <p>
 * The behaviour, in source order, is: resolve the account or card key through the card cross reference,
 * run a ten-stage validation cascade over the eleven data fields, gate the write behind a two-phase
 * confirmation handshake, generate the transaction identifier as the maximum existing key plus one, and
 * write one {@code CVTRA05Y} transaction record.
 * </p>
 *
 * <h2>Preserved quirks, most consequential first</h2>
 * <ol>
 *   <li><b>Blocker — this program generates no timestamp at all.</b> {@code app/cbl/COTRN02C.cbl:464-465}
 *       moves {@code TORIGDTI} and {@code TPROCDTI}, both {@code PIC X(10)}
 *       ({@code app/cpy-bms/COTRN02.CPY:102} and {@code :108}), into {@code TRAN-ORIG-TS} and
 *       {@code TRAN-PROC-TS}, both {@code PIC X(26)}. A COBOL alphanumeric move into a wider field
 *       left-justifies and right-space-pads, so the persisted value is the validated ten-character
 *       {@code YYYY-MM-DD} date followed by sixteen spaces. {@code FUNCTION CURRENT-DATE} occurs exactly
 *       once in the whole program, at {@code app/cbl/COTRN02C.cbl:554}, and only for the screen header.
 *       This class therefore consults <em>no</em> clock: it holds no {@code Clock}, calls no {@code now()}
 *       and formats no timestamp. Both the batch rendering {@code yyyy-MM-dd-HH.mm.ss.SS0000} and the
 *       online rendering {@code yyyy-MM-dd HH:mm:ss.SSSSSS} are wrong for this program. This corrects the
 *       technical specification, which describes a generated timestamp; owed an entry in the planned
 *       {@code DECISION_LOG.md}. Remediation: none — the source is authoritative.</li>
 *   <li><b>High — the identifier generation race is deliberately retained.</b>
 *       {@code app/cbl/COTRN02C.cbl:443-450} moves {@code HIGH-VALUES} into the key, browses backwards for
 *       the highest existing identifier, adds one, and writes. That is racy under concurrency, exactly as
 *       the CICS browse was. No sequence, identity column, generated value, retry, lock, mutex, static
 *       counter or upsert is substituted, because substituting one would change generated values and break
 *       the Gate 1 comparison against the legacy baseline. A collision surfaces as
 *       {@code DuplicateRecordException} from the primary key constraint, which is the intended outcome and
 *       matches the shared {@code DUPKEY}/{@code DUPREC} branch at {@code app/cbl/COTRN02C.cbl:735-736}.
 *       Owed an entry in the planned {@code DECISION_LOG.md}. Remediation: acceptable only because parity against the
 *       legacy baseline is the contract; revisit if concurrent add throughput becomes a requirement.</li>
 *   <li><b>High — validation is strictly fail fast.</b> {@code SEND-TRNADD-SCREEN} issues
 *       {@code EXEC CICS SEND} and then {@code EXEC CICS RETURN} at
 *       {@code app/cbl/COTRN02C.cbl:530-534}, so every failure branch terminates the task immediately.
 *       Failures are therefore first-failure-wins in source order and are never accumulated into a list.
 *       The sibling programs {@code COTRN01C} and {@code COTRN00C} send without returning and must not be
 *       modelled this way; equally, this program's pattern is not exported to them. Remediation: none —
 *       accumulating violations would change which single message the caller receives, which is a parity
 *       break rather than an improvement.</li>
 *   <li><b>Medium — the cascade ordering is counter-intuitive and is not reordered.</b> The amount is
 *       currency parsed and echoed at {@code app/cbl/COTRN02C.cbl:383-386}, which is <em>before</em> the two
 *       dates are semantically validated at {@code :389-427} and <em>before</em> the merchant identifier
 *       numeric check at {@code :430-437}. A tidier ordering would change which echo the caller receives on
 *       a date validation failure. Owed an entry in the planned {@code DECISION_LOG.md}. Remediation: none — reorder
 *       only if the parity contract is renegotiated, because the echoed amount is byte compared.</li>
 *   <li><b>Medium — on the PF5 path the key validation and the cross reference read execute twice.</b>
 *       {@code COPY-LAST-TRAN-DATA} performs {@code VALIDATE-INPUT-KEY-FIELDS} at
 *       {@code app/cbl/COTRN02C.cbl:473} and then performs {@code PROCESS-ENTER-KEY} at {@code :495}, which
 *       performs {@code VALIDATE-INPUT-KEY-FIELDS} again at {@code :166}. The redundant read is source
 *       behaviour and is not optimised away; the efficiency clause is discharged by this written
 *       justification and the entry owed to the planned {@code DECISION_LOG.md}, not by deduplicating. Remediation:
 *       revisit only if PF5 prefill latency becomes a measured problem; the repeat is a single indexed lookup.</li>
 *   <li><b>Medium — two distinct numeric parsers are used deliberately.</b> {@code FUNCTION NUMVAL} parses
 *       the account identifier at {@code app/cbl/COTRN02C.cbl:204} and the card number at {@code :218};
 *       {@code FUNCTION NUMVAL-C}, which additionally tolerates currency symbols and thousands separators,
 *       parses the amount at {@code :383-384} and again at {@code :456-457}. Both amount parses are
 *       reproduced rather than cached and reused. Using one parser for both classes of field would accept
 *       input the legacy system rejects, or reject input it accepts. Remediation: none — the split is required, and
 *       both converters are consumed from {@code WebConfig} rather than reimplemented here.</li>
 *   <li><b>Medium — the raw-to-edited amount move on the PF5 path is a distinct path.</b>
 *       {@code app/cbl/COTRN02C.cbl:481} moves {@code TRAN-AMT} straight into the edited field, bypassing
 *       the working numeric entirely, and is not folded into the {@code :385} echo path. Remediation: none — folding
 *       them would route a stored record amount through the currency parser, which the source never does.</li>
 *   <li><b>Low — inert legacy declarations are documented rather than modelled.</b>
 *       {@code WS-TRAN-AMT} at {@code app/cbl/COTRN02C.cbl:53} and {@code WS-TRAN-DATE} at {@code :54} are
 *       declared and never referenced. {@code WS-USR-MODIFIED} at {@code :49-51} is set at {@code :110} and
 *       never read. {@code WS-ACCTDAT-FILE} at {@code :40} and {@code COPY CVACT01Y} at {@code :89} are
 *       never referenced anywhere in the procedure division — no {@code EXEC CICS} verb names the dataset
 *       and no account field is read or written — so {@code Account} and {@code AccountRepository} are
 *       deliberately neither imported, injected nor referenced. Importing them would leave unused imports,
 *       which Rule 1 Clause B forbids - caught at review rather than by {@code -Xlint:all -Werror}, since
 *       {@code javac} 25 publishes no {@code unused} lint key. No Java field is created for any of
 *       these five declarations.</li>
 *   <li><b>Low — {@code CURTIMEI} is {@code PIC X(8)}</b> at {@code app/cpy-bms/COTRN02.CPY:54}, not the
 *       {@code X(9)} the specification claims universally. The header renders {@code MM/DD/YY} and
 *       {@code HH:MM:SS}, eight characters each, per {@code app/cpy/CSDAT01Y.cpy:30-41}.</li>
 *   <li><b>Low — verified label lines differ from the specification in four places.</b> {@code MAIN-PARA}
 *       is at {@code app/cbl/COTRN02C.cbl:107}, {@code VALIDATE-INPUT-DATA-FIELDS} at {@code :235},
 *       {@code RECEIVE-TRNADD-SCREEN} at {@code :539} and {@code POPULATE-HEADER-INFO} at {@code :552}. The
 *       source governs and the citations below use the verified lines.</li>
 *   <li><b>Low — the screen header reads no clock in this target.</b>
 *       {@code MOVE FUNCTION CURRENT-DATE} at {@code app/cbl/COTRN02C.cbl:554} is presentation only, and the
 *       zero-clock constraint above is absolute for this file, so {@code populateHeaderInfo} echoes the
 *       request's own date and time text. Owed an entry in the planned {@code DECISION_LOG.md}.</li>
 *   </ol>
 *
 * <h2>Fixed-width input contract</h2>
 * <p>
 * The request carries BMS field content. {@code app/bms/COTRN02.bms:85-90} declares the account field with
 * neither {@code ATTRB=NUM} nor {@code PICIN} nor {@code JUSTIFY}, so a 3270 transmits an incompletely keyed
 * alphanumeric field left-justified and space-padded to its declared length. Every field this class reads is
 * therefore right-space-padded to its BMS width before any {@code IS NUMERIC} test or positional mask test,
 * which means a short numeric value fails validation precisely as it does on the terminal. Leading zeros are
 * significant, which is why the account identifier, card number, merchant identifier and amount all travel
 * as text rather than as numeric types.
 * </p>
 *
 * <h2>How to build and test</h2>
 * <p>
 * Built by the Maven {@code verify} lifecycle against Java 25 with {@code -Xlint:all -Werror}, so this file
 * must be warning clean. Unit tests live under {@code src/test/java/com/cardemo/unit/} and are not created in
 * this package.
 * </p>
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 *   <li>The two numeric converters and the edited-amount printer are owned by
 *       {@link com.cardemo.config.WebConfig}, published there as singleton beans, and <b>injected</b> here -
 *       never redeclared, reimplemented or constructed. Their resolved names are
 *       {@code WebConfig.StrictIdentifierConverter}, {@code WebConfig.CurrencyAwareAmountConverter} and
 *       {@code WebConfig.EditedAmountPrinter}. They are registered for query and path binding only and do
 *       not participate in JSON body binding, so this class invokes them explicitly - on the very objects
 *       request binding is registered with, which is what keeps one parsing rule to one object.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto=validate} and {@code spring.jpa.open-in-view=false} are
 *       consumed as configured and never redeclared here. No property is read by this class, and no
 *       environment variable or system property is consulted.</li>
 *   <li>The write path runs inside a single {@code @Transactional(rollbackFor = Exception.class)} boundary,
 *       so failure semantics are reproduced by scoping rather than by conditional logic. Transaction
 *       management is owned by {@code JpaConfig}.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 *   <li>{@link com.cardemo.exception.ValidationException} — one per cascade stage, carrying the offending
 *       field name and the byte-exact legacy screen message. It never carries the field value, so neither a
 *       card number nor an amount can leak through it.</li>
 *   <li>{@link com.cardemo.exception.RecordNotFoundException} — either cross reference miss. The account
 *       branch reports the account identifier as the key; the card branch reports a redacted placeholder,
 *       because a card number must never appear in an exception.</li>
 *   <li>{@link com.cardemo.exception.DuplicateRecordException} — the retained identifier race. Two adds that
 *       read the same maximum key produce the same candidate identifier and the second write violates the
 *       primary key. Interpret it as a genuine collision and resubmit; it is not a defect and no retry is
 *       performed on the caller's behalf.</li>
 *   <li>{@link com.cardemo.exception.FileAccessException} — any other data access failure, translated
 *       through {@link com.cardemo.service.shared.FileStatusMapper} and carrying the two-character file
 *       status.</li>
 *   <li>{@link com.cardemo.exception.FatalProcessingException} — the record image violated the
 *       {@code CVTRA05Y} field contract, which is the abend path: abend code 999, return code 12.</li>
 *   </ul>
 *
 * <h2>Not available</h2>
 * <p>
 * The following information was unavailable when this file was planned, stated plainly rather than guessed,
 * each with what would be needed to close it. One item was resolved during discovery and is recorded as such
 * rather than silently dropped.
 * </p>
 * <ul>
 *   <li>The two numeric converter bean and method names were Not available when this file was planned,
 *       because the {@code config} package had no generated children at that point. Needed: the declared
 *       converters, or a documented local fallback. <b>Resolved during discovery</b> —
 *       {@link com.cardemo.config.WebConfig} exists and declares {@code StrictIdentifierConverter},
 *       {@code CurrencyAwareAmountConverter} and {@code EditedAmountPrinter}, all consumed here, so no local
 *       fallback was written and no deviation needed logging.</li>
 *   <li><b>Resolved during discovery</b> - {@code src/main/resources/db/migration/V1__create_schema.sql},
 *       {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} all exist. An earlier revision of this
 *       bullet said they did not and called the resulting schema Not available; that is no longer true and
 *       the claim is withdrawn. The column widths this class relies on are therefore enforced by the schema
 *       as well as by the entity guards, and because {@code spring.jpa.hibernate.ddl-auto} is
 *       {@code validate} in all four profiles a divergence aborts context startup. No DDL is
 *       emitted here.</li>
 *   <li>File status {@code '35'} has no literal attestation anywhere in the COBOL corpus, so its handling
 *       contract is Not available. Needed: a source occurrence before any {@code '35'} handling is asserted;
 *       none is emitted here.</li>
 *   <li>Any source basis for the specification's universal {@code CURTIMEI PIC X(9)} claim is Not available.
 *       Sixteen of the seventeen symbolic maps declare {@code PIC X(8)} and only
 *       {@code app/cpy-bms/COSGN00.CPY:54} declares {@code PIC X(9)}. Needed: nothing further — the corpus
 *       count is conclusive, so this class treats the field as {@code X(8)} per
 *       {@code app/cpy-bms/COTRN02.CPY:54}. Classified Low above.</li>
 *   </ul>
 *
 * @see com.cardemo.model.dto.TransactionAddRequest
 * @see com.cardemo.model.dto.TransactionDto
 */
@Service
public class TransactionAddService {

    /** Structured diagnostics, replacing the {@code DISPLAY} statements of the source. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionAddService.class);

    // ------------------------------------------------------------------------------------------------
    // Identity, dataset and navigation literals.
    // Source: app/cbl/COTRN02C.cbl:36-42 WORKING-STORAGE program and dataset names.
    // ------------------------------------------------------------------------------------------------

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COTRN02C'}, {@code app/cbl/COTRN02C.cbl:36}. */
    private static final String PROGRAM_NAME = "COTRN02C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CT02'}, {@code app/cbl/COTRN02C.cbl:37}. */
    private static final String TRANSACTION_ID = "CT02";

    /** {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'}, {@code app/cbl/COTRN02C.cbl:39}. */
    private static final String LOGICAL_FILE_TRANSACT = "TRANSACT";

    /** {@code WS-CCXREF-FILE PIC X(08) VALUE 'CCXREF  '}, {@code app/cbl/COTRN02C.cbl:41}. */
    private static final String LOGICAL_FILE_CCXREF = "CCXREF";

    /** {@code WS-CXACAIX-FILE PIC X(08) VALUE 'CXACAIX '}, {@code app/cbl/COTRN02C.cbl:42}. */
    private static final String LOGICAL_FILE_CXACAIX = "CXACAIX";

    /**
     * Sign-on program, the target when no communication area is present at
     * {@code app/cbl/COTRN02C.cbl:116} and the default of {@code RETURN-TO-PREV-SCREEN} at {@code :502-504}.
     */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** Main menu program, the PF3 target when no origin is carried, {@code app/cbl/COTRN02C.cbl:138}. */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /** {@code CCDA-TITLE01}, {@code app/cpy/COTTL01Y.cpy:18-19}, forty characters. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02}, {@code app/cpy/COTTL01Y.cpy:20-22}, forty characters. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    // ------------------------------------------------------------------------------------------------
    // Byte-exact screen literals. Every string below is compared byte for byte by the parity gates and
    // must not be reworded, retrimmed, recased or merged with a similar string from another program.
    // ------------------------------------------------------------------------------------------------

    /** {@code CCDA-MSG-INVALID-KEY}, {@code app/cpy/CSMSG01Y.cpy:20-21}, used at {@code COTRN02C:150}. */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /** {@code app/cbl/COTRN02C.cbl:199-200}. */
    private static final String ACCOUNT_ID_NOT_NUMERIC_MESSAGE = "Account ID must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:213-214}. */
    private static final String CARD_NUMBER_NOT_NUMERIC_MESSAGE = "Card Number must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:226-227}. */
    private static final String KEY_REQUIRED_MESSAGE = "Account or Card Number must be entered...";

    /** {@code app/cbl/COTRN02C.cbl:254-255}. */
    private static final String TYPE_CODE_EMPTY_MESSAGE = "Type CD can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:260-261}. */
    private static final String CATEGORY_CODE_EMPTY_MESSAGE = "Category CD can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:266-267}. */
    private static final String SOURCE_EMPTY_MESSAGE = "Source can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:272-273}. */
    private static final String DESCRIPTION_EMPTY_MESSAGE = "Description can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:278-279}. */
    private static final String AMOUNT_EMPTY_MESSAGE = "Amount can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:284-285}. */
    private static final String ORIGINATING_DATE_EMPTY_MESSAGE = "Orig Date can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:290-291}. */
    private static final String PROCESSING_DATE_EMPTY_MESSAGE = "Proc Date can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:296-297}. */
    private static final String MERCHANT_ID_EMPTY_MESSAGE = "Merchant ID can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:302-303}. */
    private static final String MERCHANT_NAME_EMPTY_MESSAGE = "Merchant Name can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:308-309}. */
    private static final String MERCHANT_CITY_EMPTY_MESSAGE = "Merchant City can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:314-315}. */
    private static final String MERCHANT_ZIP_EMPTY_MESSAGE = "Merchant Zip can NOT be empty...";

    /** {@code app/cbl/COTRN02C.cbl:325-326}. */
    private static final String TYPE_CODE_NOT_NUMERIC_MESSAGE = "Type CD must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:331-332}. */
    private static final String CATEGORY_CODE_NOT_NUMERIC_MESSAGE = "Category CD must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:345-346}. Note the mask carries exactly eight integer digits. */
    private static final String AMOUNT_FORMAT_MESSAGE = "Amount should be in format -99999999.99";

    /** {@code app/cbl/COTRN02C.cbl:360-361}. */
    private static final String ORIGINATING_DATE_FORMAT_MESSAGE = "Orig Date should be in format YYYY-MM-DD";

    /** {@code app/cbl/COTRN02C.cbl:375-376}. */
    private static final String PROCESSING_DATE_FORMAT_MESSAGE = "Proc Date should be in format YYYY-MM-DD";

    /** {@code app/cbl/COTRN02C.cbl:401-402}. */
    private static final String ORIGINATING_DATE_INVALID_MESSAGE = "Orig Date - Not a valid date...";

    /** {@code app/cbl/COTRN02C.cbl:421-422}. */
    private static final String PROCESSING_DATE_INVALID_MESSAGE = "Proc Date - Not a valid date...";

    /** {@code app/cbl/COTRN02C.cbl:432-433}. */
    private static final String MERCHANT_ID_NOT_NUMERIC_MESSAGE = "Merchant ID must be Numeric...";

    /** {@code app/cbl/COTRN02C.cbl:178-179}, the first phase of the confirmation handshake. */
    private static final String CONFIRMATION_REQUIRED_MESSAGE = "Confirm to add this transaction...";

    /** {@code app/cbl/COTRN02C.cbl:184-185}. */
    private static final String CONFIRMATION_INVALID_MESSAGE = "Invalid value. Valid values are (Y/N)...";

    /** {@code app/cbl/COTRN02C.cbl:593-594}. */
    private static final String ACCOUNT_ID_NOT_FOUND_MESSAGE = "Account ID NOT found...";

    /** {@code app/cbl/COTRN02C.cbl:600-601}. */
    private static final String CXACAIX_LOOKUP_FAILURE_MESSAGE =
            "Unable to lookup Acct in XREF AIX file...";

    /** {@code app/cbl/COTRN02C.cbl:626-627}. */
    private static final String CARD_NUMBER_NOT_FOUND_MESSAGE = "Card Number NOT found...";

    /** {@code app/cbl/COTRN02C.cbl:633-634}. */
    private static final String CCXREF_LOOKUP_FAILURE_MESSAGE = "Unable to lookup Card # in XREF file...";

    /** {@code app/cbl/COTRN02C.cbl:657-658}. */
    private static final String TRANSACTION_ID_NOT_FOUND_MESSAGE = "Transaction ID NOT found...";

    /**
     * {@code app/cbl/COTRN02C.cbl:664-665} and {@code :693-694}. The noun is capitalised here and in
     * {@code app/cbl/COTRN01C.cbl:292}; {@code app/cbl/COTRN00C.cbl} uses a lower-case variant that must
     * never be unified with this one.
     */
    private static final String TRANSACTION_LOOKUP_FAILURE_MESSAGE = "Unable to lookup Transaction...";

    /** {@code app/cbl/COTRN02C.cbl:738-739}, the shared {@code DUPKEY}/{@code DUPREC} branch. */
    private static final String DUPLICATE_TRANSACTION_MESSAGE = "Tran ID already exist...";

    /** {@code app/cbl/COTRN02C.cbl:745-746}. */
    private static final String ADD_FAILURE_MESSAGE = "Unable to Add Transaction...";

    /**
     * First {@code STRING} operand of the success message, {@code app/cbl/COTRN02C.cbl:728-729}, delimited
     * by size. The trailing space is part of the literal and must survive.
     */
    private static final String ADDED_MESSAGE_PREFIX = "Transaction added successfully. ";

    /**
     * Second {@code STRING} operand, {@code app/cbl/COTRN02C.cbl:730}, delimited by size. The leading space
     * is part of the literal and must survive.
     */
    private static final String ADDED_MESSAGE_INFIX = " Your Tran ID is ";

    /** Fourth {@code STRING} operand, {@code app/cbl/COTRN02C.cbl:732}, delimited by size. */
    private static final String ADDED_MESSAGE_SUFFIX = ".";

    // ------------------------------------------------------------------------------------------------
    // Cursor targets. Each is the symbolic map length field the source drives to -1 so the terminal places
    // the cursor there. Carried on the result so a caller can reproduce the focus behaviour.
    // ------------------------------------------------------------------------------------------------

    /** {@code ACTIDINL}, driven at {@code app/cbl/COTRN02C.cbl:123}, {@code :201}, {@code :228} and others. */
    private static final String CURSOR_ACCOUNT_ID = "ACTIDINL";

    /** {@code CARDNINL}, driven at {@code app/cbl/COTRN02C.cbl:215}, {@code :628} and {@code :635}. */
    private static final String CURSOR_CARD_NUMBER = "CARDNINL";

    /** {@code TTYPCDL}, driven at {@code app/cbl/COTRN02C.cbl:256} and {@code :327}. */
    private static final String CURSOR_TYPE_CODE = "TTYPCDL";

    /** {@code TCATCDL}, driven at {@code app/cbl/COTRN02C.cbl:262} and {@code :333}. */
    private static final String CURSOR_CATEGORY_CODE = "TCATCDL";

    /** {@code TRNSRCL}, driven at {@code app/cbl/COTRN02C.cbl:268}. */
    private static final String CURSOR_SOURCE = "TRNSRCL";

    /** {@code TDESCL}, driven at {@code app/cbl/COTRN02C.cbl:274}. */
    private static final String CURSOR_DESCRIPTION = "TDESCL";

    /** {@code TRNAMTL}, driven at {@code app/cbl/COTRN02C.cbl:280} and {@code :347}. */
    private static final String CURSOR_AMOUNT = "TRNAMTL";

    /** {@code TORIGDTL}, driven at {@code app/cbl/COTRN02C.cbl:286}, {@code :362} and {@code :404}. */
    private static final String CURSOR_ORIGINATING_DATE = "TORIGDTL";

    /** {@code TPROCDTL}, driven at {@code app/cbl/COTRN02C.cbl:292}, {@code :377} and {@code :424}. */
    private static final String CURSOR_PROCESSING_DATE = "TPROCDTL";

    /** {@code MIDL}, driven at {@code app/cbl/COTRN02C.cbl:298} and {@code :434}. */
    private static final String CURSOR_MERCHANT_ID = "MIDL";

    /** {@code MNAMEL}, driven at {@code app/cbl/COTRN02C.cbl:304}. */
    private static final String CURSOR_MERCHANT_NAME = "MNAMEL";

    /** {@code MCITYL}, driven at {@code app/cbl/COTRN02C.cbl:310}. */
    private static final String CURSOR_MERCHANT_CITY = "MCITYL";

    /** {@code MZIPL}, driven at {@code app/cbl/COTRN02C.cbl:316}. */
    private static final String CURSOR_MERCHANT_ZIP = "MZIPL";

    /** {@code CONFIRML}, driven at {@code app/cbl/COTRN02C.cbl:180} and {@code :186}. */
    private static final String CURSOR_CONFIRMATION = "CONFIRML";


    // ------------------------------------------------------------------------------------------------
    // Field names carried on ValidationException. These are names only; the exception never carries a
    // field value, so neither a card number nor an amount can leak through a validation failure.
    // ------------------------------------------------------------------------------------------------

    /** Request component name for {@code ACTIDINI}. */
    private static final String FIELD_ACCOUNT_ID = "accountId";

    /** Request component name for {@code CARDNINI}. */
    private static final String FIELD_CARD_NUMBER = "cardNumber";

    /** Request component name for {@code TTYPCDI}. */
    private static final String FIELD_TYPE_CODE = "typeCode";

    /** Request component name for {@code TCATCDI}. */
    private static final String FIELD_CATEGORY_CODE = "categoryCode";

    /** Request component name for {@code TRNSRCI}. */
    private static final String FIELD_SOURCE = "source";

    /** Request component name for {@code TDESCI}. */
    private static final String FIELD_DESCRIPTION = "description";

    /** Request component name for {@code TRNAMTI}. */
    private static final String FIELD_AMOUNT = "amount";

    /** Request component name for {@code TORIGDTI}. */
    private static final String FIELD_ORIGINATING_DATE = "originatingDate";

    /** Request component name for {@code TPROCDTI}. */
    private static final String FIELD_PROCESSING_DATE = "processingDate";

    /** Request component name for {@code MIDI}. */
    private static final String FIELD_MERCHANT_ID = "merchantId";

    /** Request component name for {@code MNAMEI}. */
    private static final String FIELD_MERCHANT_NAME = "merchantName";

    /** Request component name for {@code MCITYI}. */
    private static final String FIELD_MERCHANT_CITY = "merchantCity";

    /** Request component name for {@code MZIPI}. */
    private static final String FIELD_MERCHANT_ZIP = "merchantZip";

    /** Request component name for {@code CONFIRMI}. */
    private static final String FIELD_CONFIRMATION = "confirmation";

    // ------------------------------------------------------------------------------------------------
    // Field widths. Screen widths come from app/cpy-bms/COTRN02.CPY; record widths come from
    // app/cpy/CVTRA05Y.cpy. The pairs that differ are the widening moves of ADD-TRANSACTION and the
    // truncating moves of COPY-LAST-TRAN-DATA.
    // ------------------------------------------------------------------------------------------------

    /** {@code ACTIDINI PIC X(11)}, {@code app/cpy-bms/COTRN02.CPY:60}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CARDNINI PIC X(16)}, {@code app/cpy-bms/COTRN02.CPY:66}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code TTYPCDI PIC X(2)}, {@code app/cpy-bms/COTRN02.CPY:72}. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** {@code TCATCDI PIC X(4)}, {@code app/cpy-bms/COTRN02.CPY:78}. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** {@code TRNSRCI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:84}. */
    private static final int SOURCE_WIDTH = 10;

    /** {@code TDESCI PIC X(60)}, {@code app/cpy-bms/COTRN02.CPY:90}. */
    private static final int DESCRIPTION_INPUT_WIDTH = 60;

    /** {@code TRAN-DESC PIC X(100)}, {@code app/cpy/CVTRA05Y.cpy}, the widening target of {@code :455}. */
    private static final int DESCRIPTION_RECORD_WIDTH = 100;

    /** {@code TRNAMTI PIC X(12)}, {@code app/cpy-bms/COTRN02.CPY:96}. */
    private static final int AMOUNT_INPUT_WIDTH = 12;

    /** {@code TORIGDTI} and {@code TPROCDTI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:102} and {@code :108}. */
    private static final int DATE_INPUT_WIDTH = 10;

    /** {@code MIDI PIC X(9)}, {@code app/cpy-bms/COTRN02.CPY:114}. */
    private static final int MERCHANT_ID_WIDTH = 9;

    /** {@code MNAMEI PIC X(30)}, {@code app/cpy-bms/COTRN02.CPY:120}. */
    private static final int MERCHANT_NAME_INPUT_WIDTH = 30;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)}, the widening target of {@code app/cbl/COTRN02C.cbl:461}. */
    private static final int MERCHANT_NAME_RECORD_WIDTH = 50;

    /** {@code MCITYI PIC X(25)}, {@code app/cpy-bms/COTRN02.CPY:126}. */
    private static final int MERCHANT_CITY_INPUT_WIDTH = 25;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)}, the widening target of {@code app/cbl/COTRN02C.cbl:462}. */
    private static final int MERCHANT_CITY_RECORD_WIDTH = 50;

    /** {@code MZIPI PIC X(10)}, {@code app/cpy-bms/COTRN02.CPY:132}. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** {@code CONFIRMI PIC X(1)}, {@code app/cpy-bms/COTRN02.CPY:138}. */
    private static final int CONFIRMATION_WIDTH = 1;

    /** {@code ERRMSGI PIC X(78)}, {@code app/cpy-bms/COTRN02.CPY:144}. */
    private static final int ERROR_MESSAGE_WIDTH = 78;

    /** {@code TRAN-ID PIC X(16)}, {@code app/cpy/CVTRA05Y.cpy}, and {@code WS-TRAN-ID-N PIC 9(16)}. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /**
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS PIC X(26)}. The widening target of
     * {@code app/cbl/COTRN02C.cbl:464-465}, reached by right-space-padding a ten-character date.
     */
    private static final int TIMESTAMP_WIDTH = 26;

    /** {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COTRN02.CPY:24}. */
    private static final int TRANSACTION_NAME_WIDTH = 4;

    /** {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COTRN02.CPY:42}. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** {@code TITLE01I} and {@code TITLE02I PIC X(40)}, {@code app/cpy-bms/COTRN02.CPY:30} and {@code :48}. */
    private static final int TITLE_WIDTH = 40;

    /**
     * {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COTRN02.CPY:36}, rendered {@code MM/DD/YY} by
     * {@code WS-CURDATE-MM-DD-YY} at {@code app/cpy/CSDAT01Y.cpy:30-35}.
     */
    private static final int HEADER_DATE_WIDTH = 8;

    /**
     * {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COTRN02.CPY:54}, rendered {@code HH:MM:SS} by
     * {@code WS-CURTIME-HH-MM-SS} at {@code app/cpy/CSDAT01Y.cpy:36-41}. Eight characters, not nine.
     */
    private static final int HEADER_TIME_WIDTH = 8;

    // ------------------------------------------------------------------------------------------------
    // Positional mask geometry. Every offset below is one-based, exactly as COBOL reference modification
    // is, and is transcribed from the EVALUATE conditions rather than re-expressed as a pattern.
    // ------------------------------------------------------------------------------------------------

    /** {@code TRNAMTI(1:1)}, {@code app/cbl/COTRN02C.cbl:340}. */
    private static final int AMOUNT_SIGN_POSITION = 1;

    /** {@code TRNAMTI(2:8)} start, {@code app/cbl/COTRN02C.cbl:341}. */
    private static final int AMOUNT_INTEGER_POSITION = 2;

    /** {@code TRNAMTI(2:8)} length, {@code app/cbl/COTRN02C.cbl:341}. Eight integer digits, not nine. */
    private static final int AMOUNT_INTEGER_MASK_DIGITS = 8;

    /** {@code TRNAMTI(10:1)}, {@code app/cbl/COTRN02C.cbl:342}. */
    private static final int AMOUNT_POINT_POSITION = 10;

    /** {@code TRNAMTI(11:2)} start, {@code app/cbl/COTRN02C.cbl:343}. */
    private static final int AMOUNT_FRACTION_POSITION = 11;

    /** {@code TRNAMTI(11:2)} length, {@code app/cbl/COTRN02C.cbl:343}. */
    private static final int AMOUNT_FRACTION_DIGITS = 2;

    /** {@code TORIGDTI(1:4)} start, {@code app/cbl/COTRN02C.cbl:354}. */
    private static final int DATE_YEAR_POSITION = 1;

    /** {@code TORIGDTI(1:4)} length, {@code app/cbl/COTRN02C.cbl:354}. */
    private static final int DATE_YEAR_DIGITS = 4;

    /** {@code TORIGDTI(5:1)}, {@code app/cbl/COTRN02C.cbl:355}. */
    private static final int DATE_FIRST_SEPARATOR_POSITION = 5;

    /** {@code TORIGDTI(6:2)} start, {@code app/cbl/COTRN02C.cbl:356}. */
    private static final int DATE_MONTH_POSITION = 6;

    /** {@code TORIGDTI(8:1)}, {@code app/cbl/COTRN02C.cbl:357}. */
    private static final int DATE_SECOND_SEPARATOR_POSITION = 8;

    /** {@code TORIGDTI(9:2)} start, {@code app/cbl/COTRN02C.cbl:358}. */
    private static final int DATE_DAY_POSITION = 9;

    /** {@code TORIGDTI(6:2)} and {@code (9:2)} length, {@code app/cbl/COTRN02C.cbl:356} and {@code :358}. */
    private static final int DATE_COMPONENT_DIGITS = 2;

    /** The separator both date masks require. */
    private static final char DATE_SEPARATOR = '-';

    /** The decimal point the amount mask requires at position ten. */
    private static final char DECIMAL_POINT = '.';

    /** One of the two signs the amount mask admits at position one. */
    private static final char PLUS_SIGN = '+';

    /** The other sign the amount mask admits at position one. Negative amounts are never normalised. */
    private static final char MINUS_SIGN = '-';

    /** Space, the COBOL fill character for an alphanumeric move into a wider field. */
    private static final char SPACE = ' ';

    /** Zero, the fill character for a numeric move into an alphanumeric field of the same width. */
    private static final String ZERO_DIGIT = "0";

    // ------------------------------------------------------------------------------------------------
    // Date validation contract, app/cbl/COTRN02C.cbl:60, :389-407 and :409-427.
    // ------------------------------------------------------------------------------------------------

    /** {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'}, {@code app/cbl/COTRN02C.cbl:60}. */
    private static final String DATE_VALIDATION_FORMAT = "YYYY-MM-DD";

    /** The accepting severity, {@code app/cbl/COTRN02C.cbl:397} and {@code :417}. */
    private static final String SEVERITY_CODE_OK = "0000";

    /**
     * The single tolerated non-zero outcome, {@code app/cbl/COTRN02C.cbl:400} and {@code :420}. This is the
     * unsupported-range feedback condition. The tolerance is neither tightened nor widened.
     */
    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    // ------------------------------------------------------------------------------------------------
    // Confirmation values, app/cbl/COTRN02C.cbl:169-188. The field is a raw single character, never a
    // boolean, because the source distinguishes 'Y', 'y', 'N', 'n', spaces, low values and anything else.
    // ------------------------------------------------------------------------------------------------

    /** {@code WHEN 'Y'}, {@code app/cbl/COTRN02C.cbl:170}. */
    private static final String CONFIRM_YES_UPPER = "Y";

    /** {@code WHEN 'y'}, {@code app/cbl/COTRN02C.cbl:171}. */
    private static final String CONFIRM_YES_LOWER = "y";

    /** {@code WHEN 'N'}, {@code app/cbl/COTRN02C.cbl:173}. */
    private static final String CONFIRM_NO_UPPER = "N";

    /** {@code WHEN 'n'}, {@code app/cbl/COTRN02C.cbl:174}. */
    private static final String CONFIRM_NO_LOWER = "n";


    // ------------------------------------------------------------------------------------------------
    // CICS response codes. The exec layer below reports these so each paragraph can EVALUATE WS-RESP-CD
    // exactly as the source does, and the numeric values are the DFHRESP equivalents.
    // ------------------------------------------------------------------------------------------------

    /** {@code DFHRESP(NORMAL)}. */
    private static final int CICS_RESP_NORMAL = 0;

    /** {@code DFHRESP(NOTFND)}. */
    private static final int CICS_RESP_NOTFND = 13;

    /** {@code DFHRESP(DUPREC)}, {@code app/cbl/COTRN02C.cbl:736}. */
    private static final int CICS_RESP_DUPREC = 14;

    /** {@code DFHRESP(DUPKEY)}, {@code app/cbl/COTRN02C.cbl:735}. */
    private static final int CICS_RESP_DUPKEY = 15;

    /** {@code DFHRESP(IOERR)}, the {@code WHEN OTHER} arm of every guard in this program. */
    private static final int CICS_RESP_IOERR = 17;

    /** {@code DFHRESP(ENDFILE)}, {@code app/cbl/COTRN02C.cbl:688}. */
    private static final int CICS_RESP_ENDFILE = 20;

    /** {@code RESP2} is unused by this program, so the reason code is always reported as zero. */
    private static final int CICS_REAS_NONE = 0;

    /** File status {@code '00'}, success. */
    private static final String IO_STATUS_SUCCESS = "00";

    /** File status {@code '10'}, end of file. A default path, never an error. */
    private static final String IO_STATUS_END_OF_FILE = "10";

    /** File status {@code '22'}, duplicate key. */
    private static final String IO_STATUS_DUPLICATE_KEY = "22";

    /** File status {@code '23'}, record not found. */
    private static final String IO_STATUS_RECORD_NOT_FOUND = "23";

    /** File status {@code '90'}, a member of the {@code '9x'} physical or logical error family. */
    private static final String IO_STATUS_IO_ERROR = "90";

    /** {@code EXEC CICS READ}, for diagnostics and for the file access exception payload. */
    private static final String OPERATION_READ = "READ";

    /** {@code EXEC CICS STARTBR}, {@code app/cbl/COTRN02C.cbl:644}. */
    private static final String OPERATION_STARTBR = "STARTBR";

    /** {@code EXEC CICS READPREV}, {@code app/cbl/COTRN02C.cbl:675}. */
    private static final String OPERATION_READPREV = "READPREV";

    /** {@code EXEC CICS WRITE}, {@code app/cbl/COTRN02C.cbl:713}. */
    private static final String OPERATION_WRITE = "WRITE";

    // ------------------------------------------------------------------------------------------------
    // Redaction. Card numbers flow through nearly every method in this class, so no log statement, no
    // exception message and no exception key may ever carry one.
    // ------------------------------------------------------------------------------------------------

    /** Stands in for a present card number in any diagnostic or exception payload. */
    private static final String CARD_NUMBER_REDACTED = "<redacted-16-digit-card-number>";

    /** Stands in for an absent card number in any diagnostic or exception payload. */
    private static final String CARD_NUMBER_ABSENT = "<absent>";

    // ------------------------------------------------------------------------------------------------
    // Abend contract. The corpus-wide abend idiom is code 999 with return code 12; the abend work areas
    // themselves are app/cpy/CSMSG02Y.cpy, whose fields are the fatal exception payload.
    // ------------------------------------------------------------------------------------------------

    /**
     * {@code ABEND-CODE PIC X(4)} carrying the corpus abend code, {@code app/cpy/CSMSG02Y.cpy:L22-L23}.
     *
     * <p>The copybook carries COBOL sequence numbers in columns 1-6, so the {@code ABEND-DATA} group spans
     * sequence numbers {@code 001200} through {@code 002000}. Those are sequence numbers, not file lines:
     * the same declarations occupy physical lines 21-29 of the 35-line member, and it is the physical line
     * that a text editor or {@code sed} will land on.
     */
    private static final String ABEND_CODE = String.valueOf(FatalProcessingException.BATCH_ABEND_CODE);

    /** {@code ABEND-REASON PIC X(50)} for a record image that violates the {@code CVTRA05Y} contract. */
    private static final String ABEND_REASON_RECORD_CONTRACT = "TRAN-RECORD FIELD CONTRACT VIOLATED";

    /** {@code ABEND-MSG PIC X(72)} for the same condition. */
    private static final String ABEND_MESSAGE_RECORD_CONTRACT =
            "UNABLE TO BUILD TRAN-RECORD FROM SCREEN INPUT.";

    /**
     * {@code MOVE HIGH-VALUES TO TRAN-ID} at {@code app/cbl/COTRN02C.cbl:444} and {@code :475}. The sentinel
     * sorts above every stored key, exactly as the COBOL figurative constant does, and is never persisted:
     * both call sites overwrite it before any write.
     */
    private static final String HIGH_VALUES_TRANSACTION_KEY =
            String.valueOf(Character.MAX_VALUE).repeat(TRANSACTION_ID_WIDTH);

    /**
     * {@code MOVE ZEROS TO TRAN-ID} at {@code app/cbl/COTRN02C.cbl:689}, the end-of-file default, confirmed
     * independently by {@code app/cbl/COBIL00C.cbl:487-488}. It is what makes the first generated identifier
     * {@code 0000000000000001}.
     */
    private static final String ZERO_FILLED_TRANSACTION_KEY = ZERO_DIGIT.repeat(TRANSACTION_ID_WIDTH);

    // ------------------------------------------------------------------------------------------------
    // The two numeric converters and the edited-amount printer are owned by com.cardemo.config.WebConfig,
    // published there as singleton beans, and INJECTED here. They are deliberately not constructed in this
    // class: doing so produced a second copy of each parsing rule, so the object the MVC conversion service
    // registered was never the object that actually parsed a transaction amount or a card number. One rule,
    // one object. WebConfig registers them for query and path binding only, so a JSON request body does not
    // pass through them and this class must invoke them explicitly.
    // ------------------------------------------------------------------------------------------------

    /**
     * Digits-only parser, the counterpart of {@code FUNCTION NUMVAL} at {@code app/cbl/COTRN02C.cbl:204},
     * {@code :218} and the merchant identifier guard at {@code :430}. The shared bean, injected.
     */
    private final WebConfig.StrictIdentifierConverter strictIdentifierConverter;

    /**
     * Currency-tolerant parser, the counterpart of {@code FUNCTION NUMVAL-C} at
     * {@code app/cbl/COTRN02C.cbl:383-384} and {@code :456-457}. It is applied to the amount and to nothing
     * else. The shared bean, injected.
     */
    private final WebConfig.CurrencyAwareAmountConverter currencyAwareAmountConverter;

    /**
     * Renderer for {@code WS-TRAN-AMT-E PIC +99999999.99} at {@code app/cbl/COTRN02C.cbl:59}. It emits
     * exactly twelve characters with a mandatory sign and eight integer digits, discarding the ninth integer
     * digit that {@code WS-TRAN-AMT-N PIC S9(9)V99} at {@code :58} can hold. That asymmetry is source
     * behaviour and is preserved rather than widened. The shared bean, injected.
     */
    private final WebConfig.EditedAmountPrinter editedAmountPrinter;

    /** Transaction dataset access, {@code TRANSACT}. */
    private final TransactionRepository transactionRepository;

    /** Card cross reference access, serving both {@code CCXREF} and the {@code CXACAIX} alternate index. */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /** The {@code CSUTLDTC} counterpart, called twice per submission. */
    private final DateValidationService dateValidationService;

    /** The single file status to exception translation point. */
    private final FileStatusMapper fileStatusMapper;

    /**
     * Wires the collaborators this service consumes. Constructor injection only: there is no field
     * injection, no setter injection and no context lookup, and every collaborator is final.
     *
     * @param transactionRepository transaction dataset access, replacing {@code EXEC CICS STARTBR},
     *     {@code READPREV}, {@code ENDBR} and {@code WRITE} against {@code TRANSACT}. Must not be null.
     * @param cardCrossReferenceRepository cross reference access, replacing {@code EXEC CICS READ} against
     *     {@code CCXREF} and against the {@code CXACAIX} alternate index path. Must not be null.
     * @param dateValidationService semantic date validation, replacing {@code CALL 'CSUTLDTC'} at
     *     {@code app/cbl/COTRN02C.cbl:393} and {@code :413}. Must not be null.
     * @param fileStatusMapper file status rendering and translation, replacing the corpus-wide
     *     {@code 9910-DISPLAY-IO-STATUS} idiom. Must not be null.
     * @param strictIdentifierConverter the application's single {@code FUNCTION NUMVAL} parser, published by
     *     {@link com.cardemo.config.WebConfig}. Injected rather than constructed so that the object this
     *     class parses with is the object request binding is registered with. Must not be null.
     * @param currencyAwareAmountConverter the application's single {@code FUNCTION NUMVAL-C} parser, on the
     *     same terms. Must not be null.
     * @param editedAmountPrinter the application's single {@code PIC +99999999.99} renderer, on the same
     *     terms. Must not be null.
     * @throws NullPointerException if any collaborator is null, which is a wiring defect rather than a
     *     business outcome and is therefore not translated into a CardDemo exception.
     */
    public TransactionAddService(final TransactionRepository transactionRepository,
                                 final CardCrossReferenceRepository cardCrossReferenceRepository,
                                 final DateValidationService dateValidationService,
                                 final FileStatusMapper fileStatusMapper,
                                 final WebConfig.StrictIdentifierConverter strictIdentifierConverter,
                                 final WebConfig.CurrencyAwareAmountConverter currencyAwareAmountConverter,
                                 final WebConfig.EditedAmountPrinter editedAmountPrinter) {
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository must not be null");
        this.dateValidationService =
                Objects.requireNonNull(dateValidationService, "dateValidationService must not be null");
        this.fileStatusMapper =
                Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.strictIdentifierConverter = Objects.requireNonNull(strictIdentifierConverter,
                "strictIdentifierConverter must not be null");
        this.currencyAwareAmountConverter = Objects.requireNonNull(currencyAwareAmountConverter,
                "currencyAwareAmountConverter must not be null");
        this.editedAmountPrinter =
                Objects.requireNonNull(editedAmountPrinter, "editedAmountPrinter must not be null");
    }


    // ================================================================================================
    // Public contract.
    // ================================================================================================

    /**
     * The attention identifier the operator raised, the counterpart of {@code EIBAID} in the
     * {@code EVALUATE} at {@code app/cbl/COTRN02C.cbl:133-152}. {@code EIBAID} itself has no REST
     * counterpart, so the key is supplied by the caller instead of being read from an exec interface block.
     */
    public enum AttentionIdentifier {

        /** {@code WHEN DFHENTER}, {@code app/cbl/COTRN02C.cbl:134-135}: validate and, if confirmed, add. */
        ENTER,

        /** {@code WHEN DFHPF3}, {@code app/cbl/COTRN02C.cbl:136-143}: navigate back. */
        PF3,

        /** {@code WHEN DFHPF4}, {@code app/cbl/COTRN02C.cbl:144-145}: clear the screen. */
        PF4,

        /** {@code WHEN DFHPF5}, {@code app/cbl/COTRN02C.cbl:146-147}: prefill from the newest transaction. */
        PF5,

        /** {@code WHEN OTHER}, {@code app/cbl/COTRN02C.cbl:148-151}: report an invalid key. */
        OTHER
    }

    /**
     * How the task terminated. Every constant corresponds to one {@code PERFORM SEND-TRNADD-SCREEN} or
     * {@code PERFORM RETURN-TO-PREV-SCREEN} site that the source reaches without an error condition the
     * REST surface reports as an exception.
     */
    public enum Outcome {

        /** The write succeeded, {@code app/cbl/COTRN02C.cbl:724-734}. */
        ADDED,

        /**
         * The confirmation gate returned the prompt and nothing was written,
         * {@code app/cbl/COTRN02C.cbl:173-181}. This is the first phase of the two-phase handshake.
         */
        CONFIRMATION_REQUIRED,

        /** The screen was displayed, {@code app/cbl/COTRN02C.cbl:122-130}. */
        SCREEN_DISPLAYED,

        /** The screen was cleared, {@code app/cbl/COTRN02C.cbl:754-757}. */
        SCREEN_CLEARED,

        /** An unsupported key was raised, {@code app/cbl/COTRN02C.cbl:148-151}. */
        INVALID_KEY,

        /** Control transferred to another program, {@code app/cbl/COTRN02C.cbl:500-511}. */
        NAVIGATED_AWAY
    }

    /**
     * What a submission produced. The screen carries the echoed field values exactly as
     * {@code EXEC CICS SEND MAP} would have carried them, including the normalised account identifier, the
     * derived card number and the edited amount.
     *
     * @param outcome how the task terminated.
     * @param screen the symbolic map image the source would have sent, or null when control transferred to
     *     another program and no map was sent.
     * @param message {@code WS-MESSAGE} as composed by the terminating branch, never null. It is the empty
     *     string when the branch left the message blank.
     * @param cursorField the symbolic map length field the source drove to -1, or null when no cursor was
     *     repositioned.
     * @param nextProgram the program control transferred to, or null when the task returned to itself.
     * @param transactionId the generated sixteen-digit identifier when the outcome is
     *     {@link Outcome#ADDED}, or null otherwise.
     */
    public record TransactionAddResult(Outcome outcome,
                                       TransactionDto screen,
                                       String message,
                                       String cursorField,
                                       String nextProgram,
                                       String transactionId) {
    }

    /**
     * Handles a re-entered submission, the {@code ELSE} arm at {@code app/cbl/COTRN02C.cbl:131-153}: receive
     * the map, then dispatch on the attention identifier.
     * <p>
     * Side effects: on the confirmed enter path exactly one transaction row is inserted and flushed inside
     * this method's transaction. Every other path is read only in effect, although the boundary is not
     * declared read only because {@link AttentionIdentifier#ENTER} and {@link AttentionIdentifier#PF5} can
     * both reach the write.
     * </p>
     *
     * @param attentionIdentifier the key the operator raised. A null value is treated as
     *     {@link AttentionIdentifier#OTHER}, reproducing {@code WHEN OTHER} at
     *     {@code app/cbl/COTRN02C.cbl:148}.
     * @param request the symbolic map input group, twenty-one fields from
     *     {@code app/cpy-bms/COTRN02.CPY}. A null request is treated as an all-blank map, which the source
     *     reaches whenever the terminal transmits nothing.
     * @param originProgram {@code CDEMO-FROM-PROGRAM} as the communication area carried it, tested at
     *     {@code app/cbl/COTRN02C.cbl:137}. It selects the target of the PF3 branch: a blank or unset value
     *     falls back to the literal {@code COMEN01C} at {@code :138}, and a supplied value becomes the
     *     transfer target at {@code :140-141}. Callers that have no origin to declare pass null, which
     *     reproduces the {@code SPACES OR LOW-VALUES} arm exactly. The value is inert on every other
     *     attention identifier, exactly as it is in the source.
     * @return how the task terminated, together with the map image the source would have sent.
     * @throws ValidationException on the first cascade failure, carrying the offending field name and the
     *     byte-exact legacy message. Validation is fail fast, so no later stage runs.
     * @throws RecordNotFoundException when neither cross reference lookup finds a record.
     * @throws DuplicateRecordException when the generated identifier collides, which is the retained race.
     * @throws FileAccessException when any data access operation fails.
     * @throws FatalProcessingException when the assembled record violates the {@code CVTRA05Y} contract.
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionAddResult submitScreen(final AttentionIdentifier attentionIdentifier,
                                             final TransactionAddRequest request,
                                             final String originProgram) {
        final ScreenWorkArea work = new ScreenWorkArea();
        work.attentionIdentifier = attentionIdentifier;
        work.reentered = true;
        work.commAreaPresent = true;
        work.cdemoFromProgram = nullToEmpty(originProgram);
        mainPara(work, request);
        return finish(work);
    }

    /**
     * Convenience entry point for the enter path, equivalent to calling
     * {@link #submitScreen(AttentionIdentifier, TransactionAddRequest, String)} with
     * {@link AttentionIdentifier#ENTER} and no origin program. It carries its own transaction boundary so
     * that the write is always transactional regardless of which entry point a caller chooses. No origin is
     * needed because the enter path never reaches the PF3 branch that reads it.
     *
     * @param request the symbolic map input group. A null request is treated as an all-blank map.
     * @return how the task terminated, together with the map image the source would have sent.
     * @throws ValidationException on the first cascade failure.
     * @throws RecordNotFoundException when neither cross reference lookup finds a record.
     * @throws DuplicateRecordException when the generated identifier collides.
     * @throws FileAccessException when any data access operation fails.
     * @throws FatalProcessingException when the assembled record violates the {@code CVTRA05Y} contract.
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionAddResult addTransaction(final TransactionAddRequest request) {
        final ScreenWorkArea work = new ScreenWorkArea();
        work.attentionIdentifier = AttentionIdentifier.ENTER;
        work.reentered = true;
        work.commAreaPresent = true;
        mainPara(work, request);
        return finish(work);
    }

    /**
     * Displays the screen for the first time, the {@code IF NOT CDEMO-PGM-REENTER} arm at
     * {@code app/cbl/COTRN02C.cbl:120-130}: blank the output map, place the cursor on the account field and,
     * when the caller arrived by deep link, prefill and immediately process.
     * <p>
     * The deep link is counter-intuitive and is reproduced exactly: {@code CDEMO-CT02-TRN-SELECTED} is moved
     * into {@code CARDNINI}, the <em>card number</em> field, at {@code app/cbl/COTRN02C.cbl:126-127}, not
     * into the account field.
     * </p>
     *
     * @param request the symbolic map input group, used for the header echo and for any field the caller
     *     pre-populates. A null request is treated as an all-blank map.
     * @param selectedTransactionIdentifier {@code CDEMO-CT02-TRN-SELECTED}, sixteen characters. When it is
     *     null, blank or unset the screen is simply displayed; otherwise it is moved into the card number
     *     field and the enter path runs immediately.
     * @return how the task terminated, together with the map image the source would have sent.
     * @throws ValidationException on the first cascade failure of the deep-link submission.
     * @throws RecordNotFoundException when the deep-link cross reference lookup finds no record.
     * @throws DuplicateRecordException when the generated identifier collides.
     * @throws FileAccessException when any data access operation fails.
     * @throws FatalProcessingException when the assembled record violates the {@code CVTRA05Y} contract.
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionAddResult openScreen(final TransactionAddRequest request,
                                           final String selectedTransactionIdentifier) {
        final ScreenWorkArea work = new ScreenWorkArea();
        work.attentionIdentifier = AttentionIdentifier.ENTER;
        work.reentered = false;
        work.commAreaPresent = true;
        work.selectedTransactionIdentifier = selectedTransactionIdentifier;
        mainPara(work, request);
        return finish(work);
    }

    /**
     * Reproduces the {@code EIBCALEN = 0} arm at {@code app/cbl/COTRN02C.cbl:115-117}: with no communication
     * area the program transfers straight to the sign-on program without sending its own map.
     *
     * @return an outcome of {@link Outcome#NAVIGATED_AWAY} naming {@code COSGN00C} as the next program, with
     *     a null screen because no map was sent.
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionAddResult openWithoutCommArea() {
        final ScreenWorkArea work = new ScreenWorkArea();
        work.attentionIdentifier = AttentionIdentifier.ENTER;
        work.reentered = false;
        work.commAreaPresent = false;
        mainPara(work, null);
        return finish(work);
    }

    /**
     * Converts the work area into the public result, throwing the retained failure when one was recorded.
     * <p>
     * Retaining the failure rather than throwing it at the point of detection is what lets each paragraph
     * reproduce the source's control flow verbatim: the source sets {@code WS-ERR-FLG}, moves the message,
     * repositions the cursor and performs {@code SEND-TRNADD-SCREEN}, which returns from the task. This
     * method is where that retained failure becomes the REST-visible outcome, so validation remains fail
     * fast at the API boundary.
     * </p>
     *
     * @param work the per-invocation work area.
     * @return the public result when no failure was retained.
     */
    private static TransactionAddResult finish(final ScreenWorkArea work) {
        if (work.pendingFailure != null) {
            throw work.pendingFailure;
        }
        return new TransactionAddResult(work.outcome,
                work.screen,
                work.wsMessage,
                work.cursorField,
                work.cdemoToProgram,
                work.addedTransactionId);
    }


    // ================================================================================================
    // Paragraph 1 of 18. Source: app/cbl/COTRN02C.cbl MAIN-PARA (:107-159).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code MAIN-PARA} ({@code :107-159}).
     * <p>
     * Clears the error flag and the message, then branches three ways: no communication area transfers to
     * the sign-on program; a first entry blanks the map, positions the cursor and honours the deep link; a
     * re-entry receives the map and dispatches on the attention identifier.
     * </p>
     * <p>
     * {@code SET USR-MODIFIED-NO TO TRUE} at {@code :110} writes {@code WS-USR-MODIFIED}, declared at
     * {@code :49-51} and never read anywhere in the program, so no Java field models it (Low). The
     * communication area copy at {@code :119} has no counterpart because the entry points carry the same
     * information as parameters. {@code EXEC CICS RETURN TRANSID} at {@code :156-159} has no counterpart
     * because a stateless request carries no next-transaction identifier and no communication area.
     * </p>
     *
     * @param work the per-invocation work area, standing in for working storage and the symbolic map.
     * @param request the received map, or null when nothing was transmitted.
     */
    private void mainPara(final ScreenWorkArea work, final TransactionAddRequest request) {
        work.errFlag = false;
        work.wsMessage = "";
        work.errMsg = "";
        work.requestCurrentDate = request == null ? "" : nullToEmpty(request.currentDate());
        work.requestCurrentTime = request == null ? "" : nullToEmpty(request.currentTime());

        if (!work.commAreaPresent) {
            work.cdemoToProgram = SIGN_ON_PROGRAM;
            returnToPrevScreen(work);
            return;
        }

        if (!work.reentered) {
            work.reentered = true;
            work.actIdIn = "";
            work.cardNIn = "";
            work.tTypCd = "";
            work.tCatCd = "";
            work.trnSrc = "";
            work.tDesc = "";
            work.trnAmt = "";
            work.tOrigDt = "";
            work.tProcDt = "";
            work.mId = "";
            work.mName = "";
            work.mCity = "";
            work.mZip = "";
            work.confirm = "";
            work.cursorField = CURSOR_ACCOUNT_ID;
            if (isSupplied(work.selectedTransactionIdentifier)) {
                work.cardNIn = fixedWidth(work.selectedTransactionIdentifier, CARD_NUMBER_WIDTH);
                processEnterKey(work);
                if (work.taskEnded) {
                    return;
                }
            }
            work.outcome = Outcome.SCREEN_DISPLAYED;
            sendTrnaddScreen(work);
            return;
        }

        receiveTrnaddScreen(work, request);
        // EVALUATE EIBAID, app/cbl/COTRN02C.cbl:133-152. EIBAID itself has no counterpart: the attention
        // identifier arrives as a parameter rather than being read from the exec interface block.
        switch (work.attentionIdentifier) {
            // WHEN DFHENTER, :134-135.
            case ENTER -> processEnterKey(work);
            // WHEN DFHPF3, :136-143. The blank or unset origin falls back to the literal at :138; a carried
            // origin becomes the transfer target at :140-141.
            case PF3 -> {
                if (isBlankOrUnset(work.cdemoFromProgram)) {
                    work.cdemoToProgram = MAIN_MENU_PROGRAM;
                } else {
                    work.cdemoToProgram = work.cdemoFromProgram;
                }
                returnToPrevScreen(work);
            }
            // WHEN DFHPF4, :144-145.
            case PF4 -> clearCurrentScreen(work);
            // WHEN DFHPF5, :146-147.
            case PF5 -> copyLastTranData(work);
            // WHEN OTHER, :148-151. A null identifier joins this arm because an untransmitted key is not a
            // supported key, which is the same outcome the source reaches.
            case null, default -> {
                work.errFlag = true;
                work.wsMessage = INVALID_KEY_MESSAGE;
                work.outcome = Outcome.INVALID_KEY;
                sendTrnaddScreen(work);
            }
        }
    }

    // ================================================================================================
    // Paragraph 2 of 18. Source: app/cbl/COTRN02C.cbl PROCESS-ENTER-KEY (:164-188).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code PROCESS-ENTER-KEY} ({@code :164-188}).
     * <p>
     * Validates the key fields, validates the data fields, then evaluates the confirmation character.
     * {@code 'Y'} or {@code 'y'} performs the add; {@code 'N'}, {@code 'n'}, spaces or low values return the
     * confirmation prompt and write nothing, which is the first phase of the two-phase handshake; anything
     * else is an invalid value. The gate is never auto-confirmed.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void processEnterKey(final ScreenWorkArea work) {
        validateInputKeyFields(work);
        if (work.taskEnded) {
            return;
        }
        validateInputDataFields(work);
        if (work.taskEnded) {
            return;
        }

        final String confirmation = fixedWidth(work.confirm, CONFIRMATION_WIDTH);
        if (CONFIRM_YES_UPPER.equals(confirmation) || CONFIRM_YES_LOWER.equals(confirmation)) {
            addTransaction(work);
            return;
        }
        if (CONFIRM_NO_UPPER.equals(confirmation) || CONFIRM_NO_LOWER.equals(confirmation)
                || isBlankOrUnset(work.confirm)) {
            work.errFlag = true;
            work.wsMessage = CONFIRMATION_REQUIRED_MESSAGE;
            work.cursorField = CURSOR_CONFIRMATION;
            work.outcome = Outcome.CONFIRMATION_REQUIRED;
            sendTrnaddScreen(work);
            return;
        }
        failValidation(work, CONFIRMATION_INVALID_MESSAGE, CURSOR_CONFIRMATION, FIELD_CONFIRMATION, false);
    }

    // ================================================================================================
    // Paragraph 3 of 18. Source: app/cbl/COTRN02C.cbl VALIDATE-INPUT-KEY-FIELDS (:193-230).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code VALIDATE-INPUT-KEY-FIELDS} ({@code :193-230}).
     * <p>
     * Resolves the account identifier or the card number through the cross reference. The account branch is
     * evaluated first, so when both fields are supplied the account branch wins and the card number is
     * overwritten from the cross reference record at {@code :209}. The branches are not reordered.
     * </p>
     * <p>
     * Both branches move the parsed numeric into two receivers, at {@code :206-207} and {@code :220-221}, so
     * the zero-padded normalised value is echoed back into the map and therefore into the response. Both
     * parse with the strict digits-only converter, never the currency-aware one.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void validateInputKeyFields(final ScreenWorkArea work) {
        if (isSupplied(work.actIdIn)) {
            if (!isCobolNumeric(work.actIdIn, ACCOUNT_ID_WIDTH)) {
                failValidation(work, ACCOUNT_ID_NOT_NUMERIC_MESSAGE, CURSOR_ACCOUNT_ID, FIELD_ACCOUNT_ID,
                        false);
                return;
            }
            final long wsAcctIdN = parseStrictIdentifier(work.actIdIn, ACCOUNT_ID_WIDTH,
                    FIELD_ACCOUNT_ID, ACCOUNT_ID_NOT_NUMERIC_MESSAGE);
            work.xrefAcctId = wsAcctIdN;
            work.actIdIn = renderZeroPadded(wsAcctIdN, ACCOUNT_ID_WIDTH);
            readCxacaixFile(work);
            if (work.taskEnded) {
                return;
            }
            work.cardNIn = fixedWidth(work.xrefCardNum, CARD_NUMBER_WIDTH);
            return;
        }

        if (isSupplied(work.cardNIn)) {
            if (!isCobolNumeric(work.cardNIn, CARD_NUMBER_WIDTH)) {
                failValidation(work, CARD_NUMBER_NOT_NUMERIC_MESSAGE, CURSOR_CARD_NUMBER, FIELD_CARD_NUMBER,
                        false);
                return;
            }
            final long wsCardNumN = parseStrictIdentifier(work.cardNIn, CARD_NUMBER_WIDTH,
                    FIELD_CARD_NUMBER, CARD_NUMBER_NOT_NUMERIC_MESSAGE);
            work.xrefCardNum = renderZeroPadded(wsCardNumN, CARD_NUMBER_WIDTH);
            work.cardNIn = work.xrefCardNum;
            readCcxrefFile(work);
            if (work.taskEnded) {
                return;
            }
            work.actIdIn = renderZeroPadded(work.xrefAcctId, ACCOUNT_ID_WIDTH);
            return;
        }

        failValidation(work, KEY_REQUIRED_MESSAGE, CURSOR_ACCOUNT_ID, FIELD_ACCOUNT_ID, true);
    }


    // ================================================================================================
    // Paragraph 4 of 18. Source: app/cbl/COTRN02C.cbl VALIDATE-INPUT-DATA-FIELDS (:235-437).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code VALIDATE-INPUT-DATA-FIELDS} ({@code :235-437}).
     * <p>
     * Ten stages in exact source order, fail fast at the first failure, no accumulation of violations.
     * </p>
     * <ol>
     *   <li>{@code :237-249} blank the eleven data fields when the error flag is already on.</li>
     *   <li>{@code :251-320} blank and low-value checks in field order.</li>
     *   <li>{@code :322-337} numeric checks on the type and category codes.</li>
     *   <li>{@code :339-351} the positional amount mask.</li>
     *   <li>{@code :353-366} the positional originating date mask.</li>
     *   <li>{@code :368-381} the positional processing date mask.</li>
     *   <li>{@code :383-386} the currency-aware amount parse and the edited echo.</li>
     *   <li>{@code :389-407} semantic validation of the originating date.</li>
     *   <li>{@code :409-427} semantic validation of the processing date.</li>
     *   <li>{@code :430-437} the numeric check on the merchant identifier.</li>
     * </ol>
     * <p>
     * The ordering is counter-intuitive and is preserved (Medium): the amount is parsed and echoed at stage
     * seven, before both date validations and before the merchant identifier check. A tidier ordering would
     * change which echo the caller receives on a date validation failure.
     * </p>
     * <p>
     * Stage one is a preserved defensive branch. Every assignment of {@code 'Y'} to {@code WS-ERR-FLG} in
     * this program is immediately followed by {@code PERFORM SEND-TRNADD-SCREEN}, which returns from the
     * task, so {@code ERR-FLG-ON} at {@code :237} cannot be true on entry. It is retained rather than
     * deleted because deleting it would break the paragraph body correspondence the coverage gate verifies
     * (Low; owed an entry in the planned {@code DECISION_LOG.md}).
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void validateInputDataFields(final ScreenWorkArea work) {
        // Stage 1 of 10, :237-249. The blanking order here is TTYPCD, TCATCD, TRNSRC, TRNAMT, TDESC, ... which
        // is deliberately NOT the validation order of stage two: the source blanks the amount before the
        // description at :241-242 but validates the description at :270 before the amount at :276.
        if (work.errFlag) {
            work.tTypCd = "";
            work.tCatCd = "";
            work.trnSrc = "";
            work.trnAmt = "";
            work.tDesc = "";
            work.tOrigDt = "";
            work.tProcDt = "";
            work.mId = "";
            work.mName = "";
            work.mCity = "";
            work.mZip = "";
        }

        // Stage 2 of 10, :251-320. Eleven blank-or-low-values checks in the source's validation order.
        if (isBlankOrUnset(work.tTypCd)) {
            failValidation(work, TYPE_CODE_EMPTY_MESSAGE, CURSOR_TYPE_CODE, FIELD_TYPE_CODE, true);
            return;
        }
        if (isBlankOrUnset(work.tCatCd)) {
            failValidation(work, CATEGORY_CODE_EMPTY_MESSAGE, CURSOR_CATEGORY_CODE, FIELD_CATEGORY_CODE, true);
            return;
        }
        if (isBlankOrUnset(work.trnSrc)) {
            failValidation(work, SOURCE_EMPTY_MESSAGE, CURSOR_SOURCE, FIELD_SOURCE, true);
            return;
        }
        if (isBlankOrUnset(work.tDesc)) {
            failValidation(work, DESCRIPTION_EMPTY_MESSAGE, CURSOR_DESCRIPTION, FIELD_DESCRIPTION, true);
            return;
        }
        if (isBlankOrUnset(work.trnAmt)) {
            failValidation(work, AMOUNT_EMPTY_MESSAGE, CURSOR_AMOUNT, FIELD_AMOUNT, true);
            return;
        }
        if (isBlankOrUnset(work.tOrigDt)) {
            failValidation(work, ORIGINATING_DATE_EMPTY_MESSAGE, CURSOR_ORIGINATING_DATE,
                    FIELD_ORIGINATING_DATE, true);
            return;
        }
        if (isBlankOrUnset(work.tProcDt)) {
            failValidation(work, PROCESSING_DATE_EMPTY_MESSAGE, CURSOR_PROCESSING_DATE,
                    FIELD_PROCESSING_DATE, true);
            return;
        }
        if (isBlankOrUnset(work.mId)) {
            failValidation(work, MERCHANT_ID_EMPTY_MESSAGE, CURSOR_MERCHANT_ID, FIELD_MERCHANT_ID, true);
            return;
        }
        if (isBlankOrUnset(work.mName)) {
            failValidation(work, MERCHANT_NAME_EMPTY_MESSAGE, CURSOR_MERCHANT_NAME, FIELD_MERCHANT_NAME,
                    true);
            return;
        }
        if (isBlankOrUnset(work.mCity)) {
            failValidation(work, MERCHANT_CITY_EMPTY_MESSAGE, CURSOR_MERCHANT_CITY, FIELD_MERCHANT_CITY,
                    true);
            return;
        }
        if (isBlankOrUnset(work.mZip)) {
            failValidation(work, MERCHANT_ZIP_EMPTY_MESSAGE, CURSOR_MERCHANT_ZIP, FIELD_MERCHANT_ZIP, true);
            return;
        }

        // Stage 3 of 10, :322-337. Numeric class tests on the type and category codes.
        if (!isCobolNumeric(work.tTypCd, TYPE_CODE_WIDTH)) {
            failValidation(work, TYPE_CODE_NOT_NUMERIC_MESSAGE, CURSOR_TYPE_CODE, FIELD_TYPE_CODE, false);
            return;
        }
        if (!isCobolNumeric(work.tCatCd, CATEGORY_CODE_WIDTH)) {
            failValidation(work, CATEGORY_CODE_NOT_NUMERIC_MESSAGE, CURSOR_CATEGORY_CODE,
                    FIELD_CATEGORY_CODE, false);
            return;
        }

        // Stage 4 of 10, :339-351. The positional amount mask.
        if (!matchesAmountMask(work.trnAmt)) {
            failValidation(work, AMOUNT_FORMAT_MESSAGE, CURSOR_AMOUNT, FIELD_AMOUNT, false);
            return;
        }

        // Stage 5 of 10, :353-366. The positional originating date mask.
        if (!matchesDateMask(work.tOrigDt)) {
            failValidation(work, ORIGINATING_DATE_FORMAT_MESSAGE, CURSOR_ORIGINATING_DATE,
                    FIELD_ORIGINATING_DATE, false);
            return;
        }
        // Stage 6 of 10, :368-381. The positional processing date mask.
        if (!matchesDateMask(work.tProcDt)) {
            failValidation(work, PROCESSING_DATE_FORMAT_MESSAGE, CURSOR_PROCESSING_DATE,
                    FIELD_PROCESSING_DATE, false);
            return;
        }

        // Stage 7 of 10, :383-386. The currency-aware parse and the edited echo. This runs BEFORE both date
        // validations and before the merchant identifier check; the ordering is preserved (Medium).
        work.wsTranAmtN = parseCurrencyAmount(work.trnAmt);
        work.wsTranAmtE = this.editedAmountPrinter.print(work.wsTranAmtN, Locale.ROOT);
        work.trnAmt = fixedWidth(work.wsTranAmtE, AMOUNT_INPUT_WIDTH);

        // Stage 8 of 10, :389-407. Semantic validation of the originating date, with the 2513 tolerance.
        final DateValidationService.DateValidationResult originatingOutcome = this.dateValidationService
                .validate(fixedWidth(work.tOrigDt, DATE_INPUT_WIDTH), DATE_VALIDATION_FORMAT);
        if (!isDateAccepted(originatingOutcome)) {
            failValidation(work, ORIGINATING_DATE_INVALID_MESSAGE, CURSOR_ORIGINATING_DATE,
                    FIELD_ORIGINATING_DATE, false);
            return;
        }

        // Stage 9 of 10, :409-427. Semantic validation of the processing date, with the same tolerance.
        final DateValidationService.DateValidationResult processingOutcome = this.dateValidationService
                .validate(fixedWidth(work.tProcDt, DATE_INPUT_WIDTH), DATE_VALIDATION_FORMAT);
        if (!isDateAccepted(processingOutcome)) {
            failValidation(work, PROCESSING_DATE_INVALID_MESSAGE, CURSOR_PROCESSING_DATE,
                    FIELD_PROCESSING_DATE, false);
            return;
        }

        // Stage 10 of 10, :430-437. The numeric class test on the merchant identifier.
        if (!isCobolNumeric(work.mId, MERCHANT_ID_WIDTH)) {
            failValidation(work, MERCHANT_ID_NOT_NUMERIC_MESSAGE, CURSOR_MERCHANT_ID, FIELD_MERCHANT_ID,
                    false);
        }
    }

    // ================================================================================================
    // Paragraph 5 of 18. Source: app/cbl/COTRN02C.cbl ADD-TRANSACTION (:442-466).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code ADD-TRANSACTION} ({@code :442-466}).
     * <p>
     * Generates the identifier as the maximum existing key plus one and assembles the {@code CVTRA05Y}
     * record image. The generation sequence is verbatim: move {@code HIGH-VALUES} into the key at
     * {@code :444}, start the browse at {@code :445}, read backwards at {@code :446}, end the browse at
     * {@code :447}, move the retrieved key into the working numeric at {@code :448}, add one at {@code :449},
     * initialise the record at {@code :450} and move the incremented value back into the key at {@code :451}
     * where a sixteen-digit numeric move into a sixteen-character alphanumeric field zero-pads.
     * </p>
     * <p>
     * The race this creates is deliberately retained (High). No sequence, identity column, generated value,
     * retry, lock, mutex, static counter or upsert is substituted; a collision surfaces as
     * {@link DuplicateRecordException} from the primary key constraint, exactly as the shared
     * {@code DUPKEY}/{@code DUPREC} branch at {@code :735-736} surfaces it.
     * </p>
     * <p>
     * Four widening moves right-space-pad: the description from sixty to one hundred at {@code :455}, the
     * merchant name from thirty to fifty at {@code :461}, the merchant city from twenty-five to fifty at
     * {@code :462}, and both timestamps from ten to twenty-six at {@code :464-465}. The timestamps are the
     * validated dates padded with sixteen spaces; no clock is consulted anywhere on this path.
     * </p>
     * <p>
     * The amount is parsed a second time at {@code :456-457}, again with the currency-aware converter. That
     * second parse is reproduced rather than reusing the stage-seven result, because the source performs it.
     * {@code INITIALIZE TRAN-RECORD} at {@code :450} also spaces the trailing {@code FILLER X(20)} at record
     * offsets 331 to 350; that filler is not an entity field, so the space fill is implicit in the
     * fixed-width emission layer rather than modelled here.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void addTransaction(final ScreenWorkArea work) {
        work.tranRecord.tranId = HIGH_VALUES_TRANSACTION_KEY;
        startbrTransactFile(work);
        if (work.taskEnded) {
            return;
        }
        readprevTransactFile(work);
        if (work.taskEnded) {
            return;
        }
        endbrTransactFile(work);

        long wsTranIdN = digitsToLong(work.tranRecord.tranId);
        wsTranIdN += 1L;
        work.tranRecord.initialize();
        work.tranRecord.tranId = renderZeroPadded(wsTranIdN, TRANSACTION_ID_WIDTH);
        work.tranRecord.tranTypeCd = fixedWidth(work.tTypCd, TYPE_CODE_WIDTH);
        work.tranRecord.tranCatCd = (int) parseStrictIdentifier(work.tCatCd, CATEGORY_CODE_WIDTH,
                FIELD_CATEGORY_CODE, CATEGORY_CODE_NOT_NUMERIC_MESSAGE);
        work.tranRecord.tranSource = fixedWidth(work.trnSrc, SOURCE_WIDTH);
        work.tranRecord.tranDesc =
                fixedWidth(truncate(work.tDesc, DESCRIPTION_INPUT_WIDTH), DESCRIPTION_RECORD_WIDTH);
        work.wsTranAmtN = parseCurrencyAmount(work.trnAmt);
        work.tranRecord.tranAmt = work.wsTranAmtN;
        work.tranRecord.tranCardNum = fixedWidth(work.cardNIn, CARD_NUMBER_WIDTH);
        work.tranRecord.tranMerchantId = parseStrictIdentifier(work.mId, MERCHANT_ID_WIDTH,
                FIELD_MERCHANT_ID, MERCHANT_ID_NOT_NUMERIC_MESSAGE);
        work.tranRecord.tranMerchantName =
                fixedWidth(truncate(work.mName, MERCHANT_NAME_INPUT_WIDTH), MERCHANT_NAME_RECORD_WIDTH);
        work.tranRecord.tranMerchantCity =
                fixedWidth(truncate(work.mCity, MERCHANT_CITY_INPUT_WIDTH), MERCHANT_CITY_RECORD_WIDTH);
        work.tranRecord.tranMerchantZip = fixedWidth(work.mZip, MERCHANT_ZIP_WIDTH);
        work.tranRecord.tranOrigTs =
                fixedWidth(truncate(work.tOrigDt, DATE_INPUT_WIDTH), TIMESTAMP_WIDTH);
        work.tranRecord.tranProcTs =
                fixedWidth(truncate(work.tProcDt, DATE_INPUT_WIDTH), TIMESTAMP_WIDTH);
        writeTransactFile(work);
    }

    // ================================================================================================
    // Paragraph 6 of 18. Source: app/cbl/COTRN02C.cbl COPY-LAST-TRAN-DATA (:471-495).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code COPY-LAST-TRAN-DATA} ({@code :471-495}). <p> The PF5 prefill. It
     * validates the key fields at {@code :473}, runs the same maximum-key browse at {@code :475-478} but here to read
     * the newest transaction rather than to generate an identifier, copies that record into the map with truncating
     * moves at {@code :480-493}, and then performs {@code PROCESS-ENTER-KEY} at {@code :495}. </p> <p> Because
     * {@code PROCESS-ENTER-KEY} performs {@code VALIDATE-INPUT-KEY-FIELDS} again at {@code :166}, the key validation
     * and the cross reference read execute twice on this path (Medium). That redundant I/O is source behaviour and is
     * not optimised away; it is justified in writing here and in the planned {@code DECISION_LOG.md} rather than
     * deduplicated. </p> <p> The two reads are not necessarily the same read, and this is easy to get wrong. When the
     * operator typed only a card number, the first pass takes the card branch and {@code :223} moves
     * {@code XREF-ACCT-ID} into {@code ACTIDINI}. The account field is therefore populated by the time the second
     * pass evaluates {@code :196}, so the second pass takes the <em>account</em> branch and reads the account-keyed
     * alternate index at {@code :208} rather than the card cross reference again. The observable sequence on the
     * card-only PF5 path is consequently a cross reference read followed by an alternate index read, and on the
     * account path it is the alternate index read twice. Both are reproduced as written. </p> <p> {@code :481} moves
     * the raw {@code TRAN-AMT} straight into the edited field, bypassing {@code WS-TRAN-AMT-N} entirely. That is a
     * distinct path from the stage-seven echo at {@code :385} and is modelled as such. The reverse truncating moves
     * are the mirror image of the widening moves of {@code ADD-TRANSACTION}: description one hundred to sixty at
     * {@code :486}, both timestamps twenty-six to ten at {@code :487-488}, merchant name fifty to thirty at
     * {@code :490} and merchant city fifty to twenty-five at {@code :491}. </p>
     *
     * @param work the per-invocation work area.
     */
    private void copyLastTranData(final ScreenWorkArea work) {
        validateInputKeyFields(work);
        if (work.taskEnded) {
            return;
        }

        work.tranRecord.tranId = HIGH_VALUES_TRANSACTION_KEY;
        startbrTransactFile(work);
        if (work.taskEnded) {
            return;
        }
        readprevTransactFile(work);
        if (work.taskEnded) {
            return;
        }
        endbrTransactFile(work);

        if (!work.errFlag) {
            work.wsTranAmtE = this.editedAmountPrinter.print(work.tranRecord.tranAmt, Locale.ROOT);
            work.tTypCd = fixedWidth(work.tranRecord.tranTypeCd, TYPE_CODE_WIDTH);
            work.tCatCd = renderZeroPadded(work.tranRecord.tranCatCd, CATEGORY_CODE_WIDTH);
            work.trnSrc = fixedWidth(work.tranRecord.tranSource, SOURCE_WIDTH);
            work.trnAmt = fixedWidth(work.wsTranAmtE, AMOUNT_INPUT_WIDTH);
            work.tDesc = fixedWidth(truncate(work.tranRecord.tranDesc, DESCRIPTION_INPUT_WIDTH),
                    DESCRIPTION_INPUT_WIDTH);
            work.tOrigDt = fixedWidth(truncate(work.tranRecord.tranOrigTs, DATE_INPUT_WIDTH),
                    DATE_INPUT_WIDTH);
            work.tProcDt = fixedWidth(truncate(work.tranRecord.tranProcTs, DATE_INPUT_WIDTH),
                    DATE_INPUT_WIDTH);
            work.mId = renderZeroPadded(work.tranRecord.tranMerchantId, MERCHANT_ID_WIDTH);
            work.mName = fixedWidth(truncate(work.tranRecord.tranMerchantName, MERCHANT_NAME_INPUT_WIDTH),
                    MERCHANT_NAME_INPUT_WIDTH);
            work.mCity = fixedWidth(truncate(work.tranRecord.tranMerchantCity, MERCHANT_CITY_INPUT_WIDTH),
                    MERCHANT_CITY_INPUT_WIDTH);
            work.mZip = fixedWidth(work.tranRecord.tranMerchantZip, MERCHANT_ZIP_WIDTH);
        }

        processEnterKey(work);
    }


    // ================================================================================================
    // Paragraph 7 of 18. Source: app/cbl/COTRN02C.cbl RETURN-TO-PREV-SCREEN (:500-511).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code RETURN-TO-PREV-SCREEN} ({@code :500-511}).
     * <p>
     * Defaults the target program to the sign-on program when none was carried at {@code :502-504}, records
     * this transaction and program as the origin at {@code :505-506}, zeroes the program context at
     * {@code :507}, then transfers control at {@code :508-511}.
     * </p>
     * <p>
     * What has no counterpart: {@code EXEC CICS XCTL} itself, because navigation is URL based rather than a
     * program transfer, and the communication area it passes, because no server-side conversation state is
     * retained. The target program name is therefore reported on the result so the caller can perform the
     * equivalent redirect. {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} at {@code :507} clears the enter versus
     * re-enter flag, which collapses into stateless request handling: the next request simply arrives without
     * a re-entry marker.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void returnToPrevScreen(final ScreenWorkArea work) {
        if (isBlankOrUnset(work.cdemoToProgram)) {
            work.cdemoToProgram = SIGN_ON_PROGRAM;
        }
        work.cdemoFromTranId = TRANSACTION_ID;
        work.cdemoFromProgram = PROGRAM_NAME;
        work.reentered = false;
        work.outcome = Outcome.NAVIGATED_AWAY;
        work.taskEnded = true;
        LOG.debug("{} transferring control to program '{}', origin recorded as transaction '{}' program '{}'",
                TRANSACTION_ID,
                work.cdemoToProgram,
                work.cdemoFromTranId,
                work.cdemoFromProgram);
    }

    // ================================================================================================
    // Paragraph 8 of 18. Source: app/cbl/COTRN02C.cbl SEND-TRNADD-SCREEN (:516-534).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code SEND-TRNADD-SCREEN} ({@code :516-534}).
     * <p>
     * Populates the header at {@code :518}, moves {@code WS-MESSAGE} into the error line at {@code :520},
     * sends the map at {@code :522-528} and then returns from the task at {@code :530-534}.
     * </p>
     * <p>
     * That trailing {@code EXEC CICS RETURN} is the single most consequential structural fact about this
     * program (High): it makes every validation failure terminate immediately, so validation is strictly
     * first-failure-wins in source order. It is reproduced by setting the task-ended marker, which every
     * caller honours before continuing. The sibling programs {@code COTRN01C} and {@code COTRN00C} send
     * without returning and rely on {@code IF NOT ERR-FLG-ON} guards instead; that pattern must not be
     * copied here, and this pattern must not be exported to them.
     * </p>
     * <p>
     * What has no counterpart: {@code ERASE} and {@code CURSOR} are 3270 write-control options, so the cursor
     * target is reported as a field name on the result rather than as a length field driven to -1; and the
     * next-transaction identifier and communication area of the return have no stateless equivalent.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void sendTrnaddScreen(final ScreenWorkArea work) {
        populateHeaderInfo(work);

        work.errMsg = truncate(work.wsMessage, ERROR_MESSAGE_WIDTH);

        work.screen = new TransactionDto(work.trnName,
                work.title01,
                work.curDate,
                work.pgmName,
                work.title02,
                work.curTime,
                work.actIdIn,
                work.addedTransactionId,
                work.cardNIn,
                work.tTypCd,
                work.tCatCd,
                work.trnSrc,
                work.tDesc,
                work.trnAmt,
                work.tOrigDt,
                work.tProcDt,
                work.mId,
                work.mName,
                work.mCity,
                work.mZip,
                work.errMsg,
                null,
                null,
                work.wsTranAmtN);

        work.taskEnded = true;
    }

    // ================================================================================================
    // Paragraph 9 of 18. Source: app/cbl/COTRN02C.cbl RECEIVE-TRNADD-SCREEN (:539-547).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code RECEIVE-TRNADD-SCREEN} ({@code :539-547}).
     * <p>
     * Reads the symbolic map input group. Over REST the map arrives as the request body, so this method
     * copies the twenty-one components into the work area and reports a normal response; there is no terminal
     * to interrogate, so the {@code RESP} and {@code RESP2} values of {@code :545-546} are recorded as normal
     * and zero and the source's absence of a {@code MAPFAIL} branch is preserved rather than a guard being
     * invented.
     * </p>
     * <p>
     * Each field is stored at its BMS width. A null request is treated as an all-blank map, which is what a
     * terminal transmitting nothing produces.
     * </p>
     *
     * @param work the per-invocation work area.
     * @param request the received map, or null when nothing was transmitted.
     */
    private void receiveTrnaddScreen(final ScreenWorkArea work, final TransactionAddRequest request) {
        work.wsRespCd = CICS_RESP_NORMAL;
        work.wsReasCd = CICS_REAS_NONE;

        if (request == null) {
            return;
        }

        work.actIdIn = fixedWidth(request.accountId(), ACCOUNT_ID_WIDTH);
        work.cardNIn = fixedWidth(request.cardNumber(), CARD_NUMBER_WIDTH);
        work.tTypCd = fixedWidth(request.typeCode(), TYPE_CODE_WIDTH);
        work.tCatCd = fixedWidth(request.categoryCode(), CATEGORY_CODE_WIDTH);
        work.trnSrc = fixedWidth(request.source(), SOURCE_WIDTH);
        work.tDesc = fixedWidth(request.description(), DESCRIPTION_INPUT_WIDTH);
        work.trnAmt = fixedWidth(request.amount(), AMOUNT_INPUT_WIDTH);
        work.tOrigDt = fixedWidth(request.originatingDate(), DATE_INPUT_WIDTH);
        work.tProcDt = fixedWidth(request.processingDate(), DATE_INPUT_WIDTH);
        work.mId = fixedWidth(request.merchantId(), MERCHANT_ID_WIDTH);
        work.mName = fixedWidth(request.merchantName(), MERCHANT_NAME_INPUT_WIDTH);
        work.mCity = fixedWidth(request.merchantCity(), MERCHANT_CITY_INPUT_WIDTH);
        work.mZip = fixedWidth(request.merchantZip(), MERCHANT_ZIP_WIDTH);
        work.confirm = fixedWidth(request.confirmation(), CONFIRMATION_WIDTH);
    }

    // ================================================================================================
    // Paragraph 10 of 18. Source: app/cbl/COTRN02C.cbl POPULATE-HEADER-INFO (:552-571).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code POPULATE-HEADER-INFO} ({@code :552-571}).
     * <p>
     * Fills the six header fields every screen in the corpus carries: the two titles at {@code :556-557}, the
     * transaction identifier at {@code :558}, the program name at {@code :559}, the date at {@code :565} and
     * the time at {@code :571}. The six fields are deliberately not extracted into a shared helper.
     * </p>
     * <p>
     * Documented deviation (Low): {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :554} is
     * the only clock read anywhere in this program, and it is presentation only. Because this program
     * generates no timestamp at all on the write path, the zero-clock constraint is absolute for this file,
     * so the header echoes the date and time text the request carried rather than consulting a clock. The
     * renderings are {@code MM/DD/YY} and {@code HH:MM:SS}, eight characters each, per
     * {@code app/cpy/CSDAT01Y.cpy:30-41}. Owed an entry in the planned {@code DECISION_LOG.md}.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void populateHeaderInfo(final ScreenWorkArea work) {
        work.title01 = fixedWidth(SCREEN_TITLE_01, TITLE_WIDTH);
        work.title02 = fixedWidth(SCREEN_TITLE_02, TITLE_WIDTH);
        work.trnName = fixedWidth(TRANSACTION_ID, TRANSACTION_NAME_WIDTH);
        work.pgmName = fixedWidth(PROGRAM_NAME, PROGRAM_NAME_WIDTH);
        work.curDate = truncate(work.requestCurrentDate, HEADER_DATE_WIDTH);
        work.curTime = truncate(work.requestCurrentTime, HEADER_TIME_WIDTH);
    }

    // ================================================================================================
    // Paragraph 11 of 18. Source: app/cbl/COTRN02C.cbl READ-CXACAIX-FILE (:576-604).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code READ-CXACAIX-FILE} ({@code :576-604}).
     * <p>
     * Reads the cross reference through the {@code CXACAIX} alternate index path, keyed on the account
     * identifier. Three outcomes: normal continues at {@code :589-590}; not found reports
     * {@code 'Account ID NOT found...'} at {@code :591-596}; anything else displays the response codes and
     * reports {@code 'Unable to lookup Acct in XREF AIX file...'} at {@code :597-603}.
     * </p>
     * <p>
     * The alternate index is non-unique, but an {@code EXEC CICS READ} through a path returns exactly the
     * <em>first</em> record carrying the alternate key, never a set. The derived finder therefore returns an
     * {@code Optional} and the database applies {@code LIMIT 1}; the {@code OrderByCardNumberAsc} clause is
     * what makes "the first record" deterministic when duplicates exist.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void readCxacaixFile(final ScreenWorkArea work) {
        final int response = execCxacaixRead(work);
        if (response == CICS_RESP_NORMAL) {
            return;
        }
        if (response == CICS_RESP_NOTFND) {
            work.errFlag = true;
            work.wsMessage = ACCOUNT_ID_NOT_FOUND_MESSAGE;
            work.cursorField = CURSOR_ACCOUNT_ID;
            work.pendingFailure = new RecordNotFoundException(ACCOUNT_ID_NOT_FOUND_MESSAGE,
                    LOGICAL_FILE_CXACAIX, renderZeroPadded(work.xrefAcctId, ACCOUNT_ID_WIDTH));
            sendTrnaddScreen(work);
            return;
        }
        work.errFlag = true;
        work.wsMessage = CXACAIX_LOOKUP_FAILURE_MESSAGE;
        work.cursorField = CURSOR_ACCOUNT_ID;
        work.pendingFailure =
                accessFailure(work, OPERATION_READ, LOGICAL_FILE_CXACAIX, CXACAIX_LOOKUP_FAILURE_MESSAGE);
        sendTrnaddScreen(work);
    }

    // ================================================================================================
    // Paragraph 12 of 18. Source: app/cbl/COTRN02C.cbl READ-CCXREF-FILE (:609-637).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code READ-CCXREF-FILE} ({@code :609-637}).
     * <p>
     * Reads the cross reference on its primary key, the card number. Three outcomes: normal continues at
     * {@code :622-623}; not found reports {@code 'Card Number NOT found...'} at {@code :624-629}; anything
     * else displays the response codes and reports {@code 'Unable to lookup Card # in XREF file...'} at
     * {@code :630-636}.
     * </p>
     * <p>
     * The not-found exception carries a redacted placeholder as its key rather than the card number, because
     * a card number must never reach an exception message, an exception key or a log statement.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void readCcxrefFile(final ScreenWorkArea work) {
        final int response = execCcxrefRead(work);
        if (response == CICS_RESP_NORMAL) {
            return;
        }
        if (response == CICS_RESP_NOTFND) {
            work.errFlag = true;
            work.wsMessage = CARD_NUMBER_NOT_FOUND_MESSAGE;
            work.cursorField = CURSOR_CARD_NUMBER;
            work.pendingFailure = new RecordNotFoundException(CARD_NUMBER_NOT_FOUND_MESSAGE,
                    LOGICAL_FILE_CCXREF, maskCardNumber(work.xrefCardNum));
            sendTrnaddScreen(work);
            return;
        }
        work.errFlag = true;
        work.wsMessage = CCXREF_LOOKUP_FAILURE_MESSAGE;
        work.cursorField = CURSOR_CARD_NUMBER;
        work.pendingFailure =
                accessFailure(work, OPERATION_READ, LOGICAL_FILE_CCXREF, CCXREF_LOOKUP_FAILURE_MESSAGE);
        sendTrnaddScreen(work);
    }

    // ================================================================================================
    // Paragraph 13 of 18. Source: app/cbl/COTRN02C.cbl STARTBR-TRANSACT-FILE (:642-668).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code STARTBR-TRANSACT-FILE} ({@code :642-668}).
     * <p>
     * Establishes the browse position on the transaction dataset at the key currently held, which both call
     * sites set to {@code HIGH-VALUES}. Three outcomes: normal continues at {@code :653-654}; not found
     * reports {@code 'Transaction ID NOT found...'} at {@code :655-660}; anything else displays the response
     * codes and reports {@code 'Unable to lookup Transaction...'} at {@code :661-667}, with the noun
     * capitalised exactly as the source writes it.
     * </p>
     * <p>
     * {@code EXEC CICS STARTBR} only positions a browse, and Spring Data JPA has no separate positioning
     * call because the descending top-one query both positions and reads. The exec layer therefore reports a
     * normal response, and the not-found and other branches are retained for paragraph completeness and
     * documented as unreachable in this target (Low; owed an entry in the planned {@code DECISION_LOG.md}).
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void startbrTransactFile(final ScreenWorkArea work) {
        final int response = execTransactStartbr(work);
        if (response == CICS_RESP_NORMAL) {
            return;
        }
        if (response == CICS_RESP_NOTFND) {
            work.errFlag = true;
            work.wsMessage = TRANSACTION_ID_NOT_FOUND_MESSAGE;
            work.cursorField = CURSOR_ACCOUNT_ID;
            work.pendingFailure = new RecordNotFoundException(TRANSACTION_ID_NOT_FOUND_MESSAGE,
                    LOGICAL_FILE_TRANSACT, work.tranRecord.tranId);
            sendTrnaddScreen(work);
            return;
        }
        work.errFlag = true;
        work.wsMessage = TRANSACTION_LOOKUP_FAILURE_MESSAGE;
        work.cursorField = CURSOR_ACCOUNT_ID;
        work.pendingFailure = accessFailure(work, OPERATION_STARTBR, LOGICAL_FILE_TRANSACT,
                TRANSACTION_LOOKUP_FAILURE_MESSAGE);
        sendTrnaddScreen(work);
    }

    // ================================================================================================
    // Paragraph 14 of 18. Source: app/cbl/COTRN02C.cbl READPREV-TRANSACT-FILE (:673-697).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code READPREV-TRANSACT-FILE} ({@code :673-697}).
     * <p>
     * Reads backwards from the browse position, so with the key at {@code HIGH-VALUES} it retrieves the
     * highest existing transaction. Three outcomes: normal continues at {@code :686-687}; end of file moves
     * zeros into the key at {@code :688-689}, which is a default path and never an error and is what makes
     * the first generated identifier {@code 0000000000000001}; anything else displays the response codes and
     * reports {@code 'Unable to lookup Transaction...'} at {@code :690-696}.
     * </p>
     * <p>
     * The end-of-file default is independently confirmed by the same idiom in the sibling program at
     * {@code app/cbl/COBIL00C.cbl:487-488}.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void readprevTransactFile(final ScreenWorkArea work) {
        final int response = execTransactReadprev(work);
        if (response == CICS_RESP_NORMAL) {
            return;
        }
        if (response == CICS_RESP_ENDFILE) {
            work.tranRecord.tranId = ZERO_FILLED_TRANSACTION_KEY;
            return;
        }
        work.errFlag = true;
        work.wsMessage = TRANSACTION_LOOKUP_FAILURE_MESSAGE;
        work.cursorField = CURSOR_ACCOUNT_ID;
        work.pendingFailure = accessFailure(work, OPERATION_READPREV, LOGICAL_FILE_TRANSACT,
                TRANSACTION_LOOKUP_FAILURE_MESSAGE);
        sendTrnaddScreen(work);
    }

    // ================================================================================================
    // Paragraph 15 of 18. Source: app/cbl/COTRN02C.cbl ENDBR-TRANSACT-FILE (:702-706).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code ENDBR-TRANSACT-FILE} ({@code :702-706}).
     * <p>
     * A plain {@code EXEC CICS ENDBR} with no response branching at all. It releases the browse position
     * established at {@code HIGH-VALUES}. There is no cursor to release in this target because the descending
     * top-one query has already completed by the time this runs, so the method records that the browse is no
     * longer open and emits a debug trace; that is the whole of its counterpart.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void endbrTransactFile(final ScreenWorkArea work) {
        if (work.browseActive) {
            work.browseActive = false;
            LOG.debug("{} ENDBR released the browse position on dataset '{}'",
                    TRANSACTION_ID, LOGICAL_FILE_TRANSACT);
        }
    }

    // ================================================================================================
    // Paragraph 16 of 18. Source: app/cbl/COTRN02C.cbl WRITE-TRANSACT-FILE (:711-749).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code WRITE-TRANSACT-FILE} ({@code :711-749}).
     * <p>
     * Writes the assembled record and evaluates three outcomes.
     * </p>
     * <ul>
     *   <li>Normal, {@code :724-734}: blank every field at {@code :725}, blank the message at {@code :726},
     *       set the message colour at {@code :727}, then compose the success message by {@code STRING} at
     *       {@code :728-733} and send. The composition is byte exact, including the trailing space after
     *       {@code successfully.} and the leading space before {@code Your}. The identifier operand is
     *       delimited by space, so it is emitted up to its first space, which for a zero-padded sixteen-digit
     *       identifier is the whole field. The field blanking at {@code :725} runs before the message is
     *       composed and does not touch the record, so the identifier is still available; the observable
     *       outcome is preserved.</li>
     *   <li>Duplicate, {@code :735-741}: {@code DUPKEY} and {@code DUPREC} share one branch reporting
     *       {@code 'Tran ID already exist...'}. This is where the retained identifier race surfaces, as
     *       {@link DuplicateRecordException}.</li>
     *   <li>Anything else, {@code :742-748}: display the response codes and report
     *       {@code 'Unable to Add Transaction...'}.</li>
     * </ul>
     * <p>
     * What has no counterpart: {@code MOVE DFHGREEN TO ERRMSGC} at {@code :727} sets a 3270 colour attribute
     * byte on the message field, and colour has no representation in a JSON response, so it is documented
     * here rather than modelled.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void writeTransactFile(final ScreenWorkArea work) {
        final String generatedIdentifier = work.tranRecord.tranId;
        final int response = execTransactWrite(work);

        if (response == CICS_RESP_NORMAL) {
            initializeAllFields(work);
            work.wsMessage = "";
            work.addedTransactionId = generatedIdentifier;
            work.wsMessage = ADDED_MESSAGE_PREFIX + ADDED_MESSAGE_INFIX
                    + delimitedBySpace(generatedIdentifier) + ADDED_MESSAGE_SUFFIX;
            work.outcome = Outcome.ADDED;
            sendTrnaddScreen(work);
            return;
        }

        if (response == CICS_RESP_DUPKEY || response == CICS_RESP_DUPREC) {
            work.errFlag = true;
            work.wsMessage = DUPLICATE_TRANSACTION_MESSAGE;
            work.cursorField = CURSOR_ACCOUNT_ID;
            work.pendingFailure = duplicateFailure(work, generatedIdentifier);
            sendTrnaddScreen(work);
            return;
        }

        work.errFlag = true;
        work.wsMessage = ADD_FAILURE_MESSAGE;
        work.cursorField = CURSOR_ACCOUNT_ID;
        work.pendingFailure =
                accessFailure(work, OPERATION_WRITE, LOGICAL_FILE_TRANSACT, ADD_FAILURE_MESSAGE);
        sendTrnaddScreen(work);
    }

    // ================================================================================================
    // Paragraph 17 of 18. Source: app/cbl/COTRN02C.cbl CLEAR-CURRENT-SCREEN (:754-757).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code CLEAR-CURRENT-SCREEN} ({@code :754-757}).
     * <p>
     * The PF4 path: blank every field at {@code :756} and send at {@code :757}. Two statements, nothing else.
     * The send terminates the task, so nothing follows it.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void clearCurrentScreen(final ScreenWorkArea work) {
        initializeAllFields(work);
        work.outcome = Outcome.SCREEN_CLEARED;
        sendTrnaddScreen(work);
    }

    // ================================================================================================
    // Paragraph 18 of 18. Source: app/cbl/COTRN02C.cbl INITIALIZE-ALL-FIELDS (:762-779).
    // ================================================================================================

    /**
     * Source: {@code app/cbl/COTRN02C.cbl} {@code INITIALIZE-ALL-FIELDS} ({@code :762-779}).
     * <p>
     * Positions the cursor on the account field at {@code :764}, then blanks fifteen items in one move at
     * {@code :765-779}: both key fields, all eleven data fields, the confirmation character and
     * {@code WS-MESSAGE}. It deliberately does not touch the record image, which is why the generated
     * identifier survives this call on the success path of {@code WRITE-TRANSACT-FILE}.
     * </p>
     *
     * @param work the per-invocation work area.
     */
    private void initializeAllFields(final ScreenWorkArea work) {
        work.cursorField = CURSOR_ACCOUNT_ID;
        work.actIdIn = "";
        work.cardNIn = "";
        work.tTypCd = "";
        work.tCatCd = "";
        work.trnSrc = "";
        work.trnAmt = "";
        work.tDesc = "";
        work.tOrigDt = "";
        work.tProcDt = "";
        work.mId = "";
        work.mName = "";
        work.mCity = "";
        work.mZip = "";
        work.confirm = "";
        work.wsMessage = "";
    }


    // ================================================================================================
    // The exec layer. One method per EXEC CICS command, reporting the response code the paragraph above
    // evaluates. Keeping the command distinct from the paragraph is what lets each paragraph body remain a
    // one-to-one rendering of its source EVALUATE.
    // ================================================================================================

    /**
     * {@code EXEC CICS READ DATASET(WS-CXACAIX-FILE)}, {@code app/cbl/COTRN02C.cbl:578-586}.
     * <p>
     * Reads through the non-unique {@code CXACAIX} alternate index path on the account identifier. The
     * derived finder returns a collection, and a path read returns the first record carrying the alternate
     * key, so the first element of the card-number-ascending ordering is taken. {@code XREF-CUST-ID} is part
     * of the record the read populates but is never referenced by this program, so it is not carried.
     * </p>
     *
     * @param work the per-invocation work area.
     * @return the {@code DFHRESP} equivalent: normal, not found, or an I/O error.
     */
    private int execCxacaixRead(final ScreenWorkArea work) {
        try {
            // LIMIT 1 at the database: a keyed read through the CXACAIX path yields one record, and only
            // the first was ever used here. See CardCrossReferenceRepository for the full reasoning.
            final Optional<CardCrossReference> found =
                    this.cardCrossReferenceRepository
                            .findFirstByAccountIdOrderByCardNumberAsc(work.xrefAcctId);
            if (found.isEmpty()) {
                return recordResponse(work, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
            final CardCrossReference match = found.get();
            work.xrefCardNum = nullToEmpty(match.getCardNumber());
            work.xrefAcctId = match.getAccountId() == null ? work.xrefAcctId : match.getAccountId();
            return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException cause) {
            work.ioFailureCause = cause;
            return recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * {@code EXEC CICS READ DATASET(WS-CCXREF-FILE)}, {@code app/cbl/COTRN02C.cbl:611-619}.
     * <p>
     * Reads the cross reference on its primary key, the card number. {@code XREF-CUST-ID} is populated by the
     * read but never referenced by this program, so it is not carried.
     * </p>
     *
     * @param work the per-invocation work area.
     * @return the {@code DFHRESP} equivalent: normal, not found, or an I/O error.
     */
    private int execCcxrefRead(final ScreenWorkArea work) {
        try {
            final Optional<CardCrossReference> match =
                    this.cardCrossReferenceRepository.findById(work.xrefCardNum);
            if (match.isEmpty()) {
                return recordResponse(work, CICS_RESP_NOTFND, IO_STATUS_RECORD_NOT_FOUND);
            }
            final CardCrossReference found = match.get();
            work.xrefCardNum = nullToEmpty(found.getCardNumber());
            work.xrefAcctId = found.getAccountId() == null ? 0L : found.getAccountId();
            return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException cause) {
            work.ioFailureCause = cause;
            return recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * {@code EXEC CICS STARTBR DATASET(WS-TRANSACT-FILE)}, {@code app/cbl/COTRN02C.cbl:644-650}.
     * <p>
     * The command only establishes a browse position. Spring Data JPA has no separate positioning call
     * because the descending top-one query of the following read both positions and reads, so this reports a
     * normal response and records that a browse is open. The not-found and other arms of the paragraph above
     * are consequently unreachable in this target and are retained for paragraph completeness (Low).
     * </p>
     *
     * @param work the per-invocation work area.
     * @return {@code DFHRESP(NORMAL)}.
     */
    private int execTransactStartbr(final ScreenWorkArea work) {
        work.browseActive = true;
        LOG.debug("{} STARTBR positioned dataset '{}' at the high-values key",
                TRANSACTION_ID, LOGICAL_FILE_TRANSACT);
        return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
    }

    /**
     * {@code EXEC CICS READPREV DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD)},
     * {@code app/cbl/COTRN02C.cbl:675-683}.
     * <p>
     * Reads backwards from the high-values position, which is the highest existing key, using the descending
     * top-one finder. An empty table reports end of file so the paragraph above can apply the zero default.
     * The whole record is loaded because the command names {@code INTO(TRAN-RECORD)} and
     * {@code COPY-LAST-TRAN-DATA} reads every field of it.
     * </p>
     *
     * @param work the per-invocation work area.
     * @return the {@code DFHRESP} equivalent: normal, end of file, or an I/O error.
     */
    private int execTransactReadprev(final ScreenWorkArea work) {
        try {
            final Optional<Transaction> previous =
                    this.transactionRepository.findFirstByOrderByTransactionIdDesc();
            if (previous.isEmpty()) {
                return recordResponse(work, CICS_RESP_ENDFILE, IO_STATUS_END_OF_FILE);
            }
            work.tranRecord.loadFrom(previous.get());
            return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataAccessException cause) {
            work.ioFailureCause = cause;
            return recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        }
    }

    /**
     * {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE) FROM(TRAN-RECORD)},
     * {@code app/cbl/COTRN02C.cbl:713-721}.
     * <p>
     * Inserts and flushes immediately. The flush is what makes the retained identifier race observable at the
     * same point the source observes it: without it the constraint violation would surface at commit, outside
     * the paragraph whose {@code DUPKEY}/{@code DUPREC} branch is supposed to report it.
     * </p>
     * <p>
     * A record image that violates the {@code CVTRA05Y} field contract is an abend condition rather than an
     * I/O condition, so it raises {@link FatalProcessingException} with abend code 999 directly, reproducing
     * the corpus abend idiom's non-local termination. It is reachable when this service is invoked directly
     * with a request that bypassed bean validation.
     * </p>
     *
     * @param work the per-invocation work area.
     * @return the {@code DFHRESP} equivalent: normal, duplicate record, or an I/O error.
     */
    private int execTransactWrite(final ScreenWorkArea work) {
        try {
            this.transactionRepository.saveAndFlush(work.tranRecord.toEntity());
            return recordResponse(work, CICS_RESP_NORMAL, IO_STATUS_SUCCESS);
        } catch (final DataIntegrityViolationException cause) {
            work.ioFailureCause = cause;
            return recordResponse(work, CICS_RESP_DUPREC, IO_STATUS_DUPLICATE_KEY);
        } catch (final DataAccessException cause) {
            work.ioFailureCause = cause;
            return recordResponse(work, CICS_RESP_IOERR, IO_STATUS_IO_ERROR);
        } catch (final IllegalArgumentException cause) {
            LOG.error("{} rejected the assembled TRAN-RECORD for dataset '{}': {}",
                    TRANSACTION_ID, LOGICAL_FILE_TRANSACT, ABEND_REASON_RECORD_CONTRACT, cause);
            throw new FatalProcessingException(ABEND_CODE, PROGRAM_NAME, ABEND_REASON_RECORD_CONTRACT,
                    ABEND_MESSAGE_RECORD_CONTRACT, cause);
        }
    }

    /**
     * Records the {@code RESP}, {@code RESP2} and file status the command produced, so that the paragraph
     * above can evaluate the response and any diagnostic can render the status.
     *
     * @param work the per-invocation work area.
     * @param responseCode the {@code DFHRESP} equivalent.
     * @param ioStatus the two-character file status.
     * @return the response code, so a caller can both record and return in one statement.
     */
    private static int recordResponse(final ScreenWorkArea work, final int responseCode,
                                      final String ioStatus) {
        work.wsRespCd = responseCode;
        work.wsReasCd = CICS_REAS_NONE;
        work.ioStatus = ioStatus;
        return responseCode;
    }

    // ================================================================================================
    // Failure construction and diagnostics.
    // ================================================================================================

    /**
     * Reproduces the recurring failure idiom of this program: set {@code WS-ERR-FLG}, move the message,
     * reposition the cursor and perform {@code SEND-TRNADD-SCREEN}, which returns from the task. It carries
     * the offending field name and never the field value, so no card number and no amount can leak.
     *
     * @param work the per-invocation work area.
     * @param screenMessage the byte-exact legacy message.
     * @param cursorField the symbolic map length field the source drives to -1.
     * @param fieldName the request component name the failure attaches to.
     * @param missing true when the field was not supplied at all, false when it was supplied and is wrong.
     */
    private void failValidation(final ScreenWorkArea work, final String screenMessage,
                                final String cursorField, final String fieldName, final boolean missing) {
        work.errFlag = true;
        work.wsMessage = screenMessage;
        work.cursorField = cursorField;
        work.pendingFailure = missing
                ? ValidationException.missingField(fieldName, screenMessage)
                : ValidationException.invalidField(fieldName, screenMessage);
        sendTrnaddScreen(work);
    }

    /**
     * Builds the file access failure for a {@code WHEN OTHER} arm, first emitting the diagnostic that
     * replaces the {@code DISPLAY 'RESP:' ... 'REAS:'} pair. The original cause is always preserved when one
     * exists, so no root cause is ever discarded.
     *
     * @param work the per-invocation work area.
     * @param operation the CICS command that failed.
     * @param logicalFileName the dataset name the command addressed.
     * @param screenMessage the byte-exact legacy message.
     * @return the exception to retain.
     */
    private FileAccessException accessFailure(final ScreenWorkArea work, final String operation,
                                              final String logicalFileName, final String screenMessage) {
        logIoDiagnostic(work, operation, logicalFileName);
        if (work.ioFailureCause == null) {
            return new FileAccessException(screenMessage, work.ioStatus, logicalFileName, operation);
        }
        return new FileAccessException(screenMessage, work.ioStatus, logicalFileName, operation,
                work.ioFailureCause);
    }

    /**
     * Builds the duplicate failure for the shared {@code DUPKEY}/{@code DUPREC} branch at
     * {@code app/cbl/COTRN02C.cbl:735-741}. This is the designed surface of the retained identifier race, so
     * the colliding key is reported to make the collision diagnosable; the key is a transaction identifier
     * and never a card number.
     *
     * @param work the per-invocation work area.
     * @param collidingKey the generated identifier that collided.
     * @return the exception to retain.
     */
    private DuplicateRecordException duplicateFailure(final ScreenWorkArea work, final String collidingKey) {
        logIoDiagnostic(work, OPERATION_WRITE, LOGICAL_FILE_TRANSACT);
        if (work.ioFailureCause == null) {
            return new DuplicateRecordException(DUPLICATE_TRANSACTION_MESSAGE, LOGICAL_FILE_TRANSACT,
                    collidingKey);
        }
        return new DuplicateRecordException(DUPLICATE_TRANSACTION_MESSAGE, LOGICAL_FILE_TRANSACT,
                collidingKey, work.ioFailureCause);
    }

    /**
     * Replaces {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}, which occurs at
     * {@code app/cbl/COTRN02C.cbl:598}, {@code :631}, {@code :662}, {@code :691} and {@code :743}, with one
     * structured statement. The file status is rendered through the shared mapper so the four-character
     * legacy layout is produced in exactly one place. No card number is emitted.
     *
     * @param work the per-invocation work area.
     * @param operation the CICS command that failed.
     * @param logicalFileName the dataset name the command addressed.
     */
    private void logIoDiagnostic(final ScreenWorkArea work, final String operation,
                                 final String logicalFileName) {
        LOG.warn("{} {} on dataset '{}' failed: resp={} reas={} {}", TRANSACTION_ID, operation,
                logicalFileName, work.wsRespCd, work.wsReasCd,
                this.fileStatusMapper.displayIoStatus(work.ioStatus));
    }

    // ================================================================================================
    // Numeric parsing. Two distinct paths, never one shared parser.
    // ================================================================================================

    /**
     * The strict digits-only path, the counterpart of {@code FUNCTION NUMVAL} at
     * {@code app/cbl/COTRN02C.cbl:204} and {@code :218}. Every call site first applies the COBOL
     * {@code IS NUMERIC} guard with its own byte-exact message, exactly as {@code :197} precedes {@code :204},
     * so the converter's own rejection is a defence in depth rather than the primary gate. When it does
     * reject, the legacy message is reported and the converter's exception is preserved as the cause.
     *
     * @param suppliedValue the map field content.
     * @param width the declared BMS width of that field.
     * @param fieldName the request component name to attach to a failure.
     * @param screenMessage the byte-exact legacy message to report on a failure.
     * @return the parsed value.
     */
    private long parseStrictIdentifier(final String suppliedValue, final int width,
                                       final String fieldName, final String screenMessage) {
        try {
            final Long parsed =
                    this.strictIdentifierConverter.convert(fixedWidth(suppliedValue, width).trim());
            if (parsed == null) {
                throw ValidationException.invalidField(fieldName, screenMessage);
            }
            return parsed.longValue();
        } catch (final ValidationException cause) {
            if (screenMessage.equals(cause.getMessage())) {
                throw cause;
            }
            throw new ValidationException(screenMessage, fieldName, ValidationException.FailureKind.INVALID,
                    cause);
        }
    }

    /**
     * The currency-aware path, the counterpart of {@code FUNCTION NUMVAL-C} at
     * {@code app/cbl/COTRN02C.cbl:383-384} and again at {@code :456-457}. It additionally tolerates currency
     * symbols and thousands separators, which is why it is applied to the amount and to nothing else. The
     * sign is never normalised: a negative amount is genuine and the positional mask explicitly admits a
     * leading minus.
     *
     * @param suppliedValue the amount field content, already known to satisfy the positional mask.
     * @return the parsed amount, scaled to two decimals with half-even rounding.
     */
    private BigDecimal parseCurrencyAmount(final String suppliedValue) {
        try {
            final BigDecimal parsed =
                    this.currencyAwareAmountConverter.convert(fixedWidth(suppliedValue, AMOUNT_INPUT_WIDTH)
                            .trim());
            if (parsed == null) {
                throw ValidationException.invalidField(FIELD_AMOUNT, AMOUNT_FORMAT_MESSAGE);
            }
            return parsed;
        } catch (final ValidationException cause) {
            if (AMOUNT_FORMAT_MESSAGE.equals(cause.getMessage())) {
                throw cause;
            }
            throw new ValidationException(AMOUNT_FORMAT_MESSAGE, FIELD_AMOUNT,
                    ValidationException.FailureKind.INVALID, cause);
        }
    }

    // ================================================================================================
    // COBOL primitives. Each renders one language behaviour the source relies on.
    // ================================================================================================

    /**
     * Applies the {@code 2513} tolerance of {@code app/cbl/COTRN02C.cbl:397-407} and {@code :417-427}: accept
     * when the severity code is {@code '0000'}, otherwise accept only when the message number is
     * {@code '2513'}, the unsupported-range feedback condition. The tolerance is neither tightened nor
     * widened, and it is the only non-zero severity accepted.
     *
     * @param outcome the immutable result the date validation service returned. Outcomes are return values,
     *     never exceptions, so this method never catches anything.
     * @return true when the source would have continued.
     */
    private static boolean isDateAccepted(final DateValidationService.DateValidationResult outcome) {
        if (SEVERITY_CODE_OK.equals(outcome.severityCode())) {
            return true;
        }
        return TOLERATED_MESSAGE_NUMBER.equals(outcome.messageNumber());
    }

    /**
     * The positional amount mask of {@code app/cbl/COTRN02C.cbl:339-351}, transcribed condition by condition
     * rather than re-expressed as a pattern, because a pattern would change which inputs pass. Position one
     * must be a plus or a minus, positions two to nine must be digits, position ten must be a decimal point
     * and positions eleven and twelve must be digits. The budget is one plus eight plus one plus two, which
     * is the twelve characters the field declares and independently confirms the eight-digit integer part.
     *
     * @param suppliedValue the amount field content.
     * @return true when all four conditions hold.
     */
    private static boolean matchesAmountMask(final String suppliedValue) {
        final String fixed = fixedWidth(suppliedValue, AMOUNT_INPUT_WIDTH);
        final char sign = charAtPosition(fixed, AMOUNT_SIGN_POSITION);
        if (sign != MINUS_SIGN && sign != PLUS_SIGN) {
            return false;
        }
        if (!isDigitRun(fixed, AMOUNT_INTEGER_POSITION, AMOUNT_INTEGER_MASK_DIGITS)) {
            return false;
        }
        if (charAtPosition(fixed, AMOUNT_POINT_POSITION) != DECIMAL_POINT) {
            return false;
        }
        return isDigitRun(fixed, AMOUNT_FRACTION_POSITION, AMOUNT_FRACTION_DIGITS);
    }

    /**
     * The positional date mask of {@code app/cbl/COTRN02C.cbl:353-366} for the originating date and
     * {@code :368-381} for the processing date. Both masks are identical in shape: four digits, a hyphen, two
     * digits, a hyphen, two digits. This is a purely positional gate and is separate from the semantic
     * validation of stages eight and nine; both gates run.
     *
     * @param suppliedValue the date field content.
     * @return true when all five conditions hold.
     */
    private static boolean matchesDateMask(final String suppliedValue) {
        final String fixed = fixedWidth(suppliedValue, DATE_INPUT_WIDTH);
        if (!isDigitRun(fixed, DATE_YEAR_POSITION, DATE_YEAR_DIGITS)) {
            return false;
        }
        if (charAtPosition(fixed, DATE_FIRST_SEPARATOR_POSITION) != DATE_SEPARATOR) {
            return false;
        }
        if (!isDigitRun(fixed, DATE_MONTH_POSITION, DATE_COMPONENT_DIGITS)) {
            return false;
        }
        if (charAtPosition(fixed, DATE_SECOND_SEPARATOR_POSITION) != DATE_SEPARATOR) {
            return false;
        }
        return isDigitRun(fixed, DATE_DAY_POSITION, DATE_COMPONENT_DIGITS);
    }

    /**
     * The COBOL {@code IS NUMERIC} class test on an alphanumeric field: true only when every character of the
     * field is an ASCII digit, so an incompletely keyed field containing trailing spaces is not numeric.
     * <p>
     * The value is first taken to its declared BMS width, because
     * {@code app/bms/COTRN02.bms:85-90} declares these fields with neither {@code ATTRB=NUM} nor
     * {@code PICIN} nor {@code JUSTIFY}, so a terminal transmits an incompletely keyed alphanumeric field
     * left-justified and space-padded. A short numeric value therefore fails here exactly as it fails on the
     * 3270, which is why leading zeros are significant on the wire.
     * </p>
     *
     * @param suppliedValue the map field content.
     * @param width the declared BMS width of that field.
     * @return true when the whole field is digits.
     */
    private static boolean isCobolNumeric(final String suppliedValue, final int width) {
        return isDigitRun(fixedWidth(suppliedValue, width), 1, width);
    }

    /**
     * Tests a one-based character run for ASCII digits, the counterpart of a COBOL reference-modified
     * {@code IS NUMERIC} test such as {@code TRNAMTI(2:8) NOT NUMERIC}. Non-ASCII Unicode digits are rejected
     * deliberately, matching the strict converter, because a COBOL class test admits only the digits of the
     * native character set.
     *
     * @param fixedValue the field content, already taken to its declared width.
     * @param position the one-based start position.
     * @param length the number of characters to test.
     * @return true when every character in the run is an ASCII digit, false when the run is empty or falls
     *     outside the field.
     */
    private static boolean isDigitRun(final String fixedValue, final int position, final int length) {
        if (length <= 0 || position < 1 || position - 1 + length > fixedValue.length()) {
            return false;
        }
        for (int offset = position - 1; offset < position - 1 + length; offset++) {
            final char candidate = fixedValue.charAt(offset);
            if (candidate < '0' || candidate > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads a one-based character, the counterpart of a COBOL reference modification of length one such as
     * {@code TRNAMTI(10:1)}.
     *
     * @param fixedValue the field content, already taken to its declared width.
     * @param position the one-based position.
     * @return the character, or a space when the position falls outside the field, which is what a
     *     space-padded fixed-width field yields.
     */
    private static char charAtPosition(final String fixedValue, final int position) {
        if (position < 1 || position > fixedValue.length()) {
            return SPACE;
        }
        return fixedValue.charAt(position - 1);
    }

    /**
     * The counterpart of {@code NOT = SPACES AND LOW-VALUES}, the condition that opens both arms of
     * {@code VALIDATE-INPUT-KEY-FIELDS} and guards the deep link at {@code app/cbl/COTRN02C.cbl:124-125}.
     *
     * @param suppliedValue the map field content, permitted to be null.
     * @return true when the field carries something other than spaces or low values.
     */
    private static boolean isSupplied(final String suppliedValue) {
        return !isBlankOrUnset(suppliedValue);
    }

    /**
     * The counterpart of {@code = SPACES OR LOW-VALUES}, the condition every stage-two check uses. An empty
     * string stands in for low values and a run of spaces stands in for spaces; both satisfy the condition,
     * exactly as they do in the source.
     *
     * @param suppliedValue the map field content, permitted to be null.
     * @return true when the field is null, empty or entirely spaces.
     */
    private static boolean isBlankOrUnset(final String suppliedValue) {
        if (suppliedValue == null || suppliedValue.isEmpty()) {
            return true;
        }
        for (int offset = 0; offset < suppliedValue.length(); offset++) {
            if (suppliedValue.charAt(offset) != SPACE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Substitutes an empty string for a null, so that a null request component is treated as low values
     * rather than propagating a null through the fixed-width helpers.
     *
     * @param value the value, permitted to be null.
     * @return the value, or an empty string when it was null.
     */
    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    /**
     * A COBOL alphanumeric move into a field of exactly this width: truncate on the right when the value is
     * longer, right-space-pad when it is shorter. This is the single primitive behind all four widening moves
     * of {@code ADD-TRANSACTION} and behind taking a received field to its declared BMS width.
     *
     * @param value the sending field, permitted to be null.
     * @param width the receiving field width, which must be positive.
     * @return a string of exactly {@code width} characters.
     */
    private static String fixedWidth(final String value, final int width) {
        final String truncated = truncate(value, width);
        if (truncated.length() == width) {
            return truncated;
        }
        final StringBuilder padded = new StringBuilder(width).append(truncated);
        while (padded.length() < width) {
            padded.append(SPACE);
        }
        return padded.toString();
    }

    /**
     * A COBOL alphanumeric move into a narrower field: keep the leftmost characters and discard the rest.
     * This is the primitive behind the truncating moves of {@code COPY-LAST-TRAN-DATA} at
     * {@code app/cbl/COTRN02C.cbl:486-491} and behind taking {@code WS-MESSAGE}, eighty characters wide, into
     * the seventy-eight-character error line at {@code :520}.
     *
     * @param value the sending field, permitted to be null.
     * @param width the maximum number of characters to keep, which must not be negative.
     * @return the truncated value, never null.
     */
    private static String truncate(final String value, final int width) {
        final String present = nullToEmpty(value);
        if (present.length() <= width) {
            return present;
        }
        return present.substring(0, width);
    }

    /**
     * A COBOL move from a numeric display field into an alphanumeric field of the same digit count, which
     * zero-pads on the left. It backs the normalised account and card echoes at
     * {@code app/cbl/COTRN02C.cbl:206-207} and {@code :220-221} and the generated identifier at {@code :451}.
     * A value with more digits than the field holds is truncated on the left, keeping the low-order digits,
     * exactly as the COBOL move does.
     *
     * @param value the numeric value, which is never negative on any path in this program.
     * @param width the receiving field width.
     * @return a string of exactly {@code width} characters.
     */
    private static String renderZeroPadded(final long value, final int width) {
        final String digits = Long.toString(value);
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return ZERO_DIGIT.repeat(width - digits.length()) + digits;
    }

    /**
     * A COBOL move from an alphanumeric field into a numeric display field, as at
     * {@code app/cbl/COTRN02C.cbl:448} where {@code TRAN-ID} is moved into {@code WS-TRAN-ID-N}. Only the
     * digits of the sending field participate, and an all-non-digit field yields zero, which is what makes
     * the end-of-file default of {@code :689} produce a first identifier of one.
     *
     * @param value the sending field, permitted to be null.
     * @return the numeric value.
     */
    private static long digitsToLong(final String value) {
        final String present = nullToEmpty(value);
        final StringBuilder digits = new StringBuilder(present.length());
        for (int offset = 0; offset < present.length(); offset++) {
            final char candidate = present.charAt(offset);
            if (candidate >= '0' && candidate <= '9') {
                digits.append(candidate);
            }
        }
        if (digits.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(digits.toString());
    }

    /**
     * The {@code DELIMITED BY SPACE} operand behaviour of the {@code STRING} statement at
     * {@code app/cbl/COTRN02C.cbl:731}: contribute the sending field up to, but not including, its first
     * space. For a zero-padded sixteen-digit identifier that is the whole field.
     *
     * @param value the sending field, permitted to be null.
     * @return the value up to its first space.
     */
    private static String delimitedBySpace(final String value) {
        final String present = nullToEmpty(value);
        final int firstSpace = present.indexOf(SPACE);
        if (firstSpace < 0) {
            return present;
        }
        return present.substring(0, firstSpace);
    }

    /**
     * Replaces a card number with a placeholder. Card numbers flow through nearly every method of this class,
     * so no diagnostic, exception message or exception key may ever carry one.
     *
     * @param cardNumber the card number, permitted to be null.
     * @return a placeholder that distinguishes present from absent without revealing anything.
     */
    private static String maskCardNumber(final String cardNumber) {
        if (isBlankOrUnset(cardNumber)) {
            return CARD_NUMBER_ABSENT;
        }
        return CARD_NUMBER_REDACTED;
    }


    // ================================================================================================
    // Per-invocation state. Every legacy working-storage item and every symbolic map field lives here, on
    // an object created inside the entry point, so nothing is ever held on the bean. There is no static
    // mutable state anywhere in this class, and in particular no static counter behind identifier
    // generation: WS-TRAN-ID-N is per-invocation working storage in the source and is a local here.
    // ================================================================================================

    /**
     * Stands in for the working-storage section of {@code app/cbl/COTRN02C.cbl:34-103} together with the
     * symbolic map group {@code COTRN2AI}, which {@code app/cpy-bms/COTRN02.CPY:145} redefines as
     * {@code COTRN2AO}. Because the input and output groups are redefinitions of one storage area, one set of
     * fields serves both directions here, exactly as one storage area serves both there.
     * <p>
     * Fields are deliberately mutable and package-private within this file: this is a data carrier standing in
     * for a COBOL storage area, not an abstraction. It is created per invocation and never shared.
     * </p>
     */
    private static final class ScreenWorkArea {
        /**
         * Creates the work area with every member at its post-{@code INITIALIZE} value, which is the state
         * the legacy {@code WORKING-STORAGE SECTION} begins each task in. Declared explicitly rather than
         * left implicit so the surface is documented; it takes no argument and performs no work.
         */
        private ScreenWorkArea() {
            // Every member carries its initial value in its own declaration above, exactly as a COBOL
            // VALUE clause does, so there is nothing for this constructor to assign.
        }

        /** {@code EIBAID}, evaluated at {@code app/cbl/COTRN02C.cbl:133}. */
        AttentionIdentifier attentionIdentifier = AttentionIdentifier.OTHER;

        /** {@code CDEMO-PGM-REENTER}, tested at {@code app/cbl/COTRN02C.cbl:120}. */
        boolean reentered;

        /** {@code EIBCALEN} being non-zero, tested at {@code app/cbl/COTRN02C.cbl:115}. */
        boolean commAreaPresent;

        /** {@code CDEMO-CT02-TRN-SELECTED}, tested at {@code app/cbl/COTRN02C.cbl:124-125}. */
        String selectedTransactionIdentifier;

        /** The date text the request carried, used by the header echo in place of a clock read. */
        String requestCurrentDate = "";

        /** The time text the request carried, used by the header echo in place of a clock read. */
        String requestCurrentTime = "";

        /** {@code TRNNAMEO}, {@code app/cbl/COTRN02C.cbl:558}. */
        String trnName = "";

        /** {@code TITLE01O}, {@code app/cbl/COTRN02C.cbl:556}. */
        String title01 = "";

        /** {@code CURDATEO}, {@code app/cbl/COTRN02C.cbl:565}, rendered {@code MM/DD/YY}. */
        String curDate = "";

        /** {@code PGMNAMEO}, {@code app/cbl/COTRN02C.cbl:559}. */
        String pgmName = "";

        /** {@code TITLE02O}, {@code app/cbl/COTRN02C.cbl:557}. */
        String title02 = "";

        /** {@code CURTIMEO}, {@code app/cbl/COTRN02C.cbl:571}, rendered {@code HH:MM:SS}. */
        String curTime = "";

        /** {@code ACTIDINI}, eleven characters. */
        String actIdIn = "";

        /** {@code CARDNINI}, sixteen characters. Never logged and never placed in an exception. */
        String cardNIn = "";

        /** {@code TTYPCDI}, two characters. */
        String tTypCd = "";

        /** {@code TCATCDI}, four characters. */
        String tCatCd = "";

        /** {@code TRNSRCI}, ten characters. */
        String trnSrc = "";

        /** {@code TDESCI}, sixty characters. */
        String tDesc = "";

        /** {@code TRNAMTI}, twelve characters. */
        String trnAmt = "";

        /** {@code TORIGDTI}, ten characters. */
        String tOrigDt = "";

        /** {@code TPROCDTI}, ten characters. */
        String tProcDt = "";

        /** {@code MIDI}, nine characters. */
        String mId = "";

        /** {@code MNAMEI}, thirty characters. */
        String mName = "";

        /** {@code MCITYI}, twenty-five characters. */
        String mCity = "";

        /** {@code MZIPI}, ten characters. */
        String mZip = "";

        /** {@code CONFIRMI}, one raw character, never a boolean. */
        String confirm = "";

        /** {@code ERRMSGO}, seventy-eight characters, {@code app/cbl/COTRN02C.cbl:520}. */
        String errMsg = "";

        /** {@code WS-MESSAGE PIC X(80)}, {@code app/cbl/COTRN02C.cbl:38}. */
        String wsMessage = "";

        /** {@code WS-ERR-FLG}, {@code app/cbl/COTRN02C.cbl:44-46}. */
        boolean errFlag;

        /** {@code WS-RESP-CD}, {@code app/cbl/COTRN02C.cbl:47}. */
        int wsRespCd = CICS_RESP_NORMAL;

        /** {@code WS-REAS-CD}, {@code app/cbl/COTRN02C.cbl:48}. */
        int wsReasCd = CICS_REAS_NONE;

        /** The two-character file status the last command produced, for rendering and translation. */
        String ioStatus = IO_STATUS_SUCCESS;

        /** The originating data access failure, preserved as the cause of any exception raised from it. */
        Throwable ioFailureCause;

        /** {@code WS-TRAN-AMT-N PIC S9(9)V99}, {@code app/cbl/COTRN02C.cbl:58}. */
        BigDecimal wsTranAmtN;

        /** {@code WS-TRAN-AMT-E PIC +99999999.99}, {@code app/cbl/COTRN02C.cbl:59}, twelve characters. */
        String wsTranAmtE = "";

        /** Whether a browse is open, so that {@code ENDBR-TRANSACT-FILE} has something to release. */
        boolean browseActive;

        /** {@code XREF-CARD-NUM PIC X(16)}, {@code app/cpy/CVACT03Y.cpy}. Never logged. */
        String xrefCardNum = "";

        /** {@code XREF-ACCT-ID PIC 9(11)}, {@code app/cpy/CVACT03Y.cpy}. */
        long xrefAcctId;

        /** {@code TRAN-RECORD}, {@code app/cpy/CVTRA05Y.cpy}, the 350-byte record image. */
        final TranRecord tranRecord = new TranRecord();

        /** {@code CDEMO-FROM-TRANID}, set at {@code app/cbl/COTRN02C.cbl:505}. */
        String cdemoFromTranId = "";

        /** {@code CDEMO-FROM-PROGRAM}, tested at {@code app/cbl/COTRN02C.cbl:137} and set at {@code :506}. */
        String cdemoFromProgram = "";

        /** {@code CDEMO-TO-PROGRAM}, set at {@code app/cbl/COTRN02C.cbl:116}, {@code :138} and {@code :503}. */
        String cdemoToProgram = "";

        /**
         * Whether {@code EXEC CICS RETURN} has executed. It is the counterpart of the task termination inside
         * {@code SEND-TRNADD-SCREEN} at {@code app/cbl/COTRN02C.cbl:530-534}, and every caller honours it
         * before continuing, which is what makes validation fail fast.
         */
        boolean taskEnded;

        /** The failure the terminating branch recorded, thrown at the API boundary. */
        CardDemoException pendingFailure;

        /** The map image {@code EXEC CICS SEND MAP} would have transmitted. */
        TransactionDto screen;

        /** How the task terminated. */
        Outcome outcome = Outcome.SCREEN_DISPLAYED;

        /** The generated identifier, populated only on the success path of {@code WRITE-TRANSACT-FILE}. */
        String addedTransactionId;

        /** The symbolic map length field the source drove to -1. */
        String cursorField;
    }

    /**
     * The {@code CVTRA05Y} transaction record image, {@code TRAN-RECORD}, 350 bytes with the offset map
     * identifier 1-16, type 17-18, category 19-22, source 23-32, description 33-132, amount 133-143, merchant
     * identifier 144-152, merchant name 153-202, merchant city 203-252, merchant postal code 253-262, card
     * number 263-278, originating timestamp 279-304, processing timestamp 305-330 and a trailing twenty-byte
     * filler 331-350 that is not modelled as a field.
     * <p>
     * Holding the record image separately from the map is what lets {@code MOVE HIGH-VALUES TO TRAN-ID},
     * {@code INITIALIZE TRAN-RECORD} and the field-by-field moves of {@code ADD-TRANSACTION} be rendered one
     * statement at a time, and it is why the generated identifier survives {@code INITIALIZE-ALL-FIELDS} on
     * the success path.
     * </p>
     */
    private static final class TranRecord {

        /** {@code TRAN-AMT S9(09)V99} at rest, two decimals, never a floating-point type. */
        private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(WebConfig.AMOUNT_SCALE);

        /** {@code TRAN-ID PIC X(16)}, record bytes 1-16. */
        String tranId;

        /** {@code TRAN-TYPE-CD PIC X(02)}, record bytes 17-18. */
        String tranTypeCd;

        /** {@code TRAN-CAT-CD PIC 9(04)}, record bytes 19-22. */
        int tranCatCd;

        /** {@code TRAN-SOURCE PIC X(10)}, record bytes 23-32. Free text, never an enum. */
        String tranSource;

        /** {@code TRAN-DESC PIC X(100)}, record bytes 33-132. */
        String tranDesc;

        /** {@code TRAN-AMT PIC S9(09)V99}, record bytes 133-143, mapped to {@code NUMERIC(11,2)}. */
        BigDecimal tranAmt;

        /** {@code TRAN-MERCHANT-ID PIC 9(09)}, record bytes 144-152. */
        long tranMerchantId;

        /** {@code TRAN-MERCHANT-NAME PIC X(50)}, record bytes 153-202. */
        String tranMerchantName;

        /** {@code TRAN-MERCHANT-CITY PIC X(50)}, record bytes 203-252. */
        String tranMerchantCity;

        /** {@code TRAN-MERCHANT-ZIP PIC X(10)}, record bytes 253-262. */
        String tranMerchantZip;

        /** {@code TRAN-CARD-NUM PIC X(16)}, record bytes 263-278. Never logged. */
        String tranCardNum;

        /**
         * {@code TRAN-ORIG-TS PIC X(26)}, record bytes 279-304. Text, not a temporal type. On this program's
         * write path it is the validated ten-character date followed by sixteen spaces.
         */
        String tranOrigTs;

        /**
         * {@code TRAN-PROC-TS PIC X(26)}, record bytes 305-330. Text, not a temporal type, populated the same
         * way as the originating timestamp.
         */
        String tranProcTs;

        /** Creates the record image in its initialised state. */
        TranRecord() {
            initialize();
        }

        /**
         * {@code INITIALIZE TRAN-RECORD}, {@code app/cbl/COTRN02C.cbl:450}: every alphanumeric item becomes
         * spaces at its declared width and every numeric item becomes zero. It also spaces the trailing
         * twenty-byte filler, which is not modelled as a field, so that space fill is implicit in the
         * fixed-width emission layer rather than represented here.
         */
        void initialize() {
            this.tranId = fixedWidth("", TRANSACTION_ID_WIDTH);
            this.tranTypeCd = fixedWidth("", TYPE_CODE_WIDTH);
            this.tranCatCd = 0;
            this.tranSource = fixedWidth("", SOURCE_WIDTH);
            this.tranDesc = fixedWidth("", DESCRIPTION_RECORD_WIDTH);
            this.tranAmt = ZERO_AMOUNT;
            this.tranMerchantId = 0L;
            this.tranMerchantName = fixedWidth("", MERCHANT_NAME_RECORD_WIDTH);
            this.tranMerchantCity = fixedWidth("", MERCHANT_CITY_RECORD_WIDTH);
            this.tranMerchantZip = fixedWidth("", MERCHANT_ZIP_WIDTH);
            this.tranCardNum = fixedWidth("", CARD_NUMBER_WIDTH);
            this.tranOrigTs = fixedWidth("", TIMESTAMP_WIDTH);
            this.tranProcTs = fixedWidth("", TIMESTAMP_WIDTH);
        }

        /**
         * The {@code INTO(TRAN-RECORD)} clause of {@code EXEC CICS READPREV} at
         * {@code app/cbl/COTRN02C.cbl:677}: replaces the whole record image with the retrieved record. Every
         * field is taken to its declared record width so that the subsequent truncating moves of
         * {@code COPY-LAST-TRAN-DATA} operate on the same geometry the source operates on.
         *
         * @param entity the retrieved row.
         */
        void loadFrom(final Transaction entity) {
            this.tranId = fixedWidth(entity.getTransactionId(), TRANSACTION_ID_WIDTH);
            this.tranTypeCd = fixedWidth(entity.getTypeCode(), TYPE_CODE_WIDTH);
            this.tranCatCd = entity.getCategoryCode() == null ? 0 : entity.getCategoryCode().intValue();
            this.tranSource = fixedWidth(entity.getTransactionSource(), SOURCE_WIDTH);
            this.tranDesc = fixedWidth(entity.getDescription(), DESCRIPTION_RECORD_WIDTH);
            this.tranAmt = entity.getAmount() == null ? ZERO_AMOUNT : entity.getAmount();
            this.tranMerchantId = entity.getMerchantId() == null ? 0L : entity.getMerchantId().longValue();
            this.tranMerchantName = fixedWidth(entity.getMerchantName(), MERCHANT_NAME_RECORD_WIDTH);
            this.tranMerchantCity = fixedWidth(entity.getMerchantCity(), MERCHANT_CITY_RECORD_WIDTH);
            this.tranMerchantZip = fixedWidth(entity.getMerchantZip(), MERCHANT_ZIP_WIDTH);
            this.tranCardNum = fixedWidth(entity.getCardNumber(), CARD_NUMBER_WIDTH);
            this.tranOrigTs = fixedWidth(entity.getOrigTs(), TIMESTAMP_WIDTH);
            this.tranProcTs = fixedWidth(entity.getProcTs(), TIMESTAMP_WIDTH);
        }

        /**
         * The {@code FROM(TRAN-RECORD)} clause of {@code EXEC CICS WRITE} at
         * {@code app/cbl/COTRN02C.cbl:715}: presents the record image as the row to insert. The entity's own
         * width and range guards enforce the {@code CVTRA05Y} contract, and a violation is an abend condition
         * rather than an I/O condition.
         *
         * @return the row to insert.
         */
        Transaction toEntity() {
            return new Transaction(this.tranId,
                    this.tranTypeCd,
                    Integer.valueOf(this.tranCatCd),
                    this.tranSource,
                    this.tranDesc,
                    this.tranAmt,
                    Long.valueOf(this.tranMerchantId),
                    this.tranMerchantName,
                    this.tranMerchantCity,
                    this.tranMerchantZip,
                    this.tranCardNum,
                    this.tranOrigTs,
                    this.tranProcTs);
        }
    }
}
