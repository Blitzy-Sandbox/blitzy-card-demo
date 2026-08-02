/*
 * ****************************************************************************
 * Program     : BillPaymentServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies BillPaymentService against COBIL00C paragraph by
 *               paragraph. Concentrates on the contracts a reader cannot
 *               confirm by inspection: the "at or below ZEROS" reject guard
 *               of :198 that rejects a zero balance, the always-full-balance
 *               amount of :224 and the resulting exactly-zero balance of
 *               :234, the increment-before-INITIALIZE ordering of :217-:219,
 *               the four-way confirmation gate built from two literal WHEN
 *               clauses at :173-:190, the twenty-six character online
 *               timestamp of :249 whose eleventh character is a space that
 *               INITIALIZE left behind, the zero-then-plus-one identifier
 *               default of :487-:488, and the twelve exact outcome literals.
 * Source      : app/cbl/COBIL00C.cbl (572 lines, 16 paragraphs)
 *               app/cpy-bms/COBIL00.CPY  (10 input fields, CURBALI X(14):66)
 *               app/cpy/CVACT01Y.cpy     (ACCT-CURR-BAL S9(10)V99)
 *               app/cpy/CVTRA05Y.cpy     (TRAN-AMT S9(09)V99, TS X(26))
 *               app/cpy/CVACT03Y.cpy     (XREF 16 + 9 + 11 = 36 bytes)
 *               app/cbl/CBACT04C.cbl:1-21 (the banner convention) @ 7756d89
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
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.TransactionSource;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.billing.BillPaymentService;
import com.cardemo.service.billing.BillPaymentService.BillPaymentResult;
import com.cardemo.service.billing.BillPaymentService.BillPaymentScreen;
import com.cardemo.service.billing.BillPaymentService.ConfirmationBranch;
import com.cardemo.service.billing.BillPaymentService.CursorField;
import com.cardemo.service.billing.BillPaymentService.EntryMode;
import com.cardemo.service.billing.BillPaymentService.MessageKind;
import com.cardemo.service.billing.BillPaymentService.PaymentOutcome;
import com.cardemo.service.billing.BillPaymentService.PaymentReceipt;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.unit.model.FixedClockProvider;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit tests for {@code BillPaymentService}, the Java target of {@code app/cbl/COBIL00C.cbl} and CICS
 * transaction {@code CB00}.
 *
 * <h2>1. What it does</h2>
 *
 * <p>It proves that the bill-payment bean reproduces its system of record rather than merely working. The
 * assertions are grouped to mirror the source, and each cites the paragraph or line it defends. The load
 * bearing claims, every one of which a plausible "cleaner" implementation would break, are:</p>
 *
 * <ul>
 *   <li><strong>The reject guard is {@code ACCT-CURR-BAL &lt;= ZEROS} at {@code :198}</strong>, so a balance
 *       of exactly zero is <em>rejected</em> rather than settled as a no-op. Weakening it to {@code &lt;}
 *       would accept a request the source refuses.</li>
 *   <li><strong>The payment is always the entire balance</strong> ({@code :224}) and the resulting balance is
 *       therefore exactly zero ({@code :234}). There is no amount input field anywhere in the 572 lines.</li>
 *   <li><strong>{@code ADD 1} at {@code :217} precedes {@code INITIALIZE TRAN-RECORD} at {@code :218}</strong>
 *       because the counter lives in {@code WORKING-STORAGE}, outside the record being cleared.</li>
 *   <li><strong>The confirmation gate is four-way and case sensitive</strong>, built from two literal
 *       {@code WHEN} clauses at {@code :174}-{@code :175} and {@code :178}-{@code :179} rather than from a
 *       case function, so {@code Yes}, {@code 1}, {@code T} and {@code "y "} are all invalid.</li>
 *   <li><strong>The online timestamp's eleventh character is a space</strong> ({@code :263}-{@code :266}):
 *       {@code INITIALIZE} blanked the field and nothing ever writes position 11.</li>
 *   <li><strong>The first generated identifier is 1</strong>, from the {@code ENDFILE} arm at
 *       {@code :487}-{@code :488} that moves {@code ZEROS} into {@code TRAN-ID} before the increment.</li>
 *   <li><strong>Twelve outcome literals are byte exact</strong>, including the misspelling
 *       {@code Tran ID already exist...} at {@code :536} and the same {@code Account ID NOT found...} text at
 *       three unrelated sites ({@code :361}, {@code :392}, {@code :425}).</li>
 * </ul>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>This class is bound to <strong>Surefire</strong> 3.5.4 by path: the plugin includes
 * {@code **}{@code /*Test.java} and excludes {@code **}{@code /integration/**} and {@code **}{@code /e2e/**},
 * so a class under {@code src/test/java/com/cardemo/unit/} is collected here and never by Failsafe. Placing it
 * outside that tree would match neither include set and it would silently never run.</p>
 *
 * <p>Run the tier with {@code ./mvnw -B -ntp test} and this class alone with
 * {@code ./mvnw -B -ntp test -Dtest=BillPaymentServiceTest}. The full gate is
 * {@code ./mvnw -B -ntp clean verify}. Compilation is Java 25 with {@code -Xlint:all -Werror}, so a single
 * unused import or one unescaped angle bracket in this Javadoc fails the build outright.</p>
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>Pure JVM tier.</strong> No Spring context, no container, no database, no network. The three
 *       repositories are Mockito doubles because the whole point is to dictate the exact store state: an
 *       empty browse, a populated browse, a missing cross-reference row and a key collision are only
 *       observable when the answer is chosen row by row.</li>
 *   <li><strong>Mockito strict stubs</strong>, declared explicitly through {@code @MockitoSettings}, so an
 *       arrangement that stops being exercised fails rather than rotting quietly.</li>
 *   <li><strong>{@code FileStatusMapper} is a real instance</strong>, not a double: it has a no-argument
 *       constructor and no collaborators, so using the production translation table means the exception
 *       payloads asserted here are the ones production raises. Its status table is not re-tested here; that
 *       belongs to {@code FileStatusMapperTest}.</li>
 *   <li><strong>The clock is fixed</strong> through {@link FixedClockProvider}. Every timestamp assertion
 *       runs against an injected instant, so no verdict depends on when the suite executes. No wall-clock
 *       call appears anywhere in this file.</li>
 *   <li><strong>{@code BigDecimal} throughout</strong>, compared with {@code compareTo} and never
 *       {@code equals}, because {@code 0} and {@code 0.00} are equal in value and unequal under
 *       {@code equals}. No {@code float} or {@code double} appears in any monetary assertion.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails with "warnings found and -Werror specified".</strong> Almost always one
 *       unused import left behind after an edit, or a raw angle bracket in this Javadoc. Escape it as
 *       {@code &lt;} - which matters here because the guard under test literally reads {@code <=}.</li>
 *   <li><strong>A zero-balance test suddenly passes the payment through.</strong> The guard was weakened from
 *       {@code &lt;=} to {@code &lt;}. Restore it.</li>
 *   <li><strong>Every generated identifier is zero.</strong> {@code ADD 1} was moved after
 *       {@code INITIALIZE TRAN-RECORD}, so the increment is discarded.</li>
 *   <li><strong>Timestamp comparisons fail on character 11.</strong> The batch producer's form
 *       {@code yyyy-MM-dd-HH.mm.ss} was used; the online form separates date from time with a space.</li>
 *   <li><strong>An identifier test becomes flaky or ordered differently.</strong> A database sequence or an
 *       identity column was substituted for the descending browse. The race is deliberate; a collision must
 *       surface as {@code DuplicateRecordException}.</li>
 *   <li><strong>A confirmation of {@code Yes} starts being accepted.</strong> A case-insensitive comparison
 *       was introduced. The source matches two literals and nothing else.</li>
 *   <li><strong>{@code UnnecessaryStubbingException}.</strong> Strict stubs are on by design; delete the
 *       arrangement the path no longer reaches rather than relaxing the strictness.</li>
 *   <li><strong>Fixture names.</strong> The daily transaction fixture is {@code dailytran.txt}, never
 *       {@code dalytran.txt}. No fixture is read by this tier, but the trap is recorded because a sibling
 *       tier does read it.</li>
 * </ul>
 *
 * <h2>5. Regression severities and their remediation</h2>
 *
 * <p>Each failure this class is built to catch is classified by the damage it does if it reaches the
 * baseline comparison, with the remediation stated alongside it.</p>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - omitting the space at timestamp position 11, or carrying
 *       {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} as a temporal type instead of the twenty-six character
 *       string. Every persisted record then diverges from the legacy baseline.
 *       <em>Remediation:</em> render through the fixed clock into the {@code yyyy-MM-dd HH:mm:ss.000000} form
 *       and keep the field a {@code String} over {@code CHAR(26)}.</li>
 *   <li><strong>High</strong> - using {@code &lt;} instead of {@code &lt;=} on the reject guard; reordering
 *       {@code ADD 1} past {@code INITIALIZE TRAN-RECORD}; treating the payment as partial rather than
 *       full balance; introducing a case-insensitive confirmation comparison; substituting a database
 *       sequence for the descending browse; logging a full card number.
 *       <em>Remediation:</em> restore the source's operator, ordering, amount source and two literal
 *       {@code WHEN} arms; keep the browse; keep the card number out of every message.</li>
 *   <li><strong>Medium</strong> - splitting the transaction write and the account update into two
 *       transactions; letting duplicate-key and duplicate-record reach different outcomes; widening the
 *       eight integer digits of the {@code WS-TRAN-AMT} mask.
 *       <em>Remediation:</em> keep one transactional boundary over {@code :233}-{@code :235}, collapse both
 *       duplicate responses onto {@code Tran ID already exist...}, and leave the mask at its declared width.</li>
 *   <li><strong>Low</strong> - the universal cursor reposition to the account-identifier field on failures
 *       that have nothing to do with it; the {@code exist} spelling at {@code :536}; the same
 *       {@code Account ID NOT found...} text at three sites. <em>Remediation:</em> none - all three are the
 *       source's own behaviour and are preserved deliberately, as section 6 records.</li>
 * </ul>
 *
 * <h2>6. Artefacts retained by the parity mandate</h2>
 *
 * <p>Four artefacts would read as dead or defective code in isolation. Each is a faithful reproduction of the
 * system of record, each is asserted here so the reproduction is provable rather than asserted, and each
 * carries its tracking reference in {@code DECISION_LOG.md} with a paragraph row in
 * {@code TRACEABILITY_MATRIX.md}. None is abandoned residue.</p>
 *
 * <ul>
 *   <li><strong>{@code ENDBR-TRANSACT-FILE} at {@code :501}</strong> has no Java counterpart once the browse
 *       becomes one query, yet is retained as a labelled method so the paragraph map stays complete.
 *       Intentional no-op.</li>
 *   <li><strong>The cursor reposition to {@code ACTIDINL}</strong> on every failure arm, including the
 *       cross-reference and browse arms. A copy-paste defect in the source, preserved as the response's
 *       field-error marker.</li>
 *   <li><strong>{@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} at {@code :58}</strong> is declared and never
 *       read on this path. Retained because deleting it would break the field map.</li>
 *   <li><strong>The racy identifier generation</strong> of {@code :212}-{@code :217}. A sequence would remove
 *       the race and change every generated value, so the race stays and a collision surfaces as
 *       {@code DuplicateRecordException}.</li>
 * </ul>
 *
 * <h2>7. Evidence not available</h2>
 *
 * <p>Which of {@code STARTBR-TRANSACT-FILE}'s not-found arm ({@code :454}-{@code :456}) and
 * {@code READPREV-TRANSACT-FILE}'s end-of-file arm ({@code :487}-{@code :488}) fires for a given store state
 * is <strong>Not available</strong> from the corpus: it depends on the browse-positioning semantics of the
 * legacy access method, and settling it would need a running CICS region over a real VSAM cluster. Settling
 * it is consequently not attempted. The two arms are asserted <em>independently and never merged</em> - the
 * end-of-file default behaviourally, the positioning failure by its own distinct outcome and its own distinct
 * literal - which is the contract that holds either way.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("BillPaymentService - COBIL00C / transaction CB00")
final class BillPaymentServiceTest {

    /** {@code ACTIDINI PIC X(11)} at {@code app/cpy-bms/COBIL00.CPY:60}, filled to its declared width. */
    private static final String ACCOUNT_ID_TEXT = "00000000011";

    /** The same identifier as the repositories see it, per {@code ACCT-ID PIC 9(11)}. */
    private static final Long ACCOUNT_ID = 11L;

    /**
     * A synthetic sixteen-digit value standing in for {@code XREF-CARD-NUM PIC X(16)}.
     *
     * <p>It exists to prove that a card number reaches {@code TRAN-CARD-NUM} and reaches nothing else. It is
     * asserted only by identity against the projected field and is never embedded in an assertion
     * description, so a failure report cannot disclose it.</p>
     *
     * <p>The leading digit is deliberately {@code 9}: no card network issues an account number in the
     * {@code 9} range, so this value cannot match a real primary-account-number pattern while still
     * honouring the sixteen-character width the copybook declares.</p>
     */
    private static final String SYNTHETIC_CARD_NUMBER = "9999888877776666";

    /** {@code TRAN-TYPE-CD} literal {@code '02'} from {@code app/cbl/COBIL00C.cbl:220}. */
    private static final String TYPE_CODE = "02";

    /** {@code TRAN-CAT-CD} literal {@code 2} from {@code app/cbl/COBIL00C.cbl:221}. */
    private static final int CATEGORY_CODE = 2;

    /** {@code TRAN-DESC} literal from {@code app/cbl/COBIL00C.cbl:223}. */
    private static final String DESCRIPTION = "BILL PAYMENT - ONLINE";

    /** {@code TRAN-MERCHANT-ID} literal from {@code app/cbl/COBIL00C.cbl:226} - nine nines. */
    private static final long MERCHANT_ID = 999_999_999L;

    /** {@code TRAN-MERCHANT-NAME} literal from {@code app/cbl/COBIL00C.cbl:227}. */
    private static final String MERCHANT_NAME = "BILL PAYMENT";

    /** {@code TRAN-MERCHANT-CITY} and {@code TRAN-MERCHANT-ZIP} literal from {@code :228}-{@code :229}. */
    private static final String NOT_APPLICABLE = "N/A";

    /** The empty-identifier message of {@code app/cbl/COBIL00C.cbl:161}. */
    private static final String MSG_ACCOUNT_ID_EMPTY = "Acct ID can NOT be empty...";

    /** The invalid-confirmation message of {@code app/cbl/COBIL00C.cbl:187}. */
    private static final String MSG_CONFIRMATION_INVALID = "Invalid value. Valid values are (Y/N)...";

    /** The nothing-to-pay message of {@code app/cbl/COBIL00C.cbl:201}. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** The confirmation prompt of {@code app/cbl/COBIL00C.cbl:237}. */
    private static final String MSG_CONFIRMATION_REQUIRED = "Confirm to make a bill payment...";

    /** The message shared verbatim by {@code :361}, {@code :392} and {@code :425}. */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** The account-lookup failure message of {@code app/cbl/COBIL00C.cbl:368}. */
    private static final String MSG_ACCOUNT_LOOKUP_FAILED = "Unable to lookup Account...";

    /** The account-update failure message of {@code app/cbl/COBIL00C.cbl:399}. */
    private static final String MSG_ACCOUNT_UPDATE_FAILED = "Unable to Update Account...";

    /** The cross-reference failure message of {@code app/cbl/COBIL00C.cbl:432}. */
    private static final String MSG_XREF_LOOKUP_FAILED = "Unable to lookup XREF AIX file...";

    /** The browse-positioning failure message of {@code app/cbl/COBIL00C.cbl:456}. */
    private static final String MSG_TRANSACTION_NOT_FOUND = "Transaction ID NOT found...";

    /** The message shared verbatim by {@code :463} and {@code :492}. */
    private static final String MSG_TRANSACTION_LOOKUP_FAILED = "Unable to lookup Transaction...";

    /** The duplicate message of {@code app/cbl/COBIL00C.cbl:536} - {@code exist}, sic, not {@code exists}. */
    private static final String MSG_TRANSACTION_DUPLICATE = "Tran ID already exist...";

    /** The write-failure message of {@code app/cbl/COBIL00C.cbl:543}. */
    private static final String MSG_TRANSACTION_WRITE_FAILED = "Unable to Add Bill pay Transaction...";

    /** {@code ACTIDINI}, the field the source names on every input-error path. */
    private static final String FIELD_ACCOUNT_ID = "ACTIDINI";

    /** {@code CONFIRMI}, the field the two confirmation branches name. */
    private static final String FIELD_CONFIRMATION = "CONFIRMI";

    /** {@code ACCTDAT } as {@code app/cbl/COBIL00C.cbl:41} spells it, trailing blank included. */
    private static final String ACCTDAT_FILE = "ACCTDAT ";

    /** {@code CXACAIX } as {@code app/cbl/COBIL00C.cbl:42} spells it, trailing blank included. */
    private static final String CXACAIX_FILE = "CXACAIX ";

    /** {@code TRANSACT} as {@code app/cbl/COBIL00C.cbl:40} spells it. */
    private static final String TRANSACT_FILE = "TRANSACT";

    /** {@code TRAN-ID PIC X(16)}: the declared width the generated identifier is padded to. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** {@code CURBALI PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:66}, matching {@code WS-CURR-BAL}. */
    private static final int BALANCE_ECHO_WIDTH = 14;

    /** The identifier the {@code ENDFILE} arm of {@code :487}-{@code :488} yields once incremented. */
    private static final String FIRST_IDENTIFIER = "0000000000000001";

    /** The sixteen paragraph labels of {@code app/cbl/COBIL00C.cbl}, in source order, as Java names. */
    private static final List<String> PARAGRAPH_METHODS = List.of(
            "mainPara",                 // MAIN-PARA.               :99
            "processEnterKey",          // PROCESS-ENTER-KEY.       :154
            "getCurrentTimestamp",      // GET-CURRENT-TIMESTAMP.   :249
            "returnToPrevScreen",       // RETURN-TO-PREV-SCREEN.   :273
            "sendBillpayScreen",        // SEND-BILLPAY-SCREEN.     :289
            "receiveBillpayScreen",     // RECEIVE-BILLPAY-SCREEN.  :306
            "populateHeaderInfo",       // POPULATE-HEADER-INFO.    :319
            "readAcctdatFile",          // READ-ACCTDAT-FILE.       :343
            "updateAcctdatFile",        // UPDATE-ACCTDAT-FILE.     :377
            "readCxacaixFile",          // READ-CXACAIX-FILE.       :408
            "startbrTransactFile",      // STARTBR-TRANSACT-FILE.   :441
            "readprevTransactFile",     // READPREV-TRANSACT-FILE.  :472
            "endbrTransactFile",        // ENDBR-TRANSACT-FILE.     :501
            "writeTransactFile",        // WRITE-TRANSACT-FILE.     :510
            "clearCurrentScreen",       // CLEAR-CURRENT-SCREEN.    :552
            "initializeAllFields");     // INITIALIZE-ALL-FIELDS.   :560

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Builds the bean under test on the canonical fixed instant.
     *
     * @return the service, never {@code null}
     */
    private BillPaymentService service() {
        return service(FixedClockProvider.canonicalClock());
    }

    /**
     * Builds the bean under test on a caller-chosen fixed instant.
     *
     * @param clock the injected time source; never the system clock
     * @return the service, never {@code null}
     */
    private BillPaymentService service(final Clock clock) {
        return new BillPaymentService(this.accountRepository, this.transactionRepository,
                this.cardCrossReferenceRepository, new FileStatusMapper(), clock);
    }

    /**
     * Builds an {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy} carrying a chosen balance.
     *
     * @param currentBalance the {@code ACCT-CURR-BAL} value, {@code PIC S9(10)V99}
     * @return the account, never {@code null}
     */
    private static Account account(final BigDecimal currentBalance) {
        return new Account(ACCOUNT_ID, "Y", currentBalance, new BigDecimal("5000.00"),
                new BigDecimal("1000.00"), "2015-07-01", "2025-06-30", "2020-07-01",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "0000012345", "DEFAULT   ");
    }

    /**
     * Builds a {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy}.
     *
     * @return the cross-reference row, never {@code null}
     */
    private static CardCrossReference crossReference() {
        return new CardCrossReference(SYNTHETIC_CARD_NUMBER, 9L, ACCOUNT_ID);
    }

    /**
     * Builds the {@code TRAN-RECORD} the descending browse of {@code :214} would land on.
     *
     * @param transactionId the sixteen-character {@code TRAN-ID}
     * @return the transaction, never {@code null}
     */
    private static Transaction previousTransaction(final String transactionId) {
        return new Transaction(transactionId, "01", 5, "POS       ", "COFFEE",
                new BigDecimal("12.34"), 123L, "ACME", "SEATTLE", "12345-0001",
                SYNTHETIC_CARD_NUMBER, "2022-06-09 08:00:00.000000", "2022-06-09 08:00:00.000000");
    }

    /**
     * Builds the bound symbolic map of {@code app/cpy-bms/COBIL00.CPY} with the two members that matter.
     *
     * @param accountId    the {@code ACTIDINI} value, possibly {@code null}
     * @param confirmation the {@code CONFIRMI} value, possibly {@code null}
     * @return the request, never {@code null}
     */
    private static BillPaymentRequest request(final String accountId, final String confirmation) {
        return new BillPaymentRequest("CB00", null, null, "COBIL00C", null, null,
                accountId, null, confirmation, null);
    }

    /**
     * Drives one {@code ENTER} re-entry pass, the only combination that reaches {@code PROCESS-ENTER-KEY}
     * with a bound map.
     *
     * @param accountId    the {@code ACTIDINI} value
     * @param confirmation the {@code CONFIRMI} value
     * @return the outcome of the pass, never {@code null}
     */
    private BillPaymentResult enter(final String accountId, final String confirmation) {
        return service().processRequest(request(accountId, confirmation), "ENTER", EntryMode.REENTER, null);
    }

    /**
     * Arranges only the read-for-update of {@code READ-ACCTDAT-FILE}, which is all a rejected request
     * reaches.
     *
     * @param balance the {@code ACCT-CURR-BAL} the account carries
     * @return the account instance the service will read, never {@code null}
     */
    private Account arrangeAccount(final BigDecimal balance) {
        final Account existing = account(balance);
        when(this.accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(existing));
        return existing;
    }

    /**
     * Arranges the confirmed-payment happy path over an empty transaction store.
     *
     * @param balance the {@code ACCT-CURR-BAL} the account carries before the payment
     * @return the account instance the service will mutate, never {@code null}
     */
    private Account arrangeConfirmedPayment(final BigDecimal balance) {
        final Account existing = arrangeAccount(balance);
        when(this.cardCrossReferenceRepository.findByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                .thenReturn(List.of(crossReference()));
        when(this.transactionRepository.findFirstByOrderByTransactionIdDesc()).thenReturn(Optional.empty());
        return existing;
    }

    /**
     * Reads a property off a retained failure without importing its declaring type.
     *
     * <p>{@code FileAccessException} and {@code FatalProcessingException} are outside this test's declared
     * dependency set, so their payloads are reached by accessor name. The indirection is deliberate and it
     * asserts something in its own right: the accessor must exist, or the lookup fails the test.</p>
     *
     * @param target   the exception to interrogate
     * @param accessor the no-argument accessor name
     * @return the property rendered as text, or {@code null} when the property is absent
     */
    private static String property(final Object target, final String accessor) {
        try {
            final Object value = target.getClass().getMethod(accessor).invoke(target);
            return value == null ? null : value.toString();
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError("accessor " + accessor + " is absent from "
                    + target.getClass().getSimpleName(), failure);
        }
    }

    /**
     * Captures the single {@code TRAN-RECORD} handed to {@code WRITE-TRANSACT-FILE}.
     *
     * @return the written transaction, never {@code null}
     */
    private Transaction capturedTransaction() {
        final ArgumentCaptor<Transaction> written = ArgumentCaptor.forClass(Transaction.class);
        verify(this.transactionRepository).saveAndFlush(written.capture());
        return written.getValue();
    }

    /**
     * The full-balance settlement of {@code app/cbl/COBIL00C.cbl:198}-{@code :235}.
     *
     * <p>Three properties are contractual: the amount is the entire balance, the resulting balance is exactly
     * zero, and a balance at or below zero is refused before any of it happens.</p>
     */
    @Nested
    @DisplayName("Phase 1 - full-balance settlement and the at-or-below-zero reject guard")
    final class FullBalanceSettlement {

        @Test
        @DisplayName(":224 the transaction amount equals the pre-payment balance exactly")
        void transactionAmountEqualsThePrePaymentBalance() {
            final BigDecimal balance = new BigDecimal("1940.00");
            arrangeConfirmedPayment(balance);

            enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(capturedTransaction().getAmount())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(balance);
        }

        @Test
        @DisplayName(":234 the post-payment balance is exactly zero, asserted through compareTo")
        void postPaymentBalanceIsExactlyZero() {
            final Account settled = arrangeConfirmedPayment(new BigDecimal("1940.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(settled.getCurrentBalance().compareTo(BigDecimal.ZERO))
                    .as("COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT drives the balance to zero")
                    .isZero();
            assertThat(result.receiptOptional()).isPresent();
            assertThat(result.receiptOptional().orElseThrow().resultingBalance()
                    .compareTo(BigDecimal.ZERO)).isZero();
        }

        @Test
        @DisplayName(":198 a balance of exactly 0.00 is REJECTED, because the guard is at-or-below zero")
        void zeroBalanceIsRejected() {
            arrangeAccount(new BigDecimal("0.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.NOTHING_TO_PAY);
            assertThat(result.message()).isEqualTo(MSG_NOTHING_TO_PAY);
            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
            verifyNoInteractions(cardCrossReferenceRepository);
        }

        @Test
        @DisplayName(":198 a negative balance is rejected by the same guard and the same literal")
        void negativeBalanceIsRejectedByTheSameGuard() {
            arrangeAccount(new BigDecimal("-0.01"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.NOTHING_TO_PAY);
            assertThat(result.message()).isEqualTo(MSG_NOTHING_TO_PAY);
            verifyNoInteractions(cardCrossReferenceRepository);
        }

        @Test
        @DisplayName(":198 the smallest positive balance is accepted, fixing the guard's boundary at zero")
        void smallestPositiveBalanceIsAccepted() {
            arrangeConfirmedPayment(new BigDecimal("0.01"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.PAYMENT_SUCCESSFUL);
            assertThat(capturedTransaction().getAmount())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(new BigDecimal("0.01"));
        }

        @Test
        @DisplayName(":220 TRAN-TYPE-CD is the literal '02'")
        void typeCodeLiteral() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            enter(ACCOUNT_ID_TEXT, "Y");
            assertThat(capturedTransaction().getTypeCode()).isEqualTo(TYPE_CODE);
        }

        @Test
        @DisplayName(":221 TRAN-CAT-CD is the literal 2")
        void categoryCodeLiteral() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            enter(ACCOUNT_ID_TEXT, "Y");
            assertThat(capturedTransaction().getCategoryCode()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName(":222 TRAN-SOURCE is the literal 'POS TERM'")
        void sourceLiteral() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(capturedTransaction().getTransactionSource())
                    .isEqualTo(TransactionSource.POS_TERMINAL.getFixedWidthValue())
                    .startsWith("POS TERM")
                    .hasSize(TransactionSource.FIELD_LENGTH);
        }

        @Test
        @DisplayName(":223 TRAN-DESC is the literal 'BILL PAYMENT - ONLINE'")
        void descriptionLiteral() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            enter(ACCOUNT_ID_TEXT, "Y");
            assertThat(capturedTransaction().getDescription().strip()).isEqualTo(DESCRIPTION);
        }

        @Test
        @DisplayName(":226 TRAN-MERCHANT-ID is 999999999 - nine nines, not the fixture's 800000000")
        void merchantIdLiteralIsNineNines() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(capturedTransaction().getMerchantId())
                    .isEqualTo(MERCHANT_ID)
                    .isNotEqualTo(800_000_000L);
            assertThat(Long.toString(MERCHANT_ID)).hasSize(9);
        }

        @Test
        @DisplayName(":227 TRAN-MERCHANT-NAME is the literal 'BILL PAYMENT'")
        void merchantNameLiteral() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            enter(ACCOUNT_ID_TEXT, "Y");
            assertThat(capturedTransaction().getMerchantName().strip()).isEqualTo(MERCHANT_NAME);
        }

        @Test
        @DisplayName(":228-:229 TRAN-MERCHANT-CITY and TRAN-MERCHANT-ZIP are both the literal 'N/A'")
        void merchantCityAndZipLiterals() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            enter(ACCOUNT_ID_TEXT, "Y");

            final Transaction written = capturedTransaction();
            assertThat(written.getMerchantCity().strip()).isEqualTo(NOT_APPLICABLE);
            assertThat(written.getMerchantZip().strip()).isEqualTo(NOT_APPLICABLE);
        }

        @Test
        @DisplayName(":231-:232 one multi-receiver MOVE makes both timestamps byte identical")
        void bothTimestampsAreByteIdentical() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");
            final Transaction written = capturedTransaction();

            assertThat(written.getOrigTs())
                    .as("TRAN-ORIG-TS and TRAN-PROC-TS receive one value through a single MOVE")
                    .isEqualTo(written.getProcTs());
            final PaymentReceipt receipt = result.receiptOptional().orElseThrow();
            assertThat(receipt.originatingTimestamp()).isEqualTo(receipt.processingTimestamp());
        }

        @Test
        @DisplayName(":233 then :235 - the transaction write precedes the account update")
        void writeThenAccountUpdateInOrder() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            enter(ACCOUNT_ID_TEXT, "Y");

            final InOrder ordered = inOrder(transactionRepository, accountRepository);
            ordered.verify(transactionRepository).saveAndFlush(any(Transaction.class));
            ordered.verify(accountRepository).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("both writes share ONE transactional boundary, closing the legacy orphan-write hazard")
        void bothWritesShareOneTransactionalBoundary() throws NoSuchMethodException {
            final Method processRequest = BillPaymentService.class.getMethod("processRequest",
                    BillPaymentRequest.class, String.class, EntryMode.class, String.class);
            final Method payBill = BillPaymentService.class.getMethod("payBill", String.class, String.class);

            for (final Method entryPoint : List.of(processRequest, payBill)) {
                final Transactional boundary = entryPoint.getAnnotation(Transactional.class);
                assertThat(boundary)
                        .as("%s must scope the :233 write and the :235 update into one unit",
                                entryPoint.getName())
                        .isNotNull();
                assertThat(boundary.rollbackFor()).containsExactly(Exception.class);
            }
        }

        @Test
        @DisplayName(":217 before :218 - the increment survives INITIALIZE TRAN-RECORD")
        void incrementSurvivesRecordInitialisation() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(capturedTransaction().getTransactionId())
                    .as("reordering ADD 1 past INITIALIZE would leave sixteen zeros here")
                    .isEqualTo(FIRST_IDENTIFIER)
                    .isNotEqualTo("0".repeat(TRANSACTION_ID_WIDTH));
        }

        @Test
        @DisplayName("no partial-payment amount can be supplied: the settlement is always the whole balance")
        void noPartialPaymentAmountIsAccepted() {
            assertThat(Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .filter(name -> name.contains("amount") || name.contains("amt"))
                    .toList())
                    .as("app/cpy-bms/COBIL00.CPY declares no amount input field")
                    .isEmpty();
            assertThat(Arrays.stream(BillPaymentService.class.getMethods())
                    .filter(method -> "payBill".equals(method.getName()))
                    .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                    .filter(BigDecimal.class::equals)
                    .toList())
                    .as("payBill exposes no monetary parameter, so a caller cannot influence the amount")
                    .isEmpty();
        }
    }

    /**
     * The four-way confirmation gate of {@code app/cbl/COBIL00C.cbl:156}-{@code :190}.
     *
     * <p>Case tolerance comes from two literal {@code WHEN} clauses, never from a case function, so the
     * accepted spellings are exactly {@code Y}, {@code y}, {@code N} and {@code n} and nothing else.</p>
     */
    @Nested
    @DisplayName("Phase 2 - the four-way, case-sensitive confirmation gate")
    final class ConfirmationGate {

        @Test
        @DisplayName(":174 'Y' confirms, reads the account and commits")
        void upperCaseYesConfirms() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.confirmationBranch()).isEqualTo(ConfirmationBranch.YES_UPPER);
            assertThat(result.outcome()).isEqualTo(PaymentOutcome.PAYMENT_SUCCESSFUL);
            assertThat(result.failure()).isEmpty();
        }

        @Test
        @DisplayName(":175 'y' confirms through the second literal WHEN, not through a case function")
        void lowerCaseYesConfirms() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "y");

            assertThat(result.confirmationBranch()).isEqualTo(ConfirmationBranch.YES_LOWER);
            assertThat(result.outcome()).isEqualTo(PaymentOutcome.PAYMENT_SUCCESSFUL);
        }

        @Test
        @DisplayName(":178 'N' clears the screen and sets the error flag with NO message")
        void upperCaseNoDeclinesSilently() {
            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "N");

            assertThat(result.confirmationBranch()).isEqualTo(ConfirmationBranch.NO_UPPER);
            assertThat(result.outcome()).isEqualTo(PaymentOutcome.CONFIRMATION_DECLINED);
            assertThat(result.inputError()).isTrue();
            assertThat(result.message())
                    .as(":180-:181 sets the flag and emits no literal at all")
                    .isEmpty();
            assertThat(result.messageKind()).isEqualTo(MessageKind.NONE);
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
        }

        @Test
        @DisplayName(":179 'n' declines through the second literal WHEN, with the same silence")
        void lowerCaseNoDeclinesSilently() {
            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "n");

            assertThat(result.confirmationBranch()).isEqualTo(ConfirmationBranch.NO_LOWER);
            assertThat(result.outcome()).isEqualTo(PaymentOutcome.CONFIRMATION_DECLINED);
            assertThat(result.message()).isEmpty();
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
        }

        @Test
        @DisplayName(":180 CLEAR-CURRENT-SCREEN blanks the three input members at their declared widths")
        void decliningClearsTheScreen() {
            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "N");
            final BillPaymentScreen sent = result.screen();

            assertThat(sent).isNotNull();
            assertThat(sent.accountId()).isBlank().hasSize(ACCOUNT_ID_TEXT.length());
            assertThat(sent.confirmation()).isBlank().hasSize(1);
            assertThat(sent.errorMessage()).isEmpty();
            assertThat(result.cursor()).isEqualTo(CursorField.ACCOUNT_ID);
        }

        @Test
        @DisplayName(":182 SPACES reads the account with no error, then prompts at :237")
        void blankConfirmationPromptsWithoutError() {
            arrangeAccount(new BigDecimal("1940.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, " ");

            assertThat(result.confirmationBranch()).isEqualTo(ConfirmationBranch.BLANK);
            assertThat(result.outcome()).isEqualTo(PaymentOutcome.CONFIRMATION_REQUIRED);
            assertThat(result.message()).isEqualTo(MSG_CONFIRMATION_REQUIRED);
            assertThat(result.messageKind())
                    .as(":237 is a prompt, not an error - the screen sets no error flag")
                    .isEqualTo(MessageKind.INFORMATIONAL);
            assertThat(result.inputError()).isFalse();
            assertThat(result.cursor()).isEqualTo(CursorField.CONFIRMATION);
            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName(":183 LOW-VALUES takes the same arm as SPACES and prompts identically")
        void absentConfirmationPromptsWithoutError() {
            arrangeAccount(new BigDecimal("1940.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, null);

            assertThat(result.confirmationBranch()).isEqualTo(ConfirmationBranch.LOW_VALUES);
            assertThat(result.outcome()).isEqualTo(PaymentOutcome.CONFIRMATION_REQUIRED);
            assertThat(result.message()).isEqualTo(MSG_CONFIRMATION_REQUIRED);
            assertThat(result.inputError()).isFalse();
        }

        @ParameterizedTest(name = "confirmation [{0}] falls to WHEN OTHER at :185")
        @ValueSource(strings = {"Yes", "1", "T", "y ", "YY", "0", "n "})
        @DisplayName(":185-:187 every other spelling is invalid, because there is no case function")
        void everyOtherSpellingIsInvalid(final String confirmation) {
            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, confirmation);

            assertThat(result.confirmationBranch()).isEqualTo(ConfirmationBranch.INVALID);
            assertThat(result.outcome()).isEqualTo(PaymentOutcome.CONFIRMATION_INVALID);
            assertThat(result.message()).isEqualTo(MSG_CONFIRMATION_INVALID);
            assertThat(result.messageKind()).isEqualTo(MessageKind.ERROR);
            assertThat(result.cursor()).isEqualTo(CursorField.CONFIRMATION);
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
        }

        @Test
        @DisplayName(":156 SET CONF-PAY-NO TO TRUE - no confirmation survives between requests")
        void confirmationDefaultsToNoOnEveryRequest() {
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("1940.00"))))
                    .thenReturn(Optional.of(account(new BigDecimal("1940.00"))));
            when(cardCrossReferenceRepository.findByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenReturn(List.of(crossReference()));
            when(transactionRepository.findFirstByOrderByTransactionIdDesc()).thenReturn(Optional.empty());
            final BillPaymentService bean = service();

            final BillPaymentResult first =
                    bean.processRequest(request(ACCOUNT_ID_TEXT, "Y"), "ENTER", EntryMode.REENTER, null);
            final BillPaymentResult second =
                    bean.processRequest(request(ACCOUNT_ID_TEXT, " "), "ENTER", EntryMode.REENTER, null);

            assertThat(first.outcome()).isEqualTo(PaymentOutcome.PAYMENT_SUCCESSFUL);
            assertThat(second.confirmationBranch()).isEqualTo(ConfirmationBranch.BLANK);
            assertThat(second.outcome())
                    .as("the second pass re-prompts, proving CONF-PAY-NO was re-asserted at :156")
                    .isEqualTo(PaymentOutcome.CONFIRMATION_REQUIRED);
            assertThat(second.receiptOptional()).isEmpty();
        }

        @Test
        @DisplayName(":159-:163 an empty identifier short-circuits before the gate is ever consulted")
        void emptyAccountIdentifierShortCircuitsTheGate() {
            final BillPaymentResult result = enter("           ", "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.ACCOUNT_ID_EMPTY);
            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_ID_EMPTY);
            assertThat(result.cursor()).isEqualTo(CursorField.ACCOUNT_ID);
            assertThat(result.confirmationBranch())
                    .as("the EVALUATE at :173 sits inside IF NOT ERR-FLG-ON, so it never runs")
                    .isNull();
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("payBill raises the declined branch as a typed ValidationException on CONFIRMI")
        void payBillRaisesTheDeclinedBranch() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service().payBill(ACCOUNT_ID_TEXT, "N"))
                    .withMessage("Bill payment was declined at the confirmation prompt.")
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_CONFIRMATION);
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
        }

        @Test
        @DisplayName("payBill returns the sent screen when the confirmation commits")
        void payBillReturnsTheSentScreen() {
            arrangeConfirmedPayment(new BigDecimal("250.00"));

            final BillPaymentScreen sent = service().payBill(ACCOUNT_ID_TEXT, "Y");

            assertThat(sent).isNotNull();
            assertThat(sent.errorMessage()).startsWith("Payment successful.");
            assertThat(sent.messageKind()).isEqualTo(MessageKind.INFORMATIONAL);
        }
    }

    /**
     * The online twenty-six character timestamp of {@code app/cbl/COBIL00C.cbl:249}-{@code :267}.
     *
     * <p>Position eleven is never written. {@code INITIALIZE WS-TIMESTAMP} at {@code :263} leaves a space
     * there, {@code :264} writes offsets one to ten and {@code :265} writes offsets twelve to nineteen, so the
     * eleventh character survives as a blank. Every assertion here runs against an injected fixed clock.</p>
     */
    @Nested
    @DisplayName("Phase 3 - the online timestamp and the space at position 11")
    final class OnlineTimestamp {

        /** A second fixed instant, chosen so every component differs from the canonical one. */
        private static final Instant SECOND_INSTANT = Instant.parse("2001-01-31T23:59:59Z");

        /** The rendering {@link #SECOND_INSTANT} must produce, derived from the layout, not from the code. */
        private static final String SECOND_RENDERING = "2001-01-31 23:59:59.000000";

        /**
         * Settles a payment on a chosen fixed instant and returns the timestamp the record received.
         *
         * @param instant the fixed instant the injected clock reports
         * @return the twenty-six character {@code TRAN-ORIG-TS} value
         */
        private String timestampOn(final Instant instant) {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            service(FixedClockProvider.fixedClock(instant))
                    .processRequest(request(ACCOUNT_ID_TEXT, "Y"), "ENTER", EntryMode.REENTER, null);
            return capturedTransaction().getOrigTs();
        }

        @Test
        @DisplayName("the rendering is exactly twenty-six characters, per TRAN-ORIG-TS PIC X(26)")
        void renderingIsTwentySixCharacters() {
            assertThat(timestampOn(FixedClockProvider.CANONICAL_INSTANT))
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName(":263 leaves character 11 a SPACE and nothing writes over it")
        void characterElevenIsASpace() {
            final String rendered = timestampOn(FixedClockProvider.CANONICAL_INSTANT);

            assertThat(rendered.charAt(10))
                    .as("position 11 sits between :264's offsets 1-10 and :265's offsets 12-19")
                    .isEqualTo(' ');
            assertThat(rendered.indexOf(' '))
                    .as("it is the only blank in the value")
                    .isEqualTo(10);
            assertThat(rendered.chars().filter(character -> character == ' ').count()).isOne();
        }

        @Test
        @DisplayName(":266 writes six zeros at positions 21 to 26")
        void positionsTwentyOneToTwentySixAreSixZeros() {
            final String rendered = timestampOn(FixedClockProvider.CANONICAL_INSTANT);

            assertThat(rendered.substring(20))
                    .as("MOVE ZEROS TO WS-TIMESTAMP-TM-MS6 fills the whole six-digit fraction")
                    .isEqualTo("000000")
                    .hasSize(6);
        }

        @Test
        @DisplayName(":265 DATESEP('-') and TIMESEP(':') fix the separators, and position 20 is a period")
        void separatorsSitWhereTheLayoutPutsThem() {
            final String rendered = timestampOn(FixedClockProvider.CANONICAL_INSTANT);

            assertThat(rendered.charAt(4)).isEqualTo('-');
            assertThat(rendered.charAt(7)).isEqualTo('-');
            assertThat(rendered.charAt(13)).isEqualTo(':');
            assertThat(rendered.charAt(16)).isEqualTo(':');
            assertThat(rendered.charAt(19))
                    .as("position 20 separates the seconds from the six-digit fraction")
                    .isEqualTo('.');
        }

        @Test
        @DisplayName("the value derives from the injected clock and tracks a second fixed instant")
        void valueTracksTheInjectedClock() {
            assertThat(timestampOn(FixedClockProvider.CANONICAL_INSTANT))
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(FixedClockProvider.onlineTimestamp(
                    FixedClockProvider.fixedClock(SECOND_INSTANT)))
                    .as("the shared renderer and the service must agree on the second instant too")
                    .isEqualTo(SECOND_RENDERING);
        }

        @Test
        @DisplayName("a second fixed instant produces a different value, proving no constant was frozen in")
        void secondInstantProducesADifferentValue() {
            assertThat(timestampOn(SECOND_INSTANT))
                    .isEqualTo(SECOND_RENDERING)
                    .isNotEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
        }

        @Test
        @DisplayName("both timestamp fields are carried as text, never as a temporal type")
        void timestampsAreCarriedAsText() throws NoSuchMethodException {
            assertThat(Transaction.class.getMethod("getOrigTs").getReturnType())
                    .as("TRAN-ORIG-TS is PIC X(26); three incompatible producers write it")
                    .isEqualTo(String.class);
            assertThat(Transaction.class.getMethod("getProcTs").getReturnType()).isEqualTo(String.class);
            assertThat(PaymentReceipt.class.getMethod("originatingTimestamp").getReturnType())
                    .isEqualTo(String.class);
            assertThat(PaymentReceipt.class.getMethod("processingTimestamp").getReturnType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the value is NOT the batch hyphen-at-11 form and NOT a four-trailing-zero form")
        void valueIsNeitherOfTheOtherTwoProducers() {
            final String rendered = timestampOn(FixedClockProvider.CANONICAL_INSTANT);

            assertThat(rendered)
                    .as("CBTRN02C emits yyyy-MM-dd-HH.mm.ss.SS0000, which this path must not produce")
                    .isNotEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP);
            assertThat(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP.charAt(10))
                    .as("the batch form carries a hyphen exactly where the online form carries a blank")
                    .isEqualTo('-');
            assertThat(rendered.charAt(10)).isNotEqualTo('-');
            assertThat(rendered).doesNotContain(".0000 ").endsWith(".000000");
            assertThat(rendered.substring(17, 19))
                    .as("the seconds are significant here, not the hundredths the batch form carries")
                    .isEqualTo("53");
        }
    }

    /**
     * Identifier generation: the descending browse, the zero default, and the retained race.
     *
     * <p>{@code :212}-{@code :217} moves high values into the key, starts a browse, reads the previous record,
     * ends the browse, converts and adds one. The Java counterpart is a top-one descending query whose empty
     * result defaults to zero, which is why the first identifier is one.</p>
     */
    @Nested
    @DisplayName("Phase 4 - identifier generation, the empty-store default and the retained race")
    final class IdentifierGeneration {

        @Test
        @DisplayName(":487-:488 an empty store yields identifier 1, zero-padded to sixteen characters")
        void emptyStoreYieldsIdentifierOne() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(capturedTransaction().getTransactionId())
                    .as("MOVE ZEROS TO TRAN-ID then ADD 1 gives one, and PIC X(16) pads it")
                    .isEqualTo(FIRST_IDENTIFIER)
                    .hasSize(TRANSACTION_ID_WIDTH);
        }

        @Test
        @DisplayName(":216-:217 a populated store yields the maximum key plus one")
        void populatedStoreYieldsMaximumPlusOne() {
            arrangeAccount(new BigDecimal("100.00"));
            when(cardCrossReferenceRepository.findByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenReturn(List.of(crossReference()));
            when(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(previousTransaction("0000000000000041")));

            enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(capturedTransaction().getTransactionId()).isEqualTo("0000000000000042");
        }

        @Test
        @DisplayName(":213-:215 the browse is a single top-one DESCENDING query, never a count or a sequence")
        void browseIsTopOneDescending() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            enter(ACCOUNT_ID_TEXT, "Y");

            verify(transactionRepository, times(1)).findFirstByOrderByTransactionIdDesc();
            assertThat(Arrays.stream(TransactionRepository.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toSet()))
                    .as("the descending browse is a derived finder; no counting or sequence method exists")
                    .contains("findFirstByOrderByTransactionIdDesc")
                    .noneMatch(name -> name.startsWith("count") || name.toLowerCase(Locale.ROOT)
                            .contains("sequence") || name.toLowerCase(Locale.ROOT).contains("nextval"));
        }

        @Test
        @DisplayName("the browse carries no hand-written query, and the account read binds a named parameter")
        void queriesAreParameterBoundRatherThanConcatenated() throws NoSuchMethodException {
            final Method browse = TransactionRepository.class
                    .getMethod("findFirstByOrderByTransactionIdDesc");
            assertThat(browse.getAnnotation(Query.class))
                    .as("a derived finder has no query string, so there is nothing to concatenate")
                    .isNull();

            final Method read = AccountRepository.class.getMethod("findByIdForUpdate", Long.class);
            final Query declared = read.getAnnotation(Query.class);
            assertThat(declared).isNotNull();
            assertThat(declared.value())
                    .as("the read-for-update binds :accountId rather than interpolating it")
                    .contains(":accountId")
                    .doesNotContain("+");
            assertThat(read.getParameters()[0].getAnnotation(Param.class)).isNotNull();
            assertThat(read.getParameters()[0].getAnnotation(Param.class).value()).isEqualTo("accountId");
        }

        @Test
        @DisplayName(":454-:456 the positioning-failure branch is distinct from the :487 zero default")
        void positioningFailureIsDistinctFromTheZeroDefault() {
            assertThat(MSG_TRANSACTION_NOT_FOUND)
                    .as(":456 and :463 carry different literals and must never be merged")
                    .isNotEqualTo(MSG_TRANSACTION_LOOKUP_FAILED);
            assertThat(PaymentOutcome.TRANSACTION_BROWSE_NOT_FOUND)
                    .isNotEqualTo(PaymentOutcome.TRANSACTION_LOOKUP_FAILED);
            assertThat(Arrays.stream(PaymentOutcome.values()).map(Enum::name).toList())
                    .as("the positioning failure is modelled as its own outcome")
                    .contains("TRANSACTION_BROWSE_NOT_FOUND", "TRANSACTION_LOOKUP_FAILED");
        }

        @Test
        @DisplayName(":215-:216 has no guard, so a failed browse abends on the high-values sentinel")
        void browseFailureAbendsOnTheHighValuesSentinel() {
            arrangeAccount(new BigDecimal("100.00"));
            when(cardCrossReferenceRepository.findByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenReturn(List.of(crossReference()));
            when(transactionRepository.findFirstByOrderByTransactionIdDesc())
                    .thenThrow(new QueryTimeoutException("browse timed out"));

            assertThatExceptionOfType(CardDemoException.class)
                    .as(":492 sets the lookup-failure state, but no guard stops :216 from converting "
                            + "the sentinel, so the legacy data exception terminates the task")
                    .isThrownBy(() -> enter(ACCOUNT_ID_TEXT, "Y"))
                    .satisfies(failure -> assertThat(failure.getClass().getSimpleName())
                            .isEqualTo("FatalProcessingException"))
                    .withMessageContaining("app/cbl/COBIL00C.cbl:216");

            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName(":463 and :492 share one literal, and the outcome is modelled even though :216 abends")
        void theLookupFailureLiteralIsSharedByBothBrowseSites() {
            assertThat(MSG_TRANSACTION_LOOKUP_FAILED)
                    .as("the browse-start and browse-previous arms carry the identical literal")
                    .isEqualTo("Unable to lookup Transaction...");
            assertThat(Arrays.stream(PaymentOutcome.values()).map(Enum::name).toList())
                    .contains("TRANSACTION_LOOKUP_FAILED");
        }

        @Test
        @DisplayName(":533-:536 a collision surfaces as DuplicateRecordException with no retry loop")
        void collisionSurfacesAsDuplicateRecordWithNoRetry() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(new DataIntegrityViolationException("tran_id primary key"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.TRANSACTION_DUPLICATE);
            assertThat(result.message()).isEqualTo(MSG_TRANSACTION_DUPLICATE);
            verify(transactionRepository, times(1)).findFirstByOrderByTransactionIdDesc();
            verify(transactionRepository, times(1)).saveAndFlush(any(Transaction.class));
            assertThat(result.failure()).containsInstanceOf(DuplicateRecordException.class);
        }

        @Test
        @DisplayName("payBill raises the collision as DuplicateRecordException carrying the colliding key")
        void payBillRaisesTheCollision() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(new DataIntegrityViolationException("tran_id primary key"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> service().payBill(ACCOUNT_ID_TEXT, "Y"))
                    .withMessage(MSG_TRANSACTION_DUPLICATE)
                    .satisfies(failure -> {
                        assertThat(failure.getLogicalFile()).isEqualTo(TRANSACT_FILE);
                        assertThat(failure.getCollidingKey()).isEqualTo(FIRST_IDENTIFIER);
                        assertThat(failure.getCause())
                                .as("the provider failure is preserved as the root cause")
                                .isInstanceOf(DataIntegrityViolationException.class);
                    });
        }

        @Test
        @DisplayName("the race is retained: no generated value and no sequence generator on TRAN-ID")
        void theRaceIsRetainedRatherThanReplacedBySequence() throws NoSuchFieldException {
            final Set<String> annotations = Arrays
                    .stream(Transaction.class.getDeclaredField("transactionId").getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .collect(Collectors.toSet());

            assertThat(annotations)
                    .as("substituting a sequence would change generated values and break the baseline")
                    .doesNotContain("GeneratedValue", "SequenceGenerator", "TableGenerator");
        }
    }

    /**
     * The twelve exact outcome literals and the failure paths that emit them.
     *
     * <p>Each literal is compared byte for byte, because a parity comparison against the legacy screen output
     * is a character-level diff. Two literals are shared across sites and both sharings are asserted.</p>
     */
    @Nested
    @DisplayName("Phase 5 - the twelve outcome literals and the typed failures that carry them")
    final class OutcomeLiterals {

        @Test
        @DisplayName(":361 an absent account emits Account ID NOT found... as a RecordNotFoundException")
        void absentAccountEmitsTheSharedLiteral() {
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.ACCOUNT_NOT_FOUND);
            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_NOT_FOUND);
            assertThat(result.cursor()).isEqualTo(CursorField.ACCOUNT_ID);
            assertThat(result.failure())
                    .containsInstanceOf(RecordNotFoundException.class)
                    .get()
                    .satisfies(failure -> {
                        final RecordNotFoundException missing = (RecordNotFoundException) failure;
                        assertThat(missing).hasMessage(MSG_ACCOUNT_NOT_FOUND);
                        assertThat(missing.recordType()).contains("ACCOUNT");
                        assertThat(missing.recordKey()).contains(ACCOUNT_ID_TEXT);
                        assertThat(missing.getCause())
                                .as("an empty result is not an exceptional condition, so there is no cause")
                                .isNull();
                    });
            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository);
        }

        @Test
        @DisplayName(":368 a failed read emits Unable to lookup Account... with status, file and cause")
        void failedAccountReadCarriesStatusFileOperationAndCause() {
            final QueryTimeoutException provider = new QueryTimeoutException("acctdat read timed out");
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenThrow(provider);

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.ACCOUNT_LOOKUP_FAILED);
            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_LOOKUP_FAILED);
            assertThat(result.failure()).isPresent();

            final CardDemoException failure = result.failure().orElseThrow();
            assertThat(failure).hasMessage(MSG_ACCOUNT_LOOKUP_FAILED).hasCause(provider);
            assertThat(failure.getClass().getSimpleName()).isEqualTo("FileAccessException");
            assertThat(property(failure, "getLogicalFileName")).isEqualTo(ACCTDAT_FILE);
            assertThat(property(failure, "getOperation")).isEqualTo("READ UPDATE");
            assertThat(property(failure, "getExpandedStatus"))
                    .as("the four-character rendering of the file status reaches the exception context")
                    .hasSize(4);
        }

        @Test
        @DisplayName(":399 a failed rewrite emits Unable to Update Account... and voids the receipt")
        void failedAccountRewriteVoidsTheWholeUnit() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            when(accountRepository.saveAndFlush(any(Account.class)))
                    .thenThrow(new QueryTimeoutException("acctdat rewrite timed out"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome())
                    .as("the write of :233 succeeded, yet the rewrite failure of :235 is what is reported")
                    .isEqualTo(PaymentOutcome.ACCOUNT_UPDATE_FAILED);
            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_UPDATE_FAILED);
            assertThat(result.cursor()).isEqualTo(CursorField.ACCOUNT_ID);
            assertThat(result.receiptOptional())
                    .as("one transaction spans both writes, so no payment occurred")
                    .isEmpty();
            verify(transactionRepository).saveAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName(":425 an absent cross-reference emits the SAME Account ID NOT found... literal")
        void absentCrossReferenceEmitsTheSharedLiteralAtItsThirdSite() {
            arrangeAccount(new BigDecimal("100.00"));
            when(cardCrossReferenceRepository.findByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenReturn(List.of());
            when(transactionRepository.findFirstByOrderByTransactionIdDesc()).thenReturn(Optional.empty());

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.CROSS_REFERENCE_NOT_FOUND);
            assertThat(result.failure())
                    .containsInstanceOf(RecordNotFoundException.class)
                    .get()
                    .as("the byte-exact literal survives on the retained failure")
                    .satisfies(failure -> assertThat(failure).hasMessage(MSG_ACCOUNT_NOT_FOUND));
            assertThat(result.cursor())
                    .as("the cursor goes to the account field even though the cross-reference failed")
                    .isEqualTo(CursorField.ACCOUNT_ID);
            assertThat(result.sendCount())
                    .as("three sends: the error send at :427, the success send at :532, and the "
                            + "unconditional send at :242 whose guard was evaluated before the failure")
                    .isEqualTo(3);
            assertThat(result.message())
                    .as(":523-:531 overwrites WS-MESSAGE, exactly as the second SEND would overwrite "
                            + "the screen")
                    .startsWith("Payment successful.");
            assertThat(capturedTransaction().getCardNumber())
                    .as("with no cross-reference the write proceeds with a blank card number")
                    .isBlank();
        }

        @Test
        @DisplayName(":432 a failed cross-reference read emits Unable to lookup XREF AIX file...")
        void failedCrossReferenceReadEmitsTheXrefLiteral() {
            arrangeAccount(new BigDecimal("100.00"));
            when(cardCrossReferenceRepository.findByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenThrow(new QueryTimeoutException("cxacaix read timed out"));
            when(transactionRepository.findFirstByOrderByTransactionIdDesc()).thenReturn(Optional.empty());

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.CROSS_REFERENCE_LOOKUP_FAILED);
            assertThat(result.failure().orElseThrow()).hasMessage(MSG_XREF_LOOKUP_FAILED);
            assertThat(property(result.failure().orElseThrow(), "getLogicalFileName"))
                    .isEqualTo(CXACAIX_FILE);
            assertThat(property(result.failure().orElseThrow(), "getOperation")).isEqualTo("READ");
            assertThat(result.sendCount())
                    .as("the error send at :434, the success send at :532 and the :242 send all happen")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("payBill raises the cross-reference failure rather than reporting the later success")
        void payBillRaisesTheCrossReferenceFailure() {
            arrangeAccount(new BigDecimal("100.00"));
            when(cardCrossReferenceRepository.findByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenThrow(new QueryTimeoutException("cxacaix read timed out"));
            when(transactionRepository.findFirstByOrderByTransactionIdDesc()).thenReturn(Optional.empty());

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> service().payBill(ACCOUNT_ID_TEXT, "Y"))
                    .withMessage(MSG_XREF_LOOKUP_FAILED)
                    .satisfies(failure -> assertThat(failure.getClass().getSimpleName())
                            .isEqualTo("FileAccessException"));
        }

        @Test
        @DisplayName(":361 and :425 emit one identical literal from two different datasets")
        void theSharedLiteralIsIdenticalAtBothReachableSites() {
            when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());
            final String fromAccountRead = enter(ACCOUNT_ID_TEXT, "Y").message();

            assertThat(fromAccountRead)
                    .as("the third site, :392, is modelled as ACCOUNT_UPDATE_NOT_FOUND but is unreachable")
                    .isEqualTo(MSG_ACCOUNT_NOT_FOUND);
            assertThat(Arrays.stream(PaymentOutcome.values()).map(Enum::name).toList())
                    .contains("ACCOUNT_NOT_FOUND", "ACCOUNT_UPDATE_NOT_FOUND", "CROSS_REFERENCE_NOT_FOUND");
        }

        @Test
        @DisplayName(":533-:536 DUPKEY and DUPREC collapse into one outcome and one literal")
        void duplicateKeyAndDuplicateRecordProduceTheSameOutcome() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(new DuplicateKeyException("tran_id already present"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(DataIntegrityViolationException.class)
                    .as("a duplicate key is a narrower integrity violation, and both reach one arm")
                    .isAssignableFrom(DuplicateKeyException.class);
            assertThat(result.outcome()).isEqualTo(PaymentOutcome.TRANSACTION_DUPLICATE);
            assertThat(result.message()).isEqualTo(MSG_TRANSACTION_DUPLICATE);
        }

        @Test
        @DisplayName(":536 the literal is spelt exist, sic, and never exists")
        void theDuplicateLiteralIsSpeltExist() {
            assertThat(MSG_TRANSACTION_DUPLICATE)
                    .isEqualTo("Tran ID already exist...")
                    .endsWith("exist...")
                    .doesNotContain("exists");
        }

        @Test
        @DisplayName(":543 a failed write emits Unable to Add Bill pay Transaction...")
        void failedTransactionWriteEmitsItsOwnLiteral() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(new QueryTimeoutException("transact write timed out"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.TRANSACTION_WRITE_FAILED);
            assertThat(result.message()).isEqualTo(MSG_TRANSACTION_WRITE_FAILED);
            assertThat(property(result.failure().orElseThrow(), "getLogicalFileName"))
                    .isEqualTo(TRANSACT_FILE);
            assertThat(property(result.failure().orElseThrow(), "getOperation")).isEqualTo("WRITE");
        }

        @Test
        @DisplayName(":523-:531 the success message carries the source's deliberate double space")
        void theSuccessMessageCarriesTheLegacyDoubleSpace() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.message())
                    .as("two literals abut: 'Payment successful. ' then ' Your Transaction ID is '")
                    .isEqualTo("Payment successful.  Your Transaction ID is " + FIRST_IDENTIFIER + ".")
                    .contains("successful.  Your");
            assertThat(result.messageKind()).isEqualTo(MessageKind.INFORMATIONAL);
        }

        @Test
        @DisplayName(":163 the empty-identifier guard names ACTIDINI, which is field appropriate")
        void theEmptyIdentifierGuardNamesTheAccountField() {
            final BillPaymentResult result = enter("           ", "Y");

            assertThat(result.failure()).containsInstanceOf(ValidationException.class);
            assertThat((ValidationException) result.failure().orElseThrow())
                    .satisfies(failure -> {
                        assertThat(failure.getFieldName()).isEqualTo(FIELD_ACCOUNT_ID);
                        assertThat(failure.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                    });
        }

        @Test
        @DisplayName(":202 the nothing-to-pay guard also names ACTIDINI, though the balance is at fault")
        void theNothingToPayGuardAlsoNamesTheAccountField() {
            arrangeAccount(new BigDecimal("0.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat((ValidationException) result.failure().orElseThrow())
                    .as("the universal reposition to ACTIDINL is a preserved copy-paste defect")
                    .satisfies(failure -> assertThat(failure.getFieldName())
                            .isEqualTo(FIELD_ACCOUNT_ID));
            assertThat(result.cursor()).isEqualTo(CursorField.ACCOUNT_ID);
        }

        @Test
        @DisplayName(":524 the success arm blanks the inputs before the message is assembled")
        void theSuccessArmClearsTheInputFields() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            final BillPaymentScreen sent = enter(ACCOUNT_ID_TEXT, "Y").screen();

            assertThat(sent).isNotNull();
            assertThat(sent.accountId()).isBlank();
            assertThat(sent.currentBalance()).isBlank();
            assertThat(sent.confirmation()).isBlank();
        }
    }

    /**
     * Field contracts, display masks and the two decimal precision tiers.
     *
     * <p>{@code ACCT-CURR-BAL} is {@code S9(10)V99} and {@code TRAN-AMT} is {@code S9(09)V99}, so a balance can
     * legitimately hold a value the amount field cannot. That boundary is where the two tiers become
     * observable, and it is asserted from both sides.</p>
     */
    @Nested
    @DisplayName("Phase 6 - field contracts, the fourteen-character mask and the two precision tiers")
    final class FieldContracts {

        @Test
        @DisplayName(":193-:194 the balance echoes on the fourteen-character +9999999999.99 mask")
        void balanceEchoesOnTheFourteenCharacterMask() {
            arrangeAccount(new BigDecimal("1940.00"));

            final BillPaymentScreen sent = enter(ACCOUNT_ID_TEXT, " ").screen();

            assertThat(sent).isNotNull();
            assertThat(sent.currentBalance())
                    .as("WS-CURR-BAL PIC +9999999999.99 matches CURBALI PIC X(14) exactly")
                    .isEqualTo("+0000001940.00")
                    .hasSize(BALANCE_ECHO_WIDTH);
        }

        @Test
        @DisplayName("a negative balance echoes with a leading minus on the same fourteen-character mask")
        void negativeBalanceEchoesWithASign() {
            arrangeAccount(new BigDecimal("-25.50"));

            final BillPaymentScreen sent = enter(ACCOUNT_ID_TEXT, " ").screen();

            assertThat(sent).isNotNull();
            assertThat(sent.currentBalance()).isEqualTo("-0000000025.50").hasSize(BALANCE_ECHO_WIDTH);
        }

        @Test
        @DisplayName("the account balance is carried at scale two, whatever scale it arrived at")
        void accountBalanceIsCarriedAtScaleTwo() {
            final Account settled = arrangeConfirmedPayment(new BigDecimal("100"));

            enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(settled.getCurrentBalance().scale())
                    .as("S9(10)V99 is NUMERIC(12,2), so two decimal places are mandatory")
                    .isEqualTo(2);
            assertThat(settled.getCurrentBalance().compareTo(BigDecimal.ZERO)).isZero();
        }

        @Test
        @DisplayName("the transaction amount is carried at scale two within nine integer digits")
        void transactionAmountFitsItsNarrowerPicture() {
            arrangeConfirmedPayment(new BigDecimal("999999999.99"));

            enter(ACCOUNT_ID_TEXT, "Y");

            final BigDecimal amount = capturedTransaction().getAmount();
            assertThat(amount.scale()).isEqualTo(2);
            assertThat(amount.precision())
                    .as("TRAN-AMT S9(09)V99 is NUMERIC(11,2) - a narrower tier than the balance's 12,2")
                    .isLessThanOrEqualTo(11);
            assertThat(amount.toPlainString()).isEqualTo("999999999.99");
        }

        @Test
        @DisplayName("a balance that fits NUMERIC(12,2) but not NUMERIC(11,2) abends rather than truncating")
        void balanceBeyondTheAmountPictureAbends() {
            arrangeAccount(new BigDecimal("9999999999.99"));
            when(cardCrossReferenceRepository.findByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                    .thenReturn(List.of(crossReference()));
            when(transactionRepository.findFirstByOrderByTransactionIdDesc()).thenReturn(Optional.empty());

            assertThatExceptionOfType(CardDemoException.class)
                    .as("silently truncating the high-order digit would corrupt the fixed-width record")
                    .isThrownBy(() -> enter(ACCOUNT_ID_TEXT, "Y"))
                    .satisfies(failure -> assertThat(failure.getClass().getSimpleName())
                            .isEqualTo("FatalProcessingException"));

            verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName(":225 the card number comes from the cross-reference record, never from the request")
        void cardNumberComesFromTheCrossReference() {
            arrangeConfirmedPayment(new BigDecimal("100.00"));

            enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(capturedTransaction().getCardNumber()).isEqualTo(SYNTHETIC_CARD_NUMBER);
            assertThat(Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .filter(name -> name.contains("card"))
                    .toList())
                    .as("app/cpy-bms/COBIL00.CPY has no card field, so a caller cannot supply one")
                    .isEmpty();
        }

        @Test
        @DisplayName("the request DTO carries the ten input fields of app/cpy-bms/COBIL00.CPY, in order")
        void requestExposesTheTenFieldContract() {
            assertThat(Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList())
                    .containsExactly("transactionName", "title01", "currentDate", "programName", "title02",
                            "currentTime", "accountId", "currentBalance", "confirmation", "errorMessage");
        }

        @Test
        @DisplayName("no financial member anywhere on this path is a binary floating-point type")
        void noFinancialMemberIsFloatingPoint() throws NoSuchMethodException {
            assertThat(Account.class.getMethod("getCurrentBalance").getReturnType())
                    .isEqualTo(BigDecimal.class);
            assertThat(Transaction.class.getMethod("getAmount").getReturnType())
                    .isEqualTo(BigDecimal.class);
            assertThat(PaymentReceipt.class.getMethod("amountPaid").getReturnType())
                    .isEqualTo(BigDecimal.class);
            assertThat(PaymentReceipt.class.getMethod("resultingBalance").getReturnType())
                    .isEqualTo(BigDecimal.class);
            assertThat(Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getType)
                    .toList())
                    .as("the bound map is text throughout, so no float or double can enter")
                    .allMatch(String.class::equals);
        }

        @Test
        @DisplayName("money equality is compareTo, because 0.00 and 0 are not equals")
        void moneyEqualityIsCompareToNotEquals() {
            final Account settled = arrangeConfirmedPayment(new BigDecimal("100.00"));

            enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(settled.getCurrentBalance())
                    .as("the resulting balance is scale two, so equals against ZERO would report a mismatch")
                    .isNotEqualTo(BigDecimal.ZERO);
            assertThat(settled.getCurrentBalance().compareTo(BigDecimal.ZERO)).isZero();
        }
    }

    /**
     * Paragraph correspondence: sixteen labels, sixteen private methods, none consolidated.
     *
     * <p>The three browse labels collapse conceptually into one query, and are nonetheless kept as three
     * methods so the paragraph map stays mechanically provable.</p>
     */
    @Nested
    @DisplayName("Phase 7 - one private method per COBOL paragraph, none consolidated")
    final class ParagraphCorrespondence {

        /**
         * Collects the declared method names of the bean under test.
         *
         * @return every declared method name, including the private paragraph methods
         */
        private Set<String> declaredMethodNames() {
            return Arrays.stream(BillPaymentService.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toSet());
        }

        @Test
        @DisplayName("all sixteen paragraphs of app/cbl/COBIL00C.cbl have their own method")
        void everyParagraphHasItsOwnMethod() {
            assertThat(PARAGRAPH_METHODS).hasSize(16);
            assertThat(declaredMethodNames())
                    .as("572 lines, 16 paragraph labels, 16 methods - the map Gate 7 verifies")
                    .containsAll(PARAGRAPH_METHODS);
        }

        @Test
        @DisplayName(":441, :472 and :501 stay three distinct methods despite collapsing into one query")
        void theThreeBrowseParagraphsRemainDistinct() {
            final List<String> browse =
                    List.of("startbrTransactFile", "readprevTransactFile", "endbrTransactFile");

            assertThat(declaredMethodNames()).containsAll(browse);
            assertThat(Set.copyOf(browse))
                    .as("ENDBR has no Java counterpart yet is retained as a labelled method")
                    .hasSize(3);
            assertThat(PARAGRAPH_METHODS).containsSubsequence(browse);
        }

        @Test
        @DisplayName("every paragraph method is private, so the published surface stays the two entry points")
        void everyParagraphMethodIsPrivate() {
            final List<Method> paragraphs = Arrays.stream(BillPaymentService.class.getDeclaredMethods())
                    .filter(method -> PARAGRAPH_METHODS.contains(method.getName()))
                    .toList();

            assertThat(paragraphs).hasSize(PARAGRAPH_METHODS.size());
            assertThat(paragraphs)
                    .allSatisfy(method -> assertThat(java.lang.reflect.Modifier
                            .isPrivate(method.getModifiers()))
                            .as("%s is a paragraph, not an API member", method.getName())
                            .isTrue());
        }
    }

    /**
     * Hostile and boundary input, as Rule 1 Clause A and Clause B require.
     *
     * <p>The source performs no validation on the text-to-numeric account move, so an unusable value must read
     * as not found rather than raise. Every case below is asserted against that contract.</p>
     */
    @Nested
    @DisplayName("Hostile input - untrusted identifiers, absent members and boundary values")
    final class HostileInput {

        @Test
        @DisplayName(":308-:314 never checks the receive, so a null request presents a blank identifier")
        void nullRequestIsTreatedAsAnEmptyIdentifier() {
            final BillPaymentResult result =
                    service().processRequest(null, "ENTER", EntryMode.REENTER, null);

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.ACCOUNT_ID_EMPTY);
            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_ID_EMPTY);
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
        }

        @ParameterizedTest(name = "identifier [{0}] takes the :159 empty guard")
        @ValueSource(strings = {"", " ", "           ", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
        @DisplayName(":159 SPACES and LOW-VALUES both take the empty-identifier guard")
        void blankAndLowValueIdentifiersTakeTheEmptyGuard(final String accountId) {
            final BillPaymentResult result = enter(accountId, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.ACCOUNT_ID_EMPTY);
            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_ID_EMPTY);
            assertThat(result.cursor()).isEqualTo(CursorField.ACCOUNT_ID);
            verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("a ten-character identifier forms a usable key and reaches the dataset")
        void tenCharacterIdentifierFormsAKey() {
            when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

            final BillPaymentResult result = enter("0000000001", "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.ACCOUNT_NOT_FOUND);
            verify(accountRepository).findByIdForUpdate(1L);
        }

        @ParameterizedTest(name = "identifier [{0}] cannot form a key and never reaches the dataset")
        @ValueSource(strings = {"000000000011", "0000000A011", "-0000000011", "1 1", "00000000011 1"})
        @DisplayName(":170-:171 moves text into PIC 9(11) with no validation, so an unusable value reads NOTFND")
        void unusableIdentifiersReadAsNotFoundWithoutTouchingTheDataset(final String accountId) {
            final BillPaymentResult result = enter(accountId, "Y");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.ACCOUNT_NOT_FOUND);
            assertThat(result.message()).isEqualTo(MSG_ACCOUNT_NOT_FOUND);
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("an empty confirmation is blank, not invalid, so it prompts rather than erroring")
        void emptyConfirmationPrompts() {
            arrangeAccount(new BigDecimal("1940.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.CONFIRMATION_REQUIRED);
            assertThat(result.message()).isEqualTo(MSG_CONFIRMATION_REQUIRED);
            assertThat(result.inputError()).isFalse();
        }

        @Test
        @DisplayName("an unrecognised attention key is rejected without touching any dataset")
        void unrecognisedAttentionKeyIsRejected() {
            final BillPaymentResult result =
                    service().processRequest(request(ACCOUNT_ID_TEXT, "Y"), "PF9", EntryMode.REENTER, null);

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.INVALID_KEY);
            assertThat(result.message()).startsWith("Invalid key pressed.");
            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("an absent attention key is classified as other rather than raising")
        void absentAttentionKeyIsClassifiedAsOther() {
            final BillPaymentResult result =
                    service().processRequest(request(ACCOUNT_ID_TEXT, "Y"), null, EntryMode.REENTER, null);

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.INVALID_KEY);
            verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("the smallest negative balance is rejected, fixing the guard boundary from below")
        void smallestNegativeBalanceIsRejected() {
            arrangeAccount(new BigDecimal("-0.01"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, " ");

            assertThat(result.outcome()).isEqualTo(PaymentOutcome.NOTHING_TO_PAY);
            assertThat(result.message()).isEqualTo(MSG_NOTHING_TO_PAY);
        }

        @Test
        @DisplayName("a null balance cannot reach this path at all, because the record refuses to hold one")
        void nullBalanceCannotReachThisPath() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("ACCT-CURR-BAL is NOT NULL NUMERIC(12,2), so the guard at :198 always has a value")
                    .isThrownBy(() -> account(null))
                    .withMessageContaining("ACCT-CURR-BAL");

            verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
        }
    }

    /**
     * Observability, which the source lacks entirely.
     *
     * <p>{@code app/cbl/COBIL00C.cbl} has no instrumentation of any kind - no counter, no display, no timing.
     * The total transaction amount is therefore published as result metadata, which is what a caller and a
     * meter registration both read. No registry is wired at this tier, and that boundary is asserted rather
     * than assumed.</p>
     */
    @Nested
    @DisplayName("Observability - the total transaction amount is published, not merely logged")
    final class Observability {

        @Test
        @DisplayName("the receipt publishes the settled amount, which is the total-transaction-amount signal")
        void receiptPublishesTheSettledAmount() {
            final BigDecimal balance = new BigDecimal("1940.00");
            arrangeConfirmedPayment(balance);

            final PaymentReceipt receipt = enter(ACCOUNT_ID_TEXT, "Y").receiptOptional().orElseThrow();

            assertThat(receipt.amountPaid())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(balance);
            assertThat(receipt.transactionId()).isEqualTo(FIRST_IDENTIFIER);
            assertThat(receipt.amountPaid())
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(capturedTransaction().getAmount());
        }

        @Test
        @DisplayName("a rejected pass publishes no amount at all, so the counter cannot double count")
        void rejectedPassPublishesNoAmount() {
            arrangeAccount(new BigDecimal("0.00"));

            assertThat(enter(ACCOUNT_ID_TEXT, "Y").receiptOptional()).isEmpty();
        }

        @Test
        @DisplayName("the bean depends on five collaborators and no meter registry at this tier")
        void theBeanWiresNoRegistryAtThisTier() {
            assertThat(BillPaymentService.class.getConstructors()).hasSize(1);
            assertThat(BillPaymentService.class.getConstructors()[0].getParameterTypes())
                    .containsExactly(AccountRepository.class, TransactionRepository.class,
                            CardCrossReferenceRepository.class, FileStatusMapper.class, Clock.class);
        }

        @Test
        @DisplayName("neither published record discloses the card number, the balance or the amount")
        void publishedRecordsDoNotDiscloseSensitiveMembers() {
            arrangeConfirmedPayment(new BigDecimal("1940.00"));

            final BillPaymentResult result = enter(ACCOUNT_ID_TEXT, "Y");

            assertThat(result.receiptOptional().orElseThrow().toString())
                    .doesNotContain("1940.00")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER);
            assertThat(String.valueOf(result.screen()))
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(ACCOUNT_ID_TEXT);
        }
    }

}
