package com.cardemo.unit.service.billing;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.billing.BillPaymentService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fast, fully-mocked unit test for {@link BillPaymentService} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.x translation of the online CICS program {@code app/cbl/COBIL00C.cbl}
 * (CICS transaction {@code CB00}, BMS mapset {@code COBIL00}, "Bill Payment - Pay account balance in
 * full"). Bill payment is feature {@code F-009} of the preserved CardDemo estate: it pays an
 * account's current balance <em>in full</em> in a single unit of work, generating one payment
 * {@code Transaction} for the full balance and decrementing the {@code Account} balance to zero.
 *
 * <h2>Test strategy</h2>
 * <p>These are millisecond, in-memory, pure-Mockito unit tests: there is <em>no</em> Spring context,
 * no database, no Testcontainers and no I/O. The three repository collaborators are Mockito
 * {@code @Mock}s and the system under test is wired by constructor injection via {@code @InjectMocks}.
 * The class runs under {@link MockitoExtension} (default {@code STRICT_STUBS}), so tests that throw
 * before any read stub <em>nothing</em> and assert
 * {@link org.mockito.Mockito#verifyNoInteractions(Object...) verifyNoInteractions} to prove the early
 * exit, while each read/write path stubs <em>only</em> the collaborators it actually exercises with
 * the exact keys the service uses (no {@code lenient()}, no needless {@code any()}).</p>
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.1&ndash;&sect;0.7.2)</h2>
 * <p>Every assertion reflects {@code COBIL00C}'s observable behavior <em>exactly</em>, with no
 * "improvements": the {@code PROCESS-ENTER-KEY} evaluation order (empty-id edit &rarr; confirm
 * {@code EVALUATE} &rarr; account read &rarr; "nothing to pay" edit &rarr; pay/preview), the verbatim
 * {@code WS-MESSAGE} strings (including their trailing {@code "..."} and the canonical <strong>double
 * space</strong> in the success message from the {@code STRING} concatenation at L527-531), the
 * generated transaction constants ({@code '02'}, {@code 2}, {@code 'POS TERM'},
 * {@code 'BILL PAYMENT - ONLINE'}, {@code 999999999}), the full-balance payment arithmetic
 * ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}) and the 16-digit zero-padded id
 * generation ({@code MOVE HIGH-VALUES} + browse-to-end + {@code ADD 1}).</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>Every monetary {@link BigDecimal} assertion uses
 * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(BigDecimal)
 * isEqualByComparingTo} (compareTo semantics) &mdash; <strong>never</strong> the scale-sensitive
 * {@code isEqualTo}/{@code equals} (under which {@code 0.00} is not equal to {@code 0}). This applies
 * to {@code tranAmt}, {@code acctCurrBal}, {@code currentBalance} and {@code newBalance}. String
 * messages and id values use ordinary {@code isEqualTo} because string equality is correct for them.</p>
 *
 * <h2>Golden data</h2>
 * <p>Balances are the canonical values from {@code app/data/ASCII/acctdata.txt}: account
 * {@code 00000000001} carries {@code ACCT-CURR-BAL} = {@code 194.00} (zoned-decimal overpunch
 * {@code 00000001940}{@code &#125;}), per {@code app/cpy/CVACT01Y.cpy}
 * ({@code ACCT-CURR-BAL PIC S9(10)V99} &rarr; scale 2). The COBOL is read-only reference material at
 * the frozen baseline commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only
 * its observable contract is asserted here.</p>
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see AccountRepository
 * @see TransactionRepository
 * @see CardCrossReferenceRepository
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService — COBIL00C bill payment (CB00), full-balance pay parity")
class BillPaymentServiceTest {

    // -----------------------------------------------------------------------------------------------
    // Golden constants (canonical fixture values; see class Javadoc).
    // -----------------------------------------------------------------------------------------------

    /** {@code ACTIDINI PIC X(11)} request value for account 1; the service parses it to {@link #ACCOUNT_ID_KEY}. */
    private static final String ACCOUNT_ID_INPUT = "00000000001";

    /** The parsed numeric account key ({@code Long.parseLong("00000000001")}) used as the JPA id. */
    private static final long ACCOUNT_ID_KEY = 1L;

    /** Account 1 current balance from {@code acctdata.txt} (overpunch {@code 00000001940}{@code &#125;}). */
    private static final String GOLDEN_BALANCE = "194.00";

    /** The 16-character card number resolved from the {@code CXACAIX} cross-reference (&rarr; {@code TRAN-CARD-NUM}). */
    private static final String GOLDEN_CARD_NUMBER = "1234567890123456";

    // -----------------------------------------------------------------------------------------------
    // Collaborators (Mockito mocks) and the system under test (constructor-injected).
    // -----------------------------------------------------------------------------------------------

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @InjectMocks
    private BillPaymentService billPaymentService;

    // -----------------------------------------------------------------------------------------------
    // Dependency-free test-data builders.
    // -----------------------------------------------------------------------------------------------

    /**
     * Builds a {@link BillPaymentRequest} via setters (mirrors {@code RECEIVE MAP} binding
     * {@code ACTIDINI}/{@code CONFIRMI} into {@code COBIL0AI}).
     *
     * @param accountId the {@code ACTIDINI} value (may be {@code null}/blank to drive edits)
     * @param confirm   the {@code CONFIRMI} value (may be {@code null}/blank/{@code Y}/{@code N}/other)
     * @return a populated request
     */
    private BillPaymentRequest request(String accountId, String confirm) {
        BillPaymentRequest req = new BillPaymentRequest();
        req.setAccountId(accountId);
        req.setConfirm(confirm);
        return req;
    }

    /**
     * Builds an in-memory {@link Account} ({@code ACCTDAT} record) with the given id and balance.
     *
     * @param id      the account id ({@code ACCT-ID})
     * @param balance the current balance ({@code ACCT-CURR-BAL}); parsed exactly, preserving scale
     * @return an account whose {@code @Version} is seeded to {@code 0}
     */
    private Account account(long id, String balance) {
        Account acct = new Account();
        acct.setAcctId(id);
        acct.setAcctCurrBal(new BigDecimal(balance));
        acct.setVersion(0L);
        return acct;
    }

    /**
     * Builds an in-memory {@link CardCrossReference} ({@code CARDXREF}/{@code CXACAIX} record).
     *
     * @param cardNum the 16-character card number ({@code XREF-CARD-NUM})
     * @param acctId  the owning account id ({@code XREF-ACCT-ID}; the {@code CXACAIX} key)
     * @return a cross-reference with an arbitrary (non-null) customer id
     */
    private CardCrossReference xref(String cardNum, long acctId) {
        CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum(cardNum);
        x.setXrefAcctId(acctId);
        x.setXrefCustId(9L); // XREF-CUST-ID is irrelevant to bill payment; any non-null value
        return x;
    }

    /**
     * Stubs the three reads the confirmed-payment path performs, in COBOL order: the {@code ACCTDAT}
     * keyed read ({@code findById}), the {@code CXACAIX} alternate-index read ({@code findByXrefAcctId})
     * and the {@code TRANSACT} browse-to-end max-id discovery ({@code findMaxTransactionId}). Every stub
     * it registers is exercised by each caller, so it is safe under {@code STRICT_STUBS}.
     *
     * @param balance    the account balance to seed ({@code ACCT-CURR-BAL})
     * @param maxTranId  the {@code findMaxTransactionId()} result ({@code Optional.empty()} = empty file)
     * @return the managed account the service mutates and persists (for {@code ArgumentCaptor} cross-checks)
     */
    private Account stubConfirmedPaymentReads(String balance, Optional<String> maxTranId) {
        Account acct = account(ACCOUNT_ID_KEY, balance);
        when(accountRepository.findById(ACCOUNT_ID_KEY)).thenReturn(Optional.of(acct));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID_KEY))
                .thenReturn(List.of(xref(GOLDEN_CARD_NUMBER, ACCOUNT_ID_KEY)));
        when(transactionRepository.findMaxTransactionId()).thenReturn(maxTranId);
        return acct;
    }

    // ===============================================================================================
    // 3.1 — Blank / null / whitespace account id -> ValidationException
    // COBOL PROCESS-ENTER-KEY: EVALUATE TRUE WHEN ACTIDINI = SPACES OR LOW-VALUES (L158-167).
    // ===============================================================================================

    /**
     * COBOL L159-163: an empty {@code ACTIDINI} ({@code SPACES OR LOW-VALUES}) is rejected with
     * {@code 'Acct ID can NOT be empty...'} before any file is read. {@code null}, {@code ""} and
     * blank/whitespace all map to that same edit (the service guards with {@code isBlank()}). Under
     * {@code STRICT_STUBS} nothing is stubbed, and {@code verifyNoInteractions} proves the method threw
     * before touching any repository.
     *
     * @param accountId the empty/blank account-id variant under test
     */
    @ParameterizedTest(name = "accountId=[{0}] -> ValidationException \"Acct ID can NOT be empty...\"")
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("3.1 blank/null/whitespace account id -> ValidationException (empty-id edit, L160-163)")
    void processBillPayment_blankAccountId_throwsValidationException(String accountId) {
        BillPaymentRequest req = request(accountId, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Acct ID can NOT be empty");

        // The empty-id edit fires before READ-ACCTDAT — no repository is touched.
        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    /**
     * COBOL {@code ACCT-ID PIC 9(11)} is strictly numeric; the DTO {@code @Pattern("\\d{1,11}")} enforces
     * digits at the controller, but {@link BillPaymentService} re-parses defensively so a direct
     * (non-validated) call still holds parity. A non-blank, non-numeric id therefore fails
     * {@code Long.parseLong} and is reported with the same {@code 'Acct ID can NOT be empty...'} edit
     * before any file is read (no repository is touched).
     */
    @Test
    @DisplayName("3.1 non-numeric account id -> ValidationException, no account read (defensive parse)")
    void processBillPayment_nonNumericAccountId_throwsValidationException() {
        BillPaymentRequest req = request("ABCDEFGHIJK", "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Acct ID can NOT be empty");

        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    // ===============================================================================================
    // 3.2 — Confirm-flag branching: Y / N / blank / other
    // COBOL PROCESS-ENTER-KEY: EVALUATE CONFIRMI OF COBIL0AI (L172-194).
    // ===============================================================================================

    /**
     * COBOL L178-181, {@code WHEN 'N'/'n'}: a decline runs {@code CLEAR-CURRENT-SCREEN} and sets the
     * error flag, short-circuiting the remainder of {@code PROCESS-ENTER-KEY} &mdash; there is no
     * account read, no payment, no exception and no success/confirm message. In REST this is a cleared
     * (empty) {@link BillPaymentRequest.Response}. Verified for both {@code 'N'} and {@code 'n'}.
     *
     * @param confirm the decline keystroke variant ({@code "N"} or {@code "n"})
     */
    @ParameterizedTest(name = "confirm=[{0}] -> cleared response, no read, no write")
    @ValueSource(strings = {"N", "n"})
    @DisplayName("3.2 confirm 'N'/'n' -> cancel: cleared response, no account read, no write (L178-181)")
    void processBillPayment_confirmNo_cancelsWithoutReadOrWrite(String confirm) {
        BillPaymentRequest req = request(ACCOUNT_ID_INPUT, confirm);

        BillPaymentRequest.Response response = billPaymentService.processBillPayment(req);

        // Cleared response: no payment was performed, so id/message/balances are unset (null).
        assertThat(response).isNotNull();
        assertThat(response.getTransactionId()).isNull();
        assertThat(response.getMessage()).isNull();
        assertThat(response.getNewBalance()).isNull();

        // The decline short-circuits before READ-ACCTDAT — no repository is touched.
        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    /**
     * COBOL L185-190, {@code WHEN OTHER}: any confirm value other than {@code Y}/{@code y},
     * {@code N}/{@code n} or blank is rejected with {@code 'Invalid value. Valid values are (Y/N)...'}.
     * The invalid branch fires before {@code READ-ACCTDAT}, so no repository is touched.
     */
    @Test
    @DisplayName("3.2 confirm other ('X') -> ValidationException, no account read (L185-190)")
    void processBillPayment_invalidConfirm_throwsValidationException() {
        BillPaymentRequest req = request(ACCOUNT_ID_INPUT, "X");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid value");

        verifyNoInteractions(accountRepository, transactionRepository, cardCrossReferenceRepository);
    }

    // ===============================================================================================
    // 3.3 — Record lookups not found -> RecordNotFoundException
    // COBOL READ-ACCTDAT-FILE NOTFND (L359-364) and READ-CXACAIX-FILE NOTFND.
    // ===============================================================================================

    /**
     * COBOL L356-364, {@code READ-ACCTDAT-FILE} {@code WHEN DFHRESP(NOTFND)}: a missing account yields
     * {@code 'Account ID NOT found...'}. The VSAM {@code NOTFND} maps to {@link RecordNotFoundException}
     * (HTTP 404) when {@code findById} returns {@code Optional.empty()}. Only {@code findById} is stubbed.
     */
    @Test
    @DisplayName("3.3 account not found -> RecordNotFoundException \"Account ID NOT found...\" (L359-362)")
    void processBillPayment_accountNotFound_throwsRecordNotFoundException() {
        when(accountRepository.findById(ACCOUNT_ID_KEY)).thenReturn(Optional.empty());

        BillPaymentRequest req = request(ACCOUNT_ID_INPUT, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Account ID NOT found");
    }

    /**
     * COBOL {@code READ-CXACAIX-FILE} {@code WHEN DFHRESP(NOTFND)}: when the confirmed-payment path
     * resolves the card cross-reference and the {@code CXACAIX} alternate index returns nothing, the
     * COBOL surfaced {@code 'Account ID NOT found...'}. Here an empty {@code findByXrefAcctId} list maps
     * to {@link RecordNotFoundException}. The xref read happens <em>after</em> the account read and the
     * "nothing to pay" edit, so the account is stubbed with a positive balance; {@code findMaxTransactionId}
     * is never reached and therefore not stubbed.
     */
    @Test
    @DisplayName("3.3 cross-reference not found -> RecordNotFoundException \"Account ID NOT found...\"")
    void processBillPayment_crossReferenceNotFound_throwsRecordNotFoundException() {
        when(accountRepository.findById(ACCOUNT_ID_KEY))
                .thenReturn(Optional.of(account(ACCOUNT_ID_KEY, GOLDEN_BALANCE)));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID_KEY)).thenReturn(List.of());

        BillPaymentRequest req = request(ACCOUNT_ID_INPUT, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Account ID NOT found");

        // The transaction id is never generated and no write occurs once the xref is missing.
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
    }

    // ===============================================================================================
    // 3.4 — Nothing to pay (balance <= 0) -> ValidationException
    // COBOL PROCESS-ENTER-KEY: IF ACCT-CURR-BAL <= ZEROS ... 'You have nothing to pay...' (L197-206).
    // ===============================================================================================

    /**
     * COBOL L198-205: a non-positive balance ({@code ACCT-CURR-BAL <= ZEROS}) is rejected with
     * {@code 'You have nothing to pay...'}. COBOL's {@code <= ZEROS} covers both zero and negative
     * balances, so the service must use {@code compareTo(ZERO) <= 0} (NEVER scale-sensitive
     * {@code equals}). The edit runs after {@code READ-ACCTDAT} but before any payment, so only
     * {@code findById} is stubbed; the transaction/xref repositories are untouched.
     *
     * @param balance the non-positive balance variant ({@code "0.00"} or {@code "-5.00"})
     */
    @ParameterizedTest(name = "balance={0} -> ValidationException \"You have nothing to pay...\"")
    @ValueSource(strings = {"0.00", "-5.00"})
    @DisplayName("3.4 balance <= 0 (zero & negative) -> ValidationException, no payment (L198-205)")
    void processBillPayment_nonPositiveBalance_throwsNothingToPay(String balance) {
        when(accountRepository.findById(ACCOUNT_ID_KEY))
                .thenReturn(Optional.of(account(ACCOUNT_ID_KEY, balance)));

        BillPaymentRequest req = request(ACCOUNT_ID_INPUT, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("nothing to pay");

        // The "nothing to pay" edit fires before READ-CXACAIX and any write.
        verifyNoInteractions(cardCrossReferenceRepository);
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
    }

    // ===============================================================================================
    // 3.5 — Preview / confirm gate (blank confirm) -> balance + "Confirm..." prompt, no writes
    // COBOL PROCESS-ENTER-KEY: ELSE branch 'Confirm to make a bill payment...' (L236-240).
    // ===============================================================================================

    /**
     * COBOL L236-240 ({@code CONFIRMI} blank, {@code CONF-PAY} not set): the program reads the account,
     * paints the current balance and re-prompts with {@code 'Confirm to make a bill payment...'} without
     * writing anything. Here a blank confirm returns a preview {@link BillPaymentRequest.Response}
     * carrying the balance and that prompt; no transaction is written and the account is not updated. The
     * preview path never reads the cross-reference, so {@code cardCrossReferenceRepository} is untouched.
     *
     * @param confirm the preview-triggering confirm variant ({@code null} or blank)
     */
    @ParameterizedTest(name = "confirm=[{0}] -> preview balance + confirm prompt, no writes")
    @NullSource
    @ValueSource(strings = {"", " "})
    @DisplayName("3.5 blank confirm -> preview: current balance + 'Confirm...' prompt, no writes (L236-240)")
    void processBillPayment_blankConfirm_returnsPreviewWithoutWrites(String confirm) {
        when(accountRepository.findById(ACCOUNT_ID_KEY))
                .thenReturn(Optional.of(account(ACCOUNT_ID_KEY, GOLDEN_BALANCE)));

        BillPaymentRequest.Response response =
                billPaymentService.processBillPayment(request(ACCOUNT_ID_INPUT, confirm));

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("Confirm to make a bill payment");
        // Decimal fidelity: compareTo, never scale-sensitive equals (AAP §0.7.3).
        assertThat(response.getCurrentBalance()).isEqualByComparingTo(new BigDecimal(GOLDEN_BALANCE));
        // Preview makes no payment: no transaction id and no post-payment balance.
        assertThat(response.getTransactionId()).isNull();

        // No writes and no cross-reference read on the preview path.
        verifyNoInteractions(cardCrossReferenceRepository);
        verify(transactionRepository, never()).save(any());
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
    }

    // ===============================================================================================
    // 3.6 — Confirmed payment pays the FULL balance; resulting balance -> zero
    // COBOL CONF-PAY-YES branch: MOVE ACCT-CURR-BAL TO TRAN-AMT + COMPUTE ... - TRAN-AMT (L224-234).
    // ===============================================================================================

    /**
     * COBOL L210-235, {@code IF CONF-PAY-YES}: a confirmed payment charges the <em>full</em> current
     * balance ({@code MOVE ACCT-CURR-BAL TO TRAN-AMT}, L224) and then decrements the account
     * ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}, L234), leaving zero after a full
     * payment. Verified for both {@code 'Y'} and {@code 'y'} (case-insensitive {@code CONF-PAY-YES}).
     * Every monetary assertion uses {@code isEqualByComparingTo} (compareTo), never the scale-sensitive
     * {@code equals} (AAP §0.7.3): {@code 0.00} must compare equal to {@code 0}.
     *
     * @param confirm the confirmation keystroke variant ({@code "Y"} or {@code "y"})
     */
    @ParameterizedTest(name = "confirm=[{0}] -> pays full balance, resulting balance 0")
    @ValueSource(strings = {"Y", "y"})
    @DisplayName("3.6 confirmed payment pays FULL balance; resulting balance -> zero (L224-234)")
    void processBillPayment_confirmedPayment_paysFullBalanceToZero(String confirm) {
        Account managed = stubConfirmedPaymentReads(GOLDEN_BALANCE, Optional.empty());

        BillPaymentRequest.Response response =
                billPaymentService.processBillPayment(request(ACCOUNT_ID_INPUT, confirm));

        // TRAN-AMT == full ACCT-CURR-BAL (MOVE ACCT-CURR-BAL TO TRAN-AMT, L224).
        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getTranAmt())
                .isEqualByComparingTo(new BigDecimal(GOLDEN_BALANCE));

        // COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT -> 0 after a full payment (L234).
        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(acctCaptor.capture());
        assertThat(acctCaptor.getValue().getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
        // The persisted account is the same managed instance the service mutated in place.
        assertThat(managed.getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);

        // Response carries the pre-payment balance and the (zero) post-payment balance.
        assertThat(response.getCurrentBalance()).isEqualByComparingTo(new BigDecimal(GOLDEN_BALANCE));
        assertThat(response.getNewBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ===============================================================================================
    // 3.7 — Generated transaction constants, id generation and zeroed timestamps
    // COBOL INITIALIZE TRAN-RECORD + MOVE block (L213-232) and GET-CURRENT-TIMESTAMP (L249-267).
    // ===============================================================================================

    /**
     * COBOL L218-229: the payment {@code TRAN-RECORD} is populated with verbatim constants that form
     * part of the preserved external contract (AAP §0.7.2). The card number is the {@code XREF-CARD-NUM}
     * resolved from the {@code CXACAIX} cross-reference (L225). With an empty {@code TRANSACT} file the
     * generated id is {@code 0000000000000001} (browse-to-end yields zero, then {@code ADD 1}, then the
     * {@code PIC 9(16)} zero-pad). The {@code GET-CURRENT-TIMESTAMP} paragraph (L249-267) zeroes the
     * sub-second fraction ({@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6}); the entity maps the timestamps to
     * {@code LocalDateTime}, so the faithful parity check is non-null, both equal (one instant assigned
     * to {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}) and whole-second (zero nanos), not an exact value.
     */
    @Test
    @DisplayName("3.7 confirmed payment populates verbatim transaction constants + zeroed timestamps (L218-232)")
    void processBillPayment_confirmedPayment_populatesTransactionConstants() {
        stubConfirmedPaymentReads(GOLDEN_BALANCE, Optional.empty());

        billPaymentService.processBillPayment(request(ACCOUNT_ID_INPUT, "Y"));

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(txnCaptor.capture());
        Transaction saved = txnCaptor.getValue();

        // Verbatim COBOL MOVE constants (L220-229).
        assertThat(saved.getTranTypeCd()).isEqualTo("02");                 // MOVE '02' TO TRAN-TYPE-CD
        assertThat(saved.getTranCatCd()).isEqualTo(2);                     // MOVE 2 TO TRAN-CAT-CD
        assertThat(saved.getTranSource()).isEqualTo("POS TERM");           // MOVE 'POS TERM'
        assertThat(saved.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE"); // MOVE 'BILL PAYMENT - ONLINE'
        assertThat(saved.getTranMerchantId()).isEqualTo(999999999L);       // MOVE 999999999
        assertThat(saved.getTranMerchantName()).isEqualTo("BILL PAYMENT"); // MOVE 'BILL PAYMENT'
        assertThat(saved.getTranMerchantCity()).isEqualTo("N/A");          // MOVE 'N/A'
        assertThat(saved.getTranMerchantZip()).isEqualTo("N/A");           // MOVE 'N/A'
        assertThat(saved.getTranCardNum()).isEqualTo(GOLDEN_CARD_NUMBER);  // MOVE XREF-CARD-NUM
        // Empty TRANSACT file -> 0 + 1 -> 16-digit zero-pad (L212-219).
        assertThat(saved.getTranId()).isEqualTo("0000000000000001");

        // Timestamps: non-null, identical instant, sub-second fraction zeroed (L263-266).
        assertThat(saved.getTranOrigTs()).isNotNull();
        assertThat(saved.getTranProcTs()).isNotNull();
        assertThat(saved.getTranOrigTs()).isEqualTo(saved.getTranProcTs());
        assertThat(saved.getTranOrigTs().getNano()).isZero();
    }

    /**
     * COBOL L212-219: the next transaction id is the existing maximum plus one. With an existing max
     * {@code TRAN-ID} of {@code 0000000000000010}, the generated id is {@code 0000000000000011} (parse
     * to {@code 10}, {@code ADD 1}, then the {@code PIC 9(16)} / {@code %016d} zero-pad). This proves
     * the increment and zero-pad are performed in the service, not the repository.
     */
    @Test
    @DisplayName("3.7 transaction id = max(existing) + 1, 16-digit zero-padded (browse-to-end + ADD 1)")
    void processBillPayment_confirmedPayment_generatesIdFromMaxPlusOne() {
        stubConfirmedPaymentReads(GOLDEN_BALANCE, Optional.of("0000000000000010"));

        billPaymentService.processBillPayment(request(ACCOUNT_ID_INPUT, "Y"));

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getTranId()).isEqualTo("0000000000000011");
    }

    // ===============================================================================================
    // 3.8 — Success message has TWO spaces
    // COBOL WRITE-TRANSACT-FILE NORMAL: STRING 'Payment successful. ' DELIMITED ... (L527-531).
    // ===============================================================================================

    /**
     * COBOL L527-531: the success message is built by {@code STRING} concatenation of
     * {@code 'Payment successful. '} (trailing space) and {@code ' Your Transaction ID is '} (leading
     * space), yielding the canonical <strong>double</strong> space between {@code "successful."} and
     * {@code "Your"}. That double space is part of the preserved external contract and is asserted
     * verbatim. String equality is correct here (only {@link BigDecimal} comparisons must use
     * {@code compareTo}). The generated id also surfaces as the response {@code confirmationNumber}.
     */
    @Test
    @DisplayName("3.8 success message has TWO spaces: 'Payment successful.  Your Transaction ID is ...' (L527-531)")
    void processBillPayment_confirmedPayment_successMessageHasDoubleSpace() {
        stubConfirmedPaymentReads(GOLDEN_BALANCE, Optional.empty());

        BillPaymentRequest.Response response =
                billPaymentService.processBillPayment(request(ACCOUNT_ID_INPUT, "Y"));

        assertThat(response.getMessage())
                .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000001.");
        // Lock the double space explicitly so a future single-space regression is caught.
        assertThat(response.getMessage()).contains("Payment successful.  Your");
        assertThat(response.getTransactionId()).isEqualTo("0000000000000001");
        assertThat(response.getConfirmationNumber()).isEqualTo("0000000000000001");
    }

    // ===============================================================================================
    // 3.9 — Dual write issued within the single processBillPayment invocation (single CICS UOW)
    // COBOL WRITE-TRANSACT-FILE (L233) then UPDATE-ACCTDAT-FILE (L235), one unit of work.
    // ===============================================================================================

    /**
     * COBOL L233/L235: a confirmed payment performs two writes &mdash; the transaction insert
     * ({@code WRITE-TRANSACT-FILE}) and the account balance {@code REWRITE} ({@code UPDATE-ACCTDAT-FILE})
     * &mdash; that {@code COBIL00C} committed together (single CICS unit of work) before its implicit
     * {@code RETURN}. Runtime rollback atomicity is guaranteed by the service's {@code @Transactional}
     * (the COBOL {@code SYNCPOINT}/single UOW) and is exercised end-to-end by the integration test; this
     * unit test asserts that <em>both</em> writes are issued exactly once within the one invocation and
     * that they are mutually consistent (the transaction charges the full original balance; the account
     * is decremented to zero).
     */
    @Test
    @DisplayName("3.9 confirmed payment issues BOTH writes within one processBillPayment call (single UOW)")
    void processBillPayment_confirmedPayment_dualWriteWithinSingleInvocation() {
        stubConfirmedPaymentReads(GOLDEN_BALANCE, Optional.empty());

        billPaymentService.processBillPayment(request(ACCOUNT_ID_INPUT, "Y"));

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(transactionRepository, times(1)).saveAndFlush(txnCaptor.capture());
        verify(accountRepository, times(1)).save(acctCaptor.capture());

        // The two writes are consistent: txn TRAN-AMT == original balance; account decremented to 0.
        assertThat(txnCaptor.getValue().getTranAmt())
                .isEqualByComparingTo(new BigDecimal(GOLDEN_BALANCE));
        assertThat(acctCaptor.getValue().getAcctCurrBal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ===============================================================================================
    // 3.10 — Duplicate transaction id -> DuplicateRecordException; account NOT updated
    // COBOL WRITE-TRANSACT-FILE DUPKEY/DUPREC -> 'Tran ID already exist...' (L533-538).
    // ===============================================================================================

    /**
     * COBOL L533-538, {@code WRITE-TRANSACT-FILE} {@code WHEN DFHRESP(DUPKEY)/DFHRESP(DUPREC)}: a
     * duplicate transaction id yields {@code 'Tran ID already exist...'}. The service flushes the insert
     * ({@code saveAndFlush}) so the duplicate-key violation surfaces immediately as a
     * {@link DataIntegrityViolationException}, which it maps to {@link DuplicateRecordException}
     * (HTTP 409). Because the transaction write fails, the account balance {@code REWRITE} must never
     * run (the whole {@code @Transactional} unit of work rolls back, so no partial payment persists).
     */
    @Test
    @DisplayName("3.10 duplicate transaction id -> DuplicateRecordException; account NOT updated (L533-538)")
    void processBillPayment_duplicateTransactionId_throwsDuplicateRecordException() {
        stubConfirmedPaymentReads(GOLDEN_BALANCE, Optional.empty());
        when(transactionRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate transaction id"));

        BillPaymentRequest req = request(ACCOUNT_ID_INPUT, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessageContaining("already exist");

        // The balance REWRITE must not happen once the transaction WRITE fails.
        verify(accountRepository, never()).save(any());
    }


}
