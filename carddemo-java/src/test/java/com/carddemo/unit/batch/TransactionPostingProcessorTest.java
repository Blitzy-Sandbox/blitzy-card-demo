package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.processors.TransactionPostingProcessor;
import com.carddemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.DailyTransaction;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.enums.RejectCode;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.shared.FileStatusMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-JVM unit tests for {@link TransactionPostingProcessor}, the per-record
 * {@link org.springframework.batch.item.ItemProcessor} that re-platforms the daily-transaction
 * posting logic of the mainframe program {@code app/cbl/CBTRN02C.cbl} onto Spring Batch
 * (REFERENCE-ONLY; the COBOL is not copied; source commit {@code 27d6c6f}). The originating job
 * context is {@code app/jcl/POSTTRAN.jcl} ({@code STEP15 EXEC PGM=CBTRN02C}, whose {@code DALYREJS}
 * reject file is {@code LRECL=430} = 350 data + 80 trailer).
 *
 * <p>These tests assert byte-exact parity with the COBOL {@code 1500-VALIDATE-TRAN} cascade and the
 * {@code 2000-POST-TRANSACTION} posting branch:</p>
 * <ul>
 *   <li><b>Stage A</b> ({@code 1500-A-LOOKUP-XREF}) &mdash; an absent card cross-reference rejects
 *       with {@code 100} and short-circuits before the account is ever read.</li>
 *   <li><b>Stage B</b> ({@code 1500-B-LOOKUP-ACCT}) &mdash; an absent account rejects with
 *       {@code 101} and short-circuits before the transaction-category balance is ever read.</li>
 *   <li><b>Stage C</b> (overlimit) &mdash; {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT -
 *       ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}; rejects with {@code 102} when
 *       {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} fails. The check uses
 *       {@link BigDecimal#compareTo(BigDecimal)}, never {@code equals}.</li>
 *   <li><b>Stage D</b> (expiration) &mdash; rejects with {@code 103} when
 *       {@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10)} fails (the getter preserves the
 *       COBOL field misspelling {@code ACCT-EXPIRAION-DATE}).</li>
 * </ul>
 *
 * <p>The headline parity case ({@link #bothOverlimitAndExpired_finalRejectCodeIs103_expirationPrecedence()})
 * proves that C and D run as two consecutive {@code IF} blocks with no short-circuit between them:
 * when both fail, the expiration reason {@code 103} overwrites the overlimit reason {@code 102}.</p>
 *
 * <p>The success path proves the 1:1 field mapping onto a {@link Transaction}, the deterministic
 * {@code TRAN-PROC-TS} stamped from an injected fixed {@link Clock}, the {@code 2700-UPDATE-TCATBAL}
 * find-or-create of the transaction-category balance, the {@code 2800-UPDATE-ACCOUNT-REC} credit/debit
 * sign-split, and the read-only invariant: the processor performs reads and in-memory arithmetic only
 * and never calls a repository {@code save}/{@code saveAndFlush} (persistence is the writer's job, and
 * reject {@code 109} is the writer's {@code REWRITE INVALID KEY} path, out of scope here).</p>
 *
 * <p>Mockito mocks the three repositories and the {@link FileStatusMapper}. No Spring context,
 * Testcontainers, or LocalStack is started. Monetary values are always asserted by value with
 * {@code compareTo} semantics and never with scale-sensitive {@code BigDecimal.equals}
 * (AAP &sect;0.8.2). Reject descriptions are sourced from {@link RejectCode}, never hardcoded.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionPostingProcessor - CBTRN02C 4-stage cascade + 103-precedence parity")
class TransactionPostingProcessorTest {

    /** 26-character {@code DALYTRAN-ORIG-TS} whose first ten characters are the origination date. */
    private static final String ORIG_TS = "2022-07-18-00.00.00.000000";

    /**
     * Expected {@code TRAN-PROC-TS} for the fixed clock: {@code 2022-07-18T10:15:30.123456Z} rendered
     * in the DB2 layout {@code yyyy-MM-dd-HH.mm.ss.SSSSSS}.
     */
    private static final String EXPECTED_PROC_TS = "2022-07-18-10.15.30.123456";

    /** Fixed clock so the DB2-format processing timestamp is deterministic. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-18T10:15:30.123456Z"), ZoneOffset.UTC);

    @Mock
    private CardCrossReferenceRepository xrefRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionCategoryBalanceRepository tcatbalRepository;

    @Mock
    private FileStatusMapper fileStatusMapper;

    private TransactionPostingProcessor processor;

    @BeforeEach
    void setUp() {
        // The fixed clock is supplied through the production five-argument constructor so the
        // DB2-format processing timestamp is deterministic. Metrics are recorded by the writer
        // (TransactionWriter), not the processor, so no MeterRegistry collaborator is required.
        processor = new TransactionPostingProcessor(
                xrefRepository, accountRepository, tcatbalRepository,
                fileStatusMapper, FIXED_CLOCK);
    }

    @Test
    @DisplayName("Stage A: absent cross-reference rejects 100 and never reads the account (1500-A-LOOKUP-XREF)")
    void stageA_missingXref_rejects100_andStops() {
        when(xrefRepository.findById("9999999999999999")).thenReturn(Optional.empty());

        PostingResult r = processor.process(daily("9999999999999999", "01", 5, "10.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode()).isEqualTo(100);
        assertThat(r.rejectReasonDescription().strip())
                .isEqualTo(RejectCode.INVALID_CARD_NUMBER.getDescription());
        assertThat(r.postedTransaction()).isNull();
        assertThat(r.updatedAccount()).isNull();
        assertThat(r.updatedCategoryBalance()).isNull();
        // The 350-byte reject data is carried for the downstream reject writer.
        assertThat(r.originalDailyTransaction()).isNotNull();
        // STOP: the cascade short-circuits before the account or TCATBAL is ever read.
        verifyNoInteractions(accountRepository, tcatbalRepository);
    }

    @Test
    @DisplayName("Stage B: absent account rejects 101 and never reads TCATBAL (1500-B-LOOKUP-ACCT)")
    void stageB_missingAccount_rejects101_andStops() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        when(accountRepository.findById(12345L)).thenReturn(Optional.empty());

        PostingResult r = processor.process(daily("1234567890123456", "01", 5, "10.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode()).isEqualTo(101);
        assertThat(r.rejectReasonDescription().strip())
                .isEqualTo(RejectCode.ACCOUNT_NOT_FOUND.getDescription());
        assertThat(r.postedTransaction()).isNull();
        // STOP: TCATBAL is only read on the posting path, which is never reached.
        verifyNoInteractions(tcatbalRepository);
    }

    @Test
    @DisplayName("Stage C only: overlimit rejects 102 (ACCT-CREDIT-LIMIT >= WS-TEMP-BAL fails)")
    void stageC_overlimitOnly_rejects102() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        // tempBal = 1000 - 0 + 500 = 1500 > creditLimit 1000 -> overlimit; expDate 2099 keeps D passing.
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "0.00", "1000.00", "1000.00", "0.00", "2099-12-31")));

        PostingResult r = processor.process(daily("1234567890123456", "01", 5, "500.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode()).isEqualTo(102);
        assertThat(r.rejectReasonDescription().strip())
                .isEqualTo(RejectCode.OVERLIMIT_TRANSACTION.getDescription());
        verifyNoInteractions(tcatbalRepository);
    }

    @Test
    @DisplayName("Stage D only: expired account rejects 103 (ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10) fails)")
    void stageD_expirationOnly_rejects103() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        // tempBal = 10 <= creditLimit 1000 -> C passes; expDate 2020-01-01 < 2022-07-18 -> D fails.
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "0.00", "1000.00", "0.00", "0.00", "2020-01-01")));

        PostingResult r = processor.process(daily("1234567890123456", "01", 5, "10.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode()).isEqualTo(103);
        assertThat(r.rejectReasonDescription().strip())
                .isEqualTo(RejectCode.TRANSACTION_AFTER_EXPIRATION.getDescription());
        verifyNoInteractions(tcatbalRepository);
    }

    @Test
    @DisplayName("Stage C boundary: credit limit exactly equal to WS-TEMP-BAL passes (compareTo >= 0, not equals)")
    void overlimitBoundary_equalCreditLimit_passesStageC() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        // tempBal = 0 - 0 + 1000 = 1000 == creditLimit 1000 -> inclusive boundary passes C; 2099 passes D.
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(12345L, "01", 5)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        PostingResult r = processor.process(daily("1234567890123456", "01", 5, "1000.00", ORIG_TS));

        assertThat(r.rejected()).isFalse();
        assertThat(r.rejectCode()).isNull();
        assertThat(r.postedTransaction()).isNotNull();
    }

    @Test
    @DisplayName("CRITICAL parity: both overlimit AND expired -> 103, not 102 (no short-circuit; D overwrites C)")
    void bothOverlimitAndExpired_finalRejectCodeIs103_expirationPrecedence() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        // C fails: tempBal 1500 > limit 1000 (would set 102). D fails: expDate 2020 < 2022-07-18 (sets 103).
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "0.00", "1000.00", "1000.00", "0.00", "2020-01-01")));

        PostingResult r = processor.process(daily("1234567890123456", "01", 5, "500.00", ORIG_TS));

        assertThat(r.rejected()).isTrue();
        assertThat(r.rejectCode())
                .withFailMessage("CBTRN02C runs the overlimit (102) and expiration (103) checks "
                        + "sequentially with no short-circuit; when both fail, 103 (expiration) must "
                        + "overwrite 102 (overlimit). Got %s", r.rejectCode())
                .isEqualTo(103);
        assertThat(r.rejectReasonDescription().strip())
                .isEqualTo(RejectCode.TRANSACTION_AFTER_EXPIRATION.getDescription());
        verifyNoInteractions(tcatbalRepository);
    }

    @Test
    @DisplayName("Reject trailer is exactly 80 chars: 4-digit zero-padded code + 76-char space-padded description")
    void rejectTrailer_is80Chars_codeZeroPaddedPlusDescription() {
        when(xrefRepository.findById("9999999999999999")).thenReturn(Optional.empty());

        PostingResult r = processor.process(daily("9999999999999999", "01", 5, "10.00", ORIG_TS));

        String trailer = r.rejectTrailer();
        assertThat(trailer).isNotNull();
        assertThat(trailer).hasSize(80);
        assertThat(trailer.substring(0, 4)).isEqualTo("0100");
        assertThat(trailer.substring(4)).hasSize(76);
        assertThat(trailer.substring(4).strip())
                .isEqualTo(RejectCode.INVALID_CARD_NUMBER.getDescription());
    }

    @Test
    @DisplayName("Success + TCATBAL absent: posts the record, creates a balance equal to the amount, maps fields 1:1")
    void allStagesPass_tcatbalAbsent_createsBalanceEqualToAmount() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(12345L, "01", 5)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        DailyTransaction dailyTxn = daily("1234567890123456", "01", 5, "150.00", ORIG_TS);
        PostingResult r = processor.process(dailyTxn);

        assertThat(r.rejected()).isFalse();
        assertThat(r.postedTransaction()).isNotNull();
        assertThat(r.updatedAccount()).isNotNull();
        assertThat(r.updatedCategoryBalance()).isNotNull();

        // 2000-POST-TRANSACTION: field-for-field copy of DALYTRAN onto TRAN.
        Transaction posted = r.postedTransaction();
        assertThat(posted.getTranId()).isEqualTo("DT00000000000001");
        assertThat(posted.getTranTypeCd()).isEqualTo("01");
        assertThat(posted.getTranCatCd()).isEqualTo(5);
        assertThat(posted.getTranSource()).isEqualTo("POS TERM");
        assertThat(posted.getTranCardNum()).isEqualTo("1234567890123456");
        assertThat(posted.getTranOrigTs()).isEqualTo(dailyTxn.getDalytranOrigTs());
        assertThat(posted.getTranMerchantId()).isEqualTo(123456789L);
        assertThat(posted.getTranAmt()).isEqualByComparingTo(new BigDecimal("150.00"));

        // 2700-A-CREATE-TCATBAL-REC: new balance initialized to the transaction amount.
        assertThat(r.updatedCategoryBalance().getTranCatBal())
                .isEqualByComparingTo(new BigDecimal("150.00"));

        // The composite key is the (XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD) triple.
        verify(tcatbalRepository).findById(new TransactionCategoryBalanceId(12345L, "01", 5));
    }

    @Test
    @DisplayName("Success + TCATBAL present: adds the amount to the existing balance (2700-B-UPDATE-TCATBAL-REC)")
    void allStagesPass_tcatbalPresent_addsAmountToExistingBalance() {
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(12345L, "01", 5);
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(key)).thenReturn(Optional.of(existingTcatbal(key, "40.00")));
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(false);

        PostingResult r = processor.process(daily("1234567890123456", "01", 5, "150.00", ORIG_TS));

        assertThat(r.rejected()).isFalse();
        assertThat(r.updatedCategoryBalance().getTranCatBal())
                .isEqualByComparingTo(new BigDecimal("190.00"));
    }

    @Test
    @DisplayName("tranProcTs is stamped from the injected Clock in DB2 format yyyy-MM-dd-HH.mm.ss.SSSSSS")
    void tranProcTs_setFromInjectedClock_db2Format() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "0.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(12345L, "01", 5)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        PostingResult r = processor.process(daily("1234567890123456", "01", 5, "150.00", ORIG_TS));

        assertThat(r.postedTransaction().getTranProcTs()).isEqualTo(EXPECTED_PROC_TS);
    }

    @Test
    @DisplayName("Account update, positive amount: adds to ACCT-CURR-BAL and ACCT-CURR-CYC-CREDIT (2800-UPDATE-ACCOUNT-REC)")
    void accountUpdate_positiveAmount_addsToCurrBalAndCycCredit() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "100.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(12345L, "01", 5)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        PostingResult r = processor.process(daily("1234567890123456", "01", 5, "150.00", ORIG_TS));

        assertThat(r.updatedAccount().getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(r.updatedAccount().getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(r.updatedAccount().getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("Account update, negative amount: adds to ACCT-CURR-BAL and ACCT-CURR-CYC-DEBIT (sign-split via compareTo)")
    void accountUpdate_negativeAmount_addsToCurrBalAndCycDebit() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        // tempBal = 0 - 0 + (-30) = -30 <= creditLimit 1000 -> C passes; expDate 2099 -> D passes.
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "100.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(12345L, "01", 5)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        PostingResult r = processor.process(daily("1234567890123456", "01", 5, "-30.00", ORIG_TS));

        assertThat(r.updatedAccount().getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(r.updatedAccount().getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("-30.00"));
        assertThat(r.updatedAccount().getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("Read-only invariant: the processor never persists (save/saveAndFlush belong to the writer)")
    void processor_neverPersists() {
        when(xrefRepository.findById("1234567890123456"))
                .thenReturn(Optional.of(xref("1234567890123456", 12345L)));
        when(accountRepository.findById(12345L))
                .thenReturn(Optional.of(acct(12345L, "100.00", "1000.00", "0.00", "0.00", "2099-12-31")));
        when(tcatbalRepository.findById(new TransactionCategoryBalanceId(12345L, "01", 5)))
                .thenReturn(Optional.empty());
        when(fileStatusMapper.isRecordNotFound(anyString())).thenReturn(true);

        processor.process(daily("1234567890123456", "01", 5, "150.00", ORIG_TS));

        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).saveAndFlush(any());
        verify(tcatbalRepository, never()).save(any());
        verify(xrefRepository, never()).save(any());
    }

    /**
     * Builds a fully populated {@link DailyTransaction} from the {@code CVTRA06Y} (350-byte) layout
     * so the posting assertions exercise a realistic record rather than a sparse stub.
     *
     * @param card    the 16-character {@code DALYTRAN-CARD-NUM} cross-reference key
     * @param typeCd  the 2-character {@code DALYTRAN-TYPE-CD}
     * @param catCd   the {@code DALYTRAN-CAT-CD} category code
     * @param amt     the {@code DALYTRAN-AMT} monetary value (scale preserved by the caller)
     * @param origTs  the 26-character {@code DALYTRAN-ORIG-TS} (first ten characters are the date)
     * @return a new, fully populated daily-transaction record
     */
    private DailyTransaction daily(String card, String typeCd, int catCd, String amt, String origTs) {
        DailyTransaction d = new DailyTransaction();
        d.setDalytranId("DT00000000000001");
        d.setDalytranTypeCd(typeCd);
        d.setDalytranCatCd(catCd);
        d.setDalytranSource("POS TERM");
        d.setDalytranDesc("GROCERY");
        d.setDalytranAmt(new BigDecimal(amt));
        d.setDalytranMerchantId(123456789L);
        d.setDalytranMerchantName("ACME GROCERY");
        d.setDalytranMerchantCity("SEATTLE");
        d.setDalytranMerchantZip("98101-0000");
        d.setDalytranCardNum(card);
        d.setDalytranOrigTs(origTs);
        d.setDalytranProcTs("");
        return d;
    }

    /**
     * Builds a {@link CardCrossReference} ({@code CVACT03Y}) linking a card number to an account id.
     *
     * @param card   the 16-character {@code XREF-CARD-NUM}
     * @param acctId the {@code XREF-ACCT-ID} resolved by the cross-reference read
     * @return a new card cross-reference record
     */
    private CardCrossReference xref(String card, Long acctId) {
        CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum(card);
        x.setXrefCustId(7L);
        x.setXrefAcctId(acctId);
        return x;
    }

    /**
     * Builds an {@link Account} ({@code CVACT01Y}, 300-byte) with the balance and date fields the
     * overlimit (C) and expiration (D) checks read; the remaining fields are set to neutral defaults.
     *
     * @param id          the {@code ACCT-ID}
     * @param currBal     the {@code ACCT-CURR-BAL}
     * @param creditLimit the {@code ACCT-CREDIT-LIMIT} compared against {@code WS-TEMP-BAL}
     * @param cycCredit   the {@code ACCT-CURR-CYC-CREDIT}
     * @param cycDebit    the {@code ACCT-CURR-CYC-DEBIT}
     * @param expDate     the {@code ACCT-EXPIRAION-DATE} (yyyy-MM-dd; getter preserves the misspelling)
     * @return a new account record
     */
    private Account acct(Long id, String currBal, String creditLimit, String cycCredit,
            String cycDebit, String expDate) {
        Account a = new Account();
        a.setAcctId(id);
        a.setAcctActiveStatus("Y");
        a.setAcctCurrBal(new BigDecimal(currBal));
        a.setAcctCreditLimit(new BigDecimal(creditLimit));
        a.setAcctCashCreditLimit(new BigDecimal("0.00"));
        a.setAcctCurrCycCredit(new BigDecimal(cycCredit));
        a.setAcctCurrCycDebit(new BigDecimal(cycDebit));
        a.setAcctExpiraionDate(expDate);
        a.setAcctGroupId("DEFAULT   ");
        return a;
    }

    /**
     * Builds an existing {@link TransactionCategoryBalance} ({@code CVTRA01Y}) for the update path.
     *
     * @param key the composite key identifying the balance row
     * @param bal the current {@code TRAN-CAT-BAL} value
     * @return a new transaction-category-balance record carrying the supplied balance
     */
    private TransactionCategoryBalance existingTcatbal(TransactionCategoryBalanceId key, String bal) {
        TransactionCategoryBalance t = new TransactionCategoryBalance();
        t.setId(key);
        t.setTranCatBal(new BigDecimal(bal));
        return t;
    }
}
