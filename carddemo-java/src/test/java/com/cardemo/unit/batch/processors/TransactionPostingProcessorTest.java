package com.cardemo.unit.batch.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.model.dto.PostedTransactionResult;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Fast, fully-mocked unit test for {@link TransactionPostingProcessor} &mdash; the Spring Batch
 * {@code ItemProcessor<DailyTransaction, PostedTransactionResult>} that reproduces the
 * {@code 1500-VALIDATE-TRAN} validation cascade and the {@code 2000-POST-TRANSACTION} field mapping of
 * the legacy AWS CardDemo daily-posting batch program {@code app/cbl/CBTRN02C.cbl}.
 *
 * <h2>Provenance / governance</h2>
 * <p>The COBOL source {@code app/cbl/CBTRN02C.cbl} is <strong>read-only reference</strong> material at the
 * frozen legacy baseline commit SHA {@code 27d6c6f}; it is <strong>never copied</strong> into this
 * repository and is referenced here only by SHA and paragraph/line locator. Per the Minimal Change Clause
 * (AAP &sect;0.7.1) and the 100% behavioural-parity requirement (AAP &sect;0.7.2), these tests assert
 * COBOL-identical reject codes, evaluation ordering and the "reject &amp; continue" contract and invent
 * nothing. The application base package is {@code com.cardemo} (decision D-006).</p>
 *
 * <h2>The three parity traps this test locks down</h2>
 * <ol>
 *   <li><strong>Credit basis.</strong> The over-limit check uses
 *       {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} (CBTRN02C L403-405), NOT the
 *       running balance {@code ACCT-CURR-BAL}.</li>
 *   <li><strong>Two independent IFs.</strong> The credit-limit check (reject {@code 102}, L407-413) and the
 *       expiration check (reject {@code 103}, L414-420) are evaluated as two sequential, independent
 *       {@code IF} statements (not {@code ELSE IF}); when both fail, {@code 103} overwrites {@code 102}
 *       (last-failure-wins).</li>
 *   <li><strong>Reject &amp; continue.</strong> A rejected record is <em>carried</em> in a non-null
 *       {@link PostedTransactionResult} with the failing {@link RejectCode}; the processor never throws and
 *       never returns {@code null} (a {@code null} return would make Spring Batch silently filter the reject
 *       out of the chunk).</li>
 * </ol>
 *
 * <h2>Test strategy</h2>
 * <p>This is a pure unit test: {@code @ExtendWith(MockitoExtension.class)} with {@code @Mock} repositories
 * and the processor instantiated directly &mdash; <strong>no</strong> Spring context, database, AWS,
 * Testcontainers or {@code spring-batch-test}, and no new dependencies. Mockito runs in its default
 * {@code STRICT_STUBS} mode; each test stubs only the lookups its path actually consumes so the build stays
 * warning-free. All monetary values are {@link BigDecimal} built from {@link String} literals and compared
 * with {@code compareTo}/{@code isEqualByComparingTo}, never {@code equals} (AAP &sect;0.7.3).</p>
 *
 * <h2>Note on {@code java.time} field types (deviation rationale)</h2>
 * <p>The migrated entities store {@code DALYTRAN-ORIG-TS}/{@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} as
 * {@link LocalDateTime} and {@code ACCT-EXPIRAION-DATE} as {@link LocalDate} (not the raw 26-/10-character
 * COBOL text). Because the production processor exposes an injectable {@link Clock}, the
 * {@code tranProcTs} assertion injects a fixed {@link Clock} and asserts the <em>exact</em> stamped value
 * (the prompt's preferred deterministic path), rather than a 26-character string regex which is
 * inapplicable to a {@link LocalDateTime}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionPostingProcessor — CBTRN02C 1500-VALIDATE-TRAN cascade + 2000-POST mapping (SHA 27d6c6f)")
class TransactionPostingProcessorTest {

    // --- Shared fixture constants -------------------------------------------------------------------

    /** Card number used as the cross-reference key (clean, recognisable test PAN). */
    private static final String CARD_NUM = "4111111111111111";

    /** Account id resolved from the cross-reference (acctdata.txt record 1 -> 00000000001 -> 1). */
    private static final Long ACCT_ID = 1L;

    /** Customer id carried on the cross-reference (not read by the processor; set for realism). */
    private static final Long CUST_ID = 100000001L;

    // Field-mapping literals, informed by app/data/ASCII/dailytran.txt record 1 (hardcoded, never read).
    private static final String TXN_ID = "0000000000683580";
    private static final String TYPE_CD = "01";
    private static final Integer CAT_CD = 1;
    private static final String SOURCE = "POS TERM";
    private static final String DESC = "Purchase at Abshire-Lowe";
    private static final Long MERCHANT_ID = 800000000L;
    private static final String MERCHANT_NAME = "Abshire-Lowe";
    private static final String MERCHANT_CITY = "North Enoshaven";
    private static final String MERCHANT_ZIP = "72112";

    /** Transaction origination timestamp (origTs date 2022-06-10 drives the expiration comparison). */
    private static final LocalDateTime ORIG_TS = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /** Date portion of {@link #ORIG_TS} (COBOL {@code DALYTRAN-ORIG-TS(1:10)}). */
    private static final LocalDate ORIG_DATE = LocalDate.of(2022, 6, 10);

    /** Expiration safely after {@link #ORIG_DATE}: the expiration (103) check passes. */
    private static final LocalDate FUTURE_EXP = LocalDate.of(2099, 12, 31);

    /** Expiration before {@link #ORIG_DATE}: the expiration (103) check fails. */
    private static final LocalDate PAST_EXP = LocalDate.of(2020, 1, 1);

    /**
     * Fixed clock pinned to a sub-hundredth instant so the processing-timestamp assertion is deterministic
     * AND exercises the COBOL hundredths truncation: {@code 687654321 ns -> 68 hundredths -> ".680000"}.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-06-15T10:30:45.687654321Z"), ZoneOffset.UTC);

    /**
     * Exact value {@code TRAN-PROC-TS} must hold after {@code Z-GET-DB2-FORMAT-TIMESTAMP} runs on
     * {@link #FIXED_CLOCK}: the nanos are truncated to hundredths (680,000,000 ns). Differs from
     * {@link #ORIG_TS} (2022-06-10), so the "re-stamped" assertion holds.
     */
    private static final LocalDateTime EXPECTED_PROC_TS =
            LocalDateTime.of(2024, 6, 15, 10, 30, 45, 680_000_000);

    @Mock
    private CardCrossReferenceRepository crossReferenceRepository;

    @Mock
    private AccountRepository accountRepository;

    private TransactionPostingProcessor processor;

    @BeforeEach
    void setUp() {
        // Inject the fixed clock so the freshly-stamped TRAN-PROC-TS is deterministic (the production
        // 2-arg constructor would use the system clock). Both repositories are STRICT_STUBS mocks.
        processor = new TransactionPostingProcessor(crossReferenceRepository, accountRepository, FIXED_CLOCK);
    }

    // --- Phase A: fixtures / builders ---------------------------------------------------------------

    /**
     * Builds a fully-populated, valid {@link DailyTransaction} staging record (all 13 mapped fields set).
     * The card number matches {@link #CARD_NUM} so the cross-reference stub resolves.
     *
     * @return a fresh, fully-populated daily-transaction input
     */
    private DailyTransaction validInput() {
        DailyTransaction input = new DailyTransaction();
        input.setDalytranId(TXN_ID);
        input.setDalytranTypeCd(TYPE_CD);
        input.setDalytranCatCd(CAT_CD);
        input.setDalytranSource(SOURCE);
        input.setDalytranDesc(DESC);
        input.setDalytranAmt(new BigDecimal("100.00"));
        input.setDalytranMerchantId(MERCHANT_ID);
        input.setDalytranMerchantName(MERCHANT_NAME);
        input.setDalytranMerchantCity(MERCHANT_CITY);
        input.setDalytranMerchantZip(MERCHANT_ZIP);
        input.setDalytranCardNum(CARD_NUM);
        input.setDalytranOrigTs(ORIG_TS);
        return input;
    }

    /**
     * Builds an {@link Account} with exactly the fields the validation cascade reads. The running balance
     * ({@code acctCurrBal}) is intentionally left unset; tests that prove the credit basis set it explicitly.
     *
     * @param creditLimit the account credit limit ({@code ACCT-CREDIT-LIMIT})
     * @param cycCredit   the current-cycle credit ({@code ACCT-CURR-CYC-CREDIT})
     * @param cycDebit    the current-cycle debit ({@code ACCT-CURR-CYC-DEBIT})
     * @param expiration  the account expiration date ({@code ACCT-EXPIRAION-DATE})
     * @return a fresh account carrying the supplied validation inputs
     */
    private Account accountWith(BigDecimal creditLimit, BigDecimal cycCredit, BigDecimal cycDebit,
            LocalDate expiration) {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCreditLimit(creditLimit);
        account.setAcctCurrCycCredit(cycCredit);
        account.setAcctCurrCycDebit(cycDebit);
        account.setAcctExpirationDate(expiration);
        return account;
    }

    /**
     * Builds a {@link CardCrossReference} keyed by {@link #CARD_NUM} that resolves to {@code acctId}.
     *
     * @param acctId the account id the cross-reference points at ({@code XREF-ACCT-ID})
     * @return a fresh cross-reference record
     */
    private CardCrossReference xref(Long acctId) {
        CardCrossReference crossReference = new CardCrossReference();
        crossReference.setXrefCardNum(CARD_NUM);
        crossReference.setXrefAcctId(acctId);
        crossReference.setXrefCustId(CUST_ID);
        return crossReference;
    }

    // --- Phase B: lookup cascade order (1500-A / 1500-B) --------------------------------------------

    @Nested
    @DisplayName("1500-VALIDATE-TRAN lookup order: 1500-A-LOOKUP-XREF (100) gates 1500-B-LOOKUP-ACCT (101)")
    class LookupCascadeOrder {

        @Test
        @DisplayName("reject 100: missing cross-reference => INVALID_CARD_NUMBER; account lookup is skipped (CBTRN02C L380-387)")
        void missingCrossReferenceYieldsReject100AndSkipsAccountLookup() {
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            PostedTransactionResult result = processor.process(validInput());

            assertThat(result).isNotNull();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.INVALID_CARD_NUMBER);
            assertThat(result.rejectCode().getCode()).isEqualTo(100);
            assertThat(result.transaction()).isNull();
            assertThat(result.crossReference()).isNull();
            assertThat(result.account()).isNull();
            assertThat(result.originalTransaction()).isNotNull();
            // 1500-B is performed only IF WS-VALIDATION-FAIL-REASON = 0 (CBTRN02C L372-373): once 1500-A
            // set reject 100 the account lookup must NOT run -> proves XREF gates the account read.
            verify(accountRepository, never()).findById(any());
        }

        @Test
        @DisplayName("reject 101: xref present but account missing => ACCOUNT_NOT_FOUND, only after XREF passed (CBTRN02C L393-399)")
        void missingAccountAfterXrefYieldsReject101() {
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            PostedTransactionResult result = processor.process(validInput());

            assertThat(result).isNotNull();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.ACCOUNT_NOT_FOUND);
            assertThat(result.rejectCode().getCode()).isEqualTo(101);
            assertThat(result.transaction()).isNull();
            // XREF resolved before the account lookup failed, so it is carried; account stays null.
            assertThat(result.crossReference()).isNotNull();
            assertThat(result.crossReference().getXrefAcctId()).isEqualTo(ACCT_ID);
            assertThat(result.account()).isNull();
            // The account lookup ran exactly once, keyed by the resolved XREF-ACCT-ID (ordering proof).
            verify(accountRepository).findById(ACCT_ID);
        }
    }

    // --- Phase C: credit (102) & expiration (103) are two independent IFs (1500-B, L403-420) --------

    @Nested
    @DisplayName("1500-B-LOOKUP-ACCT: credit (102) & expiration (103) are two INDEPENDENT sequential IFs (CBTRN02C L403-420)")
    class CreditAndExpirationAreIndependentIfs {

        @Test
        @DisplayName("reject 102: over-limit only — credit basis = ACCT-CURR-CYC-CREDIT − ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT (L403-405), NOT ACCT-CURR-BAL")
        void overLimitOnlyYieldsReject102AndProvesCreditBasisIsCycleNotRunningBalance() {
            DailyTransaction input = validInput();
            input.setDalytranAmt(new BigDecimal("200.00"));
            // tempBal = 900 - 0 + 200 = 1100; creditLimit 1000 < 1100 => 102. Expiry in the far future => no 103.
            Account account = accountWith(new BigDecimal("1000.00"), new BigDecimal("900.00"),
                    new BigDecimal("0.00"), FUTURE_EXP);
            // Running balance set to a value that would FLIP the decision if (wrongly) used as the basis:
            // a balance-basis temp of 0 + 200 = 200 < 1000 would NOT be over-limit. The cycle basis (1100)
            // still yields 102, proving the credit decision ignores ACCT-CURR-BAL.
            account.setAcctCurrBal(new BigDecimal("0.00"));
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            PostedTransactionResult result = processor.process(input);

            assertThat(result).isNotNull();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
            assertThat(result.rejectCode().getCode()).isEqualTo(102);
            assertThat(result.transaction()).isNull();
            assertThat(result.account()).isNotNull();
            assertThat(result.crossReference()).isNotNull();
        }

        @Test
        @DisplayName("boundary: creditLimit == tempBal is ACCEPTED (COBOL IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL is inclusive)")
        void creditLimitEqualToTempBalIsAccepted() {
            DailyTransaction input = validInput();
            input.setDalytranAmt(new BigDecimal("200.00"));
            // tempBal = 900 - 0 + 200 = 1100; creditLimit exactly 1100 => >= holds => no 102. Future expiry => no 103.
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    accountWith(new BigDecimal("1100.00"), new BigDecimal("900.00"),
                            new BigDecimal("0.00"), FUTURE_EXP)));

            PostedTransactionResult result = processor.process(input);

            assertThat(result).isNotNull();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.NONE);
            assertThat(result.transaction()).isNotNull();
        }

        @Test
        @DisplayName("reject 103: expired only — expiration < origTs date (CBTRN02C L414-419)")
        void expiredOnlyYieldsReject103() {
            DailyTransaction input = validInput(); // origTs date = 2022-06-10
            // creditLimit high and tempBal low => no 102; expiry 2020-01-01 < 2022-06-10 => 103.
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    accountWith(new BigDecimal("100000.00"), new BigDecimal("0.00"),
                            new BigDecimal("0.00"), PAST_EXP)));

            PostedTransactionResult result = processor.process(input);

            assertThat(result).isNotNull();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.TRANSACTION_AFTER_EXPIRATION);
            assertThat(result.rejectCode().getCode()).isEqualTo(103);
            assertThat(result.transaction()).isNull();
        }

        @Test
        @DisplayName("boundary: expiration == origTs date is ACCEPTED (COBOL IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10) is inclusive)")
        void expirationEqualToOrigDateIsAccepted() {
            DailyTransaction input = validInput(); // origTs date = 2022-06-10
            // expiry exactly equals the origTs date => >= holds => no 103; creditLimit high => no 102.
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    accountWith(new BigDecimal("100000.00"), new BigDecimal("0.00"),
                            new BigDecimal("0.00"), ORIG_DATE)));

            PostedTransactionResult result = processor.process(input);

            assertThat(result).isNotNull();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.NONE);
            assertThat(result.transaction()).isNotNull();
        }

        @Test
        @DisplayName("★ last-failure-wins: over-limit AND expired => 103 overwrites 102 (two independent IFs, CBTRN02C L407-420)")
        void overLimitAndExpiredYieldsReject103NotReject102() {
            DailyTransaction input = validInput(); // origTs date = 2022-06-10
            input.setDalytranAmt(new BigDecimal("200.00"));
            // FAILS BOTH: tempBal 1100 > creditLimit 1000 (would set 102) AND expiry 2020 < 2022 (sets 103 last).
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    accountWith(new BigDecimal("1000.00"), new BigDecimal("900.00"),
                            new BigDecimal("0.00"), PAST_EXP)));

            PostedTransactionResult result = processor.process(input);

            assertThat(result).isNotNull();
            // The credit IF set 102, then the INDEPENDENT expiration IF overwrote it with 103 (NOT else-if):
            // this is the single most important parity guarantee in the file.
            assertThat(result.rejectCode()).isEqualTo(RejectCode.TRANSACTION_AFTER_EXPIRATION);
            assertThat(result.rejectCode().getCode()).isEqualTo(103);
            assertThat(result.rejectCode()).isNotEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
            assertThat(result.transaction()).isNull();
        }
    }

    // --- Phase D: 2000-POST-TRANSACTION field mapping on the accepted path (L424-438) ---------------

    @Nested
    @DisplayName("2000-POST-TRANSACTION field mapping on the accepted path (CBTRN02C L424-438)")
    class AcceptPathFieldMapping {

        /** Stubs the lookups for an accepted transaction (within credit limit, not expired). */
        private void stubAcceptedLookups() {
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    accountWith(new BigDecimal("5000.00"), new BigDecimal("900.00"),
                            new BigDecimal("0.00"), FUTURE_EXP)));
        }

        @Test
        @DisplayName("accepted: rejectCode NONE, mapped transaction present, account/xref/original carried through (no re-read)")
        void acceptedYieldsNoneWithMappedTransactionAndPassThroughRecords() {
            DailyTransaction input = validInput();
            CardCrossReference expectedXref = xref(ACCT_ID);
            Account expectedAccount = accountWith(new BigDecimal("5000.00"), new BigDecimal("900.00"),
                    new BigDecimal("0.00"), FUTURE_EXP);
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(expectedXref));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(expectedAccount));

            PostedTransactionResult result = processor.process(input);

            assertThat(result).isNotNull();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.NONE);
            assertThat(result.isAccepted()).isTrue();
            assertThat(result.transaction()).isNotNull();
            // Pass-through identity: the writer reuses these resolved records without re-reading,
            // mirroring CBTRN02C's reuse of the in-memory ACCOUNT-RECORD / CARD-XREF-RECORD.
            assertThat(result.originalTransaction()).isSameAs(input);
            assertThat(result.account()).isSameAs(expectedAccount);
            assertThat(result.crossReference()).isSameAs(expectedXref);
        }

        @Test
        @DisplayName("field MOVEs: all 13 fields copied as-is (CBTRN02C L425-437); BigDecimal amount via compareTo")
        void allThirteenFieldsAreCopiedFromDailyTransaction() {
            stubAcceptedLookups();
            DailyTransaction input = validInput();

            Transaction posted = processor.process(input).transaction();

            assertThat(posted).isNotNull();
            assertThat(posted.getTranId()).isEqualTo(input.getDalytranId());
            assertThat(posted.getTranTypeCd()).isEqualTo(input.getDalytranTypeCd());
            assertThat(posted.getTranCatCd()).isEqualTo(input.getDalytranCatCd());
            assertThat(posted.getTranSource()).isEqualTo(input.getDalytranSource());
            assertThat(posted.getTranDesc()).isEqualTo(input.getDalytranDesc());
            assertThat(posted.getTranMerchantId()).isEqualTo(input.getDalytranMerchantId());
            assertThat(posted.getTranMerchantName()).isEqualTo(input.getDalytranMerchantName());
            assertThat(posted.getTranMerchantCity()).isEqualTo(input.getDalytranMerchantCity());
            assertThat(posted.getTranMerchantZip()).isEqualTo(input.getDalytranMerchantZip());
            assertThat(posted.getTranCardNum()).isEqualTo(input.getDalytranCardNum());
            // BigDecimal MUST be compared with compareTo semantics, never equals (AAP §0.7.3).
            assertThat(posted.getTranAmt()).isEqualByComparingTo(input.getDalytranAmt());
        }

        @Test
        @DisplayName("TRAN-ORIG-TS copied as-is (L436); TRAN-PROC-TS freshly stamped from the fixed Clock and != TRAN-ORIG-TS")
        void origTimestampCopiedAsIsAndProcTimestampFreshlyStamped() {
            stubAcceptedLookups();
            DailyTransaction input = validInput();

            Transaction posted = processor.process(input).transaction();

            assertThat(posted).isNotNull();
            // MOVE DALYTRAN-ORIG-TS TO TRAN-ORIG-TS (CBTRN02C L436): copied verbatim, never re-stamped.
            assertThat(posted.getTranOrigTs()).isEqualTo(input.getDalytranOrigTs());
            // PERFORM Z-GET-DB2-FORMAT-TIMESTAMP; MOVE DB2-FORMAT-TS TO TRAN-PROC-TS: freshly stamped.
            // The entity stores a LocalDateTime; with the injected fixed Clock the value is deterministic
            // (nanos truncated to COBOL hundredths), so we assert the EXACT stamped value.
            assertThat(posted.getTranProcTs()).isNotNull();
            assertThat(posted.getTranProcTs()).isEqualTo(EXPECTED_PROC_TS);
            // It was re-stamped, so it must differ from the (copied-as-is) origination timestamp.
            assertThat(posted.getTranProcTs()).isNotEqualTo(posted.getTranOrigTs());
        }
    }

    // --- Phase E: reject & continue semantics (never throw, never null) -----------------------------

    @Nested
    @DisplayName("reject & continue: non-null carrier with failing code, never thrown, only {0,100,101,102,103}")
    class RejectAndContinueSemantics {

        @Test
        @DisplayName("rejects are CARRIED (non-null carrier with failing RejectCode), never thrown, never null")
        void rejectIsCarriedInNonNullResultNotThrownNotNull() {
            DailyTransaction input = validInput();
            input.setDalytranAmt(new BigDecimal("200.00"));
            // over-limit reject path (102).
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    accountWith(new BigDecimal("1000.00"), new BigDecimal("900.00"),
                            new BigDecimal("0.00"), FUTURE_EXP)));

            PostedTransactionResult result = processor.process(input);

            // A null return would make Spring Batch FILTER (silently drop) the reject so it never reaches
            // the reject writer — a parity violation. The reject must be carried in a non-null result.
            assertThat(result).isNotNull();
            assertThat(result.isRejected()).isTrue();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
            assertThat(result.transaction()).isNull();
            assertThat(result.originalTransaction()).isSameAs(input);
        }

        @Test
        @DisplayName("no exception on a business reject (the batch path uses the RejectCode carrier, not CreditLimit/ExpiredCard exceptions)")
        void businessRejectDoesNotThrow() {
            DailyTransaction input = validInput();
            input.setDalytranAmt(new BigDecimal("200.00"));
            // both-fail path (resolves to 103) — still must not throw.
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(
                    accountWith(new BigDecimal("1000.00"), new BigDecimal("900.00"),
                            new BigDecimal("0.00"), PAST_EXP)));

            assertThatCode(() -> processor.process(input)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("code 109 (the writer's 2800 REWRITE-failure path) is never produced by process(...)")
        void code109IsNeverProducedByProcessor() {
            // exercise an early reject (100): the processor never reaches the writer's rewrite path.
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            PostedTransactionResult result = processor.process(validInput());

            assertThat(result.rejectCode()).isNotEqualTo(RejectCode.ACCOUNT_NOT_FOUND_ON_UPDATE);
            assertThat(result.rejectCode().getCode()).isNotEqualTo(109);
        }

        @Test
        @DisplayName("codes 104-108 do not exist in the RejectCode value set")
        void codes104To108DoNotExist() {
            assertThat(RejectCode.values())
                    .extracting(RejectCode::getCode)
                    .doesNotContain(104, 105, 106, 107, 108);
        }
    }

    // --- Phase F: parameterized cascade matrix ------------------------------------------------------

    /**
     * Enumerates the full validation cascade: (xrefPresent, acctPresent, creditLimit, cycCredit, cycDebit,
     * amt, expiration) -> expected {@link RejectCode}. Covers 100, 101, 102-only, 103-only, both-fail
     * (=> 103), accepted, and the two inclusive ({@code >=}) boundaries. Constants are static so this
     * provider can be {@code static} as required by {@link MethodSource}.
     *
     * @return the matrix of cascade scenarios
     */
    static Stream<Arguments> cascadeMatrix() {
        return Stream.of(
                Arguments.of("100 no xref", false, false,
                        null, null, null, new BigDecimal("100.00"), null,
                        RejectCode.INVALID_CARD_NUMBER),
                Arguments.of("101 no account", true, false,
                        null, null, null, new BigDecimal("100.00"), null,
                        RejectCode.ACCOUNT_NOT_FOUND),
                Arguments.of("102 over-limit only", true, true,
                        new BigDecimal("1000.00"), new BigDecimal("900.00"), new BigDecimal("0.00"),
                        new BigDecimal("200.00"), FUTURE_EXP,
                        RejectCode.OVERLIMIT_TRANSACTION),
                Arguments.of("103 expired only", true, true,
                        new BigDecimal("100000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                        new BigDecimal("100.00"), PAST_EXP,
                        RejectCode.TRANSACTION_AFTER_EXPIRATION),
                Arguments.of("both fail => 103 wins", true, true,
                        new BigDecimal("1000.00"), new BigDecimal("900.00"), new BigDecimal("0.00"),
                        new BigDecimal("200.00"), PAST_EXP,
                        RejectCode.TRANSACTION_AFTER_EXPIRATION),
                Arguments.of("accepted", true, true,
                        new BigDecimal("5000.00"), new BigDecimal("900.00"), new BigDecimal("0.00"),
                        new BigDecimal("200.00"), FUTURE_EXP,
                        RejectCode.NONE),
                Arguments.of("credit boundary == accepted", true, true,
                        new BigDecimal("1100.00"), new BigDecimal("900.00"), new BigDecimal("0.00"),
                        new BigDecimal("200.00"), FUTURE_EXP,
                        RejectCode.NONE),
                Arguments.of("expiration boundary == accepted", true, true,
                        new BigDecimal("100000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                        new BigDecimal("100.00"), ORIG_DATE,
                        RejectCode.NONE));
    }

    @ParameterizedTest(name = "[{index}] {0} => {8}")
    @MethodSource("cascadeMatrix")
    @DisplayName("full cascade matrix locks codes {0,100,101,102,103}, last-failure-wins, and both >= boundaries")
    void fullCascadeMatrix(String scenario, boolean xrefPresent, boolean acctPresent,
            BigDecimal creditLimit, BigDecimal cycCredit, BigDecimal cycDebit, BigDecimal amt,
            LocalDate expiration, RejectCode expected) {
        DailyTransaction input = validInput();
        input.setDalytranAmt(amt);
        // Conditional stubbing keeps STRICT_STUBS happy: the account lookup is only stubbed (and only
        // reached) once the cross-reference is present, exactly mirroring the COBOL guard.
        when(crossReferenceRepository.findById(CARD_NUM))
                .thenReturn(xrefPresent ? Optional.of(xref(ACCT_ID)) : Optional.empty());
        if (xrefPresent) {
            when(accountRepository.findById(ACCT_ID)).thenReturn(acctPresent
                    ? Optional.of(accountWith(creditLimit, cycCredit, cycDebit, expiration))
                    : Optional.empty());
        }

        PostedTransactionResult result = processor.process(input);

        assertThat(result).isNotNull();
        assertThat(result.rejectCode()).isEqualTo(expected);
        // Processor scope: never the writer's 109, never the non-existent 104-108.
        assertThat(result.rejectCode()).isNotEqualTo(RejectCode.ACCOUNT_NOT_FOUND_ON_UPDATE);
        assertThat(result.rejectCode().getCode()).isIn(0, 100, 101, 102, 103);
        // Accepted iff NONE; every reject carries a null mapped transaction.
        if (expected == RejectCode.NONE) {
            assertThat(result.transaction()).isNotNull();
        } else {
            assertThat(result.transaction()).isNull();
        }
    }
}

