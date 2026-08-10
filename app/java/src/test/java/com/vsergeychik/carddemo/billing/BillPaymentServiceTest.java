package com.vsergeychik.carddemo.billing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.billing.BillPaymentService.PaymentState;
import com.vsergeychik.carddemo.billing.BillPaymentService.SentScreen;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link BillPaymentService} - every arm of {@code app/cbl/COBIL00C.cbl}'s guard chain, its two
 * arithmetic statements, its seven file operations and both of the defects it preserves.
 *
 * <p>Nothing here goes through HTTP. The service is exercised directly with stubbed repositories, which
 * is the whole point of the controller/service split: every decision the program makes is reachable
 * without a servlet container in the path.
 *
 * <p>The clock is fixed, so the twenty-six byte timestamp is compared literally rather than loosely.
 * The unit of work is real - a {@link DataSourceTransactionManager} over an in-memory database - because
 * {@link AccountRepository#readForUpdate(String)} refuses to issue a locking read outside an actually
 * active transaction, and a stub that merely pretended would let the write path pass over a boundary
 * that holds nothing.
 */
@DisplayName("BillPaymentService - COBIL00C, the CB00 bill-payment transaction")
class BillPaymentServiceTest {

    /** The dataset code page for the text fixtures; the repository reports it and the service adopts it. */
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    /**
     * The instant every timestamp in this suite is composed from - the version footer of
     * {@code app/cbl/COBIL00C.cbl:571}, which makes the expected image easy to check by eye.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    /** The expected 26-character {@code WS-TIMESTAMP} for {@link #FIXED_INSTANT}. */
    private static final String EXPECTED_TIMESTAMP = "2022-07-19 23:12:32.000000";

    /** An eleven-digit account identifier, as the screen field carries it. */
    private static final String ACCT_KEY = "00000000011";

    /** A sixteen-digit card number, as {@code XREF-CARD-NUM} carries it. */
    private static final String CARD_NUMBER = "4111111111111111";

    private AccountRepository accountRepository;
    private CardXrefRepository cardXrefRepository;
    private TransactionRepository transactionRepository;
    private TransactionRepository.Browse browse;
    private BillPaymentService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardXrefRepository = mock(CardXrefRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        browse = mock(TransactionRepository.Browse.class);
        when(accountRepository.datasetCharset()).thenReturn(CHARSET);
        when(transactionRepository.startBrowse(BrowseDirection.BACKWARD)).thenReturn(browse);
        service = new BillPaymentService(accountRepository, cardXrefRepository, transactionRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), newUnitOfWork());
    }

    /**
     * A real unit of work over a database of this test's own, so no two tests share one.
     *
     * @return a boundary whose {@link DatasetUnitOfWork#active()} reports the truth
     */
    private static DatasetUnitOfWork newUnitOfWork() {
        PlatformTransactionManager manager = new DataSourceTransactionManager(
                new SimpleDriverDataSource(new org.h2.Driver(),
                        "jdbc:h2:mem:billpay-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        return new DatasetUnitOfWork(manager);
    }

    // =================================================================================================
    // Fixtures.
    // =================================================================================================

    /**
     * A stored account carrying the given balance.
     *
     * @param balance {@code ACCT-CURR-BAL}
     * @return a fresh 300-byte record
     */
    private static AccountRecord account(String balance) {
        AccountRecord record = new AccountRecord(CHARSET);
        record.setAcctId(11L);
        record.setAcctActiveStatus("Y");
        record.setAcctCurrBal(new BigDecimal(balance));
        record.setAcctCreditLimit(new BigDecimal("5000.00"));
        return record;
    }

    /**
     * A stored cross-reference row for {@link #CARD_NUMBER}.
     *
     * @return the outcome a successful alternate-index read reports
     */
    private static CardXrefRepository.ReadResult xrefFound() {
        CardXrefRecord record = new CardXrefRecord(CARD_NUMBER, 1, 11L);
        return CardXrefRepository.ReadResult.found(CardXrefRepository.ALTERNATE_INDEX_DD_NAME, record,
                new String(record.encode(CHARSET), CHARSET));
    }

    /**
     * A stored transaction carrying the given identifier.
     *
     * @param tranId {@code TRAN-ID}, sixteen characters
     * @return a fresh 350-byte record
     */
    private static TranRecord tran(String tranId) {
        TranRecord record = new TranRecord(CHARSET);
        record.moveTranId(tranId);
        return record;
    }

    /**
     * The screen the controller would bind.
     *
     * @param actIdIn {@code ACTIDINI}
     * @param confirm {@code CONFIRMI}
     * @return a request carrying those two fields and an empty communication area
     */
    private static BillPaymentRequest request(String actIdIn, String confirm) {
        BillPaymentRequest bound = new BillPaymentRequest();
        bound.setActIdIn(actIdIn);
        bound.setConfirm(confirm);
        bound.setNavigationContext(NavigationContext.empty());
        return bound;
    }

    /**
     * Stubs the whole happy path: the account reads and rewrites, the cross-reference is found, the
     * browse returns the highest existing identifier, and the write succeeds.
     *
     * @param balance the stored balance
     * @param highest the highest existing {@code TRAN-ID}, or {@code null} for an empty master
     */
    private void stubHappyPath(String balance, String highest) {
        when(accountRepository.readForUpdate(anyString()))
                .thenReturn(AccountRepository.ReadResult.found(account(balance)));
        when(accountRepository.rewrite(any())).thenReturn(AccountRepository.WriteResult.written());
        when(cardXrefRepository.readByAccountIdViaAltIndex(anyString())).thenReturn(xrefFound());
        when(browse.readPrev()).thenReturn(highest == null
                ? TransactionRepository.ReadResult.endOfFile(TransactionRepository.CICS_FILE_NAME)
                : TransactionRepository.ReadResult.found(TransactionRepository.CICS_FILE_NAME,
                        tran(highest)));
        when(transactionRepository.write(any()))
                .thenReturn(TransactionRepository.WriteResult.written(
                        TransactionRepository.CICS_FILE_NAME));
    }

    /**
     * A state whose account record carries the given balance and whose key fields are set, as the
     * two-receiver {@code MOVE} at lines 170-171 leaves them.
     *
     * @param balance {@code ACCT-CURR-BAL}
     * @return a fresh working storage
     */
    private PaymentState stateWithAccount(String balance) {
        PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());
        state.setAcctIdRidfld(ACCT_KEY);
        state.setXrefAcctIdRidfld(ACCT_KEY);
        state.setAccountRecord(account(balance));
        return state;
    }

    // =================================================================================================
    // Construction and the closed collaborator set.
    // =================================================================================================

    @Nested
    @DisplayName("construction - five collaborators, constructor injection only")
    class Construction {

        @Test
        @DisplayName("every collaborator is required")
        void everyCollaboratorIsRequired() {
            Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
            DatasetUnitOfWork work = newUnitOfWork();
            Assertions.assertThatThrownBy(() -> new BillPaymentService(null, cardXrefRepository,
                            transactionRepository, clock, work))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new BillPaymentService(accountRepository, null,
                            transactionRepository, clock, work))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new BillPaymentService(accountRepository,
                            cardXrefRepository, null, clock, work))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new BillPaymentService(accountRepository,
                            cardXrefRepository, transactionRepository, null, work))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new BillPaymentService(accountRepository,
                            cardXrefRepository, transactionRepository, clock, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the code page comes from the dataset that owns the balance, and is required")
        void codePageComesFromTheRepository() {
            Assertions.assertThat(service.codec().charset()).isEqualTo(CHARSET);

            AccountRepository silent = mock(AccountRepository.class);
            when(silent.datasetCharset()).thenReturn(null);
            Assertions.assertThatThrownBy(() -> new BillPaymentService(silent, cardXrefRepository,
                            transactionRepository, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                            newUnitOfWork()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("code page");
        }

        @Test
        @DisplayName("the program identity and the three eight-character CICS file names")
        void programIdentity() {
            Assertions.assertThat(BillPaymentService.WS_PGMNAME).isEqualTo("COBIL00C");
            Assertions.assertThat(BillPaymentService.WS_TRANID).isEqualTo("CB00");
            // The trailing space on two of the three is the PIC X(08) padding and is part of the value.
            Assertions.assertThat(BillPaymentService.WS_TRANSACT_FILE).isEqualTo("TRANSACT");
            Assertions.assertThat(BillPaymentService.WS_ACCTDAT_FILE).isEqualTo("ACCTDAT ");
            Assertions.assertThat(BillPaymentService.WS_CXACAIX_FILE).isEqualTo("CXACAIX ");
            Assertions.assertThat(BillPaymentService.WS_TRANSACT_FILE).hasSize(8);
            Assertions.assertThat(BillPaymentService.WS_ACCTDAT_FILE).hasSize(8);
            Assertions.assertThat(BillPaymentService.WS_CXACAIX_FILE).hasSize(8);
        }

        @Test
        @DisplayName("the declared-and-never-referenced items survive, unused")
        void deadCodeSurvives() {
            PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());
            // WS-TRAN-AMT PIC +99999999.99 - twelve positions, two narrower than the balance mask.
            Assertions.assertThat(BillPaymentService.WS_TRAN_AMT_LENGTH).isEqualTo(12);
            Assertions.assertThat(state.tranAmtEdited()).hasSize(12).isBlank();
            // WS-TRAN-DATE PIC X(08) VALUE '00/00/00'.
            Assertions.assertThat(state.tranDate()).isEqualTo("00/00/00");
            // WS-USR-MODIFIED - write-only state, and there is deliberately no setter for 'Y'.
            Assertions.assertThat(state.usrModified()).isEqualTo(BillPaymentService.USR_MODIFIED_NO);
            state.setUsrModifiedNo();
            Assertions.assertThat(state.usrModified()).isEqualTo(BillPaymentService.USR_MODIFIED_NO);
            Assertions.assertThat(BillPaymentService.USR_MODIFIED_YES).isEqualTo("Y");
        }

        @Test
        @DisplayName("the message colour is the one attribute byte, carried as one character")
        void messageHighlightRoundTrips() {
            Assertions.assertThat(BillPaymentService.MESSAGE_HIGHLIGHT_GREEN).hasSize(1);
            Assertions.assertThat((byte) BillPaymentService.MESSAGE_HIGHLIGHT_GREEN.charAt(0))
                    .isEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("the CICS absolute time epoch offset is seventy years of milliseconds")
        void abstimeOffset() {
            Assertions.assertThat(BillPaymentService.CICS_ABSTIME_EPOCH_OFFSET_MILLIS)
                    .isEqualTo(2_208_988_800_000L);
        }

        @Test
        @DisplayName("one public constructor, no field or setter injection anywhere (practice B9)")
        void injectionIsConstructorOnly() {
            // A single public constructor is what lets Spring wire the bean with no @Autowired
            // annotation at all, which is what practice B9 asks for.
            Assertions.assertThat(BillPaymentService.class.getConstructors()).hasSize(1);
            Assertions.assertThat(BillPaymentService.class.getConstructors()[0].getParameterTypes())
                    .containsExactly(AccountRepository.class, CardXrefRepository.class,
                            TransactionRepository.class, Clock.class, DatasetUnitOfWork.class);
            Assertions.assertThat(BillPaymentService.class.getDeclaredMethods())
                    .noneMatch(method -> method.isAnnotationPresent(Autowired.class));
            Assertions.assertThat(BillPaymentService.class.getDeclaredFields())
                    .noneMatch(field -> field.isAnnotationPresent(Autowired.class));
            // And no static mutable state: every static member is final (gate G53).
            Assertions.assertThat(BillPaymentService.class.getDeclaredFields())
                    .filteredOn(field -> Modifier.isStatic(field.getModifiers()))
                    .allMatch(field -> Modifier.isFinal(field.getModifiers()));
            Assertions.assertThat(PaymentState.class.getDeclaredFields())
                    .noneMatch(field -> Modifier.isStatic(field.getModifiers())
                            && !Modifier.isFinal(field.getModifiers()));
        }

        @Test
        @DisplayName("a real Spring context builds the bean from its five collaborators (gate G3)")
        void aRealContextWiresTheBean() {
            // The module has no live DataSource here, so a whole-application context load would fail
            // for reasons that have nothing to do with this bean - which is why CardDemoApplicationTest
            // avoids @SpringBootTest too. Registering the collaborators as singletons and letting the
            // container do the autowiring proves the property that matters without needing a backend.
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.getBeanFactory().registerSingleton("accountRepository", accountRepository);
                context.getBeanFactory().registerSingleton("cardXrefRepository", cardXrefRepository);
                context.getBeanFactory().registerSingleton("transactionRepository",
                        transactionRepository);
                context.getBeanFactory().registerSingleton("clock",
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
                context.getBeanFactory().registerSingleton("datasetUnitOfWork", newUnitOfWork());
                context.register(BillPaymentService.class);
                context.refresh();

                BillPaymentService bean = context.getBean(BillPaymentService.class);
                Assertions.assertThat(bean).isNotNull();
                // A singleton, as a stateless service must be: two lookups return one instance.
                Assertions.assertThat(context.getBean(BillPaymentService.class)).isSameAs(bean);
                // The @Service stereotype is what put it there, and the bean name follows from it.
                Assertions.assertThat(BillPaymentService.class.isAnnotationPresent(Service.class))
                        .isTrue();
                Assertions.assertThat(context.getBeanNamesForType(BillPaymentService.class))
                        .containsExactly("billPaymentService");
                // And it received the registered collaborators rather than fresh ones: the stub set on
                // the mock here is the one the wired bean observes.
                when(accountRepository.readForUpdate(anyString()))
                        .thenReturn(AccountRepository.ReadResult.notFound());
                PaymentState state = bean.processEnterKey(ACCT_KEY, " ", NavigationContext.empty());

                Assertions.assertThat(state.isErrFlagOn()).isTrue();
                Assertions.assertThat(state.message()).startsWith("Account ID NOT found...");
                verify(accountRepository).readForUpdate(ACCT_KEY);
            }
        }
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY stage one - :158-167.
    // =================================================================================================

    @Nested
    @DisplayName("stage 1 - :158-167, the empty-identifier check")
    class StageOne {

        @Test
        @DisplayName("SPACES rejects with 'Acct ID can NOT be empty...' and no file is touched")
        void spacesRejects() {
            PaymentState state = service.processEnterKey("           ", " ",
                    NavigationContext.empty());

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message())
                    .startsWith("Acct ID can NOT be empty...")
                    .hasSize(BillPaymentService.WS_MESSAGE_LENGTH);
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.ACTIDIN);
            Assertions.assertThat(state.screensSent()).isOne();
            Assertions.assertThat(state.acctIdCheckContinued()).isFalse();
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("LOW-VALUES - a null field - rejects the same way")
        void lowValuesRejects() {
            PaymentState state = service.processEnterKey(null, null, null);

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message()).startsWith("Acct ID can NOT be empty...");
            Assertions.assertThat(state.commarea()).isEqualTo(NavigationContext.empty());
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("an empty string is a blank field and rejects")
        void emptyStringRejects() {
            PaymentState state = service.processEnterKey("", "", NavigationContext.empty());

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.actIdIn()).hasSize(BillPaymentService.ACT_ID_IN_LENGTH);
        }

        @Test
        @DisplayName("a partially blank field is NEITHER figurative constant and passes the check")
        void partiallyBlankPasses() {
            when(accountRepository.readForUpdate("123        "))
                    .thenReturn(AccountRepository.ReadResult.notFound());

            PaymentState state = service.processEnterKey("123", " ", NavigationContext.empty());

            // It passed stage one, reached the file as eleven bytes of "123" plus eight spaces, and
            // simply missed - it was NOT reshaped into "00000000123".
            Assertions.assertThat(state.acctIdCheckContinued()).isTrue();
            Assertions.assertThat(state.acctIdRidfld()).isEqualTo("123        ");
            Assertions.assertThat(state.message()).startsWith("Account ID NOT found...");
            verify(accountRepository).readForUpdate("123        ");
        }

        @Test
        @DisplayName("a bound request supplies the three items the paragraph reads")
        void boundRequestIsRead() {
            stubHappyPath("100.00", "0000000000000041");

            PaymentState state = service.processEnterKey(request(ACCT_KEY, "Y"));

            Assertions.assertThat(state.isConfPayYes()).isTrue();
            Assertions.assertThat(state.acctIdRidfld()).isEqualTo(ACCT_KEY);
        }

        @Test
        @DisplayName("a bound request is required")
        void boundRequestIsRequired() {
            Assertions.assertThatThrownBy(() -> service.processEnterKey(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY stage two - :169-195, the confirmation switch and the stale balance.
    // =================================================================================================

    @Nested
    @DisplayName("stage 2 - :169-195, the confirmation switch")
    class StageTwo {

        @ParameterizedTest(name = "confirm={0} reads the account and confirms the payment")
        @ValueSource(strings = {"Y", "y"})
        @DisplayName(":174-177 - 'Y' and 'y' share one body")
        void yesArmsShareOneBody(String confirm) {
            stubHappyPath("100.00", "0000000000000041");

            PaymentState state = service.processEnterKey(ACCT_KEY, confirm,
                    NavigationContext.empty());

            Assertions.assertThat(state.isConfPayYes()).isTrue();
            Assertions.assertThat(state.isErrFlagOn()).isFalse();
            verify(accountRepository).readForUpdate(ACCT_KEY);
        }

        @ParameterizedTest(name = "confirm={0} clears the screen, sends, THEN raises the flag")
        @ValueSource(strings = {"N", "n"})
        @DisplayName(":178-181 - the order is observable")
        void noArmsClearThenFlag(String confirm) {
            PaymentState state = service.processEnterKey(ACCT_KEY, confirm,
                    NavigationContext.empty());

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.isConfPayYes()).isFalse();
            // INITIALIZE-ALL-FIELDS blanked the three input fields and the message.
            Assertions.assertThat(state.actIdIn()).isBlank();
            Assertions.assertThat(state.confirm()).isBlank();
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.ACTIDIN);
            // The one screen it sent carried a BLANK message, because the flag was raised afterwards.
            Assertions.assertThat(state.screensSent()).isOne();
            Assertions.assertThat(state.sentScreens().get(0).errMsg()).isBlank();
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @ParameterizedTest(name = "confirm=[{0}] reads the account without confirming")
        @CsvSource(value = {"' '", "NULL"}, nullValues = "NULL")
        @DisplayName(":182-184 - SPACES and LOW-VALUES share one body")
        void blankArmsReadWithoutConfirming(String confirm) {
            when(accountRepository.readForUpdate(ACCT_KEY))
                    .thenReturn(AccountRepository.ReadResult.found(account("100.00")));

            PaymentState state = service.processEnterKey(ACCT_KEY, confirm,
                    NavigationContext.empty());

            Assertions.assertThat(state.isConfPayYes()).isFalse();
            Assertions.assertThat(state.isErrFlagOn()).isFalse();
            // Stage four's ELSE arm: a prompt, and NO error flag.
            Assertions.assertThat(state.message()).startsWith("Confirm to make a bill payment...");
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.CONFIRM);
            verify(accountRepository).readForUpdate(ACCT_KEY);
            verify(transactionRepository, never()).write(any());
        }

        @Test
        @DisplayName(":185-190 - WHEN OTHER rejects with the (Y/N) message and no case folding")
        void otherArmRejects() {
            PaymentState state = service.processEnterKey(ACCT_KEY, "X", NavigationContext.empty());

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message())
                    .startsWith("Invalid value. Valid values are (Y/N)...");
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.CONFIRM);
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":193-194 - THE STALE-BALANCE DEFECT is preserved on the 'N' path")
        void staleBalanceIsWrittenAfterTheSend() {
            PaymentState state = service.processEnterKey(ACCT_KEY, "N", NavigationContext.empty());

            // No account was ever read, so the balance edited is the initial content of the
            // WORKING-STORAGE record area - a positive zero.
            Assertions.assertThat(state.currBalEdited()).isEqualTo("+0000000000.00");
            Assertions.assertThat(state.curBal()).isEqualTo("+0000000000.00");
            // And it landed there AFTER the screen was sent: the transmitted balance field was blank.
            Assertions.assertThat(state.sentScreens()).hasSize(1);
            Assertions.assertThat(state.sentScreens().get(0).curBal()).isBlank();
            verify(accountRepository, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName(":193-194 - and on the WHEN OTHER path too, where the flag was raised first")
        void staleBalanceIsWrittenOnTheInvalidValuePathToo() {
            PaymentState state = service.processEnterKey(ACCT_KEY, "?", NavigationContext.empty());

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.curBal()).isEqualTo("+0000000000.00");
            Assertions.assertThat(state.sentScreens().get(0).curBal()).isBlank();
        }

        @Test
        @DisplayName(":193-194 - and on a failed account read, which is the same defect")
        void staleBalanceIsWrittenAfterAFailedRead() {
            when(accountRepository.readForUpdate(ACCT_KEY))
                    .thenReturn(AccountRepository.ReadResult.notFound());

            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            Assertions.assertThat(state.message()).startsWith("Account ID NOT found...");
            Assertions.assertThat(state.curBal()).isEqualTo("+0000000000.00");
        }

        @Test
        @DisplayName(":170-171 - one MOVE, two receivers, the same eleven characters")
        void oneMoveTwoReceivers() {
            when(accountRepository.readForUpdate(ACCT_KEY))
                    .thenReturn(AccountRepository.ReadResult.found(account("100.00")));

            PaymentState state = service.processEnterKey(ACCT_KEY, " ", NavigationContext.empty());

            Assertions.assertThat(state.acctIdRidfld()).isEqualTo(ACCT_KEY);
            Assertions.assertThat(state.xrefAcctIdRidfld()).isEqualTo(ACCT_KEY);
        }
    }


    // =================================================================================================
    // PROCESS-ENTER-KEY stage three - :197-206, the compound condition.
    // =================================================================================================

    @Nested
    @DisplayName("stage 3 - :197-206, 'You have nothing to pay...'")
    class StageThree {

        @ParameterizedTest(name = "balance={0} identifier supplied={1} -> nothing to pay = {2}")
        @CsvSource({
                "0.00,       true,  true",
                "-25.00,     true,  true",
                "0.01,       true,  false",
                "1000.00,    true,  false",
                "0.00,       false, false",
                "-25.00,     false, false"
        })
        @DisplayName("all four combinations of the AND at :198-199")
        void compoundCondition(String balance, boolean supplied, boolean expected) {
            String actIdIn = supplied ? ACCT_KEY : "           ";
            Assertions.assertThat(service.isNothingToPay(new BigDecimal(balance), actIdIn))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("LOW-VALUES also satisfies the second operand's negation")
        void lowValuesIdentifier() {
            String lowValues = "\u0000".repeat(BillPaymentService.ACT_ID_IN_LENGTH);
            Assertions.assertThat(service.isNothingToPay(new BigDecimal("0.00"), lowValues)).isFalse();
        }

        @Test
        @DisplayName("both operands are required")
        void operandsAreRequired() {
            Assertions.assertThatThrownBy(() -> service.isNothingToPay(null, ACCT_KEY))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(
                            () -> service.isNothingToPay(CobolDecimal.monetaryZero(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a zero balance stops the payment through the whole paragraph")
        void zeroBalanceStopsThePayment() {
            when(accountRepository.readForUpdate(ACCT_KEY))
                    .thenReturn(AccountRepository.ReadResult.found(account("0.00")));

            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message()).startsWith("You have nothing to pay...");
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.ACTIDIN);
            verify(transactionRepository, never()).write(any());
            verify(accountRepository, never()).rewrite(any());
        }

        @Test
        @DisplayName("a negative balance stops it too - the test is <= ZEROS, not = ZEROS")
        void negativeBalanceStopsThePayment() {
            when(accountRepository.readForUpdate(ACCT_KEY))
                    .thenReturn(AccountRepository.ReadResult.found(account("-10.00")));

            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            Assertions.assertThat(state.message()).startsWith("You have nothing to pay...");
            // The edited stale-balance render is the real, negative balance on this path.
            Assertions.assertThat(state.curBal()).isEqualTo("-0000000010.00");
        }
    }

    // =================================================================================================
    // The payment sequence - :211-235, its ordering and its arithmetic.
    // =================================================================================================

    @Nested
    @DisplayName("the payment sequence - :211-235")
    class PaymentSequence {

        @Test
        @DisplayName("the happy path writes the transaction, debits the balance and rewrites")
        void happyPath() {
            stubHappyPath("1234.56", "0000000000000041");

            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            Assertions.assertThat(state.isErrFlagOn()).isFalse();
            Assertions.assertThat(state.tranIdNum()).isEqualTo(42L);
            Assertions.assertThat(state.accountRewritten()).isTrue();
            Assertions.assertThat(state.browseStarted()).isTrue();

            TranRecord written = state.tranRecord().orElseThrow();
            Assertions.assertThat(written.tranId()).isEqualTo("0000000000000042");
            Assertions.assertThat(written.tranTypeCd()).isEqualTo("02");
            Assertions.assertThat(written.tranCatCdImage()).isEqualTo("0002");
            Assertions.assertThat(written.tranSource()).isEqualTo("POS TERM  ");
            Assertions.assertThat(written.tranDesc()).startsWith("BILL PAYMENT - ONLINE").hasSize(100);
            Assertions.assertThat(written.tranAmt()).isEqualByComparingTo(new BigDecimal("1234.56"));
            Assertions.assertThat(written.tranCardNum()).isEqualTo(CARD_NUMBER);
            Assertions.assertThat(written.tranMerchantIdImage()).isEqualTo("999999999");
            Assertions.assertThat(written.tranMerchantName()).startsWith("BILL PAYMENT").hasSize(50);
            Assertions.assertThat(written.tranMerchantCity()).startsWith("N/A").hasSize(50);
            Assertions.assertThat(written.tranMerchantZip()).isEqualTo("N/A       ");
            Assertions.assertThat(written.tranOrigTs()).isEqualTo(EXPECTED_TIMESTAMP);
            Assertions.assertThat(written.tranProcTs()).isEqualTo(EXPECTED_TIMESTAMP);

            // The balance was debited in full, because 1234.56 fits nine integer digits.
            Assertions.assertThat(state.accountRecord().getAcctCurrBal())
                    .isEqualByComparingTo(CobolDecimal.monetaryZero());

            // The success message, and the second send at :242.
            Assertions.assertThat(state.message()).isEqualTo(
                    "Payment successful.  Your Transaction ID is 0000000000000042."
                            + " ".repeat(BillPaymentService.WS_MESSAGE_LENGTH - 61));
            Assertions.assertThat(state.messageHighlight())
                    .isEqualTo(BillPaymentService.MESSAGE_HIGHLIGHT_GREEN);
            Assertions.assertThat(state.screensSent()).isEqualTo(2);
        }

        @Test
        @DisplayName("an empty master yields identifier 1 through the ENDFILE arm")
        void emptyMasterYieldsOne() {
            stubHappyPath("50.00", null);

            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            Assertions.assertThat(state.tranIdNum()).isOne();
            Assertions.assertThat(state.tranRecord().orElseThrow().tranId())
                    .isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("the record is exactly 350 bytes with the trailing FILLER X(20) space-filled")
        void recordGeometry() {
            stubHappyPath("50.00", "0000000000000001");

            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            TranRecord written = state.tranRecord().orElseThrow();
            Assertions.assertThat(written.encode(CHARSET)).hasSize(TranRecord.RECORD_LENGTH);
            Assertions.assertThat(written.filler()).hasSize(TranRecord.FILLER_LENGTH).isBlank();
            Assertions.assertThat(state.accountRecord().toByteArray())
                    .hasSize(AccountRecord.RECORD_LENGTH);
            Assertions.assertThat(state.cardXrefRecord().orElseThrow().encode(CHARSET))
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("THE HEADLINE TRAP - a ten-digit balance truncates on the LEFT into TRAN-AMT, "
                + "so :234 does NOT yield zero")
        void leftTruncationLeavesANonZeroBalance() {
            stubHappyPath("1234567890.12", "0000000000000041");

            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            // TRAN-AMT is PIC S9(09)V99, so the high-order 1 was discarded.
            Assertions.assertThat(state.tranRecord().orElseThrow().tranAmt())
                    .isEqualByComparingTo(new BigDecimal("234567890.12"));
            // 1234567890.12 - 234567890.12 = 1000000000.00, which is emphatically not zero.
            Assertions.assertThat(state.accountRecord().getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("1000000000.00"));
            Assertions.assertThat(state.accountRecord().getAcctCurrBal().signum()).isPositive();
        }

        @Test
        @DisplayName("the largest storable balance truncates the same way")
        void largestBalanceTruncates() {
            stubHappyPath("9999999999.99", "0000000000000041");

            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            Assertions.assertThat(state.tranRecord().orElseThrow().tranAmt())
                    .isEqualByComparingTo(new BigDecimal("999999999.99"));
            Assertions.assertThat(state.accountRecord().getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("9000000000.00"));
        }

        @Test
        @DisplayName("every stored decimal is scale exactly 2, and truncation is DOWN not HALF_EVEN")
        void scaleAndRoundingMode() {
            stubHappyPath("1234.56", "0000000000000041");
            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            Assertions.assertThat(state.tranRecord().orElseThrow().tranAmt().scale())
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);
            Assertions.assertThat(state.accountRecord().getAcctCurrBal().scale())
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);
            Assertions.assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("the account is rewritten AFTER the transaction is written, never before")
        void writeOrdering() {
            stubHappyPath("100.00", "0000000000000041");
            List<String> order = new ArrayList<>();
            when(transactionRepository.write(any())).thenAnswer(invocation -> {
                order.add("write TRANSACT");
                return TransactionRepository.WriteResult.written(
                        TransactionRepository.CICS_FILE_NAME);
            });
            when(accountRepository.rewrite(any())).thenAnswer(invocation -> {
                order.add("rewrite ACCTDAT");
                return AccountRepository.WriteResult.written();
            });

            service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            Assertions.assertThat(order).containsExactly("write TRANSACT", "rewrite ACCTDAT");
        }

        @Test
        @DisplayName("the browse is a BACKWARD boundary browse and is ended")
        void browseShape() {
            stubHappyPath("100.00", "0000000000000041");

            service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            verify(transactionRepository).startBrowse(BrowseDirection.BACKWARD);
            verify(browse).readPrev();
            // Ended explicitly at :215 and again by try-with-resources, which is safe by contract.
            verify(browse, times(1)).endBrowse();
            verify(browse, times(1)).close();
        }

        @Test
        @DisplayName("THE UNGUARDED SEQUENCE - a missing cross-reference still writes the transaction")
        void unguardedSequenceStillWrites() {
            stubHappyPath("100.00", "0000000000000041");
            when(cardXrefRepository.readByAccountIdViaAltIndex(anyString()))
                    .thenReturn(CardXrefRepository.ReadResult.notFound(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME));

            PaymentState state = service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty());

            // The flag was raised and the screen repainted, and the payment happened anyway.
            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            verify(transactionRepository).write(any());
            verify(accountRepository).rewrite(any());
            // TRAN-CARD-NUM carries the LOW-VALUES of an unfilled WORKING-STORAGE record area.
            Assertions.assertThat(state.tranRecord().orElseThrow().tranCardNum())
                    .isEqualTo("\u0000".repeat(CardXrefRecord.XREF_CARD_NUM_LENGTH));
            Assertions.assertThat(state.cardXrefRecord()).isEmpty();
        }

        @Test
        @DisplayName("a failed READPREV leaves HIGH-VALUES in the key, which is the S0C7 equivalent")
        void readprevFailureIsADataException() {
            stubHappyPath("100.00", "0000000000000041");
            when(browse.readPrev()).thenReturn(TransactionRepository.ReadResult.other(
                    TransactionRepository.CICS_FILE_NAME,
                    TransactionRepository.PERMANENT_ERROR_STATUS));

            Assertions.assertThatThrownBy(
                            () -> service.processEnterKey(ACCT_KEY, "Y", NavigationContext.empty()))
                    .isInstanceOf(IllegalArgumentException.class);

            // Nothing was written: the task abended before the record was assembled.
            verify(transactionRepository, never()).write(any());
            verify(accountRepository, never()).rewrite(any());
        }
    }


    // =================================================================================================
    // The seven file operations - one test per arm of every ordered EVALUATE (gate G47).
    // =================================================================================================

    @Nested
    @DisplayName("the file operations - :343-547, every arm of every EVALUATE")
    class FileOperations {

        @Test
        @DisplayName("READ-ACCTDAT-FILE :357-358 NORMAL replaces the record area")
        void readAccountNormal() {
            PaymentState state = stateWithAccount("0.00");
            when(accountRepository.readForUpdate(ACCT_KEY))
                    .thenReturn(AccountRepository.ReadResult.found(account("77.77")));

            service.readAcctdatFile(state);

            Assertions.assertThat(state.isErrFlagOn()).isFalse();
            Assertions.assertThat(state.accountRecord().getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("77.77"));
            Assertions.assertThat(state.screensSent()).isZero();
        }

        @Test
        @DisplayName("READ-ACCTDAT-FILE :359-364 NOTFND rejects with 'Account ID NOT found...'")
        void readAccountNotFound() {
            PaymentState state = stateWithAccount("0.00");
            when(accountRepository.readForUpdate(ACCT_KEY))
                    .thenReturn(AccountRepository.ReadResult.notFound());

            service.readAcctdatFile(state);

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message()).startsWith("Account ID NOT found...");
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.ACTIDIN);
            Assertions.assertThat(state.displays()).isEmpty();
            Assertions.assertThat(state.respCd()).isEqualTo(FileStatus.NOTFND);
        }

        @Test
        @DisplayName("READ-ACCTDAT-FILE :365-371 WHEN OTHER displays RESP/REAS then rejects")
        void readAccountOther() {
            PaymentState state = stateWithAccount("0.00");
            when(accountRepository.readForUpdate(ACCT_KEY)).thenReturn(
                    AccountRepository.ReadResult.of(AccountRepository.PERMANENT_ERROR_STATUS));

            service.readAcctdatFile(state);

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message()).startsWith("Unable to lookup Account...");
            Assertions.assertThat(state.displays()).hasSize(1);
            Assertions.assertThat(state.displays().get(0)).startsWith("RESP:").contains("REAS:");
            // Both operands are rendered at the nine digit positions DISPLAY shows.
            Assertions.assertThat(state.displays().get(0)).hasSize(5 + 9 + 5 + 9);
        }

        @Test
        @DisplayName("UPDATE-ACCTDAT-FILE - all three arms")
        void rewriteArms() {
            PaymentState written = stateWithAccount("10.00");
            when(accountRepository.rewrite(any())).thenReturn(AccountRepository.WriteResult.written());
            service.updateAcctdatFile(written);
            Assertions.assertThat(written.accountRewritten()).isTrue();
            Assertions.assertThat(written.isErrFlagOn()).isFalse();

            PaymentState missing = stateWithAccount("10.00");
            when(accountRepository.rewrite(any())).thenReturn(AccountRepository.WriteResult.notFound());
            service.updateAcctdatFile(missing);
            Assertions.assertThat(missing.message()).startsWith("Account ID NOT found...");
            Assertions.assertThat(missing.accountRewritten()).isFalse();

            PaymentState refused = stateWithAccount("10.00");
            when(accountRepository.rewrite(any())).thenReturn(
                    AccountRepository.WriteResult.of(AccountRepository.PERMANENT_ERROR_STATUS));
            service.updateAcctdatFile(refused);
            Assertions.assertThat(refused.message()).startsWith("Unable to Update Account...");
            Assertions.assertThat(refused.displays()).hasSize(1);
        }

        @Test
        @DisplayName("READ-CXACAIX-FILE - all three arms, and NOTFND reuses the account's own text")
        void xrefArms() {
            PaymentState found = stateWithAccount("10.00");
            when(cardXrefRepository.readByAccountIdViaAltIndex(ACCT_KEY)).thenReturn(xrefFound());
            service.readCxacaixFile(found);
            Assertions.assertThat(found.cardXrefRecord()).isPresent();
            Assertions.assertThat(found.xrefCardNum()).isEqualTo(CARD_NUMBER);

            PaymentState missing = stateWithAccount("10.00");
            when(cardXrefRepository.readByAccountIdViaAltIndex(ACCT_KEY)).thenReturn(
                    CardXrefRepository.ReadResult.notFound(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME));
            service.readCxacaixFile(missing);
            Assertions.assertThat(missing.message()).startsWith("Account ID NOT found...");
            Assertions.assertThat(missing.cardXrefRecord()).isEmpty();

            PaymentState refused = stateWithAccount("10.00");
            when(cardXrefRepository.readByAccountIdViaAltIndex(ACCT_KEY)).thenReturn(
                    CardXrefRepository.ReadResult.other(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            CardXrefRepository.PERMANENT_ERROR_STATUS));
            service.readCxacaixFile(refused);
            Assertions.assertThat(refused.message()).startsWith("Unable to lookup XREF AIX file...");
            Assertions.assertThat(refused.displays()).hasSize(1);
        }

        @Test
        @DisplayName("STARTBR-TRANSACT-FILE :452-453 NORMAL - the only outcome the sequence supplies")
        void startbrNormal() {
            PaymentState state = stateWithAccount("10.00");

            service.startbrTransactFile(state, BillPaymentService.STARTBR_POSITIONING_OUTCOME);

            Assertions.assertThat(BillPaymentService.STARTBR_POSITIONING_OUTCOME)
                    .isEqualTo(Outcome.OK);
            Assertions.assertThat(state.browseStarted()).isTrue();
            Assertions.assertThat(state.isErrFlagOn()).isFalse();
        }

        @Test
        @DisplayName("STARTBR-TRANSACT-FILE :454-459 NOTFND rejects with 'Transaction ID NOT found...'")
        void startbrNotFound() {
            PaymentState state = stateWithAccount("10.00");

            service.startbrTransactFile(state, Outcome.NOT_FOUND);

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message()).startsWith("Transaction ID NOT found...");
            Assertions.assertThat(state.browseStarted()).isFalse();
        }

        @ParameterizedTest(name = "STARTBR outcome {0} lands on WHEN OTHER")
        @EnumSource(value = Outcome.class, names = {"END_OF_FILE", "DUPLICATE", "OTHER"})
        @DisplayName("STARTBR-TRANSACT-FILE :460-466 WHEN OTHER catches everything else")
        void startbrOther(Outcome outcome) {
            PaymentState state = stateWithAccount("10.00");

            service.startbrTransactFile(state, outcome);

            Assertions.assertThat(state.message()).startsWith("Unable to lookup Transaction...");
            Assertions.assertThat(state.displays()).hasSize(1);
        }

        @Test
        @DisplayName("STARTBR-TRANSACT-FILE requires a state and an outcome")
        void startbrArgumentsAreRequired() {
            PaymentState state = stateWithAccount("10.00");
            Assertions.assertThatThrownBy(() -> service.startbrTransactFile(null, Outcome.OK))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> service.startbrTransactFile(state, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("READPREV-TRANSACT-FILE :485-486 NORMAL takes the highest existing identifier")
        void readprevNormal() {
            PaymentState state = stateWithAccount("10.00");

            service.readprevTransactFile(state, TransactionRepository.ReadResult.found(
                    TransactionRepository.CICS_FILE_NAME, tran("0000000000000099")));

            Assertions.assertThat(state.tranIdRidfld()).isEqualTo("0000000000000099");
            Assertions.assertThat(state.tranRecord()).isPresent();
            Assertions.assertThat(state.isErrFlagOn()).isFalse();
        }

        @Test
        @DisplayName("READPREV-TRANSACT-FILE :487-488 ENDFILE moves ZEROS and raises nothing")
        void readprevEndFile() {
            PaymentState state = stateWithAccount("10.00");

            service.readprevTransactFile(state, TransactionRepository.ReadResult.endOfFile(
                    TransactionRepository.CICS_FILE_NAME));

            Assertions.assertThat(state.tranIdRidfld()).isEqualTo("0".repeat(16));
            Assertions.assertThat(state.isErrFlagOn()).isFalse();
            Assertions.assertThat(state.screensSent()).isZero();
            Assertions.assertThat(state.displays()).isEmpty();
        }

        @Test
        @DisplayName("READPREV-TRANSACT-FILE :489-495 WHEN OTHER leaves the key untouched")
        void readprevOther() {
            PaymentState state = stateWithAccount("10.00");
            state.setTranIdRidfld(BillPaymentService.HIGH_VALUES_TRAN_ID);

            service.readprevTransactFile(state, TransactionRepository.ReadResult.other(
                    TransactionRepository.CICS_FILE_NAME,
                    TransactionRepository.PERMANENT_ERROR_STATUS));

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message()).startsWith("Unable to lookup Transaction...");
            Assertions.assertThat(state.tranIdRidfld())
                    .isEqualTo(BillPaymentService.HIGH_VALUES_TRAN_ID);
            Assertions.assertThat(state.displays()).hasSize(1);
        }

        @Test
        @DisplayName("READPREV-TRANSACT-FILE - a NOTFND also lands on WHEN OTHER")
        void readprevNotFoundIsOther() {
            PaymentState state = stateWithAccount("10.00");

            service.readprevTransactFile(state, TransactionRepository.ReadResult.notFound(
                    TransactionRepository.CICS_FILE_NAME));

            Assertions.assertThat(state.message()).startsWith("Unable to lookup Transaction...");
        }

        @Test
        @DisplayName("READPREV-TRANSACT-FILE requires a state and a result")
        void readprevArgumentsAreRequired() {
            PaymentState state = stateWithAccount("10.00");
            TransactionRepository.ReadResult ok = TransactionRepository.ReadResult.endOfFile(
                    TransactionRepository.CICS_FILE_NAME);
            Assertions.assertThatThrownBy(() -> service.readprevTransactFile(null, ok))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> service.readprevTransactFile(state, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ENDBR-TRANSACT-FILE :501-505 has no status and no error handling at all")
        void endbrHasNoHandling() {
            service.endbrTransactFile(browse);

            verify(browse).endBrowse();
            Assertions.assertThatThrownBy(() -> service.endbrTransactFile(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("WRITE-TRANSACT-FILE :523-532 NORMAL clears, greens and confirms")
        void writeNormal() {
            PaymentState state = stateWithAccount("10.00");
            state.setTranRecord(tran("0000000000000042"));
            state.setActIdIn(ACCT_KEY);
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.written(
                            TransactionRepository.CICS_FILE_NAME));

            service.writeTransactFile(state);

            Assertions.assertThat(state.isErrFlagOn()).isFalse();
            Assertions.assertThat(state.actIdIn()).isBlank();
            Assertions.assertThat(state.curBal()).isBlank();
            Assertions.assertThat(state.confirm()).isBlank();
            Assertions.assertThat(state.messageHighlight())
                    .isEqualTo(BillPaymentService.MESSAGE_HIGHLIGHT_GREEN);
            Assertions.assertThat(state.message()).isEqualTo(
                    "Payment successful.  Your Transaction ID is 0000000000000042."
                            + " ".repeat(BillPaymentService.WS_MESSAGE_LENGTH - 61));
            Assertions.assertThat(state.screensSent()).isOne();
            // The screen field is two bytes narrower than the message, and the send applies that.
            SentScreen sent = state.sentScreens().get(0);
            Assertions.assertThat(sent.errMsg()).hasSize(BillPaymentService.ERR_MSG_LENGTH);
            Assertions.assertThat(sent.ordinal()).isOne();
        }

        @Test
        @DisplayName("WRITE-TRANSACT-FILE :533-539 DUPKEY and DUPREC share one arm")
        void writeDuplicate() {
            PaymentState state = stateWithAccount("10.00");
            state.setTranRecord(tran("0000000000000042"));
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.duplicate(
                            TransactionRepository.CICS_FILE_NAME));

            service.writeTransactFile(state);

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message()).startsWith("Tran ID already exist...");
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.ACTIDIN);
            Assertions.assertThat(state.messageHighlight()).isNull();
        }

        @Test
        @DisplayName("WRITE-TRANSACT-FILE :540-546 WHEN OTHER displays then rejects")
        void writeOther() {
            PaymentState state = stateWithAccount("10.00");
            state.setTranRecord(tran("0000000000000042"));
            when(transactionRepository.write(any())).thenReturn(
                    TransactionRepository.WriteResult.other(TransactionRepository.CICS_FILE_NAME,
                            TransactionRepository.PERMANENT_ERROR_STATUS));

            service.writeTransactFile(state);

            Assertions.assertThat(state.message())
                    .startsWith("Unable to Add Bill pay Transaction...");
            Assertions.assertThat(state.displays()).hasSize(1);
        }

        @Test
        @DisplayName("WRITE-TRANSACT-FILE needs an assembled record, and a state")
        void writeNeedsARecord() {
            PaymentState empty = stateWithAccount("10.00");
            Assertions.assertThatThrownBy(() -> service.writeTransactFile(empty))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("assembled");
            Assertions.assertThatThrownBy(() -> service.writeTransactFile(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("every paragraph requires a state")
        void everyParagraphRequiresAState() {
            Assertions.assertThatThrownBy(() -> service.readAcctdatFile(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> service.updateAcctdatFile(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> service.readCxacaixFile(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> service.getCurrentTimestamp(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> service.sendBillpayScreen(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> service.clearCurrentScreen(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> service.initializeAllFields(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> service.invalidKeyPressed(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }


    // =================================================================================================
    // GET-CURRENT-TIMESTAMP, the edit mask, the screen paragraphs and the key switch's WHEN OTHER.
    // =================================================================================================

    @Nested
    @DisplayName("GET-CURRENT-TIMESTAMP - :249-267")
    class Timestamp {

        @Test
        @DisplayName("twenty-six characters, and the microseconds are always zeros")
        void twentySixCharactersWithZeroMicroseconds() {
            PaymentState state = stateWithAccount("10.00");

            String composed = service.getCurrentTimestamp(state);

            Assertions.assertThat(composed).isEqualTo(EXPECTED_TIMESTAMP).hasSize(26);
            Assertions.assertThat(composed).endsWith(".000000");
            Assertions.assertThat(composed.charAt(10)).isEqualTo(' ');
            Assertions.assertThat(composed.charAt(19)).isEqualTo('.');
            Assertions.assertThat(state.timestamp()).isEqualTo(EXPECTED_TIMESTAMP);
            Assertions.assertThat(state.curDateX10()).isEqualTo("2022-07-19");
            Assertions.assertThat(state.curTimeX08()).isEqualTo("23:12:32");
        }

        @Test
        @DisplayName("WS-ABS-TIME is the CICS absolute time, milliseconds since 1900-01-01")
        void absTime() {
            PaymentState state = stateWithAccount("10.00");

            service.getCurrentTimestamp(state);

            Assertions.assertThat(state.absTime()).isEqualTo(
                    FIXED_INSTANT.toEpochMilli() + BillPaymentService.CICS_ABSTIME_EPOCH_OFFSET_MILLIS);
            Assertions.assertThat(state.absTime()).isLessThan(1_000_000_000_000_000L);
        }

        @Test
        @DisplayName("every component is zero-filled to its declared digit count")
        void componentsAreZeroFilled() {
            BillPaymentService january = new BillPaymentService(accountRepository, cardXrefRepository,
                    transactionRepository,
                    Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC),
                    newUnitOfWork());
            PaymentState state = new PaymentState(january.codec(), NavigationContext.empty());

            Assertions.assertThat(january.getCurrentTimestamp(state))
                    .isEqualTo("2024-01-02 03:04:05.000000");
        }

        @Test
        @DisplayName("the clock is read once, so a second call on a fixed clock is identical")
        void clockIsDeterministic() {
            PaymentState first = stateWithAccount("10.00");
            PaymentState second = stateWithAccount("10.00");

            Assertions.assertThat(service.getCurrentTimestamp(first))
                    .isEqualTo(service.getCurrentTimestamp(second));
        }
    }

    @Nested
    @DisplayName("the WS-CURR-BAL edit mask - PIC +9999999999.99, :56 and :193")
    class EditMask {

        @ParameterizedTest(name = "{0} renders {1}")
        @CsvSource({
                "0.00,           +0000000000.00",
                "0.01,           +0000000000.01",
                "1234.56,        +0000001234.56",
                "-50.00,         -0000000050.00",
                "-0.01,          -0000000000.01",
                "9999999999.99,  +9999999999.99",
                "-9999999999.99, -9999999999.99"
        })
        @DisplayName("a forced sign, ten zero-filled integer digits, the point and two fraction digits")
        void rendersTheMask(String balance, String expected) {
            Assertions.assertThat(service.editCurrBal(new BigDecimal(balance)))
                    .isEqualTo(expected)
                    .hasSize(BillPaymentService.CUR_BAL_LENGTH);
        }

        @Test
        @DisplayName("the mask width is derived from the picture and equals the screen field's")
        void maskWidthEqualsTheFieldWidth() {
            Assertions.assertThat(BillPaymentService.WS_CURR_BAL_LENGTH)
                    .isEqualTo(BillPaymentService.CUR_BAL_LENGTH)
                    .isEqualTo(14);
        }

        @Test
        @DisplayName("excess fraction digits truncate toward zero, never round")
        void fractionTruncates() {
            Assertions.assertThat(service.editCurrBal(new BigDecimal("1.999")))
                    .isEqualTo("+0000000001.99");
            Assertions.assertThat(service.editCurrBal(new BigDecimal("-1.999")))
                    .isEqualTo("-0000000001.99");
        }

        @Test
        @DisplayName("excess integer digits lose their HIGH-order positions")
        void integerDigitsTruncateOnTheLeft() {
            Assertions.assertThat(service.editCurrBal(new BigDecimal("123456789012.34")))
                    .isEqualTo("+3456789012.34");
        }

        @Test
        @DisplayName("a balance is required")
        void balanceIsRequired() {
            Assertions.assertThatThrownBy(() -> service.editCurrBal(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("the screen paragraphs - :289-301, :552-566, and the key switch's WHEN OTHER at :138")
    class ScreenParagraphs {

        @Test
        @DisplayName("SEND-BILLPAY-SCREEN moves the 80-byte message into the 78-byte field")
        void sendTruncatesOnTheRight() {
            PaymentState state = stateWithAccount("10.00");
            String eighty = "A".repeat(BillPaymentService.WS_MESSAGE_LENGTH);
            state.setMessage(eighty);

            service.sendBillpayScreen(state);

            Assertions.assertThat(state.message()).hasSize(80);
            Assertions.assertThat(state.errMsg())
                    .hasSize(BillPaymentService.ERR_MSG_LENGTH)
                    .isEqualTo("A".repeat(78));
            Assertions.assertThat(state.screensSent()).isOne();
        }

        @Test
        @DisplayName("INITIALIZE-ALL-FIELDS blanks four receivers at four different widths")
        void initializeAllFields() {
            PaymentState state = stateWithAccount("10.00");
            state.setActIdIn(ACCT_KEY);
            state.setCurBal("+0000001234.56");
            state.setConfirm("Y");
            state.setMessage("something");
            state.setCursorField(CursorField.CONFIRM);

            service.initializeAllFields(state);

            Assertions.assertThat(state.actIdIn()).hasSize(11).isBlank();
            Assertions.assertThat(state.curBal()).hasSize(14).isBlank();
            Assertions.assertThat(state.confirm()).hasSize(1).isBlank();
            Assertions.assertThat(state.message()).hasSize(80).isBlank();
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.ACTIDIN);
            // ERRMSGO is deliberately NOT blanked by this paragraph.
            Assertions.assertThat(state.screensSent()).isZero();
        }

        @Test
        @DisplayName("CLEAR-CURRENT-SCREEN initialises and then sends")
        void clearCurrentScreen() {
            PaymentState state = stateWithAccount("10.00");
            state.setActIdIn(ACCT_KEY);

            service.clearCurrentScreen(state);

            Assertions.assertThat(state.actIdIn()).isBlank();
            Assertions.assertThat(state.screensSent()).isOne();
            Assertions.assertThat(state.sentScreens().get(0).actIdIn()).isBlank();
        }

        @Test
        @DisplayName(":138-141 - the invalid-key arm widens a 50-byte message to 80 and raises the flag")
        void invalidKeyPressed() {
            PaymentState state = stateWithAccount("10.00");

            service.invalidKeyPressed(state);

            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);
            Assertions.assertThat(state.message())
                    .hasSize(BillPaymentService.WS_MESSAGE_LENGTH)
                    .startsWith("Invalid key pressed. Please see below...");
            // No cursor statement appears on this arm, so the map's own IC position stands.
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(state.screensSent()).isOne();
        }
    }

    // =================================================================================================
    // PaymentState - the per-invocation working storage, its widths and its guards.
    // =================================================================================================

    @Nested
    @DisplayName("PaymentState - the WORKING-STORAGE of one invocation")
    class WorkingStorage {

        @Test
        @DisplayName("the declared initial state")
        void initialState() {
            PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());

            Assertions.assertThat(state.errFlg()).isEqualTo(BillPaymentService.ERR_FLG_OFF);
            Assertions.assertThat(state.isErrFlagOn()).isFalse();
            Assertions.assertThat(state.confPayFlg()).isEqualTo(BillPaymentService.CONF_PAY_NO);
            Assertions.assertThat(state.isConfPayYes()).isFalse();
            Assertions.assertThat(state.message()).hasSize(80).isBlank();
            Assertions.assertThat(state.errMsg()).hasSize(78).isBlank();
            Assertions.assertThat(state.currBalEdited()).hasSize(14).isBlank();
            Assertions.assertThat(state.tranIdNum()).isZero();
            Assertions.assertThat(state.absTime()).isZero();
            Assertions.assertThat(state.respCd()).isEqualTo(FileStatus.NORMAL);
            Assertions.assertThat(state.reasCd()).isEqualTo(FileStatus.NO_REASON_CODE);
            Assertions.assertThat(state.cursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(state.messageHighlight()).isNull();
            Assertions.assertThat(state.cardXrefRecord()).isEmpty();
            Assertions.assertThat(state.tranRecord()).isEmpty();
            Assertions.assertThat(state.sentScreens()).isEmpty();
            Assertions.assertThat(state.displays()).isEmpty();
            Assertions.assertThat(state.browseStarted()).isFalse();
            Assertions.assertThat(state.accountRewritten()).isFalse();
            Assertions.assertThat(state.acctIdCheckContinued()).isFalse();
            // The unfilled 300-byte area reads as a positive zero balance.
            Assertions.assertThat(state.accountRecord().getAcctCurrBal())
                    .isEqualByComparingTo(CobolDecimal.monetaryZero());
            // And the unfilled 50-byte area contributes LOW-VALUES to TRAN-CARD-NUM.
            Assertions.assertThat(state.xrefCardNum()).isEqualTo("\u0000".repeat(16));
        }

        @Test
        @DisplayName("both constructor arguments are required")
        void constructorArgumentsAreRequired() {
            Assertions.assertThatThrownBy(
                            () -> new PaymentState(null, NavigationContext.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new PaymentState(service.codec(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the flags round-trip through their SET statements")
        void flagsRoundTrip() {
            PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());

            state.setErrFlagOn();
            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            state.setConfPayYes();
            Assertions.assertThat(state.isConfPayYes()).isTrue();
            state.setConfPayNo();
            Assertions.assertThat(state.isConfPayYes()).isFalse();
            state.setAcctIdCheckContinued();
            Assertions.assertThat(state.acctIdCheckContinued()).isTrue();
            state.setBrowseStarted();
            Assertions.assertThat(state.browseStarted()).isTrue();
            state.setAccountRewritten();
            Assertions.assertThat(state.accountRewritten()).isTrue();
        }

        @Test
        @DisplayName("every setter applies its receiver's declared width")
        void settersApplyDeclaredWidths() {
            PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());

            state.setMessage("short");
            Assertions.assertThat(state.message()).hasSize(80).startsWith("short");
            state.setMessage("X".repeat(200));
            Assertions.assertThat(state.message()).hasSize(80);
            state.setMessageSpaces();
            Assertions.assertThat(state.message()).hasSize(80).isBlank();

            state.setActIdIn("1");
            Assertions.assertThat(state.actIdIn()).isEqualTo("1          ");
            state.setCurBal("+1.00");
            Assertions.assertThat(state.curBal()).hasSize(14);
            state.setConfirm("YES");
            Assertions.assertThat(state.confirm()).isEqualTo("Y");
            state.setCurDateX10("2024-01-02");
            Assertions.assertThat(state.curDateX10()).isEqualTo("2024-01-02");
            state.setCurTimeX08("03:04:05");
            Assertions.assertThat(state.curTimeX08()).isEqualTo("03:04:05");
            state.setErrMsg("Z".repeat(78));
            Assertions.assertThat(state.errMsg()).hasSize(78);
            state.setMessageHighlight(null);
            Assertions.assertThat(state.messageHighlight()).isNull();
        }

        @Test
        @DisplayName("null is refused wherever COBOL has no null representation")
        void nullsAreRefused() {
            PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());

            Assertions.assertThatThrownBy(() -> state.setMessage(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setActIdIn(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setCurBal(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setConfirm(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setErrMsg(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setCurDateX10(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setCurTimeX08(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setCurrBalEdited(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setTimestamp(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setTranIdRidfld(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setAcctIdRidfld(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setXrefAcctIdRidfld(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setAccountRecord(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setCardXrefRecord(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setTranRecord(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.setCursorField(null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> state.recordDisplay(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a fixed-width item is never padded or truncated into a fixed-width receiver")
        void fixedWidthItemsRefuseTheWrongWidth() {
            PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());

            Assertions.assertThatThrownBy(() -> state.setCurrBalEdited("+1.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("+9999999999.99");
            Assertions.assertThatThrownBy(() -> state.setTimestamp("2024-01-02"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WS-TIMESTAMP");
            Assertions.assertThatThrownBy(() -> state.setTranIdRidfld("1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TRAN-ID");
            Assertions.assertThatThrownBy(() -> state.setAcctIdRidfld("1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ACCT-ID");
            Assertions.assertThatThrownBy(() -> state.setXrefAcctIdRidfld("1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XREF-ACCT-ID");
            Assertions.assertThatThrownBy(() -> state.setTranIdNum(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsigned");
        }

        @Test
        @DisplayName("the recorded sends and displays are unmodifiable views")
        void recordedViewsAreUnmodifiable() {
            PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());
            state.recordSend();
            state.recordDisplay("RESP:000000013REAS:000000000");

            Assertions.assertThat(state.sentScreens()).hasSize(1);
            Assertions.assertThat(state.displays()).containsExactly("RESP:000000013REAS:000000000");
            Assertions.assertThatThrownBy(() -> state.sentScreens().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            Assertions.assertThatThrownBy(() -> state.displays().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("the response codes are recorded verbatim, including the unreported sentinel")
        void responseCodesAreRecorded() {
            PaymentState state = new PaymentState(service.codec(), NavigationContext.empty());

            state.setResponseCodes(FileStatus.RESP_NOT_REPORTED, FileStatus.NO_REASON_CODE);
            Assertions.assertThat(state.respCd()).isEqualTo(FileStatus.RESP_NOT_REPORTED);
            Assertions.assertThat(FileStatus.respReported(state.respCd())).isFalse();

            state.setResponseCodes(FileStatus.NOTFND, 42);
            Assertions.assertThat(state.respCd()).isEqualTo(FileStatus.NOTFND);
            Assertions.assertThat(state.reasCd()).isEqualTo(42);
        }

        @Test
        @DisplayName("an unreported response renders at the same nine positions as a reported one")
        void unreportedResponseRendersAtFullWidth() {
            PaymentState state = stateWithAccount("10.00");
            // AccountRepository.ReadResult.of(status) synthesises the response from the status, so this
            // exercises the reported branch; the sentinel branch is exercised through respImage below.
            when(accountRepository.readForUpdate(ACCT_KEY)).thenReturn(
                    AccountRepository.ReadResult.of(AccountRepository.PERMANENT_ERROR_STATUS));
            service.readAcctdatFile(state);
            Assertions.assertThat(state.displays().get(0)).hasSize(28);

            PaymentState sentinel = stateWithAccount("10.00");
            sentinel.setResponseCodes(FileStatus.RESP_NOT_REPORTED, FileStatus.NO_REASON_CODE);
            service.readprevTransactFile(sentinel, TransactionRepository.ReadResult.other(
                    TransactionRepository.CICS_FILE_NAME,
                    TransactionRepository.PERMANENT_ERROR_STATUS));
            Assertions.assertThat(sentinel.displays().get(0)).hasSize(28);
        }

        @Test
        @DisplayName("the communication area is carried in and handed straight back")
        void commareaIsCarried() {
            NavigationContext carried = NavigationContext.empty()
                    .withFromProgram("COMEN01C").withUserTypeUser();

            PaymentState state = service.processEnterKey(null, null, carried);

            Assertions.assertThat(state.commarea()).isEqualTo(carried);
        }

        @Test
        @DisplayName("two invocations share no state")
        void invocationsAreIsolated() {
            when(accountRepository.readForUpdate(anyString()))
                    .thenReturn(AccountRepository.ReadResult.found(account("500.00")));

            PaymentState first = service.processEnterKey(ACCT_KEY, " ", NavigationContext.empty());
            PaymentState second = service.processEnterKey("           ", " ",
                    NavigationContext.empty());

            Assertions.assertThat(first).isNotSameAs(second);
            Assertions.assertThat(first.isErrFlagOn()).isFalse();
            Assertions.assertThat(second.isErrFlagOn()).isTrue();
            Assertions.assertThat(first.curBal()).isEqualTo("+0000000500.00");
            Assertions.assertThat(second.curBal()).isBlank();
        }
    }

    // =================================================================================================
    // The two COBOL semantics helpers, asserted across every argument shape.
    //
    // Both are exercised on the program's own path already - the identifier strung at :527-531 is
    // sixteen zero-padded digits, and the account field moved at :171-172 is eleven characters wide -
    // so on that path one arm of each helper never runs. The arms still have to be correct: the
    // truncating arm of DELIMITED BY SPACE is the entire difference between that phrase and DELIMITED
    // BY SIZE, and the zero-length guard is what keeps "all characters are digits" from being
    // vacuously true. Each is therefore asserted directly, and then once more through the public path
    // that reaches it.
    // =================================================================================================

    @Nested
    @DisplayName("COBOL semantics helpers - DELIMITED BY SPACE and the digit test")
    class CobolSemanticsHelpers {

        @Test
        @DisplayName("DELIMITED BY SPACE :529 - an operand with no space contributes all of itself")
        void delimitedBySpaceWithoutASpaceTakesEverything() {
            Assertions.assertThat(BillPaymentService.delimitedBySpace("0000000000000001"))
                    .isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("DELIMITED BY SPACE :529 - an interior space stops the contribution there")
        void delimitedBySpaceStopsAtAnInteriorSpace() {
            Assertions.assertThat(BillPaymentService.delimitedBySpace("1 3")).isEqualTo("1");
            Assertions.assertThat(BillPaymentService.delimitedBySpace("1               "))
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("DELIMITED BY SPACE :529 - a leading space contributes nothing at all")
        void delimitedBySpaceWithALeadingSpaceContributesNothing() {
            Assertions.assertThat(BillPaymentService.delimitedBySpace(" 0000001")).isEmpty();
            Assertions.assertThat(BillPaymentService.delimitedBySpace("                ")).isEmpty();
        }

        @Test
        @DisplayName("DELIMITED BY SPACE :529 - an empty operand contributes nothing")
        void delimitedBySpaceOfAnEmptyOperandIsEmpty() {
            Assertions.assertThat(BillPaymentService.delimitedBySpace("")).isEmpty();
        }

        @Test
        @DisplayName("the digit test rejects a zero-length value rather than accepting it vacuously")
        void isAllDigitsRejectsAnEmptyValue() {
            Assertions.assertThat(BillPaymentService.isAllDigits("")).isFalse();
        }

        @Test
        @DisplayName("the digit test accepts both boundary characters and every digit between them")
        void isAllDigitsAcceptsTheWholeDigitRange() {
            Assertions.assertThat(BillPaymentService.isAllDigits("0")).isTrue();
            Assertions.assertThat(BillPaymentService.isAllDigits("9")).isTrue();
            Assertions.assertThat(BillPaymentService.isAllDigits("0123456789")).isTrue();
            Assertions.assertThat(BillPaymentService.isAllDigits(ACCT_KEY)).isTrue();
        }

        @Test
        @DisplayName("the digit test rejects a character below '0', which is how a space is rejected")
        void isAllDigitsRejectsACharacterBelowZero() {
            Assertions.assertThat(BillPaymentService.isAllDigits("123        ")).isFalse();
            Assertions.assertThat(BillPaymentService.isAllDigits("/")).isFalse();
        }

        @Test
        @DisplayName("the digit test rejects a character above '9', which is how a letter is rejected")
        void isAllDigitsRejectsACharacterAboveNine() {
            Assertions.assertThat(BillPaymentService.isAllDigits("ABCDEFGHIJK")).isFalse();
            Assertions.assertThat(BillPaymentService.isAllDigits(":")).isFalse();
            Assertions.assertThat(BillPaymentService.isAllDigits("1234567890A")).isFalse();
        }

        @Test
        @DisplayName("WRITE-TRANSACT :527-531 honours DELIMITED BY SPACE on a padded identifier")
        void successMessageStopsAtTheIdentifiersFirstSpace() {
            when(transactionRepository.write(any()))
                    .thenReturn(TransactionRepository.WriteResult.written(
                            TransactionRepository.CICS_FILE_NAME));
            PaymentState state = stateWithAccount("500.00");
            state.setTranRecord(tran("1"));

            service.writeTransactFile(state);

            // moveTranId right-space-pads to sixteen, so the stored identifier is "1" then fifteen
            // spaces; DELIMITED BY SPACE contributes the single character and nothing after it.
            Assertions.assertThat(state.tranRecord().orElseThrow().tranId())
                    .isEqualTo("1               ");
            Assertions.assertThat(state.message())
                    .startsWith("Payment successful.  Your Transaction ID is 1.");
            Assertions.assertThat(state.message()).doesNotContain("1  ");
        }

        @Test
        @DisplayName(":171-172 carries an alphabetic account field verbatim, and the read simply misses")
        void alphabeticAccountFieldIsCarriedVerbatimAndMisses() {
            when(accountRepository.readForUpdate("ABCDEFGHIJK"))
                    .thenReturn(AccountRepository.ReadResult.notFound());

            PaymentState state = service.processEnterKey("ABCDEFGHIJK", " ",
                    NavigationContext.empty());

            // BMS declares ACTIDIN as ATTRB=(FSET,IC,NORM,UNPROT) and not NUM, so letters can be
            // typed. The equal-width alphanumeric-to-numeric MOVE copies the bytes as they are; the
            // program adds no validation, so the value reaches the file and misses.
            Assertions.assertThat(state.acctIdRidfld()).isEqualTo("ABCDEFGHIJK");
            Assertions.assertThat(state.xrefAcctIdRidfld()).isEqualTo("ABCDEFGHIJK");
            Assertions.assertThat(state.isErrFlagOn()).isTrue();
            Assertions.assertThat(state.message()).startsWith("Account ID NOT found...");
            verify(accountRepository).readForUpdate("ABCDEFGHIJK");
        }
    }

}
