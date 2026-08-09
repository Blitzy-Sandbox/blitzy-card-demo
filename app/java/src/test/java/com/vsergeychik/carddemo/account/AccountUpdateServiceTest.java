package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountUpdateService.AccountData;
import com.vsergeychik.carddemo.account.AccountUpdateService.AccountUpdateDetails;
import com.vsergeychik.carddemo.account.AccountUpdateService.Block;
import com.vsergeychik.carddemo.account.AccountUpdateService.ChangeCheck;
import com.vsergeychik.carddemo.account.AccountUpdateService.Comparison;
import com.vsergeychik.carddemo.account.AccountUpdateService.ComparedItem;
import com.vsergeychik.carddemo.account.AccountUpdateService.CustomerData;
import com.vsergeychik.carddemo.account.AccountUpdateService.DetailGroup;
import com.vsergeychik.carddemo.account.AccountUpdateService.MonetaryEdit;
import com.vsergeychik.carddemo.account.AccountUpdateService.NumvalArgument;
import com.vsergeychik.carddemo.account.AccountUpdateService.WriteOutcome;
import com.vsergeychik.carddemo.account.AccountUpdateService.WriteResult;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AccountUpdateService} against {@code app/cbl/COACTUPC.cbl:1073-1136} (the five
 * {@code COMPUTE} sites), {@code :3889-4106} ({@code 9600-WRITE-PROCESSING}) and {@code :4109-4194}
 * ({@code 9700-CHECK-CHANGE-IN-REC}).
 *
 * <p>The concurrency check is the reason this class exists, so it gets the densest coverage: one
 * parameterized case per {@link ComparedItem}, driven from the enum itself so a new item cannot be
 * added without a case appearing for it. Around that sit the fold-direction cases, the asymmetric
 * date-of-birth case, the two lock arms, the two rewrite arms, the staged-image offsets - including
 * the layout defect - and every invariant the five value objects enforce.
 */
@DisplayName("AccountUpdateService - COACTUPC 9600-WRITE-PROCESSING and 9700-CHECK-CHANGE-IN-REC")
class AccountUpdateServiceTest {

    /** The code page the ASCII fixtures use; named explicitly, never defaulted. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** {@code CC-ACCT-ID PIC X(11)} for the fixture account. */
    private static final String ACCT_ID_CHARS = "12345678901";

    /** {@code CDEMO-CUST-ID PIC 9(09)} for the fixture customer. */
    private static final int CUST_ID = 123456789;

    /** A pending message an earlier paragraph might have left, used for the guard cases. */
    private static final String PENDING_MESSAGE = "Account number must be a non zero 11 digit number";

