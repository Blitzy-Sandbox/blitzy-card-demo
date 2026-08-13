package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.billing.BillPaymentController;
import com.vsergeychik.carddemo.billing.BillPaymentService;
import com.vsergeychik.carddemo.billing.BillPaymentService.PaymentState;
import com.vsergeychik.carddemo.billing.BillPaymentService.SentScreen;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedDataset;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenRequest;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The parity gate for {@code app/cbl/COBIL00C.cbl} - twenty declarative cases, each judged field by field,
 * each required to report a diff count of zero.
 */
@DisplayName("COBIL00C parity - 20 statically derived cases over BillPaymentService and "
        + "BillPaymentController, the bill payment that debits a balance by truncation and never "
        + "by rounding")
class COBIL00CParityTest {
    private static final String PROGRAM = BillPaymentService.WS_PGMNAME;

    private static final String TRANSACTION_ID = BillPaymentService.WS_TRANID;

    private static final String ACCTDAT = AccountRepository.CICS_FILE_NAME;

    private static final String CXACAIX = CardXrefRepository.ALTERNATE_INDEX_DD_NAME;

    private static final String TRANSACT = TransactionRepository.CICS_FILE_NAME;

    private static final Charset CHARSET = ParityHarness.FIXTURE_CHARSET;

    private static final String CHARSET_NAME = CHARSET.name();

    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    private static final String PINNED_CLOCK = LocalDateTime
            .ofInstant(PINNED_INSTANT, ZoneOffset.UTC).toString();