    private AccountRepository accountRepository;
    private CustomerRepository customerRepository;
    private RecordingTransactionManager transactionManager;
    private AccountUpdateService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        customerRepository = mock(CustomerRepository.class);
        transactionManager = new RecordingTransactionManager();
        service = new AccountUpdateService(accountRepository, customerRepository,
                new DatasetUnitOfWork(transactionManager));
    }

    /**
     * A transaction manager that is real and also says what it did.
     *
     * <p>Real, because the two {@code READ ... UPDATE} locks the paragraph takes are only locks inside an
     * actually-active transaction, and {@link DatasetUnitOfWork#active()} reports on the real thing rather
     * than on a synchronisation - so a stub that merely pretended would let a test pass over a boundary
     * that holds nothing. {@link DataSourceTransactionManager} over an in-memory database binds a
     * connection to the thread exactly as a deployment's would.
     *
     * <p>Recording, because the property under test on the rollback path is not what the method returns -
     * that is asserted separately - but that the unit of work <em>rolled back rather than committed</em>.
     * Nothing else observes that: the repositories are doubles, so no row exists to have been un-written.
     */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        /** The real manager, over a database of this instance's own so no two tests share one. */
        private final PlatformTransactionManager delegate = new DataSourceTransactionManager(
                new SimpleDriverDataSource(new org.h2.Driver(),
                        "jdbc:h2:mem:acctupd-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));

        /** How many units of work committed. */
        private int commits;

        /** How many units of work rolled back. */
        private int rollbacks;

        /** How many times a body observed an actually-active transaction. */
        private int activations;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
            delegate.commit(status);
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
            delegate.rollback(status);
        }

        /**
         * Records that a body saw an open transaction. Called from inside one.
         */
        void observeActive() {
            if (DatasetUnitOfWork.active()) {
                activations++;
            }
        }
    }

    // =================================================================================================
    // Fixtures. The stored records and the ACUP-OLD snapshot agree at every one of the thirty-five
    // compared items, so any single mutation isolates exactly one comparison.
    // =================================================================================================

    /**
     * The stored {@code ACCOUNT-RECORD}, matching {@link #matchedAccountData()} item for item.
     *
     * @return a fresh record
     */
    private static AccountRecord storedAccount() {
        AccountRecord account = new AccountRecord(StandardCharsets.US_ASCII);
        account.setAcctId(12345678901L);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1234.56"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate("2020-01-15");
        account.setAcctExpiraionDate("2025-12-31");
        account.setAcctReissueDate("2022-06-30");
        account.setAcctCurrCycCredit(new BigDecimal("250.75"));
        account.setAcctCurrCycDebit(new BigDecimal("99.99"));
        // Deliberately populated and deliberately NOT compared by 9700: ACCT-ADDR-ZIP appears nowhere
        // in app/cbl/COACTUPC.cbl:4115-4145.
        account.setAcctAddrZip("62704-0001");
        account.setAcctGroupId("GROUP01");
        return account;
    }

    /**
     * {@code ACUP-OLD-ACCT-DATA} matching {@link #storedAccount()}.
     *
     * @return the snapshot half
     */
    private static AccountData matchedAccountData() {
        return new AccountData(12345678901L, "Y",
                new BigDecimal("1234.56"), new BigDecimal("5000.00"), new BigDecimal("1000.00"),
                "2020", "01", "15",
                "2025", "12", "31",
                "2022", "06", "30",
                new BigDecimal("250.75"), new BigDecimal("99.99"), "GROUP01");
    }

    /**
     * The stored {@code CUSTOMER-RECORD}, matching {@link #matchedCustomerData()} item for item.
     *
     * @return a fresh record
     */
    private static CustomerRecord storedCustomer() {
        CustomerRecord customer = new CustomerRecord();
        customer.setCustId(CUST_ID);
        customer.setCustFirstName("JOHN");
        customer.setCustMiddleName("Q");
        customer.setCustLastName("PUBLIC");
        customer.setCustAddrLine1("1 MAIN ST");
        customer.setCustAddrLine2("APT 2");
        customer.setCustAddrLine3("SPRINGFIELD");
        customer.setCustAddrStateCd("IL");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("62704-0001");
        customer.setCustPhoneNum1("(217)555-1234");
        customer.setCustPhoneNum2("(217)555-9876");
        customer.setCustSsn(123456789);
        customer.setCustGovtIssuedId("DL1234567890");
        customer.setCustDobYyyyMmDd("1980-07-04");
        customer.setCustEftAccountId("EFT0000001");
        customer.setCustPriCardHolderInd("Y");
        customer.setCustFicoCreditScore(750);
        return customer;
    }

    /**
     * {@code ACUP-OLD-CUST-DATA} matching {@link #storedCustomer()}. Note the date of birth: the
     * snapshot holds {@code 1980}, {@code 07} and {@code 04} as three separate parts - eight characters
     * with no separators - against the record's ten-character {@code 1980-07-04}.
     *
     * @return the snapshot half
     */
    private static CustomerData matchedCustomerData() {
        return new CustomerData(CUST_ID, "JOHN", "Q", "PUBLIC",
                "1 MAIN ST", "APT 2", "SPRINGFIELD", "IL", "USA", "62704-0001",
                "(217)555-1234", "(217)555-9876", 123456789, "DL1234567890",
                "1980", "07", "04", "EFT0000001", "Y", 750);
    }

    /**
     * {@code ACUP-OLD-DETAILS} matching both stored records.
     *
     * @return the snapshot
     */
    private static AccountUpdateDetails matchedOldDetails() {
        return new AccountUpdateDetails(DetailGroup.OLD, matchedAccountData(), matchedCustomerData());
    }

    /**
     * {@code ACUP-NEW-DETAILS} carrying values distinguishable from the stored ones, so a staged image
     * can be checked field by field.
     *
     * @return the typed values
     */
    private static AccountUpdateDetails newDetails() {
        AccountData account = new AccountData(99999999999L, "N",
                new BigDecimal("-42.07"), new BigDecimal("7500.00"), new BigDecimal("1500.00"),
                "2021", "02", "28",
                "2026", "11", "30",
                "2023", "05", "01",
                new BigDecimal("11.11"), new BigDecimal("22.22"), "GRPNEW");
        CustomerData customer = new CustomerData(987654321, "JANE", "R", "ROE",
                "2 OAK AVE", "SUITE 9", "SHELBYVILLE", "IA", "USA", "50309-0002",
                "(515)555-2468", "(515)555-1357", 987654321, "DL0987654321",
                "1975", "12", "25", "EFT0000002", "N", 811);
        return new AccountUpdateDetails(DetailGroup.NEW, account, customer);
    }

    /**
     * The commarea, carrying only what {@code :3920} reads from it.
     *
     * @return a context whose {@code CDEMO-CUST-ID} is the fixture customer
     */
    private static NavigationContext commarea() {
        return NavigationContext.empty().withCustId(CUST_ID);
    }

    /**
     * Arranges both read-for-update calls to succeed with the matched fixtures.
     */
    private void arrangeBothLocks() {
        when(accountRepository.readForUpdate(anyString()))
                .thenReturn(AccountRepository.ReadResult.found(storedAccount()));
        when(customerRepository.readForUpdate(anyString()))
                .thenReturn(CustomerRepository.ReadResult.found(storedCustomer()));
    }

    /**
     * Arranges both rewrites to succeed.
     */
    private void arrangeBothRewrites() {
        when(accountRepository.rewrite(any(AccountRecord.class)))
                .thenReturn(AccountRepository.WriteResult.written());
        when(customerRepository.rewrite(any(CustomerRecord.class)))
                .thenReturn(CustomerRepository.WriteResult.written());
    }

    // =================================================================================================

    @Nested
    @DisplayName("Wiring - constructor injection only, and both collaborators required")
    class Wiring {

        @Test
        @DisplayName("the ACCTDAT repository is required")
        void accountRepositoryRequired() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new AccountUpdateService(null, customerRepository,
                            new DatasetUnitOfWork(transactionManager)))
                    .withMessageContaining("ACCTDAT");
        }

        @Test
        @DisplayName("the CUSTDAT repository is required")
        void customerRepositoryRequired() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new AccountUpdateService(accountRepository, null,
                            new DatasetUnitOfWork(transactionManager)))
                    .withMessageContaining("CUSTDAT");
        }

        @Test
        @DisplayName("the bean holds no state beyond its two final collaborators")
        void noStaticMutableState() {
            Assertions.assertThat(AccountUpdateService.class.getDeclaredFields())
                    .allSatisfy(field -> Assertions.assertThat(
                            java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                            .as(field.getName() + " must be final")
                            .isTrue());
        }

        @Test
        @DisplayName("it is a @Service with exactly one public constructor, so Spring injects it")
        void springCanInstantiateItByConstructorInjection() {
            Assertions.assertThat(AccountUpdateService.class
                    .isAnnotationPresent(org.springframework.stereotype.Service.class)).isTrue();
            // One public constructor means Spring uses it without an @Autowired annotation, which is
            // what practice B9 asks for: no field injection and no setter injection anywhere.
            Assertions.assertThat(AccountUpdateService.class.getConstructors()).hasSize(1);
            // The unit of work is a constructor dependency like any other, because the boundary the two
            // locks need belongs to this paragraph rather than to whoever calls it.
            Assertions.assertThat(AccountUpdateService.class.getConstructors()[0].getParameterTypes())
                    .containsExactly(AccountRepository.class, CustomerRepository.class,
                            DatasetUnitOfWork.class);
            Assertions.assertThat(AccountUpdateService.class.getDeclaredMethods())
                    .noneMatch(method -> method.isAnnotationPresent(
                            org.springframework.beans.factory.annotation.Autowired.class));
            Assertions.assertThat(AccountUpdateService.class.getDeclaredFields())
                    .noneMatch(field -> field.isAnnotationPresent(
                            org.springframework.beans.factory.annotation.Autowired.class));
        }

        @Test
        @DisplayName("a real Spring context builds the bean from the two collaborators (gate G3)")
        void aRealContextWiresTheBean() {
            // The module has no live DataSource here, so a whole-application context load would fail for
            // reasons that have nothing to do with this bean - which is why CardDemoApplicationTest
            // avoids @SpringBootTest too. Registering the singletons and letting the container do the
            // autowiring proves the property that matters without needing a backend.
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.getBeanFactory().registerSingleton("accountRepository", accountRepository);
                context.getBeanFactory().registerSingleton("customerRepository", customerRepository);
                context.getBeanFactory().registerSingleton("datasetUnitOfWork",
                        new DatasetUnitOfWork(transactionManager));
                context.register(AccountUpdateService.class);
                context.refresh();

                AccountUpdateService bean = context.getBean(AccountUpdateService.class);
                Assertions.assertThat(bean).isNotNull();
                // A singleton, as a stateless service must be: two lookups return the same instance.
                Assertions.assertThat(context.getBean(AccountUpdateService.class)).isSameAs(bean);
                // And it received the registered collaborators rather than fresh ones.
                when(accountRepository.readForUpdate(anyString()))
                        .thenReturn(AccountRepository.ReadResult.notFound());
                Assertions.assertThat(bean.writeProcessing(ACCT_ID_CHARS, commarea(),
                        matchedOldDetails(), newDetails(), null, CODEC).outcome())
                        .isEqualTo(WriteOutcome.COULD_NOT_LOCK_ACCT_FOR_UPDATE);
            }
        }
    }

    @Nested
    @DisplayName("Declared geometry - ACCT-UPDATE-RECORD is not CVACT01Y, and that is asserted")
    class Geometry {

        @Test
        @DisplayName("the group identifier sits where CVACT01Y keeps ACCT-ADDR-ZIP - :418-433")
        void groupIdOverlaysStoredZip() {
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_GROUP_ID_OFFSET)
                    .isEqualTo(AccountRecord.ACCT_ADDR_ZIP_OFFSET)
                    .isEqualTo(102);
        }

        @Test
        @DisplayName("the reserved span begins where CVACT01Y keeps ACCT-GROUP-ID, and is 188 wide")
        void fillerOverlaysStoredGroupId() {
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_FILLER_OFFSET)
                    .isEqualTo(AccountRecord.ACCT_GROUP_ID_OFFSET)
                    .isEqualTo(112);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_FILLER_LENGTH).isEqualTo(188);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_FILLER_LENGTH)
                    .isEqualTo(AccountRecord.FILLER_LENGTH + AccountRecord.ACCT_ADDR_ZIP_LENGTH);
        }

        @Test
        @DisplayName("every declared offset matches app/cbl/COACTUPC.cbl:422-433 exactly")
        void offsetsMatchTheSource() {
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_ID_OFFSET).isZero();
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_ACTIVE_STATUS_OFFSET).isEqualTo(11);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_CURR_BAL_OFFSET).isEqualTo(12);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_CREDIT_LIMIT_OFFSET).isEqualTo(24);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_CASH_CREDIT_LIMIT_OFFSET)
                    .isEqualTo(36);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_OPEN_DATE_OFFSET).isEqualTo(48);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_EXPIRAION_DATE_OFFSET).isEqualTo(58);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_REISSUE_DATE_OFFSET).isEqualTo(68);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_CURR_CYC_CREDIT_OFFSET)
                    .isEqualTo(78);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_CURR_CYC_DEBIT_OFFSET).isEqualTo(90);
        }

        @Test
        @DisplayName("the literals and widths are byte-exact")
        void literalsAreByteExact() {
            Assertions.assertThat(AccountUpdateService.ACCT_CICS_FILE_NAME).isEqualTo("ACCTDAT ");
            Assertions.assertThat(AccountUpdateService.CUST_CICS_FILE_NAME).isEqualTo("CUSTDAT ");
            Assertions.assertThat(AccountUpdateService.ACCT_KEY_LENGTH).isEqualTo(11);
            Assertions.assertThat(AccountUpdateService.CUST_KEY_LENGTH).isEqualTo(9);
            Assertions.assertThat(AccountUpdateService.ACCT_UPDATE_RECORD_LENGTH).isEqualTo(300);
            Assertions.assertThat(AccountUpdateService.CUST_UPDATE_RECORD_LENGTH).isEqualTo(500);
            Assertions.assertThat(AccountUpdateService.RETURN_MESSAGE_LENGTH).isEqualTo(75);
            Assertions.assertThat(AccountUpdateService.RETURN_MESSAGE_OFF).hasSize(75).isBlank();
            Assertions.assertThat(AccountUpdateService.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE)
                    .isEqualTo("Could not lock account record for update");
            Assertions.assertThat(AccountUpdateService.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE)
                    .isEqualTo("Could not lock customer record for update");
            Assertions.assertThat(AccountUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE)
                    .isEqualTo("Record changed by some one else. Please review");
            Assertions.assertThat(AccountUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED)
                    .isEqualTo("Update of record failed");
            Assertions.assertThat(AccountUpdateService.MONETARY_INTEGER_DIGITS).isEqualTo(10);
            Assertions.assertThat(AccountUpdateService.MONETARY_IMAGE_LENGTH).isEqualTo(12);
            Assertions.assertThat(AccountUpdateService.SCREEN_MONETARY_LENGTH).isEqualTo(15);
            Assertions.assertThat(AccountUpdateService.SNAPSHOT_DATE_LENGTH).isEqualTo(8);
            Assertions.assertThat(AccountUpdateService.STORED_DATE_LENGTH).isEqualTo(10);
            Assertions.assertThat(AccountUpdateService.DATE_SEPARATOR).isEqualTo("-");
            Assertions.assertThat(AccountUpdateService.NOT_SUPPLIED_MARKER).isEqualTo("*");
            Assertions.assertThat(AccountUpdateService.READ_OPERATION_NAME).isEqualTo("READ");
            Assertions.assertThat(AccountUpdateService.REWRITE_OPERATION_NAME).isEqualTo("REWRITE");
            Assertions.assertThat(AccountUpdateService.NUMVAL_CONFORMS).isZero();
            Assertions.assertThat(AccountUpdateService.LOW_VALUES_IMAGE).hasSize(15)
                    .isEqualTo("\u0000".repeat(15));
        }
    }

    @Nested
    @DisplayName("88 WS-RETURN-MSG-OFF VALUE SPACES - :480")
    class ReturnMessageOff {

        @Test
        @DisplayName("null is the cleared state, because :876 clears it on every pass")
        void nullIsOff() {
            Assertions.assertThat(AccountUpdateService.isReturnMessageOff(null)).isTrue();
        }

        @Test
        @DisplayName("an empty string is space-padded to seventy-five and reads as off")
        void emptyIsOff() {
            Assertions.assertThat(AccountUpdateService.isReturnMessageOff("")).isTrue();
        }

        @Test
        @DisplayName("seventy-five spaces read as off")
        void fullWidthSpacesAreOff() {
            Assertions.assertThat(AccountUpdateService.isReturnMessageOff(" ".repeat(75))).isTrue();
        }

        @Test
        @DisplayName("any message reads as on")
        void aMessageIsOn() {
            Assertions.assertThat(AccountUpdateService.isReturnMessageOff(PENDING_MESSAGE)).isFalse();
        }

        @Test
        @DisplayName("an over-wide message is truncated on the right and still reads as on")
        void overWideIsTruncated() {
            Assertions.assertThat(AccountUpdateService.isReturnMessageOff("x".repeat(200))).isFalse();
        }

        @Test
        @DisplayName("an over-wide run of spaces truncates to seventy-five spaces and reads as off")
        void overWideSpacesAreOff() {
            Assertions.assertThat(AccountUpdateService.isReturnMessageOff(" ".repeat(200))).isTrue();
        }
    }

    @Nested
    @DisplayName("FUNCTION UPPER-CASE and FUNCTION LOWER-CASE - :4139-4140 and :4152-4173")
    class CaseFolding {

        @ParameterizedTest
        @CsvSource({"abc,ABC", "ABC,ABC", "aB1 -,AB1 -", "'',''"})
        @DisplayName("FUNCTION UPPER-CASE folds up and preserves everything else")
        void upperCaseFolds(String input, String expected) {
            Assertions.assertThat(AccountUpdateService.upperCase(input)).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({"ABC,abc", "abc,abc", "Ab1 -,ab1 -", "'',''"})
        @DisplayName("FUNCTION LOWER-CASE folds down and preserves everything else")
        void lowerCaseFolds(String input, String expected) {
            Assertions.assertThat(AccountUpdateService.lowerCase(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("both folds preserve length, so a fixed-width comparison stays aligned")
        void foldsPreserveLength() {
            // German sharp s folds to two characters under String.toUpperCase. A PIC X(10) receiver
            // has ten characters and must still have ten afterwards, so the conversion is discarded.
            String sharp = "stra\u00dfe0000";
            Assertions.assertThat(sharp).hasSize(10);
            Assertions.assertThat(AccountUpdateService.upperCase(sharp)).hasSize(10)
                    .isEqualTo("STRA\u00dfE0000");
            Assertions.assertThat(AccountUpdateService.lowerCase(sharp)).hasSize(10);
        }

        @Test
        @DisplayName("a null operand is refused; every compared item is a fixed-width PIC X")
        void nullRefused() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.upperCase(null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.lowerCase(null));
        }
    }

    @Nested
    @DisplayName("FUNCTION NUMVAL-C and FUNCTION TEST-NUMVAL-C - :1078-1136")
    class NumvalIntrinsics {

        @ParameterizedTest
        @CsvSource({
            "123,123",
            "  123  ,123",
            "1234.56,1234.56",
            "-1234.56,-1234.56",
            "+1234.56,1234.56",
            "$1234.56,1234.56",
            "- $ 1234.56,-1234.56",
            "'1,234.56',1234.56",
            "1234.56-,-1234.56",
            "1234.56+,1234.56",
            "1234.56CR,-1234.56",
            "1234.56DB,-1234.56",
            "0.99,0.99",
            ".99,0.99",
            "12.,12"
        })
        @DisplayName("a conforming argument converts exactly, with every digit preserved")
        void conformingArguments(String image, String expected) {
            Assertions.assertThat(AccountUpdateService.testNumvalC(image))
                    .isEqualTo(AccountUpdateService.NUMVAL_CONFORMS);
            Assertions.assertThat(AccountUpdateService.numvalC(image))
                    .isEqualByComparingTo(new BigDecimal(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "abc", "-", "$", ".", "12ab", "1,,2", "12 34", "1,",
            "1234.56XY", "--12"})
        @DisplayName("a non-conforming argument is rejected and converts to zero")
        void nonConformingArguments(String image) {
            Assertions.assertThat(AccountUpdateService.testNumvalC(image))
                    .isNotEqualTo(AccountUpdateService.NUMVAL_CONFORMS);
            Assertions.assertThat(AccountUpdateService.numvalC(image))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("an argument with no digit at all is reported at its length plus one")
        void noDigitReportsLengthPlusOne() {
            Assertions.assertThat(AccountUpdateService.testNumvalC("   ")).isEqualTo(4);
            Assertions.assertThat(AccountUpdateService.testNumvalC("")).isEqualTo(1);
        }

        @Test
        @DisplayName("an argument with a bad character is reported at that character's position")
        void badCharacterReportsItsPosition() {
            Assertions.assertThat(AccountUpdateService.testNumvalC("12ab")).isEqualTo(3);
        }

        @Test
        @DisplayName("a leading sign suppresses the trailing-sign scan, exactly as the intrinsic does")
        void leadingSignSuppressesTrailing() {
            Assertions.assertThat(AccountUpdateService.testNumvalC("-12-"))
                    .isNotEqualTo(AccountUpdateService.NUMVAL_CONFORMS);
        }

        @Test
        @DisplayName("LOW-VALUES does not conform, which is why 1250-EDIT-SIGNED-9V2 rejects it")
        void lowValuesDoesNotConform() {
            Assertions.assertThat(
                    AccountUpdateService.testNumvalC(AccountUpdateService.LOW_VALUES_IMAGE))
                    .isNotEqualTo(AccountUpdateService.NUMVAL_CONFORMS);
        }

        @Test
        @DisplayName("a null argument is refused")
        void nullRefused() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.numvalC(null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.testNumvalC(null));
        }
    }

    @Nested
    @DisplayName("The five COMPUTE sites - :1079, :1093, :1107, :1121, :1135 (gate G28)")
    class ComputeSites {

        @Test
        @DisplayName(":1079 COMPUTE ACUP-NEW-CREDIT-LIMIT-N = FUNCTION NUMVAL-C(ACRDLIMI)")
        void creditLimit() {
            MonetaryEdit edit = AccountUpdateService.computeCreditLimit("$12,345.67", null, CODEC);
            Assertions.assertThat(edit.computed()).isTrue();
            Assertions.assertThat(edit.value()).isEqualByComparingTo(new BigDecimal("12345.67"));
            Assertions.assertThat(edit.value().scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            Assertions.assertThat(edit.cobolReceiver()).isEqualTo("ACUP-NEW-CREDIT-LIMIT-N");
            Assertions.assertThat(edit.sourceLines()).isEqualTo("1079-1080");
            Assertions.assertThat(edit.numvalArgument()).isEqualTo(NumvalArgument.MAP_FIELD);
            Assertions.assertThat(edit.stagingImage()).isEqualTo("$12,345.67     ");
            Assertions.assertThat(edit.testNumvalC()).hasValue(AccountUpdateService.NUMVAL_CONFORMS);
            Assertions.assertThat(edit.isNotValid()).isFalse();
        }

        @Test
        @DisplayName(":1093 COMPUTE ACUP-NEW-CASH-CREDIT-LIMIT-N = FUNCTION NUMVAL-C(ACSHLIMI)")
        void cashCreditLimit() {
            MonetaryEdit edit = AccountUpdateService.computeCashCreditLimit("1000.00", null, CODEC);
            Assertions.assertThat(edit.computed()).isTrue();
            Assertions.assertThat(edit.value()).isEqualByComparingTo(new BigDecimal("1000.00"));
            Assertions.assertThat(edit.cobolReceiver()).isEqualTo("ACUP-NEW-CASH-CREDIT-LIMIT-N");
            Assertions.assertThat(edit.numvalArgument()).isEqualTo(NumvalArgument.MAP_FIELD);
        }

        @Test
        @DisplayName(":1107 COMPUTE ACUP-NEW-CURR-BAL-N = FUNCTION NUMVAL-C(ACUP-NEW-CURR-BAL-X)")
        void currBalReadsTheStagingCopy() {
            MonetaryEdit edit = AccountUpdateService.computeCurrBal("-99.95", null, CODEC);
            Assertions.assertThat(edit.computed()).isTrue();
            Assertions.assertThat(edit.value()).isEqualByComparingTo(new BigDecimal("-99.95"));
            Assertions.assertThat(edit.cobolReceiver()).isEqualTo("ACUP-NEW-CURR-BAL-N");
            // The one operand asymmetry in the group: three sites convert the map field, two the copy.
            Assertions.assertThat(edit.numvalArgument()).isEqualTo(NumvalArgument.STAGING_COPY);
        }

        @Test
        @DisplayName(":1121 COMPUTE ACUP-NEW-CURR-CYC-CREDIT-N = FUNCTION NUMVAL-C(ACRCYCRI)")
        void currCycCredit() {
            MonetaryEdit edit = AccountUpdateService.computeCurrCycCredit("250.75", null, CODEC);
            Assertions.assertThat(edit.computed()).isTrue();
            Assertions.assertThat(edit.value()).isEqualByComparingTo(new BigDecimal("250.75"));
            Assertions.assertThat(edit.cobolReceiver()).isEqualTo("ACUP-NEW-CURR-CYC-CREDIT-N");
            Assertions.assertThat(edit.numvalArgument()).isEqualTo(NumvalArgument.MAP_FIELD);
        }

        @Test
        @DisplayName(":1135 COMPUTE ACUP-NEW-CURR-CYC-DEBIT-N = "
                + "FUNCTION NUMVAL-C(ACUP-NEW-CURR-CYC-DEBIT-X)")
        void currCycDebitReadsTheStagingCopy() {
            MonetaryEdit edit = AccountUpdateService.computeCurrCycDebit("99.99", null, CODEC);
            Assertions.assertThat(edit.computed()).isTrue();
            Assertions.assertThat(edit.value()).isEqualByComparingTo(new BigDecimal("99.99"));
            Assertions.assertThat(edit.cobolReceiver()).isEqualTo("ACUP-NEW-CURR-CYC-DEBIT-N");
            Assertions.assertThat(edit.numvalArgument()).isEqualTo(NumvalArgument.STAGING_COPY);
        }

        @Test
        @DisplayName("the excess fraction is TRUNCATED, never rounded, because ROUNDED is never used")
        void truncatesRatherThanRounds() {
            Assertions.assertThat(
                    AccountUpdateService.computeCreditLimit("1.999", null, CODEC).value())
                    .isEqualByComparingTo(new BigDecimal("1.99"));
            Assertions.assertThat(
                    AccountUpdateService.computeCurrBal("-1.999", null, CODEC).value())
                    .isEqualByComparingTo(new BigDecimal("-1.99"));
        }

        @Test
        @DisplayName("an overflow wraps to ten integer digits, as a COBOL store without ON SIZE ERROR")
        void overflowWraps() {
            MonetaryEdit edit = AccountUpdateService.computeCreditLimit("123456789012.34", null, CODEC);
            Assertions.assertThat(edit.computed()).isTrue();
            // Twelve integer digits into a ten-digit receiver: the two high-order digits are lost.
            Assertions.assertThat(edit.value()).isEqualByComparingTo(new BigDecimal("3456789012.34"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"*", "*              ", "               ", ""})
        @DisplayName("the '*' / SPACES arm moves LOW-VALUES and evaluates no conformance test - :1073")
        void notSuppliedArm(String screenField) {
            MonetaryEdit edit = AccountUpdateService.computeCreditLimit(screenField,
                    new BigDecimal("7.50"), CODEC);
            Assertions.assertThat(edit.notSupplied()).isTrue();
            Assertions.assertThat(edit.computed()).isFalse();
            Assertions.assertThat(edit.isNotValid()).isFalse();
            Assertions.assertThat(edit.testNumvalC()).isEmpty();
            Assertions.assertThat(edit.stagingImage()).isEqualTo(AccountUpdateService.LOW_VALUES_IMAGE);
            // The -N span is not assigned on this arm, so it keeps its prior content.
            Assertions.assertThat(edit.value()).isEqualByComparingTo(new BigDecimal("7.50"));
        }

        @Test
        @DisplayName("null screen field is the not-supplied state, which is what an absent field means")
        void nullIsNotSupplied() {
            Assertions.assertThat(AccountUpdateService.computeCurrBal(null, null, CODEC).notSupplied())
                    .isTrue();
        }

        @Test
        @DisplayName("the ELSE CONTINUE arm leaves the receiving span holding its prior content - :1082")
        void nonConformingLeavesThePriorValue() {
            MonetaryEdit edit = AccountUpdateService.computeCashCreditLimit("not a number",
                    new BigDecimal("3.25"), CODEC);
            Assertions.assertThat(edit.notSupplied()).isFalse();
            Assertions.assertThat(edit.computed()).isFalse();
            Assertions.assertThat(edit.isNotValid()).isTrue();
            Assertions.assertThat(edit.testNumvalC()).isPresent();
            Assertions.assertThat(edit.testNumvalC().orElseThrow())
                    .isNotEqualTo(AccountUpdateService.NUMVAL_CONFORMS);
            Assertions.assertThat(edit.value()).isEqualByComparingTo(new BigDecimal("3.25"));
            Assertions.assertThat(edit.stagingImage()).isEqualTo("not a number   ");
        }

        @Test
        @DisplayName("a higher-scale prior value is restated at the receiver's scale")
        void priorValueIsRestatedAtScaleTwo() {
            MonetaryEdit edit = AccountUpdateService.computeCurrCycDebit("*",
                    new BigDecimal("1.23456"), CODEC);
            Assertions.assertThat(edit.value()).isEqualTo(new BigDecimal("1.23"));
        }

        @Test
        @DisplayName("an over-wide screen field is truncated on the right to fifteen characters")
        void overWideScreenFieldIsTruncated() {
            MonetaryEdit edit = AccountUpdateService.computeCurrCycCredit("1234567890.1234567", null,
                    CODEC);
            Assertions.assertThat(edit.stagingImage()).hasSize(15).isEqualTo("1234567890.1234");
        }

        @Test
        @DisplayName("a codec is required at every one of the five sites")
        void codecRequired() {
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> AccountUpdateService.computeCreditLimit("1", null, null));
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> AccountUpdateService.computeCashCreditLimit("1", null, null));
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> AccountUpdateService.computeCurrBal("1", null, null));
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> AccountUpdateService.computeCurrCycCredit("1", null, null));
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> AccountUpdateService.computeCurrCycDebit("1", null, null));
        }
    }

    @Nested
    @DisplayName("The record identification fields - :3892/:3897 and :3920/:3926")
    class RecordIdentificationFields {

        @Test
        @DisplayName("the account key is CHARACTERS, right-space-padded to eleven")
        void accountKeyIsCharacters() {
            Assertions.assertThat(AccountUpdateService.acctRidImage(ACCT_ID_CHARS, CODEC))
                    .isEqualTo("12345678901");
            Assertions.assertThat(AccountUpdateService.acctRidImage("123", CODEC))
                    .isEqualTo("123        ");
            Assertions.assertThat(AccountUpdateService.acctRidImage(null, CODEC))
                    .isEqualTo("           ");
            Assertions.assertThat(AccountUpdateService.acctRidImage("123456789012345", CODEC))
                    .isEqualTo("12345678901");
        }

        @Test
        @DisplayName("the customer key is DIGITS, zero-filled on the LEFT to nine")
        void customerKeyIsZeroFilledDigits() {
            Assertions.assertThat(AccountUpdateService.custRidImage(CUST_ID, CODEC))
                    .isEqualTo("123456789");
            Assertions.assertThat(AccountUpdateService.custRidImage(42, CODEC))
                    .isEqualTo("000000042");
            Assertions.assertThat(AccountUpdateService.custRidImage(0, CODEC))
                    .isEqualTo("000000000");
        }

        @Test
        @DisplayName("a codec is required for both")
        void codecRequired() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.acctRidImage("1", null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.custRidImage(1, null));
        }
    }

    @Nested
    @DisplayName("STRING ... DELIMITED BY SIZE - :3976-3999, :4035-4049 and :4054-4059")
    class StringCompositions {

        @Test
        @DisplayName("a date fills all ten characters of its PIC X(10) receiver")
        void dateFillsTheReceiver() {
            Assertions.assertThat(
                    AccountUpdateService.composeStoredDate("2020", "01", "15", CODEC))
                    .hasSize(10).isEqualTo("2020-01-15");
        }

        @Test
        @DisplayName("each operand contributes its full declared width, short values space-padded")
        void operandsContributeTheirDeclaredWidth() {
            Assertions.assertThat(AccountUpdateService.composeStoredDate("20", "1", "5", CODEC))
                    .hasSize(10).isEqualTo("20  -1 -5 ");
            Assertions.assertThat(AccountUpdateService.composeStoredDate(null, null, null, CODEC))
                    .hasSize(10).isEqualTo("    -  -  ");
        }

        @Test
        @DisplayName("a telephone number is THIRTEEN characters; the last two come from the INITIALIZE")
        void phoneIsThirteenCharacters() {
            Assertions.assertThat(
                    AccountUpdateService.composePhoneNumber("217", "555", "1234", CODEC))
                    .hasSize(13).isEqualTo("(217)555-1234");
            Assertions.assertThat(
                    AccountUpdateService.composePhoneNumber(null, null, null, CODEC))
                    .hasSize(13).isEqualTo("(   )   -    ");
        }

        @Test
        @DisplayName("the composed telephone number round-trips through the REDEFINES sub-views")
        void phoneRoundTripsThroughTheOverlay() {
            CustomerData data = matchedCustomerData();
            Assertions.assertThat(data.phoneNum1()).isEqualTo("(217)555-1234  ");
            Assertions.assertThat(data.phoneNum1A()).isEqualTo("217");
            Assertions.assertThat(data.phoneNum1B()).isEqualTo("555");
            Assertions.assertThat(data.phoneNum1C()).isEqualTo("1234");
            Assertions.assertThat(data.phoneNum2A()).isEqualTo("217");
            Assertions.assertThat(data.phoneNum2B()).isEqualTo("555");
            Assertions.assertThat(data.phoneNum2C()).isEqualTo("9876");
            Assertions.assertThat(AccountUpdateService.composePhoneNumber(data.phoneNum1A(),
                    data.phoneNum1B(), data.phoneNum1C(), CODEC) + "  ")
                    .isEqualTo(data.phoneNum1());
        }

        @Test
        @DisplayName("a codec is required for both compositions")
        void codecRequired() {
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> AccountUpdateService.composeStoredDate("2020", "01", "15", null));
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> AccountUpdateService.composePhoneNumber("217", "555", "1234", null));
        }
    }

    @Nested
    @DisplayName("Staging - :3956-4001 and :4006-4061, including the ACCT-UPDATE-RECORD defect")
    class Staging {

        @Test
        @DisplayName("the account image is exactly 300 characters, FILLER included (gates G19, G21)")
        void accountImageIsFullWidth() {
            String image = AccountUpdateService.stageAccountUpdateImage(newDetails(), CODEC);
            Assertions.assertThat(image).hasSize(300);
        }

        @Test
        @DisplayName("every account span carries the ACUP-NEW value at its declared offset")
        void accountSpansCarryTheNewValues() {
            String image = AccountUpdateService.stageAccountUpdateImage(newDetails(), CODEC);
            Assertions.assertThat(image.substring(0, 11)).isEqualTo("99999999999");
            Assertions.assertThat(image.substring(11, 12)).isEqualTo("N");
            // -42.07 as PIC S9(10)V99: eleven leading digits then the negative overpunch of 7, 'P'.
            Assertions.assertThat(image.substring(12, 24)).isEqualTo("00000000420P");
            Assertions.assertThat(image.substring(24, 36)).isEqualTo("00000075000{");
            Assertions.assertThat(image.substring(36, 48)).isEqualTo("00000015000{");
            Assertions.assertThat(image.substring(48, 58)).isEqualTo("2021-02-28");
            Assertions.assertThat(image.substring(58, 68)).isEqualTo("2026-11-30");
            Assertions.assertThat(image.substring(68, 78)).isEqualTo("2023-05-01");
            // 11.11 -> digits 000000001111, positive overpunch of the trailing 1 is 'A'.
            Assertions.assertThat(image.substring(78, 90)).isEqualTo("00000000111A");
            // 22.22 -> digits 000000002222, positive overpunch of the trailing 2 is 'B'.
            Assertions.assertThat(image.substring(90, 102)).isEqualTo("00000000222B");
        }

        @Test
        @DisplayName("THE DEFECT: the group identifier is written over the stored ACCT-ADDR-ZIP")
        void groupIdentifierIsWrittenOverTheStoredZip() {
            String image = AccountUpdateService.stageAccountUpdateImage(newDetails(), CODEC);
            Assertions.assertThat(image.substring(102, 112)).isEqualTo("GRPNEW    ");
            // Read back through the account master's own layout, which is what the file holds.
            AccountRecord asStored = AccountRecord.decode(image, StandardCharsets.US_ASCII);
            Assertions.assertThat(asStored.getAcctAddrZip()).isEqualTo("GRPNEW    ");
            Assertions.assertThat(asStored.getAcctGroupId()).isEqualTo("          ");
        }

        @Test
        @DisplayName("THE DEFECT: the real ACCT-GROUP-ID span and the tail are blanked")
        void groupIdSpanAndTailAreBlanked() {
            String image = AccountUpdateService.stageAccountUpdateImage(newDetails(), CODEC);
            Assertions.assertThat(image.substring(112, 300)).hasSize(188).isBlank();
        }

        @Test
        @DisplayName("the customer image is exactly 500 characters and matches CVCUS01Y span for span")
        void customerImageIsFullWidth() {
            String image = AccountUpdateService.stageCustomerUpdateImage(newDetails(), CODEC);
            Assertions.assertThat(image).hasSize(500);
            Assertions.assertThat(image.substring(0, 9)).isEqualTo("987654321");
            Assertions.assertThat(image.substring(9, 34)).isEqualTo("JANE" + " ".repeat(21));
            Assertions.assertThat(image.substring(34, 59)).isEqualTo("R" + " ".repeat(24));
            Assertions.assertThat(image.substring(59, 84)).isEqualTo("ROE" + " ".repeat(22));
            Assertions.assertThat(image.substring(234, 236)).isEqualTo("IA");
            Assertions.assertThat(image.substring(236, 239)).isEqualTo("USA");
            Assertions.assertThat(image.substring(239, 249)).isEqualTo("50309-0002");
            Assertions.assertThat(image.substring(249, 264)).isEqualTo("(515)555-2468  ");
            Assertions.assertThat(image.substring(264, 279)).isEqualTo("(515)555-1357  ");
            Assertions.assertThat(image.substring(279, 288)).isEqualTo("987654321");
            Assertions.assertThat(image.substring(308, 318)).isEqualTo("1975-12-25");
            Assertions.assertThat(image.substring(318, 328)).isEqualTo("EFT0000002");
            Assertions.assertThat(image.substring(328, 329)).isEqualTo("N");
            Assertions.assertThat(image.substring(329, 332)).isEqualTo("811");
            Assertions.assertThat(image.substring(332, 500)).hasSize(168).isBlank();
        }

        @Test
        @DisplayName("the staged customer record is a CustomerRecord in its own right")
        void stagedCustomerRecordIsUsable() {
            CustomerRecord staged = AccountUpdateService.stageCustomerUpdateRecord(newDetails(), CODEC);
            Assertions.assertThat(staged.getCustId()).isEqualTo(987654321);
            Assertions.assertThat(staged.getCustDobYyyyMmDd()).isEqualTo("1975-12-25");
            Assertions.assertThat(staged.getCustFicoCreditScore()).isEqualTo(811);
        }

        @Test
        @DisplayName("staging refuses the OLD group, because the two shapes are identical")
        void stagingRefusesTheOldGroup() {
            AccountUpdateDetails old = matchedOldDetails();
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountUpdateService.stageAccountUpdateImage(old, CODEC))
                    .withMessageContaining("ACUP-NEW-DETAILS");
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> AccountUpdateService.stageCustomerUpdateRecord(old, CODEC))
                    .withMessageContaining("ACUP-NEW-DETAILS");
        }

        @Test
        @DisplayName("staging refuses a null group or a null codec")
        void stagingRefusesNulls() {
            AccountUpdateDetails details = newDetails();
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.stageAccountUpdateImage(null, CODEC));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.stageAccountUpdateImage(details, null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.stageCustomerUpdateRecord(null, CODEC));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateService.stageCustomerUpdateRecord(details, null));
        }
    }

    // =================================================================================================
    // Mutators. Each changes exactly one compared item on the stored side, so a check reports exactly
    // one difference. Driving them from the enum means a new ComparedItem cannot be added without this
    // switch failing to compile, which is the point.
    // =================================================================================================

    /**
     * Changes exactly the one account item {@code item} names.
     *
     * @param account the stored record to mutate
     * @param item    which item to make differ; must belong to {@link Block#ACCOUNT_MASTER}
     */
    private static void mutateAccount(AccountRecord account, ComparedItem item) {
        switch (item) {
            case ACCT_ACTIVE_STATUS -> account.setAcctActiveStatus("N");
            case ACCT_CURR_BAL -> account.setAcctCurrBal(new BigDecimal("1234.57"));
            case ACCT_CREDIT_LIMIT -> account.setAcctCreditLimit(new BigDecimal("5000.01"));
            case ACCT_CASH_CREDIT_LIMIT -> account.setAcctCashCreditLimit(new BigDecimal("1000.01"));
            case ACCT_CURR_CYC_CREDIT -> account.setAcctCurrCycCredit(new BigDecimal("250.76"));
            case ACCT_CURR_CYC_DEBIT -> account.setAcctCurrCycDebit(new BigDecimal("100.00"));
            case ACCT_OPEN_DATE_YEAR -> account.setAcctOpenDate("2019-01-15");
            case ACCT_OPEN_DATE_MONTH -> account.setAcctOpenDate("2020-02-15");
            case ACCT_OPEN_DATE_DAY -> account.setAcctOpenDate("2020-01-16");
            case ACCT_EXPIRAION_DATE_YEAR -> account.setAcctExpiraionDate("2024-12-31");
            case ACCT_EXPIRAION_DATE_MONTH -> account.setAcctExpiraionDate("2025-11-31");
            case ACCT_EXPIRAION_DATE_DAY -> account.setAcctExpiraionDate("2025-12-30");
            case ACCT_REISSUE_DATE_YEAR -> account.setAcctReissueDate("2021-06-30");
            case ACCT_REISSUE_DATE_MONTH -> account.setAcctReissueDate("2022-07-30");
            case ACCT_REISSUE_DATE_DAY -> account.setAcctReissueDate("2022-06-29");
            case ACCT_GROUP_ID -> account.setAcctGroupId("GROUP02");
            default -> throw new IllegalArgumentException(item + " is not an account-master item");
        }
    }

    /**
     * Changes exactly the one customer item {@code item} names.
     *
     * @param customer the stored record to mutate
     * @param item     which item to make differ; must belong to {@link Block#CUSTOMER}
     */
    private static void mutateCustomer(CustomerRecord customer, ComparedItem item) {
        switch (item) {
            case CUST_FIRST_NAME -> customer.setCustFirstName("JANE");
            case CUST_MIDDLE_NAME -> customer.setCustMiddleName("Z");
            case CUST_LAST_NAME -> customer.setCustLastName("ROE");
            case CUST_ADDR_LINE_1 -> customer.setCustAddrLine1("9 ELM ST");
            case CUST_ADDR_LINE_2 -> customer.setCustAddrLine2("APT 3");
            case CUST_ADDR_LINE_3 -> customer.setCustAddrLine3("SHELBYVILLE");
            case CUST_ADDR_STATE_CD -> customer.setCustAddrStateCd("IA");
            case CUST_ADDR_COUNTRY_CD -> customer.setCustAddrCountryCd("CAN");
            case CUST_ADDR_ZIP -> customer.setCustAddrZip("62704-0002");
            case CUST_PHONE_NUM_1 -> customer.setCustPhoneNum1("(217)555-0000");
            case CUST_PHONE_NUM_2 -> customer.setCustPhoneNum2("(217)555-1111");
            case CUST_SSN -> customer.setCustSsn(987654321);
            case CUST_GOVT_ISSUED_ID -> customer.setCustGovtIssuedId("DL0000000000");
            case CUST_DOB_YEAR -> customer.setCustDobYyyyMmDd("1981-07-04");
            case CUST_DOB_MONTH -> customer.setCustDobYyyyMmDd("1980-08-04");
            case CUST_DOB_DAY -> customer.setCustDobYyyyMmDd("1980-07-05");
            case CUST_EFT_ACCOUNT_ID -> customer.setCustEftAccountId("EFT0000002");
            case CUST_PRI_CARD_HOLDER_IND -> customer.setCustPriCardHolderInd("N");
            case CUST_FICO_CREDIT_SCORE -> customer.setCustFicoCreditScore(800);
            default -> throw new IllegalArgumentException(item + " is not a customer item");
        }
    }

    @Nested
    @DisplayName("9700-CHECK-CHANGE-IN-REC - :4109-4194, the concurrency control (gates G30, G43)")
    class CheckChangeInRec {

        @Test
        @DisplayName("all thirty-five items matching reaches both CONTINUEs and reports no change")
        void allEqualReportsNoChange() {
            ChangeCheck check = service.checkChangeInRec(storedAccount(), storedCustomer(),
                    matchedOldDetails(), CODEC);
            Assertions.assertThat(check.dataWasChanged()).isFalse();
            Assertions.assertThat(check.failingBlock()).isEmpty();
            Assertions.assertThat(check.differingItems()).isEmpty();
            Assertions.assertThat(check.describeDifferences())
                    .isEqualTo("Nothing differs: all 35 compared items matched.");
        }

        @ParameterizedTest
        @EnumSource(ComparedItem.class)
        @DisplayName("changing exactly one item makes exactly that item differ")
        void oneItemAtATime(ComparedItem item) {
            AccountRecord account = storedAccount();
            CustomerRecord customer = storedCustomer();
            if (item.block() == Block.ACCOUNT_MASTER) {
                mutateAccount(account, item);
            } else {
                mutateCustomer(customer, item);
            }

            ChangeCheck check = service.checkChangeInRec(account, customer, matchedOldDetails(), CODEC);

            Assertions.assertThat(check.dataWasChanged()).isTrue();
            Assertions.assertThat(check.failingBlock()).contains(item.block());
            Assertions.assertThat(check.differingItems()).containsExactly(item);
            Assertions.assertThat(check.describeDifferences())
                    .contains(item.cobolName())
                    .contains(item.block().cobolCaption())
                    .contains(item.block().sourceLines());
        }

        @Test
        @DisplayName("ACCT-ADDR-ZIP is deliberately NOT compared, so changing it is not a change")
        void storedZipIsNotCompared() {
            AccountRecord account = storedAccount();
            account.setAcctAddrZip("00000-0000");
            ChangeCheck check = service.checkChangeInRec(account, storedCustomer(),
                    matchedOldDetails(), CODEC);
            Assertions.assertThat(check.dataWasChanged()).isFalse();
        }

        @Test
        @DisplayName("neither identifier is compared, so changing them is not a change")
        void identifiersAreNotCompared() {
            AccountRecord account = storedAccount();
            account.setAcctId(99999999999L);
            CustomerRecord customer = storedCustomer();
            customer.setCustId(999999999);
            Assertions.assertThat(service.checkChangeInRec(account, customer, matchedOldDetails(),
                    CODEC).dataWasChanged()).isFalse();
        }

        @Test
        @DisplayName("the account block short-circuits: a change in BOTH reports account items only")
        void accountBlockShortCircuits() {
            AccountRecord account = storedAccount();
            account.setAcctActiveStatus("N");
            CustomerRecord customer = storedCustomer();
            customer.setCustFirstName("JANE");

            ChangeCheck check = service.checkChangeInRec(account, customer, matchedOldDetails(), CODEC);

            Assertions.assertThat(check.failingBlock()).contains(Block.ACCOUNT_MASTER);
            Assertions.assertThat(check.differingItems())
                    .containsExactly(ComparedItem.ACCT_ACTIVE_STATUS)
                    .doesNotContain(ComparedItem.CUST_FIRST_NAME);
        }

        @Test
        @DisplayName("several items in one block are all reported, comma-separated")
        void severalItemsInOneBlock() {
            AccountRecord account = storedAccount();
            account.setAcctActiveStatus("N");
            account.setAcctCurrBal(new BigDecimal("0.00"));
            account.setAcctGroupId("OTHER");

            ChangeCheck check = service.checkChangeInRec(account, storedCustomer(),
                    matchedOldDetails(), CODEC);

            Assertions.assertThat(check.differingItems()).containsExactly(
                    ComparedItem.ACCT_ACTIVE_STATUS, ComparedItem.ACCT_CURR_BAL,
                    ComparedItem.ACCT_GROUP_ID);
            Assertions.assertThat(check.describeDifferences()).contains(", ");
        }

        @Test
        @DisplayName("ACCT-GROUP-ID is folded to LOWER case, so a case-only difference is NO change")
        void groupIdIsFoldedDown() {
            AccountRecord account = storedAccount();
            account.setAcctGroupId("group01");
            Assertions.assertThat(service.checkChangeInRec(account, storedCustomer(),
                    matchedOldDetails(), CODEC).dataWasChanged()).isFalse();
        }

        @Test
        @DisplayName("the eight folded customer items ignore case entirely")
        void foldedCustomerItemsIgnoreCase() {
            CustomerRecord customer = storedCustomer();
            customer.setCustFirstName("john");
            customer.setCustMiddleName("q");
            customer.setCustLastName("public");
            customer.setCustAddrLine1("1 main st");
            customer.setCustAddrLine2("apt 2");
            customer.setCustAddrLine3("springfield");
            customer.setCustAddrStateCd("il");
            customer.setCustAddrCountryCd("usa");
            customer.setCustGovtIssuedId("dl1234567890");
            Assertions.assertThat(service.checkChangeInRec(storedAccount(), customer,
                    matchedOldDetails(), CODEC).dataWasChanged()).isFalse();
        }

        @Test
        @DisplayName("CUST-EFT-ACCOUNT-ID is NOT folded, so a case-only difference IS a change")
        void unfoldedItemsAreCaseSensitive() {
            CustomerRecord customer = storedCustomer();
            customer.setCustEftAccountId("eft0000001");
            ChangeCheck check = service.checkChangeInRec(storedAccount(), customer,
                    matchedOldDetails(), CODEC);
            Assertions.assertThat(check.dataWasChanged()).isTrue();
            Assertions.assertThat(check.differingItems())
                    .containsExactly(ComparedItem.CUST_EFT_ACCOUNT_ID);
        }

        @Test
        @DisplayName("CUST-PRI-CARD-HOLDER-IND is NOT folded either")
        void primaryHolderIndicatorIsCaseSensitive() {
            CustomerRecord customer = storedCustomer();
            customer.setCustPriCardHolderInd("y");
            Assertions.assertThat(service.checkChangeInRec(storedAccount(), customer,
                    matchedOldDetails(), CODEC).differingItems())
                    .containsExactly(ComparedItem.CUST_PRI_CARD_HOLDER_IND);
        }

        @Test
        @DisplayName("THE ASYMMETRIC DOB OFFSETS: ten-character YYYY-MM-DD equals eight-character "
                + "YYYYMMDD - :4174-4179")
        void asymmetricDateOfBirthOffsets() {
            // The record holds separators; the snapshot does not. The snapshot's three declared parts
            // ARE its (1:4), (5:2) and (7:2) slices, so the comparison matches.
            CustomerRecord customer = storedCustomer();
            Assertions.assertThat(customer.getCustDobYyyyMmDd()).isEqualTo("1980-07-04").hasSize(10);
            CustomerData snapshot = matchedCustomerData();
            Assertions.assertThat(snapshot.custDobYyyyMmDd()).isEqualTo("19800704").hasSize(8);

            Assertions.assertThat(service.checkChangeInRec(storedAccount(), customer,
                    new AccountUpdateDetails(DetailGroup.OLD, matchedAccountData(), snapshot), CODEC)
                    .dataWasChanged()).isFalse();
        }

        @Test
        @DisplayName("the two date separators are never compared, so a different one is NO change")
        void dateSeparatorsAreNeverCompared() {
            AccountRecord account = storedAccount();
            account.setAcctOpenDate("2020/01/15");
            account.setAcctExpiraionDate("2025.12.31");
            account.setAcctReissueDate("2022 06 30");
            CustomerRecord customer = storedCustomer();
            customer.setCustDobYyyyMmDd("1980/07/04");
            Assertions.assertThat(service.checkChangeInRec(account, customer, matchedOldDetails(),
                    CODEC).dataWasChanged()).isFalse();
        }

        @Test
        @DisplayName("a monetary scale difference is not a change, because compareTo is used")
        void scaleDifferenceIsNotAChange() {
            AccountData snapshot = new AccountData(12345678901L, "Y",
                    new BigDecimal("1234.5600"), new BigDecimal("5000"), new BigDecimal("1000.0"),
                    "2020", "01", "15", "2025", "12", "31", "2022", "06", "30",
                    new BigDecimal("250.750"), new BigDecimal("99.99"), "GROUP01");
            Assertions.assertThat(service.checkChangeInRec(storedAccount(), storedCustomer(),
                    new AccountUpdateDetails(DetailGroup.OLD, snapshot, matchedCustomerData()), CODEC)
                    .dataWasChanged()).isFalse();
        }

        @Test
        @DisplayName("the check refuses the NEW group, a null record or a null codec")
        void checkRefusesBadArguments() {
            AccountRecord account = storedAccount();
            CustomerRecord customer = storedCustomer();
            AccountUpdateDetails old = matchedOldDetails();
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> service.checkChangeInRec(null, customer, old, CODEC));
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> service.checkChangeInRec(account, null, old, CODEC));
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> service.checkChangeInRec(account, customer, old, null));
            Assertions.assertThatNullPointerException().isThrownBy(
                    () -> service.checkChangeInRec(account, customer, null, CODEC));
            Assertions.assertThatIllegalArgumentException().isThrownBy(
                    () -> service.checkChangeInRec(account, customer, newDetails(), CODEC))
                    .withMessageContaining("ACUP-OLD-DETAILS");
        }
    }

    @Nested
    @DisplayName("9600-WRITE-PROCESSING - :3889-4106, all five arms in order")
    class WriteProcessing {

        @Test
        @DisplayName("the account lock fails: INPUT-ERROR, the message is set, CUSTDAT is never read")
        void accountLockFailure() {
            when(accountRepository.readForUpdate(anyString()))
                    .thenReturn(AccountRepository.ReadResult.notFound());

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            Assertions.assertThat(result.outcome())
                    .isEqualTo(WriteOutcome.COULD_NOT_LOCK_ACCT_FOR_UPDATE);
            Assertions.assertThat(result.inputError()).isTrue();
            Assertions.assertThat(result.syncpointRollbackRequested()).isFalse();
            Assertions.assertThat(result.returnMessage()).hasSize(75)
                    .startsWith(AccountUpdateService.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE);
            Assertions.assertThat(result.fileStatus()).isEqualTo(FileStatus.NOT_FOUND);
            Assertions.assertThat(result.failedOperation())
                    .contains(AccountUpdateService.READ_OPERATION_NAME);
            Assertions.assertThat(result.failedFileName())
                    .contains(AccountUpdateService.ACCT_CICS_FILE_NAME);
            Assertions.assertThat(result.changeCheck()).isEmpty();
            Assertions.assertThat(result.acctUpdateRecordImage()).isEmpty();
            Assertions.assertThat(result.custUpdateRecordImage()).isEmpty();
            Assertions.assertThat(result.isRewritten()).isFalse();
            Assertions.assertThat(result.changeActionCode()).isEqualTo("L");
            verifyNoInteractions(customerRepository);
            verify(accountRepository, never()).rewrite(any(AccountRecord.class));
        }

        @Test
        @DisplayName("a pending message is NOT overwritten by the account lock failure - :3911")
        void accountLockFailureRespectsThePendingMessage() {
            when(accountRepository.readForUpdate(anyString()))
                    .thenReturn(AccountRepository.ReadResult.notFound());

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), PENDING_MESSAGE, CODEC);

            Assertions.assertThat(result.returnMessage()).startsWith(PENDING_MESSAGE);
            Assertions.assertThat(result.returnMessage())
                    .doesNotContain(AccountUpdateService.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE);
            Assertions.assertThat(result.inputError()).isTrue();
        }

        @Test
        @DisplayName("the customer lock fails: INPUT-ERROR, its own message, and nothing is written")
        void customerLockFailure() {
            when(accountRepository.readForUpdate(anyString()))
                    .thenReturn(AccountRepository.ReadResult.found(storedAccount()));
            when(customerRepository.readForUpdate(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.notFound());

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            Assertions.assertThat(result.outcome())
                    .isEqualTo(WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE);
            Assertions.assertThat(result.inputError()).isTrue();
            Assertions.assertThat(result.returnMessage())
                    .startsWith(AccountUpdateService.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE);
            Assertions.assertThat(result.failedFileName())
                    .contains(AccountUpdateService.CUST_CICS_FILE_NAME);
            Assertions.assertThat(result.changeCheck()).isEmpty();
            Assertions.assertThat(result.isRewritten()).isFalse();
            // THE LEGACY DEFECT: the caller's EVALUATE has no arm for this literal, so the operator is
            // shown the success action code. Reproduced, not repaired.
            Assertions.assertThat(result.changeActionCode()).isEqualTo("C");
            Assertions.assertThat(result.outcome().firstMatchWinsPosition()).isEqualTo(4);
            verify(accountRepository, never()).rewrite(any(AccountRecord.class));
            verify(customerRepository, never()).rewrite(any(CustomerRecord.class));
        }

        @Test
        @DisplayName("a pending message is NOT overwritten by the customer lock failure - :3939")
        void customerLockFailureRespectsThePendingMessage() {
            when(accountRepository.readForUpdate(anyString()))
                    .thenReturn(AccountRepository.ReadResult.found(storedAccount()));
            when(customerRepository.readForUpdate(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.notFound());

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), PENDING_MESSAGE, CODEC);

            Assertions.assertThat(result.returnMessage()).startsWith(PENDING_MESSAGE);
        }

        @Test
        @DisplayName("a changed record refuses the update and writes nothing - :3950-3952")
        void dataWasChangedBeforeUpdate() {
            AccountRecord changed = storedAccount();
            changed.setAcctCurrBal(new BigDecimal("9999.99"));
            when(accountRepository.readForUpdate(anyString()))
                    .thenReturn(AccountRepository.ReadResult.found(changed));
            when(customerRepository.readForUpdate(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.found(storedCustomer()));

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            Assertions.assertThat(result.outcome())
                    .isEqualTo(WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE);
            Assertions.assertThat(result.isDataWasChangedBeforeUpdate()).isTrue();
            Assertions.assertThat(result.inputError()).isFalse();
            Assertions.assertThat(result.returnMessage())
                    .startsWith(AccountUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
            Assertions.assertThat(result.fileStatus()).isEqualTo(FileStatus.OK);
            Assertions.assertThat(result.cicsResp()).isEmpty();
            Assertions.assertThat(result.failedOperation()).isEmpty();
            Assertions.assertThat(result.acctUpdateRecordImage()).isEmpty();
            Assertions.assertThat(result.custUpdateRecordImage()).isEmpty();
            Assertions.assertThat(result.changeCheck()).isPresent();
            Assertions.assertThat(result.changeCheck().orElseThrow().differingItems())
                    .containsExactly(ComparedItem.ACCT_CURR_BAL);
            Assertions.assertThat(result.changeActionCode()).isEqualTo("S");
            verify(accountRepository, never()).rewrite(any(AccountRecord.class));
            verify(customerRepository, never()).rewrite(any(CustomerRecord.class));
        }

        @Test
        @DisplayName("the account rewrite fails: no rollback, and CUSTDAT is never rewritten - :4076")
        void accountRewriteFailure() {
            arrangeBothLocks();
            when(accountRepository.rewrite(any(AccountRecord.class)))
                    .thenReturn(AccountRepository.WriteResult.notFound());

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            Assertions.assertThat(result.outcome()).isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
            // No SET INPUT-ERROR on this arm, unlike the two lock arms.
            Assertions.assertThat(result.inputError()).isFalse();
            Assertions.assertThat(result.syncpointRollbackRequested()).isFalse();
            Assertions.assertThat(result.returnMessage())
                    .startsWith(AccountUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED);
            Assertions.assertThat(result.failedOperation())
                    .contains(AccountUpdateService.REWRITE_OPERATION_NAME);
            Assertions.assertThat(result.failedFileName())
                    .contains(AccountUpdateService.ACCT_CICS_FILE_NAME);
            // Both records were staged before either rewrite was issued.
            Assertions.assertThat(result.acctUpdateRecordImage()).isPresent();
            Assertions.assertThat(result.custUpdateRecordImage()).isPresent();
            Assertions.assertThat(result.changeActionCode()).isEqualTo("F");
            verify(customerRepository, never()).rewrite(any(CustomerRecord.class));
        }

        @Test
        @DisplayName("the account rewrite arm overwrites a pending message, having no guard - :4079")
        void accountRewriteFailureOverwritesThePendingMessage() {
            arrangeBothLocks();
            when(accountRepository.rewrite(any(AccountRecord.class)))
                    .thenReturn(AccountRepository.WriteResult.notFound());

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), PENDING_MESSAGE, CODEC);

            Assertions.assertThat(result.returnMessage())
                    .startsWith(AccountUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED);
        }

        @Test
        @DisplayName("the customer rewrite fails: SYNCPOINT ROLLBACK is requested - :4099-4101")
        void customerRewriteFailureRequestsRollback() {
            arrangeBothLocks();
            when(accountRepository.rewrite(any(AccountRecord.class)))
                    .thenReturn(AccountRepository.WriteResult.written());
            when(customerRepository.rewrite(any(CustomerRecord.class)))
                    .thenReturn(CustomerRepository.WriteResult.notFound());

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            Assertions.assertThat(result.outcome()).isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
            Assertions.assertThat(result.syncpointRollbackRequested()).isTrue();
            Assertions.assertThat(result.failedFileName())
                    .contains(AccountUpdateService.CUST_CICS_FILE_NAME);
            Assertions.assertThat(result.isRewritten()).isFalse();
        }

        @Test
        @DisplayName("the requested SYNCPOINT ROLLBACK is performed by the unit of work, not merely "
                + "reported")
        void theRequestedRollbackIsPerformed() {
            // The finding: the result carried a rollback request and no boundary existed to honour it, so
            // the account rewrite would have stood while the screen said the update failed - a state the
            // CICS original cannot produce and has no code to recover from.
            arrangeBothLocks();
            when(accountRepository.rewrite(any(AccountRecord.class)))
                    .thenReturn(AccountRepository.WriteResult.written());
            when(customerRepository.rewrite(any(CustomerRecord.class)))
                    .thenReturn(CustomerRepository.WriteResult.notFound());

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            Assertions.assertThat(transactionManager.rollbacks).isOne();
            Assertions.assertThat(transactionManager.commits).isZero();
            // And the task continues past the rollback, exactly as EXEC CICS SYNCPOINT ROLLBACK does: the
            // paragraph still reaches its exit and the caller still gets the outcome to report.
            Assertions.assertThat(result.outcome()).isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
            Assertions.assertThat(result.syncpointRollbackRequested()).isTrue();
        }

        @Test
        @DisplayName("a successful pass commits once - the task's implicit syncpoint")
        void aSuccessfulPassCommitsOnce() {
            arrangeBothLocks();
            arrangeBothRewrites();

            service.writeProcessing(ACCT_ID_CHARS, commarea(), matchedOldDetails(), newDetails(), null,
                    CODEC);

            Assertions.assertThat(transactionManager.commits).isOne();
            Assertions.assertThat(transactionManager.rollbacks).isZero();
        }

        @Test
        @DisplayName("one unit of work spans both locks, the comparison and both rewrites")
        void oneUnitOfWorkSpansTheWholeSequence() {
            // Two units of work would release the account lock before the customer record was read, which
            // is precisely the interleaving 9700-CHECK-CHANGE-IN-REC exists to detect and cannot detect
            // from inside. So the count matters as much as the presence.
            arrangeBothLocks();
            arrangeBothRewrites();
            java.util.List<String> observed = new java.util.ArrayList<>();
            when(accountRepository.readForUpdate(anyString())).thenAnswer(invocation -> {
                observed.add("read ACCTDAT active=" + DatasetUnitOfWork.active());
                return AccountRepository.ReadResult.found(storedAccount());
            });
            when(customerRepository.readForUpdate(anyString())).thenAnswer(invocation -> {
                observed.add("read CUSTDAT active=" + DatasetUnitOfWork.active());
                return CustomerRepository.ReadResult.found(storedCustomer());
            });
            when(accountRepository.rewrite(any(AccountRecord.class))).thenAnswer(invocation -> {
                observed.add("rewrite ACCTDAT active=" + DatasetUnitOfWork.active());
                return AccountRepository.WriteResult.written();
            });
            when(customerRepository.rewrite(any(CustomerRecord.class))).thenAnswer(invocation -> {
                observed.add("rewrite CUSTDAT active=" + DatasetUnitOfWork.active());
                return CustomerRepository.WriteResult.written();
            });

            service.writeProcessing(ACCT_ID_CHARS, commarea(), matchedOldDetails(), newDetails(), null,
                    CODEC);

            // Every one of the four dataset operations saw the SAME open transaction, in source order.
            Assertions.assertThat(observed).containsExactly(
                    "read ACCTDAT active=true",
                    "read CUSTDAT active=true",
                    "rewrite ACCTDAT active=true",
                    "rewrite CUSTDAT active=true");
            // One boundary, opened once and closed once.
            Assertions.assertThat(transactionManager.commits).isOne();
            Assertions.assertThat(transactionManager.rollbacks).isZero();
            // And nothing is left open afterwards.
            Assertions.assertThat(DatasetUnitOfWork.active()).isFalse();
        }

        @Test
        @DisplayName("an abandoned update still closes its unit of work")
        void anAbandonedUpdateClosesItsUnitOfWork() {
            // The lock could not be taken, so nothing was written - but a transaction was opened to try,
            // and leaving it open would hold a connection for the life of the thread.
            when(accountRepository.readForUpdate(anyString()))
                    .thenReturn(AccountRepository.ReadResult.notFound());

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            Assertions.assertThat(result.outcome())
                    .isEqualTo(WriteOutcome.COULD_NOT_LOCK_ACCT_FOR_UPDATE);
            Assertions.assertThat(transactionManager.commits).isOne();
            Assertions.assertThat(DatasetUnitOfWork.active()).isFalse();
        }

        @Test
        @DisplayName("a rejected argument opens no unit of work at all")
        void aRejectedArgumentOpensNoUnitOfWork() {
            // A wrong detail group is a programming error, not dataset work. Opening a transaction to
            // reject one would put a connection behind a failure that never touches a dataset.
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> service.writeProcessing(ACCT_ID_CHARS, commarea(),
                            matchedOldDetails(), newDetails(), null, null));

            Assertions.assertThat(transactionManager.commits).isZero();
            Assertions.assertThat(transactionManager.rollbacks).isZero();
        }

        @Test
        @DisplayName("everything succeeds: both records rewritten at full width, message left alone")
        void changesOkayedAndDone() {
            arrangeBothLocks();
            arrangeBothRewrites();

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            Assertions.assertThat(result.outcome()).isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE);
            Assertions.assertThat(result.isRewritten()).isTrue();
            Assertions.assertThat(result.inputError()).isFalse();
            Assertions.assertThat(result.syncpointRollbackRequested()).isFalse();
            Assertions.assertThat(result.returnMessage())
                    .isEqualTo(AccountUpdateService.RETURN_MESSAGE_OFF);
            Assertions.assertThat(result.failedOperation()).isEmpty();
            Assertions.assertThat(result.failedFileName()).isEmpty();
            Assertions.assertThat(result.acctUpdateRecordImage()).get()
                    .asString().hasSize(300);
            Assertions.assertThat(result.custUpdateRecordImage()).get()
                    .asString().hasSize(500);
            Assertions.assertThat(result.changeActionCode()).isEqualTo("C");
            Assertions.assertThat(result.isDataWasChangedBeforeUpdate()).isFalse();
        }

        @Test
        @DisplayName("the record identification fields are built exactly as :3892 and :3920 build them")
        void ridFieldsAreBuiltFromTheWorkAreaAndTheCommarea() {
            arrangeBothLocks();
            arrangeBothRewrites();

            service.writeProcessing("123", commarea(), matchedOldDetails(), newDetails(), null, CODEC);

            ArgumentCaptor<String> acctKey = ArgumentCaptor.forClass(String.class);
            verify(accountRepository).readForUpdate(acctKey.capture());
            Assertions.assertThat(acctKey.getValue()).isEqualTo("123        ");

            ArgumentCaptor<String> custKey = ArgumentCaptor.forClass(String.class);
            verify(customerRepository).readForUpdate(custKey.capture());
            Assertions.assertThat(custKey.getValue()).isEqualTo("123456789");
        }

        @Test
        @DisplayName("the rewritten record carries the staged image, group identifier defect included")
        void theRewrittenRecordCarriesTheStagedImage() {
            arrangeBothLocks();
            arrangeBothRewrites();

            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            ArgumentCaptor<AccountRecord> written = ArgumentCaptor.forClass(AccountRecord.class);
            verify(accountRepository).rewrite(written.capture());
            Assertions.assertThat(written.getValue().toFixedWidthString())
                    .isEqualTo(result.acctUpdateRecordImage().orElseThrow());
            Assertions.assertThat(written.getValue().getAcctAddrZip()).isEqualTo("GRPNEW    ");
            Assertions.assertThat(written.getValue().getAcctGroupId()).isEqualTo("          ");

            ArgumentCaptor<CustomerRecord> customer = ArgumentCaptor.forClass(CustomerRecord.class);
            verify(customerRepository).rewrite(customer.capture());
            Assertions.assertThat(customer.getValue().recordImage(CODEC))
                    .isEqualTo(result.custUpdateRecordImage().orElseThrow());
        }

        @Test
        @DisplayName("the write path refuses transposed groups, a null commarea or a null codec")
        void writeProcessingRefusesBadArguments() {
            AccountUpdateDetails old = matchedOldDetails();
            AccountUpdateDetails typed = newDetails();
            Assertions.assertThatNullPointerException().isThrownBy(() -> service.writeProcessing(
                    ACCT_ID_CHARS, null, old, typed, null, CODEC));
            Assertions.assertThatNullPointerException().isThrownBy(() -> service.writeProcessing(
                    ACCT_ID_CHARS, commarea(), old, typed, null, null));
            Assertions.assertThatNullPointerException().isThrownBy(() -> service.writeProcessing(
                    ACCT_ID_CHARS, commarea(), null, typed, null, CODEC));
            Assertions.assertThatNullPointerException().isThrownBy(() -> service.writeProcessing(
                    ACCT_ID_CHARS, commarea(), old, null, null, CODEC));
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.writeProcessing(
                    ACCT_ID_CHARS, commarea(), typed, typed, null, CODEC));
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.writeProcessing(
                    ACCT_ID_CHARS, commarea(), old, old, null, CODEC));
            verifyNoInteractions(accountRepository);
            verifyNoInteractions(customerRepository);
        }
    }

    @Nested
    @DisplayName("Value-object invariants - a state the paragraph cannot reach cannot be built")
    class ValueObjectInvariants {

        private static final String FIFTEEN_SPACES = " ".repeat(15);

        @Test
        @DisplayName("MonetaryEdit refuses a null component")
        void monetaryEditRefusesNulls() {
            OptionalInt conforms = OptionalInt.of(0);
            BigDecimal value = new BigDecimal("1.00");
            Assertions.assertThatNullPointerException().isThrownBy(() -> new MonetaryEdit(
                    null, "1078", NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, false, conforms, true,
                    value));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new MonetaryEdit(
                    "R", null, NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, false, conforms, true, value));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", null, FIFTEEN_SPACES, false, conforms, true, value));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, null, false, conforms, true, value));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, false, null, true, value));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, false, conforms, true,
                    null));
        }

        @Test
        @DisplayName("MonetaryEdit refuses a staging image that is not PIC X(15) - :412-416")
        void monetaryEditRefusesTheWrongStagingWidth() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, "short", false, OptionalInt.of(0), true,
                    new BigDecimal("1.00")))
                    .withMessageContaining("PIC X(15)");
        }

        @Test
        @DisplayName("MonetaryEdit refuses a value that is not at the monetary scale")
        void monetaryEditRefusesTheWrongScale() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, false, OptionalInt.of(0),
                    true, new BigDecimal("1.000")))
                    .withMessageContaining("scale");
        }

        @Test
        @DisplayName("MonetaryEdit refuses a not-supplied arm that claims a conformance test - :1073")
        void notSuppliedArmCannotCarryAConformanceTest() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, true, OptionalInt.of(0),
                    false, CobolDecimal.monetaryZero()))
                    .withMessageContaining("TEST-NUMVAL-C is never evaluated");
        }

        @Test
        @DisplayName("MonetaryEdit refuses a not-supplied arm that claims a COMPUTE")
        void notSuppliedArmCannotCarryACompute() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, true, OptionalInt.empty(),
                    true, CobolDecimal.monetaryZero()))
                    .withMessageContaining("no COMPUTE");
        }

        @Test
        @DisplayName("MonetaryEdit refuses a supplied arm with no conformance test - :1078")
        void suppliedArmAlwaysCarriesAConformanceTest() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, false, OptionalInt.empty(),
                    false, CobolDecimal.monetaryZero()))
                    .withMessageContaining("always evaluates");
        }

        @Test
        @DisplayName("MonetaryEdit refuses a computed flag that disagrees with the conformance result")
        void computedMustFollowConformance() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, false, OptionalInt.of(3),
                    true, CobolDecimal.monetaryZero()))
                    .withMessageContaining("executes exactly when");
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new MonetaryEdit(
                    "R", "1078", NumvalArgument.MAP_FIELD, FIFTEEN_SPACES, false, OptionalInt.of(0),
                    false, CobolDecimal.monetaryZero()))
                    .withMessageContaining("executes exactly when");
        }

        @Test
        @DisplayName("isNotValid answers true only on the supplied-but-non-conforming arm")
        void isNotValidNamesTheElseContinueArm() {
            Assertions.assertThat(new MonetaryEdit("R", "1078", NumvalArgument.MAP_FIELD,
                    FIFTEEN_SPACES, false, OptionalInt.of(3), false, CobolDecimal.monetaryZero())
                    .isNotValid()).isTrue();
            Assertions.assertThat(new MonetaryEdit("R", "1078", NumvalArgument.MAP_FIELD,
                    FIFTEEN_SPACES, false, OptionalInt.of(0), true, new BigDecimal("1.00"))
                    .isNotValid()).isFalse();
            Assertions.assertThat(new MonetaryEdit("R", "1078", NumvalArgument.MAP_FIELD,
                    FIFTEEN_SPACES, true, OptionalInt.empty(), false, CobolDecimal.monetaryZero())
                    .isNotValid()).isFalse();
        }

        @Test
        @DisplayName("INITIALIZE leaves an account subgroup blank and its five amounts at scale-2 zero")
        void accountDataInitializeIsTheInitializeState() {
            AccountData blank = AccountData.initialize();
            Assertions.assertThat(blank.acctId()).isZero();
            Assertions.assertThat(blank.activeStatus()).isEqualTo(" ");
            Assertions.assertThat(blank.groupId()).isEqualTo(" ".repeat(AccountData.GROUP_ID_LENGTH));
            Assertions.assertThat(blank.openYear()).isEqualTo("    ");
            Assertions.assertThat(blank.openMon()).isEqualTo("  ");
            Assertions.assertThat(blank.openDay()).isEqualTo("  ");
            Assertions.assertThat(blank.currBal()).isEqualByComparingTo("0.00");
            Assertions.assertThat(blank.currBal().scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            Assertions.assertThat(blank.creditLimit().scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            Assertions.assertThat(blank.cashCreditLimit().scale())
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);
            Assertions.assertThat(blank.currCycCredit().scale())
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);
            Assertions.assertThat(blank.currCycDebit().scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
        }

        @Test
        @DisplayName("the account subgroup renders each of the two accessors over every span (G34)")
        void accountDataRendersBothViews() {
            AccountData data = matchedAccountData();
            Assertions.assertThat(data.acctIdImage(CODEC)).isEqualTo("12345678901");
            Assertions.assertThat(data.openDate()).isEqualTo("20200115").hasSize(8);
            Assertions.assertThat(data.expiraionDate()).isEqualTo("20251231").hasSize(8);
            Assertions.assertThat(data.reissueDate()).isEqualTo("20220630").hasSize(8);
            // PIC S9(10)V99 renders as twelve characters with the sign overpunched onto the last
            // digit: 1234.56 positive puts '6' at that position, which zoned decimal writes as 'F'.
            Assertions.assertThat(data.currBalImage(CODEC)).isEqualTo("00000012345F");
            Assertions.assertThat(data.creditLimitImage(CODEC)).hasSize(12);
            Assertions.assertThat(data.cashCreditLimitImage(CODEC)).hasSize(12);
            Assertions.assertThat(data.currCycCreditImage(CODEC)).hasSize(12);
            Assertions.assertThat(data.currCycDebitImage(CODEC)).hasSize(12);
        }

        @Test
        @DisplayName("PIC X truncates on the right when the sending item is too long")
        void picXTruncatesAnOverWideComponent() {
            // ACUP-...-ACTIVE-STATUS is PIC X(01); a two-character move keeps the first character only.
            AccountData wide = new AccountData(1L, "YN", CobolDecimal.monetaryZero(),
                    CobolDecimal.monetaryZero(), CobolDecimal.monetaryZero(),
                    "2020", "01", "15", "2025", "12", "31", "2022", "06", "30",
                    CobolDecimal.monetaryZero(), CobolDecimal.monetaryZero(),
                    "GROUP0123456789");
            Assertions.assertThat(wide.activeStatus()).isEqualTo("Y");
            Assertions.assertThat(wide.groupId()).hasSize(AccountData.GROUP_ID_LENGTH);
        }

        @Test
        @DisplayName("the account subgroup refuses a null component")
        void accountDataRefusesNulls() {
            BigDecimal zero = CobolDecimal.monetaryZero();
            Assertions.assertThatNullPointerException().isThrownBy(() -> new AccountData(
                    1L, null, zero, zero, zero, "2020", "01", "15", "2025", "12", "31", "2022", "06",
                    "30", zero, zero, "G"));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new AccountData(
                    1L, "Y", null, zero, zero, "2020", "01", "15", "2025", "12", "31", "2022", "06",
                    "30", zero, zero, "G"));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new AccountData(
                    1L, "Y", zero, zero, zero, "2020", "01", "15", "2025", "12", "31", "2022", "06",
                    "30", zero, zero, null));
        }

        @Test
        @DisplayName("INITIALIZE leaves a customer subgroup blank")
        void customerDataInitializeIsTheInitializeState() {
            CustomerData blank = CustomerData.initialize();
            Assertions.assertThat(blank.custId()).isZero();
            Assertions.assertThat(blank.ssn()).isZero();
            Assertions.assertThat(blank.ficoScore()).isZero();
            Assertions.assertThat(blank.firstName()).isBlank();
            Assertions.assertThat(blank.phoneNum1()).isEqualTo(" ".repeat(CustomerData
                    .PHONE_NUM_LENGTH));
            Assertions.assertThat(blank.custDobYyyyMmDd()).isEqualTo("        ").hasSize(8);
        }

        @Test
        @DisplayName("the customer subgroup renders the three PIC 9 spans as characters (G34)")
        void customerDataRendersBothViews() {
            CustomerData data = matchedCustomerData();
            Assertions.assertThat(data.custIdImage(CODEC)).isEqualTo("123456789");
            Assertions.assertThat(data.ssnImage(CODEC)).isEqualTo("123456789");
            Assertions.assertThat(data.ficoScoreImage(CODEC)).isEqualTo("750");
        }

        @Test
        @DisplayName("the customer subgroup refuses a null component")
        void customerDataRefusesNulls() {
            Assertions.assertThatNullPointerException().isThrownBy(() -> new CustomerData(
                    1, null, "M", "L", "A1", "A2", "A3", "IL", "USA", "Z", "P1", "P2", 1, "G",
                    "1980", "07", "04", "E", "Y", 700));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new CustomerData(
                    1, "F", "M", "L", "A1", "A2", "A3", "IL", "USA", "Z", "P1", "P2", 1, "G",
                    "1980", "07", null, "E", "Y", 700));
        }

        @Test
        @DisplayName("INITIALIZE builds a whole blank group and keeps the discriminant - :1047, :3813")
        void detailsInitializeBuildsBothSubgroups() {
            for (DetailGroup group : DetailGroup.values()) {
                AccountUpdateDetails blank = AccountUpdateDetails.initialize(group);
                Assertions.assertThat(blank.group()).isEqualTo(group);
                Assertions.assertThat(blank.acctData()).isEqualTo(AccountData.initialize());
                Assertions.assertThat(blank.custData()).isEqualTo(CustomerData.initialize());
            }
        }

        @Test
        @DisplayName("withAcctData and withCustData replace one subgroup and keep the discriminant")
        void detailsReplaceOneSubgroupAtATime() {
            AccountUpdateDetails blank = AccountUpdateDetails.initialize(DetailGroup.NEW);
            AccountUpdateDetails withAccount = blank.withAcctData(matchedAccountData());
            Assertions.assertThat(withAccount.group()).isEqualTo(DetailGroup.NEW);
            Assertions.assertThat(withAccount.acctData()).isEqualTo(matchedAccountData());
            Assertions.assertThat(withAccount.custData()).isEqualTo(CustomerData.initialize());

            AccountUpdateDetails withCustomer = blank.withCustData(matchedCustomerData());
            Assertions.assertThat(withCustomer.group()).isEqualTo(DetailGroup.NEW);
            Assertions.assertThat(withCustomer.custData()).isEqualTo(matchedCustomerData());
            Assertions.assertThat(withCustomer.acctData()).isEqualTo(AccountData.initialize());
        }

        @Test
        @DisplayName("a detail group refuses a null discriminant or a null subgroup")
        void detailsRefuseNulls() {
            AccountData acct = matchedAccountData();
            CustomerData cust = matchedCustomerData();
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new AccountUpdateDetails(null, acct, cust));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new AccountUpdateDetails(DetailGroup.OLD, null, cust));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new AccountUpdateDetails(DetailGroup.OLD, acct, null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> AccountUpdateDetails.initialize(null));
        }

        @Test
        @DisplayName("the two ChangeCheck factories build exactly the two states the AND-chain has")
        void changeCheckFactories() {
            Assertions.assertThat(ChangeCheck.unchanged().dataWasChanged()).isFalse();
            ChangeCheck changed = ChangeCheck.changed(Block.CUSTOMER,
                    EnumSet.of(ComparedItem.CUST_SSN));
            Assertions.assertThat(changed.dataWasChanged()).isTrue();
            Assertions.assertThat(changed.failingBlock()).contains(Block.CUSTOMER);
            Assertions.assertThat(changed.differingItems()).containsExactly(ComparedItem.CUST_SSN);
        }

        @Test
        @DisplayName("the differing-item set is copied and unmodifiable")
        void changeCheckDefendsItsItemSet() {
            Set<ComparedItem> mutable = EnumSet.of(ComparedItem.CUST_SSN);
            ChangeCheck changed = ChangeCheck.changed(Block.CUSTOMER, mutable);
            mutable.add(ComparedItem.CUST_FICO_CREDIT_SCORE);
            Assertions.assertThat(changed.differingItems()).containsExactly(ComparedItem.CUST_SSN);
            Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> changed.differingItems().add(ComparedItem.CUST_SSN));
        }

        @Test
        @DisplayName("ChangeCheck refuses every self-contradictory combination")
        void changeCheckRefusesContradictions() {
            Set<ComparedItem> one = EnumSet.of(ComparedItem.CUST_SSN);
            Set<ComparedItem> none = EnumSet.noneOf(ComparedItem.class);
            Optional<Block> customer = Optional.of(Block.CUSTOMER);
            Optional<Block> nothing = Optional.empty();

            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new ChangeCheck(false, null, none));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new ChangeCheck(false, nothing, null));
            // Unchanged but carrying items, and changed but carrying none.
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ChangeCheck(false, customer, one))
                    .withMessageContaining("join their comparisons with AND");
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ChangeCheck(true, customer, none))
                    .withMessageContaining("join their comparisons with AND");
            // Changed with items but no failing block.
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ChangeCheck(true, nothing, one))
                    .withMessageContaining("names the block");
            // Items from the other block: the GO TO at :4147 makes that unreachable.
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ChangeCheck(true, customer,
                            EnumSet.of(ComparedItem.ACCT_CURR_BAL)))
                    .withMessageContaining("cannot carry items from both blocks");
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> ChangeCheck.changed(Block.CUSTOMER, none))
                    .withMessageContaining("at least one item");
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> ChangeCheck.changed(null, one));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> ChangeCheck.changed(Block.CUSTOMER, null));
        }

        /**
         * The canonical successful result, which each rejection test below perturbs in exactly one way.
         *
         * @return a well-formed {@code CHANGES_OKAYED_AND_DONE} result
         */
        private static WriteResult okayedAndDone() {
            return new WriteResult(WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.of("C".repeat(500)), Optional.of(ChangeCheck.unchanged()));
        }

        @Test
        @DisplayName("the canonical successful result is accepted, so each rejection below is isolated")
        void theCanonicalResultIsWellFormed() {
            Assertions.assertThat(okayedAndDone().isRewritten()).isTrue();
        }

        @Test
        @DisplayName("WriteResult refuses a null component")
        void writeResultRefusesNulls() {
            String message = AccountUpdateService.RETURN_MESSAGE_OFF;
            Optional<String> account = Optional.of("A".repeat(300));
            Optional<String> customer = Optional.of("C".repeat(500));
            Optional<ChangeCheck> check = Optional.of(ChangeCheck.unchanged());
            WriteOutcome done = WriteOutcome.CHANGES_OKAYED_AND_DONE;

            Assertions.assertThatNullPointerException().isThrownBy(() -> new WriteResult(
                    null, false, false, message, FileStatus.OK, OptionalInt.empty(), Optional.empty(),
                    Optional.empty(), account, customer, check));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new WriteResult(
                    done, false, false, null, FileStatus.OK, OptionalInt.empty(), Optional.empty(),
                    Optional.empty(), account, customer, check));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new WriteResult(
                    done, false, false, message, null, OptionalInt.empty(), Optional.empty(),
                    Optional.empty(), account, customer, check));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new WriteResult(
                    done, false, false, message, FileStatus.OK, null, Optional.empty(),
                    Optional.empty(), account, customer, check));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new WriteResult(
                    done, false, false, message, FileStatus.OK, OptionalInt.empty(), null,
                    Optional.empty(), account, customer, check));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new WriteResult(
                    done, false, false, message, FileStatus.OK, OptionalInt.empty(), Optional.empty(),
                    null, account, customer, check));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new WriteResult(
                    done, false, false, message, FileStatus.OK, OptionalInt.empty(), Optional.empty(),
                    Optional.empty(), null, customer, check));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new WriteResult(
                    done, false, false, message, FileStatus.OK, OptionalInt.empty(), Optional.empty(),
                    Optional.empty(), account, null, check));
            Assertions.assertThatNullPointerException().isThrownBy(() -> new WriteResult(
                    done, false, false, message, FileStatus.OK, OptionalInt.empty(), Optional.empty(),
                    Optional.empty(), account, customer, null));
        }

        @Test
        @DisplayName("WriteResult refuses a message that is not PIC X(75) - :479")
        void writeResultRefusesTheWrongMessageWidth() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false, "too short", FileStatus.OK,
                    OptionalInt.empty(), Optional.empty(), Optional.empty(),
                    Optional.of("A".repeat(300)), Optional.of("C".repeat(500)),
                    Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("PIC X(75)");
        }

        @Test
        @DisplayName("WriteResult refuses a status that is not two characters")
        void writeResultRefusesTheWrongStatusWidth() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, "000", OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.of("C".repeat(500)), Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("exactly 2 characters");
        }

        @Test
        @DisplayName("INPUT-ERROR belongs to the two lock arms alone - :3910 and :3938")
        void inputErrorBelongsToTheLockArms() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, true, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.of("C".repeat(500)), Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("SET INPUT-ERROR");
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.COULD_NOT_LOCK_ACCT_FOR_UPDATE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.NOT_FOUND, OptionalInt.empty(),
                    Optional.of("READ"), Optional.of(AccountUpdateService.ACCT_CICS_FILE_NAME),
                    Optional.empty(), Optional.empty(), Optional.empty()))
                    .withMessageContaining("SET INPUT-ERROR");
        }

        @Test
        @DisplayName("a rollback belongs to the customer rewrite failure alone - :4099-4101")
        void rollbackBelongsToTheCustomerRewriteFailure() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, true,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.of("C".repeat(500)), Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("SYNCPOINT ROLLBACK");
            // The right outcome, but naming the account file: only CUSTDAT rolls back.
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.LOCKED_BUT_UPDATE_FAILED, false, true,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.NOT_FOUND, OptionalInt.empty(),
                    Optional.of("REWRITE"), Optional.of(AccountUpdateService.ACCT_CICS_FILE_NAME),
                    Optional.of("A".repeat(300)), Optional.of("C".repeat(500)),
                    Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("rewrite failure rolls back");
        }

        @Test
        @DisplayName("a failed operation names both the operation and the file - :392-395")
        void failedOperationAndFileTravelTogether() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.of("REWRITE"), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.of("C".repeat(500)), Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("names both the operation");
        }

        @Test
        @DisplayName("both images are present exactly when the paragraph reached the rewrites")
        void bothImagesTravelTogether() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of("C".repeat(500)), Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("stages both records");
            // The mirror image: the account record staged but not the customer record. :3956-4061
            // stages both before either rewrite is issued, so one without the other cannot happen.
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.empty(), Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("stages both records");
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.of("C".repeat(500)), Optional.of(ChangeCheck.changed(
                            Block.ACCOUNT_MASTER, EnumSet.of(ComparedItem.ACCT_CURR_BAL)))))
                    .withMessageContaining("stages both records");
        }

        @Test
        @DisplayName("a staged image of the wrong width is refused (gates G19 and G21)")
        void stagedImagesCarryTheirDeclaredWidth() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(299)),
                    Optional.of("C".repeat(500)), Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("ACCT-UPDATE-RECORD is 300");
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.of("C".repeat(501)), Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("CUST-UPDATE-RECORD is 500");
        }

        @Test
        @DisplayName("the concurrency check ran for every outcome except the two lock failures - :3947")
        void changeCheckPresenceFollowsTheLocks() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.of("C".repeat(500)), Optional.empty()))
                    .withMessageContaining("runs only after both locks");
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE, true, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.NOT_FOUND, OptionalInt.empty(),
                    Optional.of("READ"), Optional.of(AccountUpdateService.CUST_CICS_FILE_NAME),
                    Optional.empty(), Optional.empty(), Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("runs only after both locks");
        }

        @Test
        @DisplayName("the guard at :3950-3952 and the check's own verdict cannot disagree")
        void theGuardAgreesWithTheCheck() {
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("A".repeat(300)),
                    Optional.of("C".repeat(500)), Optional.of(ChangeCheck.changed(
                            Block.CUSTOMER, EnumSet.of(ComparedItem.CUST_SSN)))))
                    .withMessageContaining("cannot disagree");
            Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE, false, false,
                    AccountUpdateService.RETURN_MESSAGE_OFF, FileStatus.OK, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(ChangeCheck.unchanged())))
                    .withMessageContaining("cannot disagree");
        }
    }

    @Nested
    @DisplayName("Diagnostic renderings - no personal data reaches a log (CWE-532)")
    class DiagnosticRenderings {

        @Test
        @DisplayName("the customer subgroup withholds every identifying component")
        void customerDataWithholdsEveryIdentifyingComponent() {
            // The shared fixture gives CUST-ID and CUST-SSN the same digits, so a "does not contain
            // 123456789" assertion could never distinguish the two. This case gives the social security
            // number its own digits, which makes withholding it provable independently of the key.
            CustomerData data = new CustomerData(123456789, "JOHN", "Q", "PUBLIC",
                    "1 MAIN ST", "APT 2", "SPRINGFIELD", "IL", "USA", "62704-0001",
                    "(217)555-1234", "(217)555-9876", 555443333, "DL1234567890",
                    "1980", "07", "04", "EFT0000001", "Y", 750);
            String rendered = data.toString();

            Assertions.assertThat(rendered)
                    .doesNotContain("JOHN")
                    .doesNotContain("PUBLIC")
                    .doesNotContain("1 MAIN ST")
                    .doesNotContain("APT 2")
                    .doesNotContain("SPRINGFIELD")
                    .doesNotContain("62704-0001")
                    .doesNotContain("(217)555-1234")
                    .doesNotContain("(217)555-9876")
                    .doesNotContain("555443333")
                    .doesNotContain("DL1234567890")
                    .doesNotContain("19800704")
                    .doesNotContain("1980")
                    .doesNotContain("EFT0000001")
                    // The key is masked to its last four digits: enough to tell one record from another
                    // while diagnosing a parity failure, and not enough to re-identify the person the
                    // rest of this rendering is careful not to name.
                    .contains("CUST-ID='*****6789'")
                    .doesNotContain("123456789");
        }

        @Test
        @DisplayName("the customer subgroup keeps the key, the two geographic codes and the score")
        void customerDataKeepsTheDiagnosableFields() {
            String rendered = matchedCustomerData().toString();
            Assertions.assertThat(rendered)
                    .startsWith("ACUP-<group>-CUST-DATA[")
                    .endsWith("]")
                    // The customer key is masked to its last four digits, which is the module's stated
                    // treatment for an identifier and still tells one record from another.
                    .contains("CUST-ID='*****6789'")
                    .doesNotContain("123456789")
                    // The geographic codes, the holder indicator and the score carry no personal data, so
                    // they are retained - escaped rather than interpolated, because a PIC X span can hold
                    // any byte and a CR or LF among it would forge a log line.
                    .contains("IL")
                    .contains("USA")
                    .contains("750");
        }

        @Test
        @DisplayName("a populated field is described by its width and a blank one is named blank")
        void withheldTextIsDescribedNotPublished() {
            Assertions.assertThat(matchedCustomerData().toString())
                    .contains("CUST-GOVT-ISSUED-ID=<withheld text, length=20")
                    .contains("CUST-SSN=<withheld>")
                    .contains("CUST-DOB-YYYY-MM-DD=<withheld date>");
            // INITIALIZE leaves every character span blank, which discloses nothing to describe.
            Assertions.assertThat(CustomerData.initialize().toString())
                    .contains("CUST-FIRST-NAME=<blank>")
                    .contains("CUST-GOVT-ISSUED-ID=<blank>")
                    .contains("CUST-EFT-ACCOUNT-ID=<blank>")
                    .doesNotContain("<withheld text");
        }

        @Test
        @DisplayName("the rendering is a single line, so no forged log entry can follow it - CWE-117")
        void customerDataRendersOnOneLine() {
            Assertions.assertThat(matchedCustomerData().toString())
                    .doesNotContain("\n").doesNotContain("\r");
        }

        @Test
        @DisplayName("every component is still readable by name, so parity is unaffected")
        void withholdingChangesNoValue() {
            CustomerData data = matchedCustomerData();
            Assertions.assertThat(data.firstName()).startsWith("JOHN");
            Assertions.assertThat(data.govtIssuedId()).startsWith("DL1234567890");
            Assertions.assertThat(data.ssn()).isEqualTo(123456789);
            Assertions.assertThat(data.custDobYyyyMmDd()).isEqualTo("19800704");
            Assertions.assertThat(data.eftAccountId()).startsWith("EFT0000001");
        }

        @Test
        @DisplayName("a write result withholds both staged images and describes them by width")
        void writeResultWithholdsTheStagedImages() {
            arrangeBothLocks();
            arrangeBothRewrites();
            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);

            String rendered = result.toString();
            Assertions.assertThat(rendered)
                    .contains("acctUpdateRecordImage=<withheld text, length=300>")
                    .contains("custUpdateRecordImage=<withheld text, length=500>")
                    .contains("outcome=CHANGES_OKAYED_AND_DONE")
                    .contains("fileStatus='" + FileStatus.OK + "'")
                    .doesNotContain("987654321")
                    .doesNotContain("DL0987654321")
                    .doesNotContain("SHELBYVILLE")
                    .doesNotContain("\n");
            // The images themselves are untouched and still available for the parity comparison.
            Assertions.assertThat(result.custUpdateRecordImage()).get()
                    .asString().contains("987654321");
        }

        @Test
        @DisplayName("an absent staged image is named absent rather than described")
        void writeResultNamesAnAbsentImage() {
            when(accountRepository.readForUpdate(anyString()))
                    .thenReturn(AccountRepository.ReadResult.notFound());
            WriteResult result = service.writeProcessing(ACCT_ID_CHARS, commarea(),
                    matchedOldDetails(), newDetails(), null, CODEC);
            Assertions.assertThat(result.toString())
                    .contains("acctUpdateRecordImage=<absent>")
                    .contains("custUpdateRecordImage=<absent>")
                    .contains("outcome=COULD_NOT_LOCK_ACCT_FOR_UPDATE");
        }

        @Test
        @DisplayName("the account subgroup carries no personal data, so it renders in full")
        void accountDataRendersPlainly() {
            String rendered = matchedAccountData().toString();

            Assertions.assertThat(rendered)
                    .startsWith("ACUP-<group>-ACCT-DATA[")
                    .endsWith("]")
                    // The account number is masked to its last four digits, and the whole number appears
                    // nowhere - the generated rendering this replaced printed it in full.
                    .contains("ACCT-ID='*******8901'")
                    .doesNotContain("12345678901")
                    // The group id carries no personal data and is retained.
                    .contains("GROUP01");
        }

        @Test
        @DisplayName("the account rendering withholds every monetary item")
        void accountDataWithholdsEveryMonetaryItem() {
            // The five money fields were the point of the finding: a balance and two credit limits in a
            // log line describe the account, and the generated record rendering published all of them.
            String rendered = matchedAccountData().toString();

            Assertions.assertThat(rendered)
                    .contains("ACCT-CURR-BAL=<withheld>")
                    .contains("ACCT-CREDIT-LIMIT=<withheld>")
                    .contains("ACCT-CASH-CREDIT-LIMIT=<withheld>")
                    .contains("ACCT-CURR-CYC-CREDIT=<withheld>")
                    .contains("ACCT-CURR-CYC-DEBIT=<withheld>");
            for (java.math.BigDecimal amount : java.util.List.of(matchedAccountData().currBal(),
                    matchedAccountData().creditLimit(), matchedAccountData().cashCreditLimit(),
                    matchedAccountData().currCycCredit(), matchedAccountData().currCycDebit())) {
                Assertions.assertThat(rendered).doesNotContain(amount.toPlainString());
            }
            // And the accessors still answer, because those are the parity surface.
            Assertions.assertThat(matchedAccountData().currBal()).isNotNull();
        }

        @Test
        @DisplayName("the details group delegates to both subgroups' safe renderings")
        void theDetailsGroupDelegates() {
            String rendered = matchedOldDetails().toString();

            Assertions.assertThat(rendered)
                    .startsWith("ACUP-OLD-DETAILS[")
                    .contains("ACUP-<group>-ACCT-DATA[")
                    .contains("ACUP-<group>-CUST-DATA[")
                    // Nothing the subgroups withhold reappears through the group.
                    .doesNotContain("12345678901")
                    .doesNotContain("123456789");
        }

        @Test
        @DisplayName("no rendering can forge a second log line")
        void noRenderingCanForgeALogLine() {
            // CWE-117. A fixed-width field holds whatever was moved into it, control characters included,
            // so every retained text field is escaped rather than interpolated.
            CustomerData injected = new CustomerData(CUST_ID, "JOHN", "Q", "PUBLIC",
                    "1 MAIN ST", "APT 2", "SPRINGFIELD", "I\n", "USA", "62704-0001",
                    "(217)555-1234", "(217)555-9876", 123456789, "DL1234567890",
                    "1980", "07", "04", "EFT0000001", "Y", 750);

            Assertions.assertThat(injected.toString()).doesNotContain("\n").doesNotContain("\r");
        }
    }

    @Nested
    @DisplayName("Enumeration metadata - every constant carries its own COBOL evidence")
    class EnumMetadata {

        @ParameterizedTest
        @EnumSource(DetailGroup.class)
        @DisplayName("each detail group names itself as the source names it - :668 and :756")
        void detailGroupNames(DetailGroup group) {
            Assertions.assertThat(group.groupName()).isEqualTo("ACUP-" + group.name() + "-DETAILS");
        }

        @ParameterizedTest
        @EnumSource(NumvalArgument.class)
        @DisplayName("each NUMVAL-C operand shape is named, preserving the verified asymmetry")
        void numvalArgumentOperands(NumvalArgument argument) {
            Assertions.assertThat(argument.cobolOperand()).isNotBlank();
        }

        @Test
        @DisplayName("the two operand shapes are the two the five statements actually use - :1079-1135")
        void theTwoOperandShapesAreDistinct() {
            Assertions.assertThat(NumvalArgument.MAP_FIELD.cobolOperand()).contains("CACTUPAI");
            Assertions.assertThat(NumvalArgument.STAGING_COPY.cobolOperand())
                    .isEqualTo("ACUP-NEW-<item>-X");
        }

        @ParameterizedTest
        @EnumSource(Block.class)
        @DisplayName("each block carries the source's own caption and its line range")
        void blockMetadata(Block block) {
            Assertions.assertThat(block.cobolCaption()).isNotBlank();
            Assertions.assertThat(block.sourceLines()).matches("\\d{4}-\\d{4}");
        }

        @Test
        @DisplayName("the customer block's caption keeps the source's two consecutive spaces - :4150")
        void theCustomerCaptionIsVerbatim() {
            Assertions.assertThat(Block.CUSTOMER.cobolCaption()).isEqualTo("Customer  data");
            Assertions.assertThat(Block.ACCOUNT_MASTER.cobolCaption())
                    .isEqualTo("Account Master data");
        }

        @ParameterizedTest
        @EnumSource(ComparedItem.class)
        @DisplayName("each compared item names its COBOL field, its block, its treatment and its line")
        void comparedItemMetadata(ComparedItem item) {
            Assertions.assertThat(item.cobolName()).isNotBlank().startsWith(
                    item.block() == Block.ACCOUNT_MASTER ? "ACCT-" : "CUST-");
            Assertions.assertThat(item.block()).isNotNull();
            Assertions.assertThat(item.comparison()).isNotNull();
            Assertions.assertThat(item.sourceLine()).matches("\\d{4}");
        }

        @Test
        @DisplayName("the thirty-five items are sixteen account then nineteen customer, in that order")
        void comparedItemOrderMirrorsTheSource() {
            ComparedItem[] items = ComparedItem.values();
            Assertions.assertThat(items).hasSize(35);
            for (int index = 0; index < 16; index++) {
                Assertions.assertThat(items[index].block()).isEqualTo(Block.ACCOUNT_MASTER);
            }
            for (int index = 16; index < items.length; index++) {
                Assertions.assertThat(items[index].block()).isEqualTo(Block.CUSTOMER);
            }
        }

        @Test
        @DisplayName("the treatments are exactly the ones :4115-4191 applies")
        void comparisonTreatmentsMatchTheSource() {
            Assertions.assertThat(ComparedItem.ACCT_GROUP_ID.comparison())
                    .isEqualTo(Comparison.LOWER_CASE_FOLDED);
            Assertions.assertThat(ComparedItem.CUST_FIRST_NAME.comparison())
                    .isEqualTo(Comparison.UPPER_CASE_FOLDED);
            Assertions.assertThat(ComparedItem.CUST_ADDR_ZIP.comparison())
                    .isEqualTo(Comparison.EXACT);
            Assertions.assertThat(ComparedItem.ACCT_CURR_BAL.comparison())
                    .isEqualTo(Comparison.MONETARY);
            // Exactly one item is folded down, and it is the account group identifier.
            Assertions.assertThat(EnumSet.allOf(ComparedItem.class).stream()
                    .filter(item -> item.comparison() == Comparison.LOWER_CASE_FOLDED).toList())
                    .containsExactly(ComparedItem.ACCT_GROUP_ID);
            // Eight customer items are folded up.
            Assertions.assertThat(EnumSet.allOf(ComparedItem.class).stream()
                    .filter(item -> item.comparison() == Comparison.UPPER_CASE_FOLDED).count())
                    .isEqualTo(9L);
            // Five monetary items, one per amount the account master holds.
            Assertions.assertThat(EnumSet.allOf(ComparedItem.class).stream()
                    .filter(item -> item.comparison() == Comparison.MONETARY).count()).isEqualTo(5L);
        }

        @ParameterizedTest
        @EnumSource(WriteOutcome.class)
        @DisplayName("each outcome carries an action code, a condition name and a WHEN position")
        void writeOutcomeMetadata(WriteOutcome outcome) {
            Assertions.assertThat(outcome.changeActionCode()).hasSize(1);
            Assertions.assertThat(outcome.cobolCondition()).isNotBlank();
            Assertions.assertThat(outcome.firstMatchWinsPosition()).isBetween(1, 4);
            Assertions.assertThat(outcome.returnMessageLiteral())
                    .satisfies(literal -> Assertions.assertThat(literal.isPresent())
                            .isEqualTo(outcome != WriteOutcome.CHANGES_OKAYED_AND_DONE));
        }

        @Test
        @DisplayName("only the two lock failures set INPUT-ERROR, and only one outcome is a rewrite")
        void writeOutcomePredicates() {
            Assertions.assertThat(EnumSet.allOf(WriteOutcome.class).stream()
                    .filter(WriteOutcome::isLockFailure).toList())
                    .containsExactly(WriteOutcome.COULD_NOT_LOCK_ACCT_FOR_UPDATE,
                            WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE);
            Assertions.assertThat(EnumSet.allOf(WriteOutcome.class).stream()
                    .filter(WriteOutcome::isRewritten).toList())
                    .containsExactly(WriteOutcome.CHANGES_OKAYED_AND_DONE);
        }

        @Test
        @DisplayName("THE CALLER'S MISSING ARM: the customer lock failure shares WHEN OTHER's action "
                + "code and position, yet is not a rewrite - :2603-2614")
        void theCallerHasNoArmForTheCustomerLockFailure() {
            Assertions.assertThat(WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE.changeActionCode())
                    .isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE.changeActionCode());
            Assertions.assertThat(WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE
                    .firstMatchWinsPosition())
                    .isEqualTo(WriteOutcome.CHANGES_OKAYED_AND_DONE.firstMatchWinsPosition());
            Assertions.assertThat(WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE.isRewritten()).isFalse();
            Assertions.assertThat(WriteOutcome.CHANGES_OKAYED_AND_DONE.isRewritten()).isTrue();
            Assertions.assertThat(WriteOutcome.CHANGES_OKAYED_AND_DONE.cobolCondition())
                    .isEqualTo("WHEN OTHER");
        }

        @ParameterizedTest
        @EnumSource(Comparison.class)
        @DisplayName("every treatment is reachable from at least one compared item")
        void everyComparisonIsUsed(Comparison comparison) {
            Assertions.assertThat(EnumSet.allOf(ComparedItem.class).stream()
                    .anyMatch(item -> item.comparison() == comparison)).isTrue();
        }
    }

    @Nested
    @DisplayName("FUNCTION NUMVAL-C grammar - the grouping-comma and decimal-point edges")
    class NumvalGrammarEdges {

        @ParameterizedTest
        @ValueSource(strings = {"1.2.3", "1.2,3", ",1", "1,a", ".,", "1,,"})
        @DisplayName("a malformed grouping comma or a second decimal point does not conform")
        void malformedSeparatorsDoNotConform(String image) {
            Assertions.assertThat(AccountUpdateService.testNumvalC(image))
                    .isNotEqualTo(AccountUpdateService.NUMVAL_CONFORMS);
            Assertions.assertThat(AccountUpdateService.numvalC(image))
                    .isEqualByComparingTo(CobolDecimal.monetaryZero());
        }

        @ParameterizedTest
        @CsvSource({"'1,234',1234.00", "'12,345,678.90',12345678.90", "'0.05',0.05",
            "'1,000.5',1000.50"})
        @DisplayName("a well-placed grouping comma conforms and contributes no digit")
        void wellPlacedSeparatorsConform(String image, BigDecimal expected) {
            Assertions.assertThat(AccountUpdateService.testNumvalC(image))
                    .isEqualTo(AccountUpdateService.NUMVAL_CONFORMS);
            Assertions.assertThat(AccountUpdateService.numvalC(image)).isEqualByComparingTo(expected);
        }
    }
}