    private static final Clock CLOCK = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);

    private static final int EIBCALEN_WITH_COMMAREA =
            NavigationContext.COMMAREA_LENGTH + BillPaymentResponse.CB00_INFO_LENGTH;

    private static final int EIBCALEN_COLD_START = 0;

    private static final String ACCOUNT_FIXTURE = "acctdata.txt";

    private static final String XREF_FIXTURE = "cardxref.txt";

    private static final int ACCOUNT_FIXTURE_ROW = 0;

    private static final int XREF_FIXTURE_ROW = 48;

    private static final int ONE_ROW = 1;

    private static final String ACCOUNT_ID = "00000000001";

    private static final String CARD_NUMBER = "9680294154603697";

    private static final int CUSTOMER_ID = 1;

    private static final String ACCOUNT_STATUS = "Y";

    private static final String CREDIT_LIMIT = "2020.00";

    private static final String CASH_CREDIT_LIMIT = "1020.00";

    private static final String OPEN_DATE = "2014-11-20";

    private static final String EXPIRATION_DATE = "2025-05-20";

    private static final String REISSUE_DATE = "2025-05-20";

    private static final String CYCLE_CREDIT = "0.00";

    private static final String CYCLE_DEBIT = "0.00";

    private static final String ADDR_ZIP = "A000000000";

    private static final String GROUP_ID = "";

    private static final String FIXTURE_BALANCE = "194.00";

    private static final String SUB_CENT_BALANCE = "194.007";

    private static final String MAX_BALANCE = "9999999999.99";

    private static final String MAX_PAYMENT = "999999999.99";

    private static final String MAX_DEBITED_BALANCE = "9000000000.00";

    private static final String MINIMUM_BALANCE = "0.01";

    private static final String NEGATIVE_BALANCE = "-1234.56";

    private static final String ZERO_BALANCE = "0.00";

    private static final String SETTLED_BALANCE = "0.00";

    private static final String HIGHEST_TRAN_ID = "0000000000000007";

    private static final String NEXT_TRAN_ID = "0000000000000008";

    private static final String FIRST_TRAN_ID = "0000000000000001";

    private static final String ERRMSGC_GREEN = "DFHGREEN";

    private static final String CURSOR_ACTIDIN = "ACTIDINL";

    private static final String CURSOR_CONFIRM = "CONFIRML";

    private static final String MAPSET = BillPaymentResponse.MAPSET_NAME;

    private static final String MAP = BillPaymentResponse.MAP_NAME;

    private static final String SIGN_ON_PROGRAM = BillPaymentResponse.SIGN_ON_PROGRAM;

    private static final String MAIN_MENU_PROGRAM = BillPaymentResponse.MAIN_MENU_PROGRAM;

    private static final String ACTIDIN_INPUT = "ACTIDINI";

    private static final String CURBAL_INPUT = "CURBALI";

    private static final String TRN_SELECTED_FIELD = "CDEMO-CB00-TRN-SELECTED";

    private static final String TRN_SEL_FLG_FIELD = "CDEMO-CB00-TRN-SEL-FLG";

    private static final String TRNID_FIRST_FIELD = "CDEMO-CB00-TRNID-FIRST";

    private static final String TRNID_LAST_FIELD = "CDEMO-CB00-TRNID-LAST";

    private static final String PAGE_NUM_FIELD = "CDEMO-CB00-PAGE-NUM";

    private static final String NEXT_PAGE_FLG_FIELD = "CDEMO-CB00-NEXT-PAGE-FLG";

    private static final String CONFIRM_INPUT = "CONFIRMI";

    private static final String UNEXPECTED_STATUS = FileStatus.RECORD_LENGTH_CONFLICT;

    private static final String UNREPORTED_RESP_DISPLAY_LINE =
            BillPaymentService.DISPLAY_RESP_PREFIX
                    + FileStatus.respNotReportedImage(BillPaymentService.WS_RESP_CD_DIGITS)
                    + BillPaymentService.DISPLAY_REAS_PREFIX
                    + "0".repeat(BillPaymentService.WS_RESP_CD_DIGITS);

    private static final String NOTOPEN_RESP_DISPLAY_LINE =
            BillPaymentService.DISPLAY_RESP_PREFIX
                    + zeroFilled(FileStatus.NOTOPEN, BillPaymentService.WS_RESP_CD_DIGITS)
                    + BillPaymentService.DISPLAY_REAS_PREFIX
                    + "0".repeat(BillPaymentService.WS_RESP_CD_DIGITS);

    private static String picX(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    private static String lowValues(int width) {
        return String.valueOf(BillPaymentService.LOW_VALUES).repeat(width);
    }

    private static String pic9(String digits, int width) {
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    private static String zeroFilled(long value, int width) {
        return pic9(Long.toString(value), width);
    }

    private static String zoned(String value, int integerDigits, int scale) {
        BigDecimal stored = new BigDecimal(value).setScale(scale, CobolDecimal.COBOL_ROUNDING);
        String digits = pic9(stored.abs().unscaledValue().toString(), integerDigits + scale);
        char lowOrder = digits.charAt(digits.length() - 1);
        int offset = lowOrder - '0';
        char overpunched = stored.signum() < 0
                ? (offset == 0 ? '}' : (char) ('J' + offset - 1))
                : (offset == 0 ? '{' : (char) ('A' + offset - 1));
        return digits.substring(0, digits.length() - 1) + overpunched;
    }

    private static String accountImage(String balance) {
        return pic9(ACCOUNT_ID, AccountRecord.ACCT_ID_LENGTH)
                + picX(ACCOUNT_STATUS, AccountRecord.ACCT_ACTIVE_STATUS_LENGTH)
                + zoned(balance, AccountRecord.MONETARY_INTEGER_DIGITS, AccountRecord.MONETARY_SCALE)
                + zoned(CREDIT_LIMIT, AccountRecord.MONETARY_INTEGER_DIGITS,
                        AccountRecord.MONETARY_SCALE)
                + zoned(CASH_CREDIT_LIMIT, AccountRecord.MONETARY_INTEGER_DIGITS,
                        AccountRecord.MONETARY_SCALE)
                + picX(OPEN_DATE, AccountRecord.ACCT_OPEN_DATE_LENGTH)
                + picX(EXPIRATION_DATE, AccountRecord.ACCT_EXPIRAION_DATE_LENGTH)
                + picX(REISSUE_DATE, AccountRecord.ACCT_REISSUE_DATE_LENGTH)
                + zoned(CYCLE_CREDIT, AccountRecord.MONETARY_INTEGER_DIGITS,
                        AccountRecord.MONETARY_SCALE)
                + zoned(CYCLE_DEBIT, AccountRecord.MONETARY_INTEGER_DIGITS,
                        AccountRecord.MONETARY_SCALE)
                + picX(ADDR_ZIP, AccountRecord.ACCT_ADDR_ZIP_LENGTH)
                + picX(GROUP_ID, AccountRecord.ACCT_GROUP_ID_LENGTH)
                + " ".repeat(AccountRecord.FILLER_LENGTH);
    }

    private static String transactionImage(String tranId, String amount, String cardNum) {
        String timestamp = expectedTimestamp();
        return picX(tranId, TranRecord.TRAN_ID_LENGTH)
                + picX(BillPaymentService.TRAN_TYPE_CD_BILL_PAYMENT, TranRecord.TRAN_TYPE_CD_LENGTH)
                + zeroFilled(BillPaymentService.TRAN_CAT_CD_BILL_PAYMENT, TranRecord.TRAN_CAT_CD_LENGTH)
                + picX(BillPaymentService.TRAN_SOURCE_POS_TERM, TranRecord.TRAN_SOURCE_LENGTH)
                + picX(BillPaymentService.TRAN_DESC_BILL_PAYMENT_ONLINE, TranRecord.TRAN_DESC_LENGTH)
                + zoned(amount, TranRecord.TRAN_AMT_INTEGER_DIGITS, TranRecord.TRAN_AMT_SCALE)
                + zeroFilled(BillPaymentService.TRAN_MERCHANT_ID_BILL_PAYMENT,
                        TranRecord.TRAN_MERCHANT_ID_LENGTH)
                + picX(BillPaymentService.TRAN_MERCHANT_NAME_BILL_PAYMENT,
                        TranRecord.TRAN_MERCHANT_NAME_LENGTH)
                + picX(BillPaymentService.TRAN_MERCHANT_NOT_APPLICABLE,
                        TranRecord.TRAN_MERCHANT_CITY_LENGTH)
                + picX(BillPaymentService.TRAN_MERCHANT_NOT_APPLICABLE,
                        TranRecord.TRAN_MERCHANT_ZIP_LENGTH)
                + picX(cardNum, TranRecord.TRAN_CARD_NUM_LENGTH)
                + picX(timestamp, TranRecord.TRAN_ORIG_TS_LENGTH)
                + picX(timestamp, TranRecord.TRAN_PROC_TS_LENGTH)
                + " ".repeat(TranRecord.FILLER_LENGTH);
    }

    private static String expectedTimestamp() {
        LocalDateTime at = LocalDateTime.ofInstant(PINNED_INSTANT, ZoneOffset.UTC);
        return zeroFilled(at.getYear(), 4) + '-' + zeroFilled(at.getMonthValue(), 2) + '-'
                + zeroFilled(at.getDayOfMonth(), 2) + ' '
                + zeroFilled(at.getHour(), 2) + ':' + zeroFilled(at.getMinute(), 2) + ':'
                + zeroFilled(at.getSecond(), 2) + '.' + "0".repeat(6);
    }

    private static String editedBalance(String balance) {
        BigDecimal stored = new BigDecimal(balance)
                .setScale(CobolDecimal.MONETARY_SCALE, CobolDecimal.COBOL_ROUNDING);
        String digits = pic9(stored.abs().unscaledValue().toString(),
                AccountRecord.MONETARY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE);
        char sign = stored.signum() < 0
                ? BillPaymentService.CURR_BAL_SIGN_NEGATIVE
                : BillPaymentService.CURR_BAL_SIGN_POSITIVE;
        return sign + digits.substring(0, AccountRecord.MONETARY_INTEGER_DIGITS)
                + BillPaymentService.CURR_BAL_DECIMAL_POINT
                + digits.substring(AccountRecord.MONETARY_INTEGER_DIGITS);
    }

    private static String seededTransactionRow(String tranId) {
        TranRecord row = new TranRecord(CHARSET);
        row.moveTranId(tranId);
        return row.displayImage();
    }

    private record ParityScenario(ParityCase parityCase,
                                  UnitKind adapterKind,
                                  ParityHarness.ParityUnit adapter,
                                  String unitName) {
        String caseId() {
            return parityCase.caseId();
        }

        @Override
        public String toString() {
            String description = parityCase.description();
            int firstStop = description.indexOf(". ");
            return caseId() + " [" + unitName + "] "
                    + (firstStop < 0 ? description : description.substring(0, firstStop));
        }
    }

    static List<ParityScenario> cases() {
        List<ParityScenario> scenarios = ParityHarness.casesOf(PROGRAM).stream()
                .map(COBIL00CParityTest::bind)
                .toList();
        requireCompleteCaseSet(scenarios);
        return scenarios;
    }

    private static ParityScenario bind(ParityCase parityCase) {
        return switch (parityCase.unitKind()) {
            case SERVICE -> serviceCase(parityCase);
            case CONTROLLER_POJO -> controllerCase(parityCase);
            case BATCH_JOB, COMPONENT -> throw new IllegalStateException("Case " + PROGRAM + '/'
                    + parityCase.caseId() + " declares unitKind " + parityCase.unitKind()
                    + ", which COBIL00C has no unit for: it is a CICS online program, so its units are "
                    + "BillPaymentController (CONTROLLER_POJO) and BillPaymentService (SERVICE).");
        };
    }

    private static void requireCompleteCaseSet(List<ParityScenario> scenarios) {
        if (scenarios.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException(PROGRAM + " declares " + scenarios.size()
                    + " parity case(s) but the gate requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + ", named case01 through case"
                    + ParityHarness.CASES_PER_PROGRAM + ". A short set is not a smaller gate, it is a "
                    + "gate that passes without asking the questions.");
        }
        for (int ordinal = 1; ordinal <= scenarios.size(); ordinal++) {
            ParityCase declared = scenarios.get(ordinal - 1).parityCase();
            String required = ParityHarness.caseId(ordinal);
            if (!required.equals(declared.caseId())) {
                throw new IllegalStateException("Parity case " + ordinal + " of " + PROGRAM
                        + " is declared \"" + declared.caseId() + "\" where the set requires \""
                        + required + "\". The identifiers are positional and they name the fixture, so "
                        + "a gap or a repeat means a case nobody runs, or one running twice while "
                        + "another runs not at all.");
            }
            if (!PROGRAM.equals(declared.program())) {
                throw new IllegalStateException("Case " + required + " names program "
                        + declared.program() + " but this class gates " + PROGRAM + ", whose cases "
                        + "belong in " + ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + '/');
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("field-for-field identical to app/cbl/COBIL00C.cbl, with a diff count of zero")
    void theTranslationMatchesTheCobolFieldForField(ParityScenario scenario) {
        DiffResult result = ParityHarness.usAscii()
                .judge(scenario.parityCase(), scenario.adapterKind(), scenario.adapter());

        assertThat(result.count())
                .describedAs("%s/%s (%s) must diff to zero.%n%s", PROGRAM, scenario.caseId(),
                        scenario.unitName(), result.render())
                .isZero();
    }

    private static UnitOutcome billPaymentServiceUnit(Invocation invocation) {
        Charset charset = invocation.charset();
        List<String> seededAccounts = rowsOf(invocation, ACCTDAT);
        List<String> seededTransactions = rowsOf(invocation, TRANSACT);

        AtomicReference<String> rewrittenAccount = new AtomicReference<>();
        AtomicReference<String> addedTransaction = new AtomicReference<>();

        AccountRepository.WriteResult rewriteOutcome = accountRewriteOutcome(invocation);
        TransactionRepository.WriteResult writeOutcome = transactionWriteOutcome(invocation);

        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.datasetCharset()).thenReturn(charset);
        when(accounts.readForUpdate(anyString()))
                .thenReturn(accountReadOutcome(invocation, seededAccounts, charset));
        when(accounts.rewrite(any())).thenAnswer(call -> {
            AccountRecord offered = call.getArgument(0);
            rewrittenAccount.set(offered.toFixedWidthString());
            return rewriteOutcome;
        });

        CardXrefRepository crossReference = mock(CardXrefRepository.class);
        when(crossReference.readByAccountIdViaAltIndex(anyString()))
                .thenReturn(crossReferenceReadOutcome(invocation, charset));

        TransactionRepository.Browse browse = positionedBrowse();
        when(browse.readPrev()).thenReturn(highestTransactionOf(seededTransactions, charset));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.startBrowse(BrowseDirection.BACKWARD)).thenReturn(browse);
        when(transactions.write(any())).thenAnswer(call -> {
            TranRecord offered = call.getArgument(0);
            addedTransaction.set(offered.displayImage());
            return writeOutcome;
        });

        BillPaymentService service = new BillPaymentService(accounts, crossReference, transactions,
                invocation.clock(), privateUnitOfWork());

        PaymentState state = service.processEnterKey(invocation.mapFields().get(ACTIDIN_INPUT),
                invocation.mapFields().get(CONFIRM_INPUT), commareaOf(invocation));

        UnitOutcome.Builder recorder = invocation.recorder();
        recordTransactionChannel(recorder, seededTransactions, addedTransaction.get(),
                writeOutcome.isWritten());
        recordAccountChannel(recorder, seededAccounts, rewrittenAccount.get(),
                rewriteOutcome.isWritten());
        for (String line : state.displays()) {
            recorder.display(line);
        }
        recorder.response(observedServiceResponse(state));

        recorder.returnCode(0);
        return null;
    }

    private static AccountRepository.ReadResult accountReadOutcome(Invocation invocation,
                                                                   List<String> seeded,
                                                                   Charset charset) {
        if (invocation.hasForcedOutcome(RepositoryOperation.READ_FOR_UPDATE)) {
            return accountReadOf(invocation.forcedOutcome(RepositoryOperation.READ_FOR_UPDATE));
        }
        if (seeded.isEmpty()) {
            return AccountRepository.ReadResult.notFound();
        }
        return AccountRepository.ReadResult.found(AccountRecord.decode(seeded.get(0), charset));
    }

    private static AccountRepository.ReadResult accountReadOf(ForcedOutcome forced) {
        return switch (forced.outcome()) {
            case NOT_FOUND -> AccountRepository.ReadResult.notFound();
            case END_OF_FILE -> AccountRepository.ReadResult.endOfFile();
            default -> AccountRepository.ReadResult.of(UNEXPECTED_STATUS);
        };
    }

    private static AccountRepository.WriteResult accountRewriteOutcome(Invocation invocation) {
        if (!invocation.hasForcedOutcome(RepositoryOperation.REWRITE)) {
            return AccountRepository.WriteResult.written();
        }
        ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.REWRITE);
        return switch (forced.outcome()) {
            case NOT_FOUND -> AccountRepository.WriteResult.notFound();
            default -> AccountRepository.WriteResult.of(UNEXPECTED_STATUS);
        };
    }

    private static CardXrefRepository.ReadResult crossReferenceReadOutcome(Invocation invocation,
                                                                          Charset charset) {
        if (invocation.hasForcedOutcome(RepositoryOperation.READ)) {
            ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.READ);
            return switch (forced.outcome()) {
                case NOT_FOUND -> CardXrefRepository.ReadResult.notFound(CXACAIX);
                case END_OF_FILE -> CardXrefRepository.ReadResult.endOfFile(CXACAIX);
                default -> CardXrefRepository.ReadResult.other(CXACAIX, UNEXPECTED_STATUS);
            };
        }
        List<String> seeded = rowsOf(invocation, CXACAIX);
        if (seeded.isEmpty()) {
            return CardXrefRepository.ReadResult.notFound(CXACAIX);
        }
        String stored = seeded.get(0);
        return CardXrefRepository.ReadResult.found(CXACAIX,
                CardXrefRecord.decode(stored.getBytes(charset), charset), stored);
    }

    private static TransactionRepository.ReadResult highestTransactionOf(List<String> seeded,
                                                                        Charset charset) {
        if (seeded.isEmpty()) {
            return TransactionRepository.ReadResult.endOfFile(TRANSACT);
        }
        return TransactionRepository.ReadResult.found(TRANSACT,
                TranRecord.decode(seeded.get(seeded.size() - 1), charset));
    }

    private static TransactionRepository.WriteResult transactionWriteOutcome(Invocation invocation) {
        if (!invocation.hasForcedOutcome(RepositoryOperation.WRITE)) {
            return TransactionRepository.WriteResult.written(TRANSACT);
        }
        ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.WRITE);
        return switch (forced.outcome()) {
            case DUPLICATE -> TransactionRepository.WriteResult.duplicate(TRANSACT);
            default -> TransactionRepository.WriteResult.other(TRANSACT, UNEXPECTED_STATUS);
        };
    }

    private static void recordTransactionChannel(UnitOutcome.Builder recorder, List<String> seeded,
                                                 String added, boolean written) {
        boolean landed = added != null && written;
        if (added == null) {
            recorder.openedWithoutWriting(TRANSACT, TranRecord.LAYOUT);
        } else if (landed) {
            recorder.wrote(TRANSACT, TranRecord.LAYOUT, added);
        } else {
            recorder.openedWithoutWriting(TRANSACT, TranRecord.LAYOUT);
        }
        List<String> afterwards = new ArrayList<>(seeded);
        if (landed) {
            afterwards.add(added);
        }
        recorder.finalState(TRANSACT, TranRecord.LAYOUT, afterwards);
    }

    private static void recordAccountChannel(UnitOutcome.Builder recorder, List<String> seeded,
                                             String rewritten, boolean written) {
        boolean landed = rewritten != null && written;
        if (landed) {
            recorder.wrote(ACCTDAT, AccountRecord.LAYOUT, rewritten);
        } else {
            recorder.openedWithoutWriting(ACCTDAT, AccountRecord.LAYOUT);
        }
        if (seeded.isEmpty()) {
            recorder.finalState(ACCTDAT, AccountRecord.LAYOUT, List.of());
            return;
        }
        List<String> afterwards = new ArrayList<>(seeded);
        if (landed) {
            afterwards.set(0, rewritten);
        }
        recorder.finalState(ACCTDAT, AccountRecord.LAYOUT, afterwards);
    }

    private static ObservedResponse observedServiceResponse(PaymentState state) {
        return new ObservedResponse(null, null, null, navigationOf(state.commarea()),
                observedSendsOf(state.sentScreens()), cursorItemOf(state.cursorField()),
                Termination.RETURN_TRANSID);
    }

    private static List<ObservedSend> observedSendsOf(List<SentScreen> sent) {
        List<ObservedSend> sends = new ArrayList<>(sent.size());
        for (SentScreen screen : sent) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ACTIDINO", screen.actIdIn());
            fields.put("CURBALO", screen.curBal());
            fields.put("CONFIRMO", screen.confirm());
            fields.put("ERRMSGO", screen.errMsg());
            Map<String, String> attributes = new LinkedHashMap<>();
            if (BillPaymentService.MESSAGE_HIGHLIGHT_GREEN.equals(screen.messageHighlight())) {
                attributes.put("ERRMSGC", ERRMSGC_GREEN);
            }
            sends.add(attributes.isEmpty()
                    ? ObservedSend.ofFields(fields)
                    : new ObservedSend(fields, attributes));
        }
        return sends;
    }

    private static Map<String, String> navigationOf(NavigationContext commarea) {
        Map<String, String> navigation = new LinkedHashMap<>();
        navigation.put(NavigationContext.FROM_TRANID_FIELD, commarea.fromTranid());
        navigation.put(NavigationContext.FROM_PROGRAM_FIELD, commarea.fromProgram());
        navigation.put(NavigationContext.TO_TRANID_FIELD, commarea.toTranid());
        navigation.put(NavigationContext.TO_PROGRAM_FIELD, commarea.toProgram());
        navigation.put(NavigationContext.USER_ID_FIELD, commarea.userId());
        navigation.put(NavigationContext.USER_TYPE_FIELD, commarea.userType());
        navigation.put(NavigationContext.PGM_CONTEXT_FIELD,
                zeroFilled(commarea.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
        navigation.put(NavigationContext.CUST_ID_FIELD,
                zeroFilled(commarea.custId(), NavigationContext.CUST_ID_LENGTH));
        navigation.put(NavigationContext.CUST_FNAME_FIELD, commarea.custFname());
        navigation.put(NavigationContext.CUST_MNAME_FIELD, commarea.custMname());
        navigation.put(NavigationContext.CUST_LNAME_FIELD, commarea.custLname());
        navigation.put(NavigationContext.ACCT_ID_FIELD,
                zeroFilled(commarea.acctId(), NavigationContext.ACCT_ID_LENGTH));
        navigation.put(NavigationContext.ACCT_STATUS_FIELD, commarea.acctStatus());
        navigation.put(NavigationContext.CARD_NUM_FIELD,
                zeroFilled(commarea.cardNum(), NavigationContext.CARD_NUM_LENGTH));
        navigation.put(NavigationContext.LAST_MAP_FIELD, commarea.lastMap());
        navigation.put(NavigationContext.LAST_MAPSET_FIELD, commarea.lastMapset());
        return navigation;
    }

    private static String cursorItemOf(CursorField cursor) {
        return switch (cursor) {
            case ACTIDIN -> CURSOR_ACTIDIN;
            case CONFIRM -> CURSOR_CONFIRM;
            case NONE -> null;
        };
    }

    private static List<String> rowsOf(Invocation invocation, String dataset) {
        if (!invocation.hasDataset(dataset)) {
            return List.of();
        }
        SeededDataset seeded = invocation.dataset(dataset);
        return seeded.rows();
    }

    private static NavigationContext commareaOf(Invocation invocation) {
        NavigationContext commarea = NavigationContext.empty();
        Map<String, String> declared = invocation.commarea();
        String context = declared.get(NavigationContext.PGM_CONTEXT_FIELD);
        if (context != null) {
            commarea = commarea.withPgmContext(Integer.parseInt(context.trim()));
        }
        String fromProgram = declared.get(NavigationContext.FROM_PROGRAM_FIELD);
        if (fromProgram != null) {
            commarea = commarea.withFromProgram(fromProgram);
        }
        return commarea;
    }

    private static DatasetUnitOfWork privateUnitOfWork() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:parity-" + PROGRAM + '-' + UUID.randomUUID(), "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        return new DatasetUnitOfWork(new DataSourceTransactionManager(dataSource));
    }

    private static UnitOutcome billPaymentControllerUnit(Invocation invocation) {
        Charset charset = invocation.charset();
        List<String> seededAccounts = rowsOf(invocation, ACCTDAT);
        List<String> seededTransactions = rowsOf(invocation, TRANSACT);

        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.datasetCharset()).thenReturn(charset);
        when(accounts.readForUpdate(anyString()))
                .thenReturn(accountReadOutcome(invocation, seededAccounts, charset));
        when(accounts.rewrite(any())).thenReturn(AccountRepository.WriteResult.written());

        CardXrefRepository crossReference = mock(CardXrefRepository.class);
        when(crossReference.readByAccountIdViaAltIndex(anyString()))
                .thenReturn(crossReferenceReadOutcome(invocation, charset));

        TransactionRepository.Browse browse = positionedBrowse();
        when(browse.readPrev()).thenReturn(highestTransactionOf(seededTransactions, charset));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.startBrowse(BrowseDirection.BACKWARD)).thenReturn(browse);
        when(transactions.write(any())).thenReturn(TransactionRepository.WriteResult.written(TRANSACT));

        BillPaymentService service = new BillPaymentService(accounts, crossReference, transactions,
                invocation.clock(), privateUnitOfWork());
        BillPaymentController controller = new BillPaymentController(service, invocation.clock());

        BillPaymentResponse payload = controller.payBill(requestOf(invocation), null, null).screen();

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.response(observedControllerResponse(payload));
        recorder.returnCode(0);
        return null;
    }

    private static BillPaymentRequest requestOf(Invocation invocation) {
        BillPaymentRequest request = new BillPaymentRequest();
        if (invocation.eibcalen() > 0) {
            request.setNavigationContext(commareaOf(invocation));
        }
        request.setAid(aidTokenOf(invocation.aid()));
        request.setActIdIn(invocation.mapFields().get(ACTIDIN_INPUT));
        request.setConfirm(invocation.mapFields().get(CONFIRM_INPUT));
        request.setCurBal(invocation.mapFields().get(CURBAL_INPUT));
        applyTransactionSelection(request, invocation.commarea());
        return request;
    }

    private static void applyTransactionSelection(BillPaymentRequest request,
                                                  Map<String, String> declared) {
        String selected = declared.get(TRN_SELECTED_FIELD);
        if (selected != null) {
            request.setTrnSelected(selected);
        }
        String selectionFlag = declared.get(TRN_SEL_FLG_FIELD);
        if (selectionFlag != null) {
            request.setTrnSelFlg(selectionFlag);
        }
        String first = declared.get(TRNID_FIRST_FIELD);
        if (first != null) {
            request.setTrnIdFirst(first);
        }
        String last = declared.get(TRNID_LAST_FIELD);
        if (last != null) {
            request.setTrnIdLast(last);
        }
        String pageNumber = declared.get(PAGE_NUM_FIELD);
        if (pageNumber != null) {
            request.setPageNum(Integer.parseInt(pageNumber.trim()));
        }
        String nextPage = declared.get(NEXT_PAGE_FLG_FIELD);
        if (nextPage != null) {
            request.setNextPageFlg(nextPage);
        }
    }

    private static ObservedResponse observedControllerResponse(BillPaymentResponse response) {
        List<ObservedSend> sends = new ArrayList<>();
        if (namesSomething(response.getNextMap())) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("TRNNAMEO", response.getTrnName());
            fields.put("TITLE01O", response.getTitle01());
            fields.put("CURDATEO", response.getCurDate());
            fields.put("PGMNAMEO", response.getPgmName());
            fields.put("TITLE02O", response.getTitle02());
            fields.put("CURTIMEO", response.getCurTime());
            fields.put("ACTIDINO", response.getActIdIn());
            fields.put("CURBALO", response.getCurBal());
            fields.put("CONFIRMO", response.getConfirm());
            fields.put("ERRMSGO", response.getErrMsg());
            Map<String, String> attributes = new LinkedHashMap<>();
            if (BillPaymentService.MESSAGE_HIGHLIGHT_GREEN.equals(response.getMessageHighlight())) {
                attributes.put("ERRMSGC", ERRMSGC_GREEN);
            }
            sends.add(attributes.isEmpty()
                    ? ObservedSend.ofFields(fields)
                    : new ObservedSend(fields, attributes));
        }
        return new ObservedResponse(nameOrAbsent(response.getNextProgram()),
                nameOrAbsent(response.getNextMapset()),
                nameOrAbsent(response.getNextMap()),
                navigationOf(response.getNavigationContext()), sends,
                cursorItemOf(response.getCursorField()),
                namesSomething(response.getNextProgram())
                        ? Termination.XCTL
                        : Termination.RETURN_TRANSID);
    }

    private static boolean namesSomething(String carrier) {
        return carrier != null && !carrier.isEmpty()
                && !ScreenFieldImage.isSpacesOrLowValues(carrier);
    }

    private static String nameOrAbsent(String carrier) {
        return namesSomething(carrier) ? carrier : null;
    }

    private static String aidTokenOf(String mnemonic) {
        if (mnemonic == null) {
            return null;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return PfKeyResolver.aidImage(entry.getKey());
            }
        }
        throw new IllegalArgumentException('"' + mnemonic + "\" is not a DFHAID mnemonic that "
                + CicsAid.class.getName() + " reproduces, yet ParityCase accepted it. The two read the "
                + "same table, so this means they have drifted apart.");
    }

    private static final String AID_ENTER = "DFHENTER";

    private static final String AID_PF3 = "DFHPF3";

    private static final String AID_PF4 = "DFHPF4";

    private static final String AID_NO_MATCH = "DFHPF5";

    private static final String PGM_CONTEXT_ENTER = zeroFilled(
            NavigationContext.empty().pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH);

    private static final String PGM_CONTEXT_REENTER = zeroFilled(
            NavigationContext.empty().withPgmReenter().pgmContext(),
            NavigationContext.PGM_CONTEXT_LENGTH);

    private static final String BLANK_ACCOUNT_ID = " ".repeat(BillPaymentResponse.ACT_ID_IN_LENGTH);

    private static final String BLANK_CONFIRM = " ".repeat(BillPaymentResponse.CONFIRM_LENGTH);

    private static final String BLANK_BALANCE = " ".repeat(BillPaymentResponse.CUR_BAL_LENGTH);

    private static final String CONFIRM_YES = "Y";

    private static final String CONFIRM_NO_LOWER = "n";

    private static final String CONFIRM_INVALID = "X";

    private static DatasetInput fixtureAccount() {
        return new DatasetInput(List.of(), ACCOUNT_FIXTURE, ACCOUNT_FIXTURE_ROW, ONE_ROW);
    }

    private static DatasetInput accountHolding(String balance) {
        return DatasetInput.ofRows(List.of(accountImage(balance)));
    }

    private static DatasetInput absentAccount() {
        return DatasetInput.ofEmpty(AccountRecord.RECORD_LENGTH, "CVACT01Y");
    }

    private static DatasetInput fixtureCrossReference() {
        return new DatasetInput(List.of(), XREF_FIXTURE, XREF_FIXTURE_ROW, ONE_ROW);
    }

    private static DatasetInput absentCrossReference() {
        return DatasetInput.ofEmpty(CardXrefRecord.RECORD_LENGTH, "CVACT03Y");
    }

    private static DatasetInput masterHolding(String tranId) {
        return DatasetInput.ofRows(List.of(seededTransactionRow(tranId)));
    }

    private static DatasetInput emptyMaster() {
        return DatasetInput.ofEmpty(TranRecord.RECORD_LENGTH, "CVTRA05Y");
    }

    private static Map<String, DatasetInput> datasets(DatasetInput account,
                                                     DatasetInput crossReference,
                                                     DatasetInput master) {
        Map<String, DatasetInput> inputs = new LinkedHashMap<>();
        inputs.put(ACCTDAT, account);
        inputs.put(CXACAIX, crossReference);
        inputs.put(TRANSACT, master);
        return inputs;
    }

    private static List<DatasetNormalisation> normalisationsFor(DatasetInput crossReference) {
        return crossReference.fixtureBacked()
                ? List.of(new DatasetNormalisation(CXACAIX, Normalisation.CARDXREF_FILLER_PAD_36_TO_50))
                : List.of();
    }

    private static ScreenRequest enterKeyRequest(String actIdIn, String confirm,
                                                Map<RepositoryOperation, ForcedOutcome> forced) {
        Map<String, String> mapFields = new LinkedHashMap<>();
        mapFields.put(ACTIDIN_INPUT, actIdIn);
        mapFields.put(CONFIRM_INPUT, confirm);
        return new ScreenRequest(EIBCALEN_WITH_COMMAREA, AID_ENTER, PINNED_CLOCK, CHARSET_NAME,
                Map.of(NavigationContext.PGM_CONTEXT_FIELD, PGM_CONTEXT_REENTER), mapFields, forced);
    }

    private static ExpectedResponse enterKeyResponse(String cursorItem, List<ScreenSend> sends) {
        return new ExpectedResponse(null, null, null,
                navigationOf(NavigationContext.empty().withPgmReenter()), sends, cursorItem,
                Termination.RETURN_TRANSID);
    }

    private static ScreenSend send(String actIdIn, String curBal, String confirm, String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ACTIDINO", actIdIn);
        fields.put("CURBALO", curBal);
        fields.put("CONFIRMO", confirm);
        fields.put("ERRMSGO", picX(message, BillPaymentResponse.ERR_MSG_LENGTH));
        return new ScreenSend(fields, Map.of());
    }

    private static ScreenSend greenSend(String actIdIn, String curBal, String confirm,
                                       String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ACTIDINO", actIdIn);
        fields.put("CURBALO", curBal);
        fields.put("CONFIRMO", confirm);
        fields.put("ERRMSGO", picX(message, BillPaymentResponse.ERR_MSG_LENGTH));
        return new ScreenSend(fields, Map.of("ERRMSGC", ERRMSGC_GREEN));
    }

    private static String successMessage(String tranId) {
        return BillPaymentService.SUCCESS_PREFIX + BillPaymentService.SUCCESS_INFIX + tranId
                + BillPaymentService.SUCCESS_SUFFIX;
    }

    private static ExpectedRecord record(String dataset, int rowIndex, Map<String, String> fields,
                                        String image) {
        return new ExpectedRecord(dataset, rowIndex, fields, image);
    }

    private static ExpectedRecord image(String dataset, int rowIndex, String image) {
        return new ExpectedRecord(dataset, rowIndex, Map.of(), image);
    }

    private static ExpectedDataset channel(String dataset, DatasetChannel channel, int rows,
                                          int width) {
        return new ExpectedDataset(dataset, channel, rows, width);
    }

    private static Map<String, String> balanceField(String balance) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(AccountRecord.ACCT_ID_NAME, ACCOUNT_ID);
        fields.put(AccountRecord.ACCT_CURR_BAL_NAME, balance);
        fields.put(AccountRecord.ACCT_EXPIRAION_DATE_NAME, EXPIRATION_DATE);
        return fields;
    }

    private static Map<String, String> transactionFields(String tranId, String amount) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(TranRecord.TRAN_ID.name(), tranId);
        fields.put(TranRecord.TRAN_AMT.name(), amount);
        fields.put(TranRecord.TRAN_CARD_NUM.name(), CARD_NUMBER);
        return fields;
    }

    private static ParityScenario serviceCase(ParityCase parityCase) {
        return new ParityScenario(parityCase, UnitKind.SERVICE,
                COBIL00CParityTest::billPaymentServiceUnit, "BillPaymentService.processEnterKey");
    }

    private static ParityScenario controllerCase(ParityCase parityCase) {
        return new ParityScenario(parityCase, UnitKind.CONTROLLER_POJO,
                COBIL00CParityTest::billPaymentControllerUnit,
                "BillPaymentController.payBill (MAIN-PARA)");
    }

    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    private static final String TITLE02 = "              CardDemo                  ";

    private static String expectedCurrentDate() {
        LocalDateTime at = LocalDateTime.ofInstant(PINNED_INSTANT, ZoneOffset.UTC);
        return zeroFilled(at.getMonthValue(), 2) + '/' + zeroFilled(at.getDayOfMonth(), 2) + '/'
                + zeroFilled(at.getYear() % 100, 2);
    }

    private static String expectedCurrentTime() {
        LocalDateTime at = LocalDateTime.ofInstant(PINNED_INSTANT, ZoneOffset.UTC);
        return zeroFilled(at.getHour(), 2) + ':' + zeroFilled(at.getMinute(), 2) + ':'
                + zeroFilled(at.getSecond(), 2);
    }

    private static ScreenSend paintedScreen(String actIdIn, String curBal, String confirm,
                                           String errMsg) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("TRNNAMEO", TRANSACTION_ID);
        fields.put("TITLE01O", TITLE01);
        fields.put("CURDATEO", expectedCurrentDate());
        fields.put("PGMNAMEO", PROGRAM);
        fields.put("TITLE02O", TITLE02);
        fields.put("CURTIMEO", expectedCurrentTime());
        fields.put("ACTIDINO", actIdIn);
        fields.put("CURBALO", curBal);
        fields.put("CONFIRMO", confirm);
        fields.put("ERRMSGO", errMsg);
        return new ScreenSend(fields, Map.of());
    }

    @Test
    @DisplayName("the case-set guard refuses a short, misnumbered or foreign set - loudly")
    void theCaseSetGuardRefusesAnythingOtherThanTheExactTwenty() {
        List<ParityScenario> complete = cases();

        assertThat(complete)
                .describedAs("the shipped set is exactly the mandated twenty")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        assertThatThrownBy(() -> requireCompleteCaseSet(complete.subList(0, 4)))
                .describedAs("four cases would satisfy 'zero diffs across all cases' vacuously")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROGRAM)
                .hasMessageContaining("exactly");

        List<ParityScenario> misnumbered = new ArrayList<>(complete);
        misnumbered.set(0, complete.get(1));
        assertThatThrownBy(() -> requireCompleteCaseSet(misnumbered))
                .describedAs("twenty entries, but case02 twice and case01 never - the count still reads "
                        + "twenty, which is exactly why the identifiers are checked positionally")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ParityHarness.caseId(1));

        List<ParityScenario> foreign = new ArrayList<>(complete);
        foreign.set(0, controllerCase(new ParityCase("COBIL00X", ParityHarness.caseId(1),
                "A case belonging to another program, which this gate must refuse rather than judge "
                        + "against this program's unit.",
                UnitKind.CONTROLLER_POJO,
                Map.of(), Map.of(),
                new ScreenRequest(EIBCALEN_COLD_START, null, PINNED_CLOCK, CHARSET_NAME, Map.of(),
                        Map.of(), Map.of()),
                new ExpectedResponse(SIGN_ON_PROGRAM, null, null, Map.of(), List.of(), null,
                        Termination.XCTL),
                List.of(), List.of(), 0, List.of(), List.of(), List.of())));
        assertThatThrownBy(() -> requireCompleteCaseSet(foreign))
                .describedAs("a foreign case would be judged against the wrong unit entirely")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COBIL00X")
                .hasMessageContaining(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM);
    }

    @Test
    @DisplayName("the store truncates, and case02's number is sharp enough to prove it")
    void theTruncationCaseIsSharpEnoughToRejectANearestValueRule() {
        BigDecimal offered = new BigDecimal(SUB_CENT_BALANCE);
        BigDecimal stored = CobolDecimal.storeMonetary(offered);

        assertThat(stored)
                .describedAs("ACCT-CURR-BAL is PIC S9(10)V99, so storing %s keeps two fraction digits "
                        + "and discards the rest downwards", SUB_CENT_BALANCE)
                .isEqualByComparingTo(new BigDecimal(FIXTURE_BALANCE));
        assertThat(stored.scale())
                .describedAs("the stored scale is the picture's scale, not the offered value's")
                .isEqualTo(CobolDecimal.MONETARY_SCALE);
        assertThat(stored)
                .describedAs("truncation only loses magnitude; a rule that carried into the last "
                        + "retained digit would have produced a value above the one offered")
                .isLessThan(offered);

        BigDecimal discarded = offered.subtract(stored);
        BigDecimal halfOfLastPlace = BigDecimal.ONE
                .movePointLeft(CobolDecimal.MONETARY_SCALE)
                .divide(BigDecimal.valueOf(2));
        assertThat(discarded)
                .describedAs("the discarded remainder %s must exceed half of the last retained digit's "
                        + "place value %s, because that is the only region where the two rules disagree "
                        + "- a smaller remainder would leave case02 green under a wrong implementation",
                        discarded, halfOfLastPlace)
                .isGreaterThan(halfOfLastPlace);
    }

    @Test
    @DisplayName(":224 moves S9(10)V99 into S9(09)V99 and the high-order digit is what falls off")
    void theBalanceMoveIntoTranAmtDiscardsTheHighOrderDigit() {
        BigDecimal balance = new BigDecimal(MAX_BALANCE);

        BigDecimal moved = CobolDecimal.storeAtPicture(balance, TranRecord.TRAN_AMT_INTEGER_DIGITS,
                TranRecord.TRAN_AMT_SCALE);

        assertThat(moved)
                .describedAs("TRAN-AMT holds %d integer digits against ACCT-CURR-BAL's %d, so the "
                        + "leading digit of %s is the one with nowhere to go",
                        TranRecord.TRAN_AMT_INTEGER_DIGITS, AccountRecord.MONETARY_INTEGER_DIGITS,
                        MAX_BALANCE)
                .isEqualByComparingTo(new BigDecimal(MAX_PAYMENT));
        assertThat(balance.subtract(moved))
                .describedAs("the discarded leading digit is worth ten to the power of the receiver's "
                        + "integer width, which is what leaves the account holding a balance rather "
                        + "than settling it")
                .isEqualByComparingTo(new BigDecimal(MAX_DEBITED_BALANCE));
        assertThat(CobolDecimal.subtract(balance, moved, CobolDecimal.MONETARY_SCALE))
                .describedAs(":234 computes ACCT-CURR-BAL - TRAN-AMT from that pair, which is what "
                        + "case03 pins in the rewritten record")
                .isEqualByComparingTo(new BigDecimal(MAX_DEBITED_BALANCE));
        assertThat(TranRecord.TRAN_AMT_INTEGER_DIGITS)
                .describedAs("the widths must actually differ, or this whole test asserts nothing")
                .isLessThan(AccountRecord.MONETARY_INTEGER_DIGITS);
    }

    @Test
    @DisplayName("the rewritten CVACT01Y record is 300 bytes with FILLER emitted and EXPIRAION intact")
    void theRewrittenAccountIsThreeHundredBytesAndKeepsTheMisspelling() {
        String image = accountImage(SETTLED_BALANCE);

        assertThat(image.length())
                .describedAs("twelve named spans of 122 bytes plus FILLER X(178); without the FILLER "
                        + "this is 122 and every downstream offset is wrong")
                .isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(image.substring(AccountRecord.FILLER_OFFSET))
                .describedAs("FILLER is emitted as spaces, not omitted and not zero-filled")
                .isEqualTo(" ".repeat(AccountRecord.FILLER_LENGTH));

        AccountRecord decoded = AccountRecord.decode(image, CHARSET);

        assertThat(decoded.recordLength())
                .describedAs("the production record type agrees on the width")
                .isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(decoded.toFixedWidthString())
                .describedAs("decode then encode is byte-identical, which catches a mis-stated offset "
                        + "as well as a missing span")
                .isEqualTo(image);
        assertThat(decoded.getAcctCurrBal())
                .describedAs("the balance reads back at the picture's scale from the zoned image")
                .isEqualByComparingTo(new BigDecimal(SETTLED_BALANCE));

        assertThat(AccountRecord.ACCT_EXPIRAION_DATE_NAME)
                .describedAs("CVACT01Y.cpy:L11 spells it EXPIRAION, and a field-for-field diff compares "
                        + "by name - correcting the spelling would manufacture the failure it looked "
                        + "like it was reporting")
                .isEqualTo("ACCT-EXPIRAION-DATE");
        assertThat(decoded.rawAcctExpiraionDate())
                .describedAs("byte 58 for 10 bytes is the expiration date the fixture row carries")
                .isEqualTo(EXPIRATION_DATE);
        RecordLayout layout = AccountRecord.LAYOUT;
        assertThat(layout.recordLength())
                .describedAs("the layout the codec serialises through declares the same width")
                .isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(layout.spans())
                .describedAs("thirteen spans - the twelve named items and the FILLER. A layout of "
                        + "twelve would be a record with a 178-byte hole in it")
                .hasSize(13)
                .contains(AccountRecord.SPAN_ACCT_EXPIRAION_DATE, AccountRecord.SPAN_FILLER);
    }

    @Test
    @DisplayName("the added CVTRA05Y record is 350 bytes with FILLER emitted and TRAN-AMT at byte 132")
    void theAddedTransactionIsThreeHundredAndFiftyBytesWithItsFillerEmitted() {
        String image = transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE, CARD_NUMBER);

        assertThat(image.length())
                .describedAs("thirteen named spans of 330 bytes plus FILLER X(20), which "
                        + "app/jcl/INTCALC.jcl attests again as LRECL=350")
                .isEqualTo(TranRecord.RECORD_LENGTH);
        assertThat(image.substring(TranRecord.FILLER_OFFSET))
                .describedAs("FILLER is emitted as spaces")
                .isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        assertThat(image.substring(TranRecord.TRAN_AMT.offset(),
                        TranRecord.TRAN_AMT.offset() + TranRecord.TRAN_AMT_LENGTH))
                .describedAs("TRAN-AMT is a %d-byte zoned span at byte %d, and the fixture balance "
                        + "%s encodes with a positive overpunch in its low-order position",
                        TranRecord.TRAN_AMT_LENGTH, TranRecord.TRAN_AMT.offset(), FIXTURE_BALANCE)
                .isEqualTo(zoned(FIXTURE_BALANCE, TranRecord.TRAN_AMT_INTEGER_DIGITS,
                        TranRecord.TRAN_AMT_SCALE));

        TranRecord decoded = TranRecord.decode(image, CHARSET);

        assertThat(decoded.displayImage())
                .describedAs("decode then re-render is byte-identical")
                .isEqualTo(image);
        assertThat(decoded.tranAmt())
                .describedAs("the amount reads back at scale %d", TranRecord.TRAN_AMT_SCALE)
                .isEqualByComparingTo(new BigDecimal(FIXTURE_BALANCE));
        RecordLayout layout = TranRecord.LAYOUT;
        assertThat(layout.recordLength())
                .describedAs("the layout declares the same width")
                .isEqualTo(TranRecord.RECORD_LENGTH);
        assertThat(layout.spans())
                .describedAs("fourteen spans - the thirteen named items and the FILLER")
                .hasSize(14)
                .contains(TranRecord.TRAN_AMT, TranRecord.FILLER);
        assertThat(TranRecord.sumOfDeclaredSpanLengths())
                .describedAs("summing the declared span lengths independently of RECORD_LENGTH proves "
                        + "the fourteen spans really do account for every byte")
                .isEqualTo(TranRecord.RECORD_LENGTH);
    }

    @Test
    @DisplayName("the transcribed COTTL01Y titles are 40 bytes each and say what the copybook says")
    void theTranscribedTitlesAreAtTheirDeclaredWidth() {
        assertThat(TITLE01.length())
                .describedAs("CCDA-TITLE01 is PIC X(40) and the map's TITLE01I is X(40) to match")
                .isEqualTo(BillPaymentResponse.TITLE01_LENGTH);
        assertThat(TITLE02.length())
                .describedAs("CCDA-TITLE02 is PIC X(40)")
                .isEqualTo(BillPaymentResponse.TITLE02_LENGTH);
        assertThat(TITLE01.trim())
                .describedAs("the words of COTTL01Y.cpy:19, stated independently of the padding so a "
                        + "miscounted space and a mistyped word fail as separate assertions")
                .isEqualTo("AWS Mainframe Modernization");
        assertThat(TITLE01.indexOf('A'))
                .describedAs("six leading spaces before the first word, per the copybook literal")
                .isEqualTo(6);
        assertThat(TITLE02.trim())
                .describedAs("the words of COTTL01Y.cpy:22. Line 21 sits between the declaration and "
                        + "this value and is a comment holding an abandoned earlier wording, which is "
                        + "deliberately not the value")
                .isEqualTo("CardDemo");
        assertThat(TITLE02.indexOf('C'))
                .describedAs("fourteen leading spaces before CardDemo, per the copybook literal")
                .isEqualTo(14);
        assertThat(expectedCurrentDate().length())
                .describedAs("CURDATEI is X(8): mm/dd/yy, from :328-332")
                .isEqualTo(BillPaymentResponse.CUR_DATE_LENGTH);
        assertThat(expectedCurrentTime().length())
                .describedAs("CURTIMEI is X(8) on this map - hh:mm:ss, from :334-338. The sibling "
                        + "COSGN00 map declares nine, and the two must not be conflated")
                .isEqualTo(BillPaymentResponse.CUR_TIME_LENGTH);
    }

    private static BillPaymentService probeService(AccountRepository.ReadResult read,
                                                  AccountRepository.WriteResult write,
                                                  CardXrefRepository.ReadResult xref,
                                                  TransactionRepository.ReadResult prev) {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.datasetCharset()).thenReturn(CHARSET);
        when(accounts.readForUpdate(anyString())).thenReturn(read);
        when(accounts.rewrite(any())).thenReturn(write);

        CardXrefRepository crossReference = mock(CardXrefRepository.class);
        when(crossReference.readByAccountIdViaAltIndex(anyString())).thenReturn(xref);

        TransactionRepository.Browse browse = positionedBrowse();
        when(browse.readPrev()).thenReturn(prev);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.startBrowse(BrowseDirection.BACKWARD)).thenReturn(browse);
        when(transactions.write(any()))
                .thenReturn(TransactionRepository.WriteResult.written(TRANSACT));

        return new BillPaymentService(accounts, crossReference, transactions, CLOCK,
                privateUnitOfWork());
    }

    private static BillPaymentService probeServiceOnFixtureData() {
        String storedXref = new FixedWidthCodec(CHARSET)
                .padToDeclaredWidth(XREF_ROW_AS_STORED, CardXrefRecord.RECORD_LENGTH);
        return probeService(
                AccountRepository.ReadResult.found(
                        AccountRecord.decode(accountImage(FIXTURE_BALANCE), CHARSET)),
                AccountRepository.WriteResult.written(),
                CardXrefRepository.ReadResult.found(CXACAIX,
                        CardXrefRecord.decode(storedXref.getBytes(CHARSET), CHARSET), storedXref),
                TransactionRepository.ReadResult.found(TRANSACT,
                        TranRecord.decode(seededTransactionRow(HIGHEST_TRAN_ID), CHARSET)));
    }

    private static final String XREF_ROW_AS_STORED =
            CARD_NUMBER + zeroFilled(CUSTOMER_ID, CardXrefRecord.XREF_CUST_ID_LENGTH) + ACCOUNT_ID;

    @Test
    @DisplayName("no server-side state survives an invocation, so a repeat is byte-identical")
    void noServerSideStateSurvivesBetweenInvocations() {
        BillPaymentRequest firstEntry = new BillPaymentRequest();
        firstEntry.setNavigationContext(NavigationContext.empty());

        ObservedResponse fromOne = paintedBy(new BillPaymentController(
                probeServiceOnFixtureData(), CLOCK), firstEntry);
        ObservedResponse fromAnother = paintedBy(new BillPaymentController(
                probeServiceOnFixtureData(), CLOCK), firstEntry);

        assertThat(fromAnother)
                .describedAs("two independently constructed controllers must agree, or something is "
                        + "shared between them - and COBOL WORKING-STORAGE must never become a static "
                        + "Java field, because that is precisely a shared residue")
                .isEqualTo(fromOne);

        BillPaymentController reused = new BillPaymentController(probeServiceOnFixtureData(), CLOCK);
        assertThat(paintedBy(reused, firstEntry))
                .describedAs("one controller called twice with the same request must answer the same "
                        + "way twice, field for field")
                .isEqualTo(paintedBy(reused, firstEntry));

        BillPaymentRequest reentry = new BillPaymentRequest();
        reentry.setNavigationContext(NavigationContext.empty().withPgmReenter());
        reentry.setAid(aidTokenOf(AID_ENTER));
        reentry.setActIdIn(ACCOUNT_ID);
        reentry.setConfirm(CONFIRM_YES);

        assertThat(paintedBy(reused, reentry))
                .describedAs("the second request must be answered as though the first had never "
                        + "happened; this is the assertion the other two cannot make")
                .isEqualTo(paintedBy(new BillPaymentController(probeServiceOnFixtureData(), CLOCK),
                        reentry));
    }

    private static ObservedResponse paintedBy(BillPaymentController controller,
                                              BillPaymentRequest request) {
        return observedControllerResponse(controller.payBill(request, null, null).screen());
    }

    @Test
    @DisplayName("the shared PfKeyResolver reproduces this program's inline EIBAID tests exactly")
    void everyAidThisScreenTestsResolvesToTheSameBooleanTheCobolWould() {
        assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER))
                .describedAs(":126 WHEN DFHENTER performs PROCESS-ENTER-KEY")
                .isTrue();
        assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3))
                .describedAs(":128 WHEN DFHPF3 returns to the previous screen")
                .isTrue();
        assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4))
                .describedAs(":136 WHEN DFHPF4 clears the current screen")
                .isTrue();

        for (byte notAnArm : new byte[] {CicsAid.DFHPF5, CicsAid.DFHPA1, CicsAid.DFHPA2,
                CicsAid.DFHCLEAR, CicsAid.DFHNULL}) {
            assertThat(PfKeyResolver.isEnter(notAnArm) || PfKeyResolver.isPf3(notAnArm)
                    || PfKeyResolver.isPf4(notAnArm))
                    .describedAs("AID 0x%02X matches none of this program's three named arms, so it "
                            + "reaches WHEN OTHER at :138 and the screen answers with the standard "
                            + "invalid-key sentence", notAnArm)
                    .isFalse();
        }

        assertThat(aidTokenOf(AID_ENTER))
                .describedAs("the payload image for the ENTER arm is the byte :126 compares")
                .isEqualTo(PfKeyResolver.aidImage(CicsAid.DFHENTER));
        assertThat(aidTokenOf(AID_PF3))
                .describedAs("the payload image for the PF3 arm is the byte :128 compares")
                .isEqualTo(PfKeyResolver.aidImage(CicsAid.DFHPF3));
        assertThat(aidTokenOf(AID_PF4))
                .describedAs("the payload image for the PF4 arm is the byte :136 compares")
                .isEqualTo(PfKeyResolver.aidImage(CicsAid.DFHPF4));
        assertThat(aidTokenOf(AID_NO_MATCH))
                .describedAs("PF5 arrives as itself, and is none of the three bytes the arms name")
                .isEqualTo(PfKeyResolver.aidImage(CicsAid.DFHPF5));

        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13).orElseThrow())
                .describedAs("CSSTRPFY.cpy:L54-55 folds DFHPF13 back onto PFK01, and the tokens are "
                        + "enum singletons, so the fold is observable by identity - reading PF13 as an "
                        + "unrecognised key would silently lose a whole bank of function keys")
                .isSameAs(PfKeyResolver.resolve(CicsAid.DFHPF1).orElseThrow());
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15).orElseThrow())
                .describedAs("and PF15 folds onto PF3's token, so a terminal sending PF15 reaches this "
                        + "program's PF3 arm at :128")
                .isSameAs(PfKeyResolver.resolve(CicsAid.DFHPF3).orElseThrow());

        Optional<AidKey> untestedByTheCopybook = PfKeyResolver.resolve(CicsAid.DFHPA3);
        assertThat(untestedByTheCopybook)
                .describedAs("the paragraph names PA1 and PA2 and stops, so DFHPA3 matches no WHEN at "
                        + "all")
                .isEmpty();
        assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL))
                .describedAs("DFHNULL is likewise untested by the copybook, and is what the controller "
                        + "reads an absent payload token as")
                .isEmpty();
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.of(AidKey.PFK03)))
                .describedAs("the EVALUATE has no WHEN OTHER and does not clear CCARD-AID first, so an "
                        + "unmatched AID leaves the previous interaction's token standing - the most "
                        + "easily lost property of the whole paragraph")
                .contains(AidKey.PFK03);
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, untestedByTheCopybook))
                .describedAs("and with nothing recorded beforehand there is nothing to retain")
                .isEmpty();
    }

    @Test
    @DisplayName("the payload is exactly the ten xxxI items of COBIL00.CPY at their declared widths")
    void theTenPayloadFieldsAreTheSymbolicMapsInputItemsAtTheirWidths() {
        ScreenSend painted = paintedScreen(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM,
                " ".repeat(BillPaymentResponse.ERR_MSG_LENGTH));

        assertThat(painted.fields())
                .describedAs("ten DFHMDF definitions, ten payload fields - no more, and none of the "
                        + "xxxL, xxxF or xxxA metadata items among them")
                .hasSize(BillPaymentResponse.MAP_FIELD_COUNT)
                .containsOnlyKeys("TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O",
                        "CURTIMEO", "ACTIDINO", "CURBALO", "CONFIRMO", "ERRMSGO");

        Map<String, Integer> declaredWidths = new LinkedHashMap<>();
        declaredWidths.put("TRNNAMEO", BillPaymentResponse.TRN_NAME_LENGTH);
        declaredWidths.put("TITLE01O", BillPaymentResponse.TITLE01_LENGTH);
        declaredWidths.put("CURDATEO", BillPaymentResponse.CUR_DATE_LENGTH);
        declaredWidths.put("PGMNAMEO", BillPaymentResponse.PGM_NAME_LENGTH);
        declaredWidths.put("TITLE02O", BillPaymentResponse.TITLE02_LENGTH);
        declaredWidths.put("CURTIMEO", BillPaymentResponse.CUR_TIME_LENGTH);
        declaredWidths.put("ACTIDINO", BillPaymentResponse.ACT_ID_IN_LENGTH);
        declaredWidths.put("CURBALO", BillPaymentResponse.CUR_BAL_LENGTH);
        declaredWidths.put("CONFIRMO", BillPaymentResponse.CONFIRM_LENGTH);
        declaredWidths.put("ERRMSGO", BillPaymentResponse.ERR_MSG_LENGTH);

        int total = 0;
        for (Map.Entry<String, Integer> declared : declaredWidths.entrySet()) {
            assertThat(painted.fields().get(declared.getKey()).length())
                    .describedAs("%s is PIC X(%d) in app/cpy-bms/COBIL00.CPY and is compared at full "
                            + "width, never trimmed", declared.getKey(), declared.getValue())
                    .isEqualTo(declared.getValue());
            total += declared.getValue();
        }
        assertThat(total)
                .describedAs("the ten widths sum to the map's declared data length")
                .isEqualTo(BillPaymentResponse.MAP_DATA_LENGTH);

        assertThat(painted.attributes())
                .describedAs("a painted screen carries no attribute assignment unless the success arm "
                        + "coloured the message; that is asserted separately")
                .isEmpty();
    }

    @Test
    @DisplayName("CXACAIX is an alternate-index finder over the CCXREF cluster, not a second table")
    void theAlternateIndexIsAFinderOnTheBaseCluster() {
        assertThat(CXACAIX)
                .describedAs("this program's CICS file literal at :412 is the path, not the base")
                .isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME)
                .isNotEqualTo(CardXrefRepository.BASE_DD_NAME);
        assertThat(CardXrefRepository.RECORD_LENGTH)
                .describedAs("a path over a cluster reads the cluster's own records, so the length is "
                        + "CVACT03Y's fifty bytes on both DD names")
                .isEqualTo(CardXrefRecord.RECORD_LENGTH);
        assertThat(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD)
                .describedAs("the alternate key is XREF-ACCT-ID, which is what 'VIA ACCOUNT KEY' means")
                .isEqualTo(CardXrefRecord.XREF_ACCT_ID_NAME);
        assertThat(CardXrefRepository.ACCOUNT_ID_KEY_LENGTH)
                .describedAs("eleven bytes of account identifier against the primary key's sixteen "
                        + "bytes of card number - two keys, one set of records")
                .isEqualTo(BillPaymentResponse.ACT_ID_IN_LENGTH)
                .isNotEqualTo(CardXrefRepository.CARD_NUMBER_KEY_LENGTH);

        String stored = new FixedWidthCodec(CHARSET)
                .padToDeclaredWidth(XREF_ROW_AS_STORED, CardXrefRecord.RECORD_LENGTH);
        assertThat(stored.length())
                .describedAs("cardxref.txt stores %d bytes where CVACT03Y declares %d, because the "
                        + "fixture omits the trailing FILLER; the row is padded to the declared width "
                        + "before anything decodes it", XREF_ROW_AS_STORED.length(),
                        CardXrefRecord.RECORD_LENGTH)
                .isEqualTo(CardXrefRecord.RECORD_LENGTH);
        assertThat(CardXrefRecord.decode(stored.getBytes(CHARSET), CHARSET).xrefCardNum())
                .describedAs("the card number this payment stamps into TRAN-CARD-NUM comes off the "
                        + "alternate-index read, keyed by account")
                .isEqualTo(CARD_NUMBER);
    }

    @Test
    @DisplayName(":526 is this program's only attribute assignment, and nothing ever clears it")
    void theSuccessArmIsTheOnlyPlaceThisScreenColoursTheMessage() {
        assertThat(BillPaymentService.MESSAGE_HIGHLIGHT_GREEN)
                .describedAs("the production highlight is a single attribute byte")
                .hasSize(BillPaymentResponse.MESSAGE_HIGHLIGHT_LENGTH);
        assertThat(ERRMSGC_GREEN)
                .describedAs("the mnemonic these cases hand the harness must name the attribute the "
                        + "production code assigns, or every green-send expectation asserts nothing")
                .isEqualTo(BmsAttributes.colourMnemonic(
                        (byte) BillPaymentService.MESSAGE_HIGHLIGHT_GREEN.charAt(0)));
        assertThat((byte) BillPaymentService.MESSAGE_HIGHLIGHT_GREEN.charAt(0))
                .describedAs("ERRMSGC is PICTURE X, so the attribute is carried as the one character "
                        + "whose code point is the unsigned value of the DFHBMSCA byte - a hexadecimal "
                        + "rendering would be five characters and would not fit")
                .isEqualTo(BmsAttributes.DFHGREEN);

        String storedXref = new FixedWidthCodec(CHARSET)
                .padToDeclaredWidth(XREF_ROW_AS_STORED, CardXrefRecord.RECORD_LENGTH);
        BillPaymentService rejecting = probeService(
                AccountRepository.ReadResult.notFound(),
                AccountRepository.WriteResult.written(),
                CardXrefRepository.ReadResult.found(CXACAIX,
                        CardXrefRecord.decode(storedXref.getBytes(CHARSET), CHARSET), storedXref),
                TransactionRepository.ReadResult.endOfFile(TRANSACT));

        PaymentState rejected = rejecting.processEnterKey(ACCOUNT_ID, CONFIRM_YES,
                NavigationContext.empty().withPgmReenter());

        assertThat(rejected.messageHighlight())
                .describedAs("a rejection never reaches :526, so it leaves the colour item untouched")
                .isNull();
        assertThat(rejected.errMsg())
                .describedAs("the account read reported NOTFND, which is the arm at :359-364")
                .isEqualTo(picX(BillPaymentService.MSG_ACCOUNT_ID_NOT_FOUND,
                        BillPaymentResponse.ERR_MSG_LENGTH));

        PaymentState paid = probeServiceOnFixtureData().processEnterKey(ACCOUNT_ID, CONFIRM_YES,
                NavigationContext.empty().withPgmReenter());

        assertThat(paid.messageHighlight())
                .describedAs("the WRITE success arm at :526 is the one place the colour is assigned")
                .isEqualTo(BillPaymentService.MESSAGE_HIGHLIGHT_GREEN);
        assertThat(paid.errMsg())
                .describedAs("and the message it colours is the confirmation, naming the identifier "
                        + "the browse computed")
                .isEqualTo(picX(successMessage(NEXT_TRAN_ID), BillPaymentResponse.ERR_MSG_LENGTH));
    }

    @Test
    @DisplayName("all thirteen coded file-operation arms are reachable, including the four data cannot reach")
    void everyArmOfTheFourFileOperationsIsReachable() {
        BillPaymentService service = probeService(
                AccountRepository.ReadResult.found(
                        AccountRecord.decode(accountImage(FIXTURE_BALANCE), CHARSET)),
                AccountRepository.WriteResult.notFound(),
                CardXrefRepository.ReadResult.other(CXACAIX, UNEXPECTED_STATUS),
                TransactionRepository.ReadResult.other(TRANSACT, UNEXPECTED_STATUS));
        FixedWidthCodec codec = service.codec();

        PaymentState rewriteNotFound = new PaymentState(codec, NavigationContext.empty());
        service.updateAcctdatFile(rewriteNotFound);
        assertThat(rewriteNotFound.errMsg())
                .describedAs(":390-395 - the rewrite reported NOTFND, which no arrangement of seeded "
                        + "rows can produce once the read-for-update has already succeeded")
                .isEqualTo(picX(BillPaymentService.MSG_ACCOUNT_ID_NOT_FOUND,
                        BillPaymentResponse.ERR_MSG_LENGTH));
        assertThat(rewriteNotFound.accountRewritten())
                .describedAs("and the account is left unwritten")
                .isFalse();

        PaymentState crossReferenceFailed = new PaymentState(codec, NavigationContext.empty());
        crossReferenceFailed.setXrefAcctIdRidfld(ACCOUNT_ID);
        service.readCxacaixFile(crossReferenceFailed);
        assertThat(crossReferenceFailed.errMsg())
                .describedAs(":429-435 - WHEN OTHER on the alternate-index read")
                .isEqualTo(picX(BillPaymentService.MSG_UNABLE_TO_LOOKUP_XREF_AIX,
                        BillPaymentResponse.ERR_MSG_LENGTH));
        assertThat(crossReferenceFailed.displays())
                .describedAs("and the arm writes the diagnostic before it rejects, which the NOTFND "
                        + "arm does not")
                .hasSize(1);

        PaymentState positionNotFound = new PaymentState(codec, NavigationContext.empty());
        service.startbrTransactFile(positionNotFound, FileStatus.Outcome.NOT_FOUND);
        assertThat(positionNotFound.errMsg())
                .describedAs(":459-464 - WHEN DFHRESP(NOTFND) on the browse position")
                .isEqualTo(picX(BillPaymentService.MSG_TRANSACTION_ID_NOT_FOUND,
                        BillPaymentResponse.ERR_MSG_LENGTH));

        PaymentState positionFailed = new PaymentState(codec, NavigationContext.empty());
        service.startbrTransactFile(positionFailed, FileStatus.Outcome.END_OF_FILE);
        assertThat(positionFailed.errMsg())
                .describedAs("END_OF_FILE has no arm of its own in STARTBR, so it falls to WHEN OTHER "
                        + "at :465-467 - unlike in READPREV, where it does have one")
                .isEqualTo(picX(BillPaymentService.MSG_UNABLE_TO_LOOKUP_TRANSACTION,
                        BillPaymentResponse.ERR_MSG_LENGTH));

        PaymentState readPrevAtEnd = new PaymentState(codec, NavigationContext.empty());
        service.readprevTransactFile(readPrevAtEnd,
                TransactionRepository.ReadResult.endOfFile(TRANSACT));
        assertThat(readPrevAtEnd.errMsg())
                .describedAs(":487-488 - ENDFILE moves zeros to TRAN-ID and raises no flag, no message "
                        + "and no send, which is how the first payment against an empty master gets "
                        + "identifier %s", FIRST_TRAN_ID)
                .isEqualTo(" ".repeat(BillPaymentResponse.ERR_MSG_LENGTH));
        assertThat(readPrevAtEnd.tranIdNum())
                .describedAs("and the identifier the write then increments starts from zero")
                .isZero();

        PaymentState readPrevFailed = new PaymentState(codec, NavigationContext.empty());
        service.readprevTransactFile(readPrevFailed,
                TransactionRepository.ReadResult.other(TRANSACT, UNEXPECTED_STATUS));
        assertThat(readPrevFailed.errMsg())
                .describedAs(":493-499 - WHEN OTHER on the backward read")
                .isEqualTo(picX(BillPaymentService.MSG_UNABLE_TO_LOOKUP_TRANSACTION,
                        BillPaymentResponse.ERR_MSG_LENGTH));
    }

    @Test
    @DisplayName("this program copies no CSSETATY, so no field-level highlight is ever applied")
    void noFieldHighlightIsEverAppliedBecauseThisProgramDoesNotCopyCssetaty() {
        FieldHighlight wouldBeRed = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true);
        FieldHighlight wouldBeRedAndStarred =
                FieldAttributeSetter.resolve(FieldValidationState.BLANK, true);
        FieldHighlight valid = FieldAttributeSetter.resolve(FieldValidationState.OK, true);

        assertThat(wouldBeRed.colourItemAssigned())
                .describedAs("CSSETATY colours a field that failed validation, under re-entry")
                .isTrue();
        assertThat(wouldBeRed.outputItemAssigned())
                .describedAs("but does not star it - the inner test at CSSETATY.cpy:L23 checks "
                        + "blankness only")
                .isFalse();
        assertThat(wouldBeRedAndStarred.outputItemAssigned())
                .describedAs("a blank field satisfies both tests, so it is coloured and starred")
                .isTrue();
        assertThat(valid.colourItemAssigned())
                .describedAs("and a valid field is left completely untouched")
                .isFalse();
        assertThat(FieldAttributeSetter.resolve(FieldValidationState.BLANK, false)
                .colourItemAssigned())
                .describedAs("outside re-entry nothing is highlighted at all, whatever the flags say")
                .isFalse();

        BillPaymentService service = probeServiceOnFixtureData();

        PaymentState blankIdentifier = service.processEnterKey(BLANK_ACCOUNT_ID, BLANK_CONFIRM,
                NavigationContext.empty().withPgmReenter());
        assertThat(blankIdentifier.messageHighlight())
                .describedAs(":159-164 rejects a blank identifier with a sentence and a cursor "
                        + "position, and assigns no attribute - this program has no per-field colour "
                        + "item to write")
                .isNull();
        assertThat(blankIdentifier.cursorField())
                .describedAs("the cursor goes to ACTIDINL, which is the whole of the visual cue")
                .isEqualTo(CursorField.ACTIDIN);

        PaymentState badConfirmation = service.processEnterKey(ACCOUNT_ID, CONFIRM_INVALID,
                NavigationContext.empty().withPgmReenter());
        assertThat(badConfirmation.messageHighlight())
                .describedAs(":185-190, the WHEN OTHER of the EVALUATE CONFIRMI, likewise assigns no "
                        + "attribute")
                .isNull();
        assertThat(badConfirmation.cursorField())
                .describedAs("and positions the cursor on the offending field instead")
                .isEqualTo(CursorField.CONFIRM);
    }

    @Test
    @DisplayName("every fixed-width boundary names its code page rather than inheriting a default")
    void theCodecNamesItsCodePageRatherThanInheritingThePlatformDefault() {
        assertThat(CHARSET)
                .describedAs("the ASCII fixtures are authoritative for this work, and US-ASCII is what "
                        + "the harness seeds them under")
                .isEqualTo(ParityHarness.FIXTURE_CHARSET);
        assertThat(CHARSET_NAME)
                .describedAs("ParityCase accepts only the two code pages this system uses, by name")
                .isEqualTo(CHARSET.name());

        FixedWidthCodec codec = new FixedWidthCodec(CHARSET);
        assertThat(codec.charset())
                .describedAs("the codec carries the code page it was built with")
                .isEqualTo(CHARSET);
        assertThat(probeServiceOnFixtureData().codec().charset())
                .describedAs("and the service's codec takes it from the repository rather than from "
                        + "the platform, so one configuration change moves the whole chain")
                .isEqualTo(CHARSET);
        assertThat(AccountRecord.decode(accountImage(FIXTURE_BALANCE), CHARSET).charset())
                .describedAs("a decoded record remembers the code page it was decoded under")
                .isEqualTo(CHARSET);
        assertThat(codec.decodeImage(codec.encodeImage(XREF_ROW_AS_STORED, "cardxref row"),
                        "cardxref row"))
                .describedAs("encode then decode under a named charset is the identity")
                .isEqualTo(XREF_ROW_AS_STORED);
    }

    @Test
    @DisplayName("datasets are named by DD name; no catalogued data set name appears in Java")
    void theDatasetNamesAreDdNamesRatherThanDataSetNames() {
        for (String ddName : List.of(ACCTDAT, CXACAIX, TRANSACT)) {
            assertThat(ddName)
                    .describedAs("%s must be a DD name; a dotted data set name here would freeze a "
                            + "deployment decision into source", ddName)
                    .doesNotContain(".")
                    .isNotBlank();
            assertThat(ddName.length())
                    .describedAs("%s is a CICS file name, which is at most eight characters", ddName)
                    .isLessThanOrEqualTo(CardXrefRepository.CICS_FILE_NAME_LENGTH);
        }

        assertThat(ACCTDAT)
                .describedAs("taken from the repository, so a rename there fails here rather than "
                        + "diverging quietly")
                .isEqualTo(AccountRepository.CICS_FILE_NAME)
                .isEqualTo(BillPaymentService.WS_ACCTDAT_FILE.trim());
        assertThat(TRANSACT)
                .describedAs("likewise for the transaction file")
                .isEqualTo(TransactionRepository.CICS_FILE_NAME)
                .isEqualTo(BillPaymentService.WS_TRANSACT_FILE.trim());
        assertThat(CXACAIX)
                .describedAs("and for the alternate-index path")
                .isEqualTo(BillPaymentService.WS_CXACAIX_FILE.trim());
    }

    private static TransactionRepository.Browse positionedBrowse() {
        TransactionRepository.Browse handle = mock(TransactionRepository.Browse.class);
        when(handle.positioningResult()).thenReturn(
                TransactionRepository.ReadResult.found(TransactionRepository.CICS_FILE_NAME,
                        new com.vsergeychik.carddemo.transaction.model.TranRecord(
                                java.nio.charset.StandardCharsets.US_ASCII)));
        when(handle.positioningOutcome()).thenReturn(FileStatus.Outcome.OK);
        when(handle.isStarted()).thenReturn(true);
        return handle;
    }

}
