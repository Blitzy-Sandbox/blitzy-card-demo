package com.vsergeychik.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest;
import com.vsergeychik.carddemo.account.dto.AccountUpdateResponse;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.testsupport.ConcurrentTasks;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.testsupport.ConversationStateSealFixture;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link AccountUpdateController} - the {@code COACTUPC} / {@code CAUP} update-account screen.
 */
@DisplayName("AccountUpdateController - COACTUPC / CAUP, the account update screen")
class AccountUpdateControllerTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:32Z"), ZoneOffset.UTC);

    private static final String ACCT = "00000000011";
    private static final String XREF_CARD = "1234567890123456";
    private static final String XREF_IMAGE = " ".repeat(CardXrefRepository.RECORD_LENGTH);
    private static final String CUST_IMAGE = " ".repeat(CustomerRepository.RECORD_LENGTH);
    private static final int CUST_ID = 123456789;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static String at(String value, int length) {
        return CODEC.movePicX(value, length);
    }

    private AccountRepository accounts;
    private CardXrefRepository xrefs;
    private CustomerRepository customers;
    private AccountUpdateService service;
    private AccountDateValidator dates;
    private AreaCodeLookup lookups;
    private AccountUpdateController controller;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        xrefs = mock(CardXrefRepository.class);
        customers = mock(CustomerRepository.class);
        service = mock(AccountUpdateService.class);
        dates = new AccountDateValidator(new FixedWidthCodec(StandardCharsets.US_ASCII),
                new DateUtilityJob(), CLOCK);
        lookups = new AreaCodeLookup(new FixedWidthCodec(StandardCharsets.US_ASCII));
        controller = new AccountUpdateController(accounts, xrefs, customers, service, dates, lookups,
                CLOCK, ConversationStateSealFixture.seal(), StandardCharsets.US_ASCII);
    }

    private AccountUpdateRequest request(String acctsid, NavigationContext context) {
        AccountUpdateRequest received = AccountUpdateRequest.initial()
                .withValue(AccountUpdateRequest.ScreenField.ACCTSID,
                        controller.codec().movePicX(acctsid, AccountUpdateRequest.ACCTSID_LENGTH));
        return context == null ? received : received.withNavigationContext(context);
    }

    private NavigationContext enter() {
        return NavigationContext.empty()
                .withFromTranid("CM00")
                .withFromProgram("COMEN01C")
                .withPgmEnter();
    }

    private NavigationContext reenter() {
        return NavigationContext.empty()
                .withFromTranid("CAUP")
                .withFromProgram("COACTUPC")
                .withPgmReenter()
                .withAcctId(11L)
                .withCustId(CUST_ID);
    }

    private AccountUpdateController.Conversation task(AccountUpdateRequest received, int eibcalen,
            byte aid) {
        AccountUpdateController.Conversation conversation =
                new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
        controller.initializeStorage(received, conversation, eibcalen, aid);
        return conversation;
    }

    private AccountUpdateController.Conversation warmTask() {
        AccountUpdateController.Conversation conversation = task(request(ACCT, reenter()),
                AccountUpdateRequest.CommArea.RECORD_LENGTH + NavigationContext.COMMAREA_LENGTH,
                CicsAid.DFHENTER);
        conversation.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
        conversation.ccWorkArea.setCcAcctId(ACCT);
        return conversation;
    }

    private static AccountRecord account() {
        AccountRecord record = new AccountRecord(StandardCharsets.US_ASCII);
        record.setAcctId(11L);
        record.setAcctActiveStatus("Y");
        record.setAcctCurrBal(new BigDecimal("1234.56"));
        record.setAcctCreditLimit(new BigDecimal("5000.00"));
        record.setAcctCashCreditLimit(new BigDecimal("-250.00"));
        record.setAcctCurrCycCredit(new BigDecimal("0.00"));
        record.setAcctCurrCycDebit(new BigDecimal("87.05"));
        record.setAcctOpenDate("2020-01-15");
        record.setAcctExpiraionDate("2025-01-14");
        record.setAcctReissueDate("2022-06-30");
        record.setAcctGroupId("ZEROPCT");
        return record;
    }

    private static CustomerRecord customer() {
        CustomerRecord record = new CustomerRecord();
        record.setCustId(CUST_ID);
        record.setCustSsn(123456789);
        record.setCustFicoCreditScore(750);
        record.setCustDobYyyyMmDd("1980-02-29");
        record.setCustFirstName("ANNE");
        record.setCustMiddleName("Q");
        record.setCustLastName("ARCHER");
        record.setCustAddrLine1("1 MAIN STREET");
        record.setCustAddrLine2("APT 2B");
        record.setCustAddrLine3("SEATTLE");
        record.setCustAddrStateCd("WA");
        record.setCustAddrZip("98101");
        record.setCustAddrCountryCd("USA");
        record.setCustPhoneNum1("(206)555-1234  ");
        record.setCustPhoneNum2("(206)555-9876  ");
        record.setCustGovtIssuedId("WA-DL-0099887766");
        record.setCustEftAccountId("0000000001");
        record.setCustPriCardHolderInd("Y");
        return record;
    }

    /**
     * Stages a screen the twenty-four edits accept, and leaves it awaiting confirmation.
     *
     * <p>The fetched record echoed back, which is what an operator confirming a change actually sends: the
     * account and customer halves are read, copied from the {@code OLD} groups into the {@code NEW} groups
     * and the five monetary fields are staged at the width
     * {@code 1250-EDIT-SIGNED-9V2} is handed. {@code ACCT-ACTIVE-STATUS} is flipped so
     * {@code 1205-COMPARE-OLD-NEW} finds a change - without one the driver reports
     * {@code NO-CHANGES-FOUND} and no confirmation is possible.
     *
     * <p>Every arm-5 test needs this now that {@code 2000-DECIDE-ACTION} re-runs the edit pass before
     * {@code 9600-WRITE-PROCESSING}: a task whose staged fields are blank is a caller claiming a
     * confirmation for values that were never edited, and it is refused rather than written. Sharing one
     * staging helper means the arm-5 tests and {@code editDriverPromotesOnlyWhenClean} agree on what a valid
     * screen is, instead of each asserting against its own idea of one.
     *
     * @param task the conversation to stage into
     */
    private void stageConfirmableScreen(AccountUpdateController.Conversation task) {
        stubAllFound();
        task.ccWorkArea.setCcAcctId(ACCT);
        controller.readAcct9000(task);
        task.acupNewAcct.fromSnapshot(task.acupOldAcct.toSnapshot());
        task.acupNewCust.fromSnapshot(task.acupOldCust.toSnapshot());
        task.acupNewAcct.activeStatus = "N";
        task.acupNewCreditLimitX = at("5000.00",
                AccountUpdateController.WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCashCreditLimitX = at("-250.00",
                AccountUpdateController.WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrBalX = at("1234.56",
                AccountUpdateController.WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycCreditX = at("0.00",
                AccountUpdateController.WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycDebitX = at("87.05",
                AccountUpdateController.WS_EDIT_SIGNED_NUMBER_LENGTH);
    }

    private void stubAllFound() {
        when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                CardXrefRepository.ReadResult.found(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
        when(accounts.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.found(account()));
        when(customers.readByKey(anyString()))
                .thenReturn(CustomerRepository.ReadResult.found(customer(), CUST_IMAGE));
    }

    @Nested
    @DisplayName("Endpoint parameter resolution")
    class ParameterResolution {
        @Test
        @DisplayName("bind: the path variable is what lands in ACCTSIDI on EVERY turn, so the account "
                + "1100-RECEIVE-MAP reads is always the account the URI names")
        void bindPathVariable() {
            AccountUpdateRequest bound = controller.bind("11", null);
            assertThat(bound.value(AccountUpdateRequest.ScreenField.ACCTSID))
                    .isEqualTo("11         ");
            assertThat(bound.hasNavigationContext()).isFalse();

            AccountUpdateRequest blanked = controller.bind(ACCT, request(" ", reenter()));
            assertThat(blanked.value(AccountUpdateRequest.ScreenField.ACCTSID)).isEqualTo(ACCT);
            assertThat(blanked.hasNavigationContext()).isTrue();

            // And a re-entry naming a DIFFERENT account states its key twice and disagrees with itself.
            // Honouring it let a body aim this write route at another account/customer pair; overwriting
            // it silently discarded the operator's own typed key with no message. Refused, naming the
            // member and echoing neither value.
            ScreenInputRejectedException refusal = catchThrowableOfType(
                    ScreenInputRejectedException.class,
                    () -> controller.bind(ACCT, request("00000000002", reenter())));
            assertThat(refusal).isNotNull();
            assertThat(refusal.member()).contains(AccountUpdateController.ACCTSID_MEMBER);
            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.CONFLICTING_KEY);
            assertThat(refusal.getMessage()).doesNotContain("00000000002");
            assertThat(refusal.publicDetail()).doesNotContain("00000000002");

            // The asterisk COACTUPC reads at :1051 as "not supplied" names no record, so it agrees.
            assertThat(controller.bind(ACCT, request("*", reenter()))
                    .value(AccountUpdateRequest.ScreenField.ACCTSID)).isEqualTo(ACCT);
        }

        @Test
        @DisplayName("bind: an absent state token is the cold start, not a refusal - EIBCALEN = 0 at :880")
        void bindTreatsAnAbsentStateTokenAsAColdStart() {
            AccountUpdateRequest bound = controller.bind(ACCT, request(ACCT, reenter()));

            assertThat(bound.getStateToken()).isEmpty();
            assertThat(bound.getCommArea())
                    .as("INITIALIZE WS-THIS-PROGCOMMAREA, from which ACUP-DETAILS-NOT-FETCHED holds and "
                            + "no write arm is reachable")
                    .isEqualTo(AccountUpdateRequest.CommArea.initialised());
        }

        @Test
        @DisplayName("bind: a state token this screen issued restores WS-THIS-PROGCOMMAREA whole")
        void bindRestoresAnIssuedStateToken() {
            AccountUpdateRequest.CommArea awaiting = AccountUpdateRequest.CommArea.initialised()
                    .withChangeAction(AccountUpdateRequest.ChangeAction.changesOkNotConfirmed());
            String issued = ConversationStateSealFixture.seal().seal(
                    AccountUpdateController.STATE_PURPOSE, ACCT, awaiting.encode(CODEC));

            AccountUpdateRequest bound = controller.bind(ACCT,
                    request(ACCT, reenter()).withStateToken(issued));

            assertThat(bound.getCommArea()).isEqualTo(awaiting);
        }

        @Test
        @DisplayName("bind: a forged state token is refused, and no value is echoed")
        void bindRefusesAForgedStateToken() {
            ScreenInputRejectedException refusal = catchThrowableOfType(
                    ScreenInputRejectedException.class,
                    () -> controller.bind(ACCT, request(ACCT, reenter())
                            .withStateToken("bm90LWEtdG9rZW4tYXQtYWxs")));

            assertThat(refusal).isNotNull();
            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.UNAUTHENTIC_STATE);
            assertThat(refusal.member()).contains(AccountUpdateController.STATE_TOKEN_MEMBER);
            assertThat(refusal.publicDetail()).doesNotContain("bm90LWEtdG9rZW4tYXQtYWxs");
        }

        @Test
        @DisplayName("bind: a state token issued for another account is refused by this URI")
        void bindRefusesAStateTokenIssuedForAnotherAccount() {
            // The replay this closes: the operator's own confirmation for account 11, presented against
            // the URI of account 99. Both are real tokens; only one of them is this resource's.
            String forAnotherAccount = ConversationStateSealFixture.seal().seal(
                    AccountUpdateController.STATE_PURPOSE, ACCT,
                    AccountUpdateRequest.CommArea.initialised()
                            .withChangeAction(AccountUpdateRequest.ChangeAction.changesOkNotConfirmed())
                            .encode(CODEC));

            ScreenInputRejectedException refusal = catchThrowableOfType(
                    ScreenInputRejectedException.class,
                    () -> controller.bind("00000000099", request("00000000099", reenter())
                            .withStateToken(forAnotherAccount)));

            assertThat(refusal).isNotNull();
            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.STATE_NAMES_ANOTHER_RECORD);
            assertThat(refusal.publicDetail()).doesNotContain(ACCT).doesNotContain("00000000099");
        }

        @Test
        @DisplayName("the response publishes the sealed area and not the structured one - the change "
                + "action is not a member a caller can write")
        void theResponsePublishesTheSealedAreaOnly() throws Exception {
            stubAllFound();
            ObjectMapper mapper = new ObjectMapper();

            AccountUpdateResponse screen = controller.updateAccount(ACCT, request(ACCT, reenter()),
                    null, null, null).getBody().screen();
            JsonNode body = mapper.readTree(mapper.writeValueAsString(screen));

            assertThat(body.has("stateToken")).isTrue();
            assertThat(body.has("commArea"))
                    .as("its first byte is ACUP-CHANGE-ACTION, which records that the twenty-four edits "
                            + "already passed")
                    .isFalse();
            // And the token really carries the area: sealing is not dropping.
            assertThat(AccountUpdateRequest.CommArea.decode(
                    ConversationStateSealFixture.seal().unseal(
                            AccountUpdateController.STATE_TOKEN_MEMBER,
                            AccountUpdateController.STATE_PURPOSE, ACCT, screen.getStateToken()),
                    CODEC))
                    .isEqualTo(screen.getCommArea());
        }

        @Test
        @DisplayName("bind: a path variable wider than ACCTSID X(11) is rejected, never truncated")
        void bindRejectsOverlongPathVariable() {
            assertThatThrownBy(() -> controller.bind("123456789012", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ACCTSIDI PIC X(11)");
        }

        @Test
        @DisplayName("EIBCALEN: derived when absent, and every real length preserved as it arrived")
        void eibcalenResolution() {
            AccountUpdateRequest cold = request(ACCT, null);
            AccountUpdateRequest warm = request(ACCT, reenter());
            int passed = NavigationContext.COMMAREA_LENGTH
                    + AccountUpdateRequest.CommArea.RECORD_LENGTH;

            assertThat(AccountUpdateController.resolveEibcalen(null, cold)).isZero();
            assertThat(AccountUpdateController.resolveEibcalen(null, warm)).isEqualTo(passed);
            assertThat(AccountUpdateController.resolveEibcalen(0, cold)).isZero();

            assertThat(AccountUpdateController.resolveEibcalen(NavigationContext.COMMAREA_LENGTH,
                    warm)).isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(AccountUpdateController.resolveEibcalen(
                    AccountUpdateController.WS_COMMAREA_LENGTH, warm))
                    .isEqualTo(AccountUpdateController.WS_COMMAREA_LENGTH);
            assertThat(AccountUpdateController.resolveEibcalen(passed, warm)).isEqualTo(passed);
        }

        @Test
        @DisplayName("EIBCALEN: only two statements are impossible - a negative length, and one that "
                + "contradicts what the payload actually carried")
        void eibcalenRejections() {
            AccountUpdateRequest cold = request(ACCT, null);
            AccountUpdateRequest warm = request(ACCT, reenter());

            assertThat(AccountUpdateController.resolveEibcalen(99, warm)).isEqualTo(99);

            assertThatThrownBy(() -> AccountUpdateController.resolveEibcalen(-1, cold))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be negative");
            assertThatThrownBy(() -> AccountUpdateController.resolveEibcalen(0, warm))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AccountUpdateController.resolveEibcalen(
                    NavigationContext.COMMAREA_LENGTH, cold))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("EIBAID: absent means ENTER; 0 to 255 pass through; anything else is rejected")
        void aidResolution() {
            assertThat(AccountUpdateController.resolveAttentionIdentifier(null))
                    .isEqualTo(CicsAid.DFHENTER);
            assertThat(AccountUpdateController.resolveAttentionIdentifier(0)).isZero();
            assertThat(AccountUpdateController.resolveAttentionIdentifier(255))
                    .isEqualTo((byte) 255);
            assertThat(AccountUpdateController.resolveAttentionIdentifier(
                    Byte.toUnsignedInt(CicsAid.DFHPF5))).isEqualTo(CicsAid.DFHPF5);
            assertThatThrownBy(() -> AccountUpdateController.resolveAttentionIdentifier(-1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AccountUpdateController.resolveAttentionIdentifier(256))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("EIBAID: the canonical name wins over the alias, and they may not disagree")
        void aidParameterAliasing() {
            assertThat(AccountUpdateController.resolveAidParameter(null, null)).isNull();
            assertThat(AccountUpdateController.resolveAidParameter(7, null)).isEqualTo(7);
            assertThat(AccountUpdateController.resolveAidParameter(null, 7)).isEqualTo(7);
            assertThat(AccountUpdateController.resolveAidParameter(7, 7)).isEqualTo(7);
            assertThatThrownBy(() -> AccountUpdateController.resolveAidParameter(7, 8))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("PICTURE primitives")
    class Primitives {
        @Test
        @DisplayName("isAll: a figurative constant extends to the item's length, and an empty item matches")
        void isAllFigurativeConstant() {
            assertThat(AccountUpdateController.isAll("   ", ' ')).isTrue();
            assertThat(AccountUpdateController.isAll("  x", ' ')).isFalse();
            assertThat(AccountUpdateController.isAll("", ' ')).isTrue();
            assertThat(AccountUpdateController.isAll("\u0000\u0000", '\u0000')).isTrue();
            assertThat(AccountUpdateController.isAll("000", '0')).isTrue();
        }

        @Test
        @DisplayName("trimmedLength: spaces trim, LOW-VALUES do not - which is why both arms exist")
        void trimmedLengthCountsOnlySpaces() {
            assertThat(AccountUpdateController.trimmedLength("     ")).isZero();
            assertThat(AccountUpdateController.trimmedLength("  AB ")).isEqualTo(2);
            assertThat(AccountUpdateController.trimmedLength("\u0000\u0000")).isEqualTo(2);
            assertThat(AccountUpdateController.trimmedLength("")).isZero();
            assertThat(AccountUpdateController.trim("  AB  ")).isEqualTo("AB");
            assertThat(AccountUpdateController.trim("    ")).isEmpty();
        }

        @Test
        @DisplayName("windowNotSupplied: LOW-VALUES, SPACES and a window that trims away")
        void windowNotSuppliedArms() {
            assertThat(AccountUpdateController.windowNotSupplied("\u0000\u0000\u0000")).isTrue();
            assertThat(AccountUpdateController.windowNotSupplied("   ")).isTrue();
            assertThat(AccountUpdateController.windowNotSupplied("")).isTrue();
            assertThat(AccountUpdateController.windowNotSupplied(" A ")).isFalse();
            assertThat(AccountUpdateController.windowNotSupplied("\u0000 \u0000")).isFalse();
        }

        @Test
        @DisplayName("INSPECT CONVERTING blanks every character of the FROM operand and nothing else")
        void inspectConverting() {
            assertThat(AccountUpdateController.inspectConverting("AbC1 ",
                    AccountUpdateController.LIT_ALL_ALPHA_FROM_X)).isEqualTo("   1 ");
            assertThat(AccountUpdateController.inspectConverting("AbC1 ",
                    AccountUpdateController.LIT_ALL_ALPHANUM_FROM_X)).isEqualTo("     ");
            assertThat(AccountUpdateController.inspectConverting("A-B",
                    AccountUpdateController.LIT_ALL_ALPHA_FROM_X)).isEqualTo(" - ");
            assertThat(AccountUpdateController.inspectConverting("A-B",
                    AccountUpdateController.LIT_ALL_ALPHANUM_FROM_X)).isEqualTo(" - ");
            assertThat(AccountUpdateController.inspectConverting("",
                    AccountUpdateController.LIT_ALL_ALPHA_FROM_X)).isEmpty();
        }

        @Test
        @DisplayName("The two FROM operands are the 52- and 62-character literals CSSETATY-era code uses")
        void figurativeFromOperands() {
            assertThat(AccountUpdateController.LIT_ALL_ALPHA_FROM_X).hasSize(52)
                    .isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");
            assertThat(AccountUpdateController.LIT_ALL_ALPHANUM_FROM_X).hasSize(62)
                    .startsWith(AccountUpdateController.LIT_ALL_ALPHA_FROM_X)
                    .endsWith("0123456789");
        }

        @Test
        @DisplayName("IS NUMERIC on a PIC X item: digits only, and never an empty item")
        void classConditionNumeric() {
            assertThat(AccountUpdateController.isNumericPicX("00123")).isTrue();
            assertThat(AccountUpdateController.isNumericPicX("1 3")).isFalse();
            assertThat(AccountUpdateController.isNumericPicX("-12")).isFalse();
            assertThat(AccountUpdateController.isNumericPicX("1.2")).isFalse();
            assertThat(AccountUpdateController.isNumericPicX("12A")).isFalse();
            assertThat(AccountUpdateController.isNumericPicX("")).isFalse();
        }

        @Test
        @DisplayName("FUNCTION NUMVAL = 0 on an all-digit window")
        void numvalZero() {
            assertThat(AccountUpdateController.numvalIsZero("000")).isTrue();
            assertThat(AccountUpdateController.numvalIsZero("001")).isFalse();
            assertThat(AccountUpdateController.numvalIsZero("0".repeat(40))).isTrue();
        }

        @Test
        @DisplayName("UPPER-CASE and UPPER-CASE(TRIM()) - the two comparison disciplines of 1205")
        void caseFolding() {
            assertThat(AccountUpdateController.upperCase(" y ")).isEqualTo(" Y ");
            assertThat(AccountUpdateController.upperTrim(" ab ")).isEqualTo("AB");
            assertThat(AccountUpdateController.upperTrim("   ")).isEmpty();
        }

        @Test
        @DisplayName("Reference modification: slice and splice over a fixed span")
        void sliceAndSplice() {
            assertThat(AccountUpdateController.slice("20200115", 0, 4)).isEqualTo("2020");
            assertThat(AccountUpdateController.splice("20200115", 4, 2, "12"))
                    .isEqualTo("20201215");
            assertThat(AccountUpdateController.spaces(3)).isEqualTo("   ");
            assertThat(AccountUpdateController.lowValues(2)).isEqualTo("\u0000\u0000");
        }

        @Test
        @DisplayName("The eight-character date span, read and written a part at a time")
        void datePartAccessors() {
            String date = "20200115";
            assertThat(AccountUpdateController.yearOf(date)).isEqualTo("2020");
            assertThat(AccountUpdateController.monthOf(date)).isEqualTo("01");
            assertThat(AccountUpdateController.dayOf(date)).isEqualTo("15");
            assertThat(AccountUpdateController.withYear(date, "1999")).isEqualTo("19990115");
            assertThat(AccountUpdateController.withMonth(date, "12")).isEqualTo("20201215");
            assertThat(AccountUpdateController.withDay(date, "31")).isEqualTo("20200131");
        }

        @Test
        @DisplayName("The fifteen-character telephone span: parts at offsets 1, 5 and 9")
        void phonePartAccessors() {
            String phone = "(206)555-1234  ";
            assertThat(AccountUpdateController.phoneArea(phone)).isEqualTo("206");
            assertThat(AccountUpdateController.phonePrefix(phone)).isEqualTo("555");
            assertThat(AccountUpdateController.phoneLine(phone)).isEqualTo("1234");
            assertThat(AccountUpdateController.withPhoneArea(phone, "425"))
                    .isEqualTo("(425)555-1234  ");
            assertThat(AccountUpdateController.withPhonePrefix(phone, "867"))
                    .isEqualTo("(206)867-1234  ");
            assertThat(AccountUpdateController.withPhoneLine(phone, "5309"))
                    .isEqualTo("(206)555-5309  ");
            assertThat(AccountUpdateController.withPhoneArea("", "425")).startsWith(" 425");
        }

        @Test
        @DisplayName("The record dates are PIC X(10) YYYY-MM-DD and are sliced (1:4)(6:2)(9:2)")
        void recordDateSlices() {
            assertThat(AccountUpdateController.datePartYear("2020-01-15")).isEqualTo("2020");
            assertThat(AccountUpdateController.datePartMonth("2020-01-15")).isEqualTo("01");
            assertThat(AccountUpdateController.datePartDay("2020-01-15")).isEqualTo("15");
        }

        @Test
        @DisplayName("A store into PIC S9(10)V99 truncates - RoundingMode.DOWN, never HALF_UP")
        void monetaryStoreTruncates() {
            assertThat(AccountUpdateController.storeMonetary(new BigDecimal("1.999")))
                    .isEqualTo(new BigDecimal("1.99"));
            assertThat(AccountUpdateController.storeMonetary(new BigDecimal("-1.999")))
                    .isEqualTo(new BigDecimal("-1.99"));
            assertThat(AccountUpdateController.storeMonetary(null))
                    .isEqualTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("The twelve-character monetary span is a zoned image, and null reads as zero")
        void monetaryImageWidth() {
            assertThat(AccountUpdateController.monetaryImage(new BigDecimal("1234.56")))
                    .hasSize(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            assertThat(AccountUpdateController.monetaryImage(null))
                    .hasSize(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
        }

        @ParameterizedTest(name = "PIC +ZZZ,ZZZ,ZZZ.99 of {0} is \"{1}\"")
        @CsvSource(delimiter = '|', value = {
            "0.00         | '+           .00'",
            "0.07         | '+           .07'",
            "-0.01        | '-           .01'",
            "100.00       | '+        100.00'",
            "1234.56      | '+      1,234.56'",
            "-1234.56     | '-      1,234.56'",
            "1000000.00   | '+  1,000,000.00'",
            "999999999.99 | '+999,999,999.99'",
            "-999999999.99| '-999,999,999.99'",
        })
        @DisplayName("WS-EDIT-CURRENCY-9-2-F: fixed sign, nine suppressed digits, unsuppressed cents")
        void currencyMask(String value, String expected) {
            String rendered = AccountUpdateController.editCurrency92(new BigDecimal(value.trim()));
            assertThat(rendered).hasSize(AccountUpdateController.WS_EDIT_CURRENCY_LENGTH);
            assertThat(rendered).isEqualTo(expected);
        }

        @Test
        @DisplayName("Zero renders with a plus: PIC S9 zero is unsigned, so signum() drives the sign")
        void currencyMaskSignOfZero() {
            assertThat(AccountUpdateController.editCurrency92(new BigDecimal("0.00")))
                    .startsWith("+");
            assertThat(AccountUpdateController.editCurrency92(new BigDecimal("-0.00")))
                    .startsWith("+");
            assertThat(AccountUpdateController.editCurrency92(null)).startsWith("+");
            assertThat(AccountUpdateController.editCurrency92(new BigDecimal("-0.004")))
                    .isEqualTo("+           .00");
        }

        @Test
        @DisplayName("The mask keeps the low-order nine integer digits of a ten-digit sender")
        void currencyMaskTruncatesHighOrderDigit() {
            assertThat(AccountUpdateController.editCurrency92(new BigDecimal("1234567890.12")))
                    .isEqualTo("+234,567,890.12");
        }

        @Test
        @DisplayName("MOVE WS-RESP-CD TO ERROR-RESP: nine digits, then left-justified into PIC X(10)")
        void respStaging() {
            assertThat(AccountUpdateController.respImage(13)).isEqualTo("000000013 ");
            assertThat(AccountUpdateController.respImage(0)).isEqualTo("000000000 ");
            assertThat(AccountUpdateController.respImage(java.util.OptionalInt.empty()))
                    .isEqualTo("000000000 ");
            assertThat(AccountUpdateController.respImage(java.util.OptionalInt.of(13)))
                    .isEqualTo("000000013 ");
        }

        @Test
        @DisplayName("WS-EDIT-VARIABLE-NAME is PIC X(25), so the 26-character name loses its final 't'")
        void editVariableNameTruncates() {
            assertThat(AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_CURRENT_CYCLE_CREDIT_LIMIT))
                    .isEqualTo("Current Cycle Credit Limi");
            assertThat(AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_CURRENT_CYCLE_DEBIT_LIMIT))
                    .isEqualTo("Current Cycle Debit Limit");
            assertThat(AccountUpdateController.editVariableName("Zip"))
                    .isEqualTo("Zip" + " ".repeat(22));
        }

        @Test
        @DisplayName("The staging item is PIC X(256), which is what makes the (1:n) window well defined")
        void stagingItemWidth() {
            assertThat(AccountUpdateController.stagingItem("AB"))
                    .hasSize(AccountUpdateController.WS_EDIT_ALPHANUM_ONLY_LENGTH)
                    .startsWith("AB ");
            assertThat(AccountUpdateController.stagingItem(null))
                    .hasSize(AccountUpdateController.WS_EDIT_ALPHANUM_ONLY_LENGTH);
        }

        @Test
        @DisplayName("The five transcribed field lists still match the COBOL statement groups")
        void transcribedListSizes() {
            assertThat(AccountUpdateController.INITIAL_VALUE_FIELDS)
                    .hasSize(AccountUpdateController.INITIAL_VALUE_FIELD_COUNT);
            assertThat(AccountUpdateController.PROTECTABLE_FIELDS)
                    .hasSize(AccountUpdateController.PROTECTABLE_FIELD_COUNT);
            assertThat(AccountUpdateController.UNPROTECTED_FIELDS)
                    .hasSize(AccountUpdateController.UNPROTECTED_FIELD_COUNT);
            assertThat(AccountUpdateController.CURSOR_ORDER)
                    .hasSize(AccountUpdateController.CURSOR_ARM_COUNT);
            assertThat(AccountUpdateController.HIGHLIGHT_ORDER)
                    .hasSize(AccountUpdateController.HIGHLIGHT_SITE_COUNT);
            assertThat(AccountUpdateController.PROTECTABLE_FIELDS)
                    .contains(AccountUpdateResponse.ScreenField.ACCTSID);
            assertThat(AccountUpdateController.INITIAL_VALUE_FIELDS)
                    .doesNotContain(AccountUpdateResponse.ScreenField.ACCTSID);
            assertThat(AccountUpdateController.HIGHLIGHT_ORDER)
                    .doesNotContain(AccountUpdateResponse.ScreenField.ACCTSID,
                            AccountUpdateResponse.ScreenField.ACSTNUM,
                            AccountUpdateResponse.ScreenField.AADDGRP,
                            AccountUpdateResponse.ScreenField.ACSGOVT);
        }
    }

    private AccountUpdateController.Conversation windowTask(String name, String value, int length) {
        AccountUpdateController.Conversation task = warmTask();
        task.wsEditVariableName = AccountUpdateController.editVariableName(name);
        task.wsEditAlphanumOnly = AccountUpdateController.stagingItem(value);
        task.wsEditAlphanumLength = length;
        return task;
    }

    private static String returnMessageOf(AccountUpdateController.Conversation task) {
        return AccountUpdateController.trim(task.wsReturnMsg);
    }

    @Nested
    @DisplayName("1210-EDIT-ACCOUNT - the account filter")
    class EditAccount1210 {
        @Test
        @DisplayName("Valid: an eleven-digit non-zero key sets ISVALID and publishes CDEMO-ACCT-ID")
        void valid() {
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);
            controller.editAccount1210(task);

            assertThat(task.wsEditAcctFlag)
                    .isEqualTo(AccountUpdateController.FLG_FILTER_ISVALID);
            assertThat(task.flgAcctfilterIsvalid()).isTrue();
            assertThat(task.carddemoCommarea.acctId()).isEqualTo(11L);
            assertThat(task.acupNewAcct.acctIdX).isEqualTo(ACCT);
            assertThat(task.inputError()).isFalse();
            assertThat(task.returnMsgOff()).isTrue();
        }

        @Test
        @DisplayName("Blank: LOW-VALUES takes the first arm, zeroes BOTH receivers and prompts")
        void blankLowValues() {
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctIdToLowValues();
            controller.editAccount1210(task);

            assertThat(task.flgAcctfilterBlank()).isTrue();
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task))
                    .isEqualTo(AccountUpdateController.MSG_PROMPT_FOR_ACCT.trim());
            assertThat(task.carddemoCommarea.acctId()).isZero();
            assertThat(task.acupNewAcct.acctIdN()).isZero();
        }

        @Test
        @DisplayName("Blank: SPACES reaches the same arm through the second condition")
        void blankSpaces() {
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(" ".repeat(AccountUpdateRequest.ACCTSID_LENGTH));
            controller.editAccount1210(task);

            assertThat(task.flgAcctfilterBlank()).isTrue();
            assertThat(task.acupNewAcct.acctIdN()).isZero();
        }

        @Test
        @DisplayName("Not numeric: NOT-OK, and ACUP-NEW-ACCT-ID is deliberately left set")
        void notNumeric() {
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId("0000000001X");
            controller.editAccount1210(task);

            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task)).isEqualTo(
                    (AccountUpdateController.MSG_ACCT_NUMBER_11_DIGIT_A
                            + AccountUpdateController.MSG_ACCT_NUMBER_11_DIGIT_B).trim());
            assertThat(task.carddemoCommarea.acctId()).isZero();
            assertThat(task.acupNewAcct.acctIdX).isEqualTo("0000000001X");
        }

        @Test
        @DisplayName("All zeroes: numeric but zero, so the second condition of the same arm fires")
        void allZeroes() {
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId("00000000000");
            controller.editAccount1210(task);

            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(task.acupNewAcct.acctIdX).isEqualTo("00000000000");
            assertThat(task.carddemoCommarea.acctId()).isZero();
        }
    }

    @Nested
    @DisplayName("1215-EDIT-MANDATORY, 1220-EDIT-YESNO")
    class MandatoryAndYesNo {
        @ParameterizedTest(name = "1215 blank for [{0}]")
        @ValueSource(strings = {"   ", "\u0000\u0000\u0000", ""})
        @DisplayName("1215: LOW-VALUES, SPACES and an empty window all reach the BLANK arm")
        void mandatoryBlank(String value) {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_CITY, value, 3);
            controller.editMandatory1215(task);

            assertThat(task.wsEditMandatoryFlags).isEqualTo(AccountUpdateController.FLG_BLANK);
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task)).isEqualTo("City"
                    + AccountUpdateController.MSG_MUST_BE_SUPPLIED);
        }

        @Test
        @DisplayName("1215: any non-blank window is valid - the paragraph tests presence only")
        void mandatorySupplied() {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_CITY, " X ", 3);
            controller.editMandatory1215(task);

            assertThat(task.wsEditMandatoryFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.inputError()).isFalse();
            assertThat(task.returnMsgOff()).isTrue();
        }

        @ParameterizedTest(name = "1220 accepts [{0}]")
        @ValueSource(strings = {"Y", "N"})
        @DisplayName("1220: the 88 VALUES 'Y','N' pass and the item is left carrying its own value")
        void yesNoValid(String value) {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditYesNo = value;
            controller.editYesno1220(task);

            assertThat(task.wsEditYesNo).isEqualTo(value);
            assertThat(task.inputError()).isFalse();
        }

        @ParameterizedTest(name = "1220 blank for [{0}]")
        @CsvSource(value = {"' '", "'\u0000'", "'0'"}, quoteCharacter = '\'')
        @DisplayName("1220: SPACE, LOW-VALUE and - unusually - ZERO all reach the BLANK arm")
        void yesNoBlank(String value) {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_ACCOUNT_STATUS);
            task.wsEditYesNo = value;
            controller.editYesno1220(task);

            assertThat(task.wsEditYesNo).isEqualTo(AccountUpdateController.FLG_BLANK);
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task)).isEqualTo("Account Status"
                    + AccountUpdateController.MSG_MUST_BE_SUPPLIED);
        }

        @ParameterizedTest(name = "1220 rejects [{0}]")
        @ValueSource(strings = {"y", "n", "X", "1", "*"})
        @DisplayName("1220: anything else is NOT-OK, and the test is case sensitive")
        void yesNoInvalid(String value) {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_ACCOUNT_STATUS);
            task.wsEditYesNo = value;
            controller.editYesno1220(task);

            assertThat(task.wsEditYesNo).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task)).isEqualTo("Account Status"
                    + AccountUpdateController.MSG_MUST_BE_Y_OR_N);
        }

        @Test
        @DisplayName("1220: the NOT-OK flag value '0' is also the BLANK trigger, so order matters")
        void yesNoFlagCollision() {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditYesNo = AccountUpdateController.FLG_NOT_OK;
            controller.editYesno1220(task);

            assertThat(task.wsEditYesNo).isEqualTo(AccountUpdateController.FLG_BLANK);
        }
    }

    @Nested
    @DisplayName("1225 / 1230 / 1235 / 1240 - the alphabetic and alphanumeric edits")
    class AlphaAndAlphanum {
        @Test
        @DisplayName("1225 required: letters pass, a digit fails, and blank is an error")
        void alphaRequired() {
            AccountUpdateController.Conversation ok =
                    windowTask(AccountUpdateController.NAME_FIRST_NAME, "Anne", 4);
            controller.editAlphaReqd1225(ok);
            assertThat(ok.wsEditAlphaOnlyFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(ok.inputError()).isFalse();

            AccountUpdateController.Conversation bad =
                    windowTask(AccountUpdateController.NAME_FIRST_NAME, "Ann3", 4);
            controller.editAlphaReqd1225(bad);
            assertThat(bad.wsEditAlphaOnlyFlags).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(bad)).isEqualTo("First Name"
                    + AccountUpdateController.MSG_ALPHABETS_ONLY);

            AccountUpdateController.Conversation blank =
                    windowTask(AccountUpdateController.NAME_FIRST_NAME, "    ", 4);
            controller.editAlphaReqd1225(blank);
            assertThat(blank.wsEditAlphaOnlyFlags).isEqualTo(AccountUpdateController.FLG_BLANK);
            assertThat(returnMessageOf(blank)).isEqualTo("First Name"
                    + AccountUpdateController.MSG_MUST_BE_SUPPLIED);
        }

        @Test
        @DisplayName("1225: an embedded space is alphabetic-clean, because space is not in the operand")
        void alphaRequiredAllowsEmbeddedSpace() {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_LAST_NAME, "Van Ness", 8);
            controller.editAlphaReqd1225(task);
            assertThat(task.wsEditAlphaOnlyFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);
        }

        @Test
        @DisplayName("1225 writes the converted residue back into the staging item")
        void alphaRequiredWritesResidueBack() {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_FIRST_NAME, "Ann3", 4);
            controller.editAlphaReqd1225(task);
            assertThat(AccountUpdateController.editWindow(task)).isEqualTo("   3");
        }

        @Test
        @DisplayName("1230 required alphanumeric: digits and letters pass, punctuation fails")
        void alphanumRequired() {
            AccountUpdateController.Conversation ok =
                    windowTask(AccountUpdateController.NAME_ADDRESS_LINE_1, "1 MAIN", 6);
            controller.editAlphanumReqd1230(ok);
            assertThat(ok.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);

            AccountUpdateController.Conversation bad =
                    windowTask(AccountUpdateController.NAME_ADDRESS_LINE_1, "1-MAIN", 6);
            controller.editAlphanumReqd1230(bad);
            assertThat(bad.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(bad)).isEqualTo("Address Line 1"
                    + AccountUpdateController.MSG_NUMBERS_OR_ALPHABETS_ONLY);

            AccountUpdateController.Conversation blank =
                    windowTask(AccountUpdateController.NAME_ADDRESS_LINE_1, "      ", 6);
            controller.editAlphanumReqd1230(blank);
            assertThat(blank.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_BLANK);
        }

        @Test
        @DisplayName("1235 optional alphabetic: blank is VALID with no message and no INPUT-ERROR")
        void alphaOptionalBlankIsValid() {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_MIDDLE_NAME, "   ", 3);
            controller.editAlphaOpt1235(task);

            assertThat(task.wsEditAlphaOnlyFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.inputError()).isFalse();
            assertThat(task.returnMsgOff()).isTrue();
        }

        @Test
        @DisplayName("1235 optional: a supplied value is still edited, and a digit still fails")
        void alphaOptionalStillEdits() {
            AccountUpdateController.Conversation ok =
                    windowTask(AccountUpdateController.NAME_MIDDLE_NAME, "Q", 1);
            controller.editAlphaOpt1235(ok);
            assertThat(ok.wsEditAlphaOnlyFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);

            AccountUpdateController.Conversation bad =
                    windowTask(AccountUpdateController.NAME_MIDDLE_NAME, "7", 1);
            controller.editAlphaOpt1235(bad);
            assertThat(bad.wsEditAlphaOnlyFlags).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(bad.inputError()).isTrue();
            assertThat(returnMessageOf(bad)).isEqualTo("Middle Name"
                    + AccountUpdateController.MSG_ALPHABETS_ONLY);
        }

        @Test
        @DisplayName("1240 optional alphanumeric: blank is VALID; punctuation is still rejected")
        void alphanumOptional() {
            AccountUpdateController.Conversation blank =
                    windowTask(AccountUpdateController.NAME_ADDRESS_LINE_1, "  ", 2);
            controller.editAlphanumOpt1240(blank);
            assertThat(blank.wsEditAlphanumOnlyFlags)
                    .isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(blank.inputError()).isFalse();

            AccountUpdateController.Conversation ok =
                    windowTask(AccountUpdateController.NAME_ADDRESS_LINE_1, "A1", 2);
            controller.editAlphanumOpt1240(ok);
            assertThat(ok.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);

            AccountUpdateController.Conversation bad =
                    windowTask(AccountUpdateController.NAME_ADDRESS_LINE_1, "A.", 2);
            controller.editAlphanumOpt1240(bad);
            assertThat(bad.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(bad.inputError()).isTrue();
        }

        @Test
        @DisplayName("1230 and 1240 are translated although this screen never calls them (practice B5)")
        void unreachedParagraphsStillBehave() {
            AccountUpdateController.Conversation reqd =
                    windowTask(AccountUpdateController.NAME_COUNTRY, "US1", 3);
            controller.editAlphanumReqd1230(reqd);
            assertThat(reqd.wsEditAlphanumOnlyFlags)
                    .isEqualTo(AccountUpdateController.FLG_ISVALID);

            AccountUpdateController.Conversation opt =
                    windowTask(AccountUpdateController.NAME_COUNTRY, "US1", 3);
            controller.editAlphanumOpt1240(opt);
            assertThat(opt.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);
        }
    }

    @Nested
    @DisplayName("1245-EDIT-NUM-REQD - three ordered failure arms")
    class EditNumReqd1245 {
        @Test
        @DisplayName("Arm 1, blank: ' must be supplied.'")
        void blank() {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_SSN_FIRST_3, "   ", 3);
            controller.editNumReqd1245(task);

            assertThat(task.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_BLANK);
            assertThat(returnMessageOf(task)).isEqualTo("SSN: First 3 chars"
                    + AccountUpdateController.MSG_MUST_BE_SUPPLIED);
        }

        @Test
        @DisplayName("Arm 2, not numeric: ' must be all numeric.'")
        void notNumeric() {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_SSN_FIRST_3, "12X", 3);
            controller.editNumReqd1245(task);

            assertThat(task.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(task)).isEqualTo("SSN: First 3 chars"
                    + AccountUpdateController.MSG_MUST_BE_ALL_NUMERIC);
        }

        @Test
        @DisplayName("Arm 3, numerically zero: ' must not be zero.'")
        void zero() {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_SSN_FIRST_3, "000", 3);
            controller.editNumReqd1245(task);

            assertThat(task.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(task)).isEqualTo("SSN: First 3 chars"
                    + AccountUpdateController.MSG_MUST_NOT_BE_ZERO);
        }

        @Test
        @DisplayName("Valid: a non-zero all-digit window")
        void valid() {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_SSN_FIRST_3, "123", 3);
            controller.editNumReqd1245(task);

            assertThat(task.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.inputError()).isFalse();
        }

        @Test
        @DisplayName("A leading-zero value is numeric and non-zero, so it passes")
        void leadingZeroPasses() {
            AccountUpdateController.Conversation task =
                    windowTask(AccountUpdateController.NAME_SSN_LAST_4, "0007", 4);
            controller.editNumReqd1245(task);
            assertThat(task.wsEditAlphanumOnlyFlags).isEqualTo(AccountUpdateController.FLG_ISVALID);
        }

        @Test
        @DisplayName("A signed or embedded-space value is not numeric on a PIC X window")
        void signedIsNotNumeric() {
            AccountUpdateController.Conversation minus =
                    windowTask(AccountUpdateController.NAME_ZIP, "-12", 3);
            controller.editNumReqd1245(minus);
            assertThat(returnMessageOf(minus))
                    .endsWith(AccountUpdateController.MSG_MUST_BE_ALL_NUMERIC);

            AccountUpdateController.Conversation gap =
                    windowTask(AccountUpdateController.NAME_ZIP, "1 2", 3);
            controller.editNumReqd1245(gap);
            assertThat(returnMessageOf(gap))
                    .endsWith(AccountUpdateController.MSG_MUST_BE_ALL_NUMERIC);
        }
    }

    @Nested
    @DisplayName("1250-EDIT-SIGNED-9V2 - the gate on the five monetary fields")
    class EditSigned1250 {
        @ParameterizedTest(name = "1250 accepts [{0}]")
        @ValueSource(strings = {
            "1234.56        ", "-1234.56       ", "+1234.56       ",
            "0.00           ", "             12", "999999999.99   ",
        })
        @DisplayName("Conforming: FLG-SIGNED-NUMBER-ISVALID and no message")
        void conforms(String value) {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_CREDIT_LIMIT);
            task.wsEditSignedNumber9v2X = value;
            controller.editSigned9v2At1250(task);

            assertThat(task.wsFlgSignedNumberEdit)
                    .isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.inputError()).isFalse();
            assertThat(task.returnMsgOff()).isTrue();
        }

        @ParameterizedTest(name = "1250 blank for [{0}]")
        @ValueSource(strings = {"               ", "\u0000\u0000\u0000\u0000\u0000"
                + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
        @DisplayName("Blank: SPACES and LOW-VALUES both give BLANK and ' must be supplied.'")
        void blank(String value) {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_CREDIT_LIMIT);
            task.wsEditSignedNumber9v2X = value;
            controller.editSigned9v2At1250(task);

            assertThat(task.wsFlgSignedNumberEdit).isEqualTo(AccountUpdateController.FLG_BLANK);
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task)).isEqualTo("Credit Limit"
                    + AccountUpdateController.MSG_MUST_BE_SUPPLIED);
        }

        @ParameterizedTest(name = "1250 rejects [{0}]")
        @ValueSource(strings = {
            "12.3.4         ", "ABC            ", "1,234.56.7     ", "--12           ",
        })
        @DisplayName("Non-conforming: NOT-OK and ' is not valid' - the site with no END-STRING")
        void doesNotConform(String value) {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_CURRENT_BALANCE);
            task.wsEditSignedNumber9v2X = value;
            controller.editSigned9v2At1250(task);

            assertThat(task.wsFlgSignedNumberEdit).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task)).isEqualTo("Current Balance"
                    + AccountUpdateController.MSG_IS_NOT_VALID);
        }

        @Test
        @DisplayName("The gate and the service's five COMPUTEs agree, because both call testNumvalC")
        void gateAgreesWithService() {
            assertThat(AccountUpdateService.testNumvalC("1234.56        "))
                    .isEqualTo(AccountUpdateService.NUMVAL_CONFORMS);
            assertThat(AccountUpdateService.testNumvalC("12.3.4         "))
                    .isNotEqualTo(AccountUpdateService.NUMVAL_CONFORMS);
        }
    }

    @Nested
    @DisplayName("1260-EDIT-US-PHONE-NUM and its three forward stages")
    class EditPhone1260 {
        private static final String BASE_PHONE = "(206)555-1234  ";

        private AccountUpdateController.Conversation phoneTask(String span) {
            assertThat(span)
                    .describedAs("WS-EDIT-US-PHONE-NUM is PIC X(15)")
                    .hasSize(AccountUpdateController.WS_EDIT_US_PHONE_NUM_LENGTH);
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_PHONE_NUMBER_1);
            task.wsEditUsPhoneNum = span;
            return task;
        }

        @Test
        @DisplayName("The base span parses into its three parts at offsets 1, 5 and 9")
        void baseSpanParts() {
            assertThat(AccountUpdateController.phoneArea(BASE_PHONE)).isEqualTo("206");
            assertThat(AccountUpdateController.phonePrefix(BASE_PHONE)).isEqualTo("555");
            assertThat(AccountUpdateController.phoneLine(BASE_PHONE)).isEqualTo("1234");
        }

        @Test
        @DisplayName("Valid: a real area code, prefix and line number set all three part flags VALID")
        void allThreePartsValid() {
            AccountUpdateController.Conversation task = phoneTask("(206)555-1234  ");
            controller.editUsPhoneNum1260(task);

            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.wsEditEditUsPhoneb).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.wsEditEditPhonec).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.inputError()).isFalse();
        }

        @Test
        @DisplayName("Wholly blank: the guard short-circuits and all three parts are VALID, unedited")
        void whollyBlankIsValid() {
            AccountUpdateController.Conversation task = phoneTask(" ".repeat(15));
            controller.editUsPhoneNum1260(task);

            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.wsEditEditUsPhoneb).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.wsEditEditPhonec).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.inputError()).isFalse();
            assertThat(task.returnMsgOff()).isTrue();
        }

        @Test
        @DisplayName("The NUMA-for-NUMC defect at :2249-2251, preserved: a blank area code skips the "
                + "whole edit even when the line number is present")
        void numaForNumcDefect() {
            AccountUpdateController.Conversation task = phoneTask(
                    AccountUpdateController.withPhoneLine(" ".repeat(15), "1234"));
            assertThat(AccountUpdateController.phoneArea(task.wsEditUsPhoneNum)).isBlank();
            assertThat(AccountUpdateController.phonePrefix(task.wsEditUsPhoneNum)).isBlank();
            assertThat(AccountUpdateController.phoneLine(task.wsEditUsPhoneNum)).isEqualTo("1234");

            controller.editUsPhoneNum1260(task);

            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.wsEditEditPhonec).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.inputError())
                    .describedAs("the defect means no part edit runs, so no error is raised")
                    .isFalse();
        }

        @Test
        @DisplayName("A present prefix defeats the guard, and then the blank area code is caught")
        void presentPrefixDefeatsTheGuard() {
            AccountUpdateController.Conversation task = phoneTask(
                    AccountUpdateController.withPhoneLine(
                            AccountUpdateController.withPhonePrefix(" ".repeat(15), "555"),
                            "1234"));
            controller.editUsPhoneNum1260(task);

            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_BLANK);
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_AREA_CODE_MUST_BE_SUPPLIED.trim());
        }

        @Test
        @DisplayName("Area code, arm 2: not three digits")
        void areaCodeNotNumeric() {
            AccountUpdateController.Conversation task = phoneTask("(20A)555-1234  ");
            controller.editUsPhoneNum1260(task);

            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(task)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_AREA_CODE_3_DIGITS.trim());
        }

        @Test
        @DisplayName("Area code, arm 3: numerically zero")
        void areaCodeZero() {
            AccountUpdateController.Conversation task = phoneTask("(000)555-1234  ");
            controller.editUsPhoneNum1260(task);

            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(task)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_AREA_CODE_CANNOT_BE_ZERO.trim());
        }

        @Test
        @DisplayName("Area code, arm 4: three digits, non-zero, but not in the CSLKPCDY table")
        void areaCodeNotInTable() {
            AccountUpdateController.Conversation task = phoneTask("(999)555-1234  ");
            assertThat(lookups.isValidGeneralPurposeCode("999")).isFalse();
            controller.editUsPhoneNum1260(task);

            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(task)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_NOT_VALID_AREA_CODE.trim());
        }

        @Test
        @DisplayName("A failing area code does not stop the prefix and line-number stages")
        void stagesAreSequentialNotExclusive() {
            AccountUpdateController.Conversation task = phoneTask("(999)00A-0000  ");
            controller.editUsPhoneNum1260(task);

            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(task.wsEditEditUsPhoneb).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(task.wsEditEditPhonec).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(task))
                    .describedAs("the first failure owns the message")
                    .isEqualTo("Phone Number 1"
                            + AccountUpdateController.MSG_NOT_VALID_AREA_CODE.trim());
        }

        @Test
        @DisplayName("Prefix: blank, then not numeric, then zero")
        void prefixArms() {
            AccountUpdateController.Conversation blank = phoneTask("(206)   -1234  ");
            controller.editUsPhoneNum1260(blank);
            assertThat(blank.wsEditEditUsPhoneb).isEqualTo(AccountUpdateController.FLG_BLANK);
            assertThat(returnMessageOf(blank)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_PREFIX_MUST_BE_SUPPLIED.trim());

            AccountUpdateController.Conversation bad = phoneTask("(206)5X5-1234  ");
            controller.editUsPhoneNum1260(bad);
            assertThat(bad.wsEditEditUsPhoneb).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(bad)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_PREFIX_3_DIGITS.trim());

            AccountUpdateController.Conversation zero = phoneTask("(206)000-1234  ");
            controller.editUsPhoneNum1260(zero);
            assertThat(zero.wsEditEditUsPhoneb).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(zero)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_PREFIX_CANNOT_BE_ZERO.trim());
        }

        @Test
        @DisplayName("Line number: blank, then not numeric, then zero")
        void lineNumberArms() {
            AccountUpdateController.Conversation blank =
                    phoneTask(AccountUpdateController.withPhoneLine(BASE_PHONE, "    "));
            controller.editUsPhoneNum1260(blank);
            assertThat(blank.wsEditEditPhonec).isEqualTo(AccountUpdateController.FLG_BLANK);
            assertThat(returnMessageOf(blank)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_LINENUM_MUST_BE_SUPPLIED.trim());

            AccountUpdateController.Conversation bad = phoneTask("(206)555-12X4  ");
            controller.editUsPhoneNum1260(bad);
            assertThat(bad.wsEditEditPhonec).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(bad)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_LINENUM_4_DIGITS.trim());

            AccountUpdateController.Conversation zero = phoneTask("(206)555-0000  ");
            controller.editUsPhoneNum1260(zero);
            assertThat(zero.wsEditEditPhonec).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(returnMessageOf(zero)).isEqualTo("Phone Number 1"
                    + AccountUpdateController.MSG_LINENUM_CANNOT_BE_ZERO.trim());
        }

        @Test
        @DisplayName("LOW-VALUES in a part reaches the same blank arm as spaces")
        void lowValuesInParts() {
            AccountUpdateController.Conversation task =
                    phoneTask(AccountUpdateController.withPhoneArea(BASE_PHONE, "\u0000\u0000\u0000"));
            controller.editUsPhoneNum1260(task);
            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_BLANK);
        }
    }

    @Nested
    @DisplayName("1265 / 1270 / 1275 / 1280 - SSN, state, FICO and state-plus-zip")
    class SsnStateFicoZip {
        @Test
        @DisplayName("1265: all three parts valid, and each part's flag is copied out separately")
        void ssnAllValid() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.ssnX = "123456789";
            controller.editUsSsn1265(task);

            assertThat(task.wsEditUsSsnPart1Flgs).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.wsEditUsSsnPart2Flgs).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.wsEditUsSsnPart3Flgs).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.inputError()).isFalse();
        }

        @ParameterizedTest(name = "1265 excludes a first group of {0}")
        @ValueSource(strings = {"000", "666", "900", "950", "999"})
        @DisplayName("1265: the 88 INVALID-SSN-PART1 exclusion list, including both range ends")
        void ssnPart1Excluded(String part1) {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.ssnX = part1 + "456789";
            controller.editUsSsn1265(task);

            assertThat(task.wsEditUsSsnPart1Flgs).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(task.inputError()).isTrue();
        }

        @ParameterizedTest(name = "1265 allows a first group of {0}")
        @ValueSource(strings = {"001", "665", "667", "899", "123"})
        @DisplayName("1265: the values immediately outside the exclusion list are accepted")
        void ssnPart1Boundaries(String part1) {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.ssnX = part1 + "456789";
            controller.editUsSsn1265(task);

            assertThat(task.wsEditUsSsnPart1Flgs).isEqualTo(AccountUpdateController.FLG_ISVALID);
        }

        @Test
        @DisplayName("1265: '000' takes the digit test's zero arm, so the range message never appears")
        void ssnPart1ZeroIsCaughtByTheDigitTest() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.ssnX = "000456789";
            controller.editUsSsn1265(task);

            assertThat(returnMessageOf(task)).isEqualTo("SSN: First 3 chars"
                    + AccountUpdateController.MSG_MUST_NOT_BE_ZERO);
        }

        @Test
        @DisplayName("1265: the exclusion block has no GO TO, so parts 2 and 3 are still edited")
        void ssnPart1FailureDoesNotStopTheRest() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.ssnX = "666" + "00" + "0000";
            controller.editUsSsn1265(task);

            assertThat(task.wsEditUsSsnPart1Flgs).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(task.wsEditUsSsnPart2Flgs)
                    .describedAs("part 2 was still edited and found zero")
                    .isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(task.wsEditUsSsnPart3Flgs).isEqualTo(AccountUpdateController.FLG_NOT_OK);
        }

        @Test
        @DisplayName("1265: each part carries its own name into the message")
        void ssnPartNames() {
            AccountUpdateController.Conversation second = warmTask();
            second.acupNewCust.ssnX = "123" + "0X" + "6789";
            controller.editUsSsn1265(second);
            assertThat(returnMessageOf(second)).isEqualTo("SSN 4th & 5th chars"
                    + AccountUpdateController.MSG_MUST_BE_ALL_NUMERIC);

            AccountUpdateController.Conversation third = warmTask();
            third.acupNewCust.ssnX = "123" + "45" + "678X";
            controller.editUsSsn1265(third);
            assertThat(returnMessageOf(third)).isEqualTo("SSN Last 4 chars"
                    + AccountUpdateController.MSG_MUST_BE_ALL_NUMERIC);
        }

        @Test
        @DisplayName("1270: a real state code passes and sets NOTHING - success is silence")
        void stateValid() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.addrStateCd = "WA";
            controller.editUsStateCd1270(task);

            assertThat(task.flag(AccountUpdateResponse.ScreenField.ACSSTTE))
                    .isEqualTo(AccountUpdateController.INITIALIZED_FLAG);
            assertThat(task.inputError()).isFalse();
        }

        @Test
        @DisplayName("1270: an unknown code sets ACSSTTE NOT-OK and names the field")
        void stateInvalid() {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_STATE);
            task.acupNewCust.addrStateCd = "ZZ";
            controller.editUsStateCd1270(task);

            assertThat(task.flagNotOk(AccountUpdateResponse.ScreenField.ACSSTTE)).isTrue();
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task)).isEqualTo("State"
                    + AccountUpdateController.MSG_NOT_A_VALID_STATE_CODE.trim());
        }

        @ParameterizedTest(name = "1275 accepts a FICO score of {0}")
        @ValueSource(ints = {300, 301, 750, 849, 850})
        @DisplayName("1275: the 88 VALUES 300 THROUGH 850, both ends inclusive")
        void ficoInRange(int score) {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.ficoScoreX = String.format("%03d", score);
            controller.editFicoScore1275(task);

            assertThat(task.inputError()).isFalse();
            assertThat(task.flag(AccountUpdateResponse.ScreenField.ACSTFCO))
                    .isEqualTo(AccountUpdateController.INITIALIZED_FLAG);
        }

        @ParameterizedTest(name = "1275 rejects a FICO score of {0}")
        @ValueSource(ints = {0, 299, 851, 999})
        @DisplayName("1275: outside the range sets ACSTFCO NOT-OK")
        void ficoOutOfRange(int score) {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_FICO_SCORE);
            task.acupNewCust.ficoScoreX = String.format("%03d", score);
            controller.editFicoScore1275(task);

            assertThat(task.flagNotOk(AccountUpdateResponse.ScreenField.ACSTFCO)).isTrue();
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task)).isEqualTo("FICO Score"
                    + AccountUpdateController.MSG_FICO_RANGE.trim());
        }

        @Test
        @DisplayName("1275: the 88 on the numeric view agrees with the paragraph's own range test")
        void ficoConditionNameAgrees() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.ficoScoreX = "750";
            assertThat(task.acupNewCust.ficoRangeIsValid()).isTrue();
            assertThat(task.acupNewCust.ficoScoreN()).isEqualTo(750);
            task.acupNewCust.ficoScoreX = "299";
            assertThat(task.acupNewCust.ficoRangeIsValid()).isFalse();
        }

        @Test
        @DisplayName("1280: a valid state-and-first-two-of-zip combination passes silently")
        void stateZipValid() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.addrStateCd = "WA";
            task.acupNewCust.addrZip = "9810112345";
            controller.editUsStateZipCd1280(task);

            assertThat(task.inputError()).isFalse();
            assertThat(task.flag(AccountUpdateResponse.ScreenField.ACSZIPC))
                    .isEqualTo(AccountUpdateController.INITIALIZED_FLAG);
        }

        @Test
        @DisplayName("1280: a mismatch sets BOTH ACSSTTE and ACSZIPC, and the message has no field name")
        void stateZipInvalid() {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_ZIP);
            task.acupNewCust.addrStateCd = "WA";
            task.acupNewCust.addrZip = "1000112345";
            controller.editUsStateZipCd1280(task);

            assertThat(task.flagNotOk(AccountUpdateResponse.ScreenField.ACSSTTE)).isTrue();
            assertThat(task.flagNotOk(AccountUpdateResponse.ScreenField.ACSZIPC)).isTrue();
            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task))
                    .isEqualTo(AccountUpdateController.MSG_INVALID_ZIP_FOR_STATE.trim());
        }

        @Test
        @DisplayName("1280 composes the probe as state plus the first two zip digits")
        void stateZipProbeShape() {
            assertThat(lookups.composeStateAndFirstZip2("WA", "9810112345"))
                    .isEqualTo("WA98");
            assertThat(lookups.isValidStateZip2Combo("WA98")).isTrue();
            assertThat(lookups.isValidStateZip2Combo("WA10")).isFalse();
        }
    }

    private record Difference(String label, java.util.function.Consumer<
            AccountUpdateController.Conversation> mutate) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static List<Difference> accountDifferences() {
        return List.of(
                new Difference("ACCT-ID", t -> t.acupNewAcct.acctIdX = "00000000099"),
                new Difference("ACTIVE-STATUS", t -> t.acupNewAcct.activeStatus = "N"),
                new Difference("CURR-BAL", t -> t.acupNewAcct.setCurrBalN(new BigDecimal("1.00"))),
                new Difference("CREDIT-LIMIT",
                        t -> t.acupNewAcct.setCreditLimitN(new BigDecimal("2.00"))),
                new Difference("CASH-CREDIT-LIMIT",
                        t -> t.acupNewAcct.setCashCreditLimitN(new BigDecimal("3.00"))),
                new Difference("OPEN-DATE", t -> t.acupNewAcct.setOpenYear("2021")),
                new Difference("EXPIRAION-DATE", t -> t.acupNewAcct.setExpYear("2031")),
                new Difference("REISSUE-DATE", t -> t.acupNewAcct.setReissueYear("2024")),
                new Difference("CURR-CYC-CREDIT",
                        t -> t.acupNewAcct.setCurrCycCreditN(new BigDecimal("4.00"))),
                new Difference("CURR-CYC-DEBIT",
                        t -> t.acupNewAcct.setCurrCycDebitN(new BigDecimal("5.00"))),
                new Difference("GROUP-ID", t -> t.acupNewAcct.groupId = at("OTHERGP", AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH)));
    }

    private static List<Difference> customerDifferences() {
        return List.of(
                new Difference("CUST-ID", t -> t.acupNewCust.custIdX = "999999999"),
                new Difference("FIRST-NAME", t -> t.acupNewCust.firstName = at("OTHER", AccountUpdateRequest.CustSnapshot.NAME_LENGTH)),
                new Difference("MIDDLE-NAME", t -> t.acupNewCust.middleName = at("Z", AccountUpdateRequest.CustSnapshot.NAME_LENGTH)),
                new Difference("LAST-NAME", t -> t.acupNewCust.lastName = at("OTHER", AccountUpdateRequest.CustSnapshot.NAME_LENGTH)),
                new Difference("ADDR-LINE-1", t -> t.acupNewCust.addrLine1 = at("2 OTHER ST", AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH)),
                new Difference("ADDR-LINE-2", t -> t.acupNewCust.addrLine2 = at("APT 9", AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH)),
                new Difference("ADDR-LINE-3", t -> t.acupNewCust.addrLine3 = at("TACOMA", AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH)),
                new Difference("ADDR-STATE-CD", t -> t.acupNewCust.addrStateCd = "OR"),
                new Difference("ADDR-COUNTRY-CD", t -> t.acupNewCust.addrCountryCd = "CAN"),
                new Difference("ADDR-ZIP", t -> t.acupNewCust.addrZip = "9999999999"),
                new Difference("PHONE-1-A", t -> t.acupNewCust.setPhoneNum1A("425")),
                new Difference("PHONE-1-B", t -> t.acupNewCust.setPhoneNum1B("867")),
                new Difference("PHONE-1-C", t -> t.acupNewCust.setPhoneNum1C("5309")),
                new Difference("PHONE-2-A", t -> t.acupNewCust.setPhoneNum2A("425")),
                new Difference("PHONE-2-B", t -> t.acupNewCust.setPhoneNum2B("867")),
                new Difference("PHONE-2-C", t -> t.acupNewCust.setPhoneNum2C("5309")),
                new Difference("SSN", t -> t.acupNewCust.ssnX = "999999999"),
                new Difference("GOVT-ISSUED-ID", t -> t.acupNewCust.govtIssuedId = at("OTHER-ID",
                        AccountUpdateRequest.CustSnapshot.GOVT_ISSUED_ID_LENGTH)),
                new Difference("DOB", t -> t.acupNewCust.dobYyyyMmDd = "19900101"),
                new Difference("EFT-ACCOUNT-ID", t -> t.acupNewCust.eftAccountId = "9999999999"),
                new Difference("PRI-HOLDER-IND", t -> t.acupNewCust.priHolderInd = "N"),
                new Difference("FICO-SCORE", t -> t.acupNewCust.ficoScoreX = "800"));
    }

    @Nested
    @DisplayName("1205-COMPARE-OLD-NEW - change detection, term by term")
    class CompareOldNew1205 {
        private AccountUpdateController.Conversation identicalTask() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewAcct.acctIdX = ACCT;
            task.acupNewAcct.activeStatus = "Y";
            task.acupNewAcct.setCurrBalN(new BigDecimal("1234.56"));
            task.acupNewAcct.setCreditLimitN(new BigDecimal("5000.00"));
            task.acupNewAcct.setCashCreditLimitN(new BigDecimal("-250.00"));
            task.acupNewAcct.setOpenYear("2020");
            task.acupNewAcct.setOpenMon("01");
            task.acupNewAcct.setOpenDay("15");
            task.acupNewAcct.setExpYear("2025");
            task.acupNewAcct.setExpMon("01");
            task.acupNewAcct.setExpDay("14");
            task.acupNewAcct.setReissueYear("2022");
            task.acupNewAcct.setReissueMon("06");
            task.acupNewAcct.setReissueDay("30");
            task.acupNewAcct.setCurrCycCreditN(new BigDecimal("0.00"));
            task.acupNewAcct.setCurrCycDebitN(new BigDecimal("87.05"));
            task.acupNewAcct.groupId = at("ZEROPCT",
                    AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);

            task.acupNewCust.custIdX = "123456789";
            task.acupNewCust.firstName = at("ANNE", AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            task.acupNewCust.middleName = at("Q", AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            task.acupNewCust.lastName = at("ARCHER", AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            task.acupNewCust.addrLine1 = at("1 MAIN STREET",
                    AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
            task.acupNewCust.addrLine2 = at("APT 2B",
                    AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
            task.acupNewCust.addrLine3 = at("SEATTLE",
                    AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
            task.acupNewCust.addrStateCd = "WA";
            task.acupNewCust.addrCountryCd = "USA";
            task.acupNewCust.addrZip = "9810112345";
            task.acupNewCust.setPhoneNum1A("206");
            task.acupNewCust.setPhoneNum1B("555");
            task.acupNewCust.setPhoneNum1C("1234");
            task.acupNewCust.setPhoneNum2A("206");
            task.acupNewCust.setPhoneNum2B("555");
            task.acupNewCust.setPhoneNum2C("9876");
            task.acupNewCust.ssnX = "123456789";
            task.acupNewCust.govtIssuedId = at("WA-DL-0099887766",
                    AccountUpdateRequest.CustSnapshot.GOVT_ISSUED_ID_LENGTH);
            task.acupNewCust.dobYyyyMmDd = "19800229";
            task.acupNewCust.eftAccountId = "0000000001";
            task.acupNewCust.priHolderInd = "Y";
            task.acupNewCust.ficoScoreX = "750";

            task.acupOldAcct.fromSnapshot(task.acupNewAcct.toSnapshot());
            task.acupOldCust.fromSnapshot(task.acupNewCust.toSnapshot());
            return task;
        }

        @Test
        @DisplayName("Identical groups: NO-CHANGES-FOUND survives and the message says so")
        void identicalReportsNoChange() {
            AccountUpdateController.Conversation task = identicalTask();
            controller.compareOldNew1205(task);

            assertThat(task.noChangesFound()).isTrue();
            assertThat(task.changeHasOccurred()).isFalse();
            assertThat(task.noChangesDetected()).isTrue();
            assertThat(returnMessageOf(task))
                    .isEqualTo(AccountUpdateController.MSG_NO_CHANGES_DETECTED.trim());
        }

        @ParameterizedTest(name = "a difference in {0} is detected")
        @MethodSource("com.vsergeychik.carddemo.account.AccountUpdateControllerTest"
                + "#accountDifferences")
        @DisplayName("Account group: each of the eleven terms is detected on its own")
        void accountTermDetected(Difference difference) {
            AccountUpdateController.Conversation task = identicalTask();
            difference.mutate().accept(task);
            controller.compareOldNew1205(task);

            assertThat(task.changeHasOccurred()).isTrue();
            assertThat(task.noChangesDetected()).isFalse();
            assertThat(task.returnMsgOff()).isTrue();
        }

        @ParameterizedTest(name = "a difference in {0} is detected")
        @MethodSource("com.vsergeychik.carddemo.account.AccountUpdateControllerTest"
                + "#customerDifferences")
        @DisplayName("Customer group: each of the twenty-two terms is detected on its own")
        void customerTermDetected(Difference difference) {
            AccountUpdateController.Conversation task = identicalTask();
            difference.mutate().accept(task);
            controller.compareOldNew1205(task);

            assertThat(task.changeHasOccurred()).isTrue();
            assertThat(task.noChangesDetected()).isFalse();
        }

        @Test
        @DisplayName("The account status is compared folded, so a case-only edit is NOT a change")
        void accountStatusComparedFolded() {
            AccountUpdateController.Conversation task = identicalTask();
            task.acupNewAcct.activeStatus = "y";
            controller.compareOldNew1205(task);

            assertThat(task.noChangesFound())
                    .describedAs("UPPER-CASE is applied to both sides at :1686")
                    .isTrue();
        }

        @Test
        @DisplayName("The group id is folded AND trimmed, so padding and case are both immaterial")
        void groupIdComparedFoldedAndTrimmed() {
            AccountUpdateController.Conversation task = identicalTask();
            task.acupNewAcct.groupId = at("zeropct",
                    AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);
            controller.compareOldNew1205(task);
            assertThat(task.noChangesFound()).isTrue();

            AccountUpdateController.Conversation padded = identicalTask();
            padded.acupOldAcct.groupId = at("ZEROPCT",
                    AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);
            padded.acupNewAcct.groupId = at("  ZEROPCT",
                    AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);
            controller.compareOldNew1205(padded);
            assertThat(padded.noChangesFound()).isTrue();
        }

        @Test
        @DisplayName("Customer text is folded and trimmed: leading padding is not a change")
        void customerTextComparedFoldedAndTrimmed() {
            AccountUpdateController.Conversation task = identicalTask();
            task.acupNewCust.firstName = at("   anne   ",
                    AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            controller.compareOldNew1205(task);
            assertThat(task.noChangesFound()).isTrue();
        }

        @Test
        @DisplayName("The monetary spans are compared RAW, so a re-keyed equal value can still differ")
        void monetarySpansComparedRaw() {
            AccountUpdateController.Conversation task = identicalTask();
            task.acupNewAcct.currBal = " ".repeat(
                    AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);

            assertThat(task.acupNewAcct.currBalN())
                    .describedAs("a blank span decodes to zero")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            task.acupOldAcct.setCurrBalN(BigDecimal.ZERO);
            assertThat(task.acupOldAcct.currBalN()).isEqualByComparingTo(BigDecimal.ZERO);

            controller.compareOldNew1205(task);

            assertThat(task.changeHasOccurred())
                    .describedAs("equal numbers, unequal bytes - the raw compare reports a change")
                    .isTrue();
        }

        @Test
        @DisplayName("The SSN, the date of birth and the EFT id are compared raw as well")
        void otherRawComparisons() {
            AccountUpdateController.Conversation ssn = identicalTask();
            ssn.acupNewCust.ssnX = " 23456789";
            controller.compareOldNew1205(ssn);
            assertThat(ssn.changeHasOccurred()).isTrue();

            AccountUpdateController.Conversation eft = identicalTask();
            eft.acupNewCust.eftAccountId = " 000000001";
            controller.compareOldNew1205(eft);
            assertThat(eft.changeHasOccurred())
                    .describedAs("no TRIM on the EFT id, unlike the names")
                    .isTrue();
        }

        @Test
        @DisplayName("An account difference short-circuits before the customer group is looked at")
        void accountDifferenceShortCircuits() {
            AccountUpdateController.Conversation task = identicalTask();
            task.acupNewAcct.activeStatus = "N";
            task.acupNewCust.firstName = "OTHER";
            controller.compareOldNew1205(task);

            assertThat(task.changeHasOccurred()).isTrue();
            assertThat(task.noChangesDetected()).isFalse();
        }

        @Test
        @DisplayName("The flag starts optimistic: NO-CHANGES-FOUND is set before either chain runs")
        void flagStartsOptimistic() {
            AccountUpdateController.Conversation task = identicalTask();
            task.wsDatachangedFlag = AccountUpdateController.CHANGE_HAS_OCCURRED;
            controller.compareOldNew1205(task);
            assertThat(task.noChangesFound())
                    .describedAs(":1682 resets the flag, so a stale value cannot survive")
                    .isTrue();
        }

        @Test
        @DisplayName("The no-change text is written over an earlier message")
        void noChangeTextRespectsAnEarlierMessage() {
            AccountUpdateController.Conversation task = identicalTask();
            task.wsReturnMsg = AccountUpdateController.atReturnWidth("EARLIER MESSAGE");
            controller.compareOldNew1205(task);

            assertThat(returnMessageOf(task))
                    .isEqualTo(AccountUpdateController.MSG_NO_CHANGES_DETECTED.trim());
        }
    }

    @Nested
    @DisplayName("0000-MAIN dispatch - four arms in EVALUATE order")
    class MainDispatch {
        @Test
        @DisplayName("Arm 1, PF3: control transfers and the response names the next program (G40)")
        void pf3TransfersControl() {
            AccountUpdateController.PaintedScreen painted = controller.handle(
                    request(ACCT, enter()), AccountUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHPF3);

            assertThat(painted.response().getNextProgram())
                    .isEqualTo(AccountUpdateController.LIT_MENUPGM);
            assertThat(painted.response().hasNavigationContext()).isTrue();
            assertThat(painted.response().getNextProgram())
                    .describedAs("no server-side forward is performed")
                    .isNotEqualTo(AccountUpdateController.LIT_THISPGM);
            assertThat(painted.response().commareaLength())
                    .isEqualTo(AccountUpdateController.PASSED_COMMAREA_LENGTH);
            verifyNoInteractions(accounts, xrefs, customers, service);
        }

        @Test
        @DisplayName("Arm 1, PF3: an unset origin falls back to the menu literals")
        void pf3FallsBackToTheMenu() {
            AccountUpdateRequest received = request(ACCT,
                    NavigationContext.empty().withPgmReenter());
            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3);

            assertThat(painted.response().getNextProgram())
                    .isEqualTo(AccountUpdateController.LIT_MENUPGM);
        }

        @Test
        @DisplayName("Arm 1, PF3: a named origin is returned to rather than the menu")
        void pf3ReturnsToTheNamedOrigin() {
            AccountUpdateRequest received = request(ACCT, NavigationContext.empty()
                    .withPgmReenter()
                    .withFromTranid("CCLI")
                    .withFromProgram(AccountUpdateController.LIT_CCLISTPGM));
            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3);

            assertThat(painted.response().getNextProgram())
                    .isEqualTo(AccountUpdateController.LIT_CCLISTPGM);
        }

        @Test
        @DisplayName("Arm 2, cold start: the search screen is painted and the context flips to REENTER")
        void coldStartPaintsTheSearchScreen() {
            AccountUpdateController.PaintedScreen painted = controller.handle(
                    request(" ", null), AccountUpdateController.NO_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(painted.response().isReenter())
                    .describedAs(":970 sets CDEMO-PGM-REENTER before returning")
                    .isTrue();
            assertThat(painted.response().changeAction().isDetailsNotFetched()).isTrue();
            assertThat(painted.response().getTitle01())
                    .isEqualTo(com.vsergeychik.carddemo.common.ScreenTitles.CCDA_TITLE01);
            assertThat(painted.response().getInfomsg().trim())
                    .isEqualTo(AccountUpdateController.INFO_PROMPT_FOR_SEARCH_KEYS.trim());
            verifyNoInteractions(accounts, xrefs, customers, service);
        }

        @Test
        @DisplayName("Arm 2, entered from the menu: the same arm is reached through the second condition")
        void enteredFromTheMenu() {
            AccountUpdateRequest received = request(ACCT, NavigationContext.empty()
                    .withFromProgram(AccountUpdateController.LIT_MENUPGM)
                    .withPgmEnter());
            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(painted.response().changeAction().isDetailsNotFetched()).isTrue();
            assertThat(painted.response().isReenter()).isTrue();
            verifyNoInteractions(accounts, xrefs, customers, service);
        }

        @Test
        @DisplayName("Arm 3, after a committed update: the misc storage and the carried account reset")
        void afterCommittedUpdateResets() {
            AccountUpdateRequest received = request(ACCT, reenter())
                    .withCommArea(AccountUpdateRequest.CommArea.initialised()
                            .withChangeAction(
                                    AccountUpdateRequest.ChangeAction.changesOkayedAndDone()));
            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(painted.response().changeAction().isDetailsNotFetched()).isTrue();
            assertThat(painted.response().isReenter()).isTrue();
            verifyNoInteractions(accounts, xrefs, customers, service);
        }

        @Test
        @DisplayName("Arm 3 is also reached from either failure state")
        void afterFailedUpdateResets() {
            for (AccountUpdateRequest.ChangeAction failed : List.of(
                    AccountUpdateRequest.ChangeAction.changesOkayedLockError(),
                    AccountUpdateRequest.ChangeAction.changesOkayedButFailed())) {
                AccountUpdateRequest received = request(ACCT, reenter())
                        .withCommArea(AccountUpdateRequest.CommArea.initialised()
                                .withChangeAction(failed));
                AccountUpdateController.PaintedScreen painted = controller.handle(received,
                        AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

                assertThat(painted.response().changeAction().isDetailsNotFetched()).isTrue();
            }
        }

        @Test
        @DisplayName("Arm 4, WHEN OTHER: the input is received, edited, decided and painted")
        void ordinaryInputArm() {
            stubAllFound();
            AccountUpdateRequest received = request(ACCT, reenter())
                    .withCommArea(AccountUpdateRequest.CommArea.initialised()
                            .withChangeAction(AccountUpdateRequest.ChangeAction.spacesState()));
            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            verify(xrefs).readByAccountIdViaAltIndex(anyString());
            verify(accounts).readByKey(anyLong());
            verify(customers).readByKey(anyString());
            assertThat(painted.response().changeAction().isShowDetails()).isTrue();
            assertThat(painted.response().getAcsttus()).startsWith("Y");
            assertThat(painted.response().getAcsfnam()).startsWith("ANNE");
            assertThat(painted.response().getNextProgram())
                    .isEqualTo(AccountUpdateController.LIT_THISPGM);
            assertThat(painted.response().getNextMap())
                    .isEqualTo(AccountUpdateController.LIT_THISMAP);
        }

        @Test
        @DisplayName("The two PERFORM ... THRU sites at :969 and :985 are one call with identical effect")
        void bothSendMapSitesAreTheSameCall() {
            AccountUpdateController.PaintedScreen coldStart = controller.handle(
                    request(" ", null), AccountUpdateController.NO_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);
            AccountUpdateController.PaintedScreen afterCommit = controller.handle(
                    request(ACCT, reenter()).withCommArea(
                            AccountUpdateRequest.CommArea.initialised().withChangeAction(
                                    AccountUpdateRequest.ChangeAction.changesOkayedAndDone())),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(afterCommit.response().fieldValues())
                    .describedAs("both PERFORM ... THRU sites paint the same screen")
                    .isEqualTo(coldStart.response().fieldValues());
            assertThat(afterCommit.cursorField()).isEqualTo(coldStart.cursorField());
            assertThat(afterCommit.response().changeAction().isDetailsNotFetched())
                    .isEqualTo(coldStart.response().changeAction().isDetailsNotFetched());
        }

        @Test
        @DisplayName("The cold-start arm does not run 1000, so CCARD-NEXT-PROG is still LOW-VALUES")
        void freshEntryLeavesNextProgUnset() {
            AccountUpdateController.PaintedScreen painted = controller.handle(
                    request(" ", null), AccountUpdateController.NO_COMMAREA_LENGTH,
                    CicsAid.DFHENTER);

            assertThat(painted.response().getNextProgram())
                    .isEqualTo(AccountUpdateController.spaces(
                            CardScreenState.CCARD_NEXT_PROG_LENGTH));
            assertThat(painted.response().getNextMapset())
                    .isEqualTo(AccountUpdateController.LIT_THISMAPSET.trim());
        }
    }

    @Nested
    @DisplayName("YYYY-STORE-PFKEY and the validity coercion at :905-915")
    class PfKeyHandling {
        @Test
        @DisplayName("ENTER and PF3 are always valid here")
        void alwaysValidKeys() {
            for (byte aid : new byte[] {CicsAid.DFHENTER, CicsAid.DFHPF3}) {
                AccountUpdateController.Conversation task =
                        new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
                controller.initializeStorage(request(ACCT, enter()), task,
                        AccountUpdateController.PASSED_COMMAREA_LENGTH, aid);
                controller.storePfKeyYYYY(task);
                controller.coerceInvalidAid(task);
                assertThat(task.pfkValid()).isTrue();
            }
        }

        @Test
        @DisplayName("PF5 is valid only while a change is awaiting confirmation")
        void pf5NeedsPendingConfirmation() {
            AccountUpdateController.Conversation pending =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            controller.initializeStorage(request(ACCT, reenter()), pending,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF5);
            pending.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            controller.storePfKeyYYYY(pending);
            controller.coerceInvalidAid(pending);
            assertThat(pending.pfkValid()).isTrue();
            assertThat(pending.ccWorkArea.isCcardAidPfk05()).isTrue();

            AccountUpdateController.Conversation idle =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            controller.initializeStorage(request(ACCT, reenter()), idle,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF5);
            idle.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            controller.storePfKeyYYYY(idle);
            controller.coerceInvalidAid(idle);
            assertThat(idle.pfkInvalid()).isTrue();
            assertThat(idle.ccWorkArea.isCcardAidEnter()).isTrue();
        }

        @Test
        @DisplayName("PF12 is valid only once the details have been fetched")
        void pf12NeedsFetchedDetails() {
            AccountUpdateController.Conversation fetched =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            controller.initializeStorage(request(ACCT, reenter()), fetched,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF12);
            fetched.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            controller.storePfKeyYYYY(fetched);
            controller.coerceInvalidAid(fetched);
            assertThat(fetched.pfkValid()).isTrue();

            AccountUpdateController.Conversation notFetched =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            controller.initializeStorage(request(ACCT, reenter()), notFetched,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF12);
            notFetched.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            controller.storePfKeyYYYY(notFetched);
            controller.coerceInvalidAid(notFetched);
            assertThat(notFetched.pfkInvalid()).isTrue();
            assertThat(notFetched.ccWorkArea.isCcardAidEnter()).isTrue();
        }

        @ParameterizedTest(name = "AID {0} is coerced to ENTER")
        @ValueSource(ints = {0x00, 0x6D, 0xF1, 0xF4})
        @DisplayName("Any other key, including CLEAR and PA1, is coerced to ENTER")
        void otherKeysAreCoerced(int aid) {
            AccountUpdateController.Conversation task =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            controller.initializeStorage(request(ACCT, reenter()), task,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, (byte) aid);
            controller.storePfKeyYYYY(task);
            controller.coerceInvalidAid(task);

            assertThat(task.pfkInvalid()).isTrue();
            assertThat(task.ccWorkArea.isCcardAidEnter()).isTrue();
        }

        @Test
        @DisplayName("CSSTRPFY has no WHEN OTHER, so an unmatched AID leaves the condition untouched")
        void unmatchedAidLeavesTheFlagsAlone() {
            AccountUpdateController.Conversation task =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            controller.initializeStorage(request(ACCT, reenter()), task,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, (byte) 0x7F);
            controller.storePfKeyYYYY(task);
            assertThat(task.ccWorkArea.isCcardAidEnter())
                    .describedAs("INITIALIZE CC-WORK-AREA left CCARD-AID LOW-VALUES, not ENTER")
                    .isFalse();
            controller.coerceInvalidAid(task);
            assertThat(task.ccWorkArea.isCcardAidEnter()).isTrue();
        }
    }

    @Nested
    @DisplayName("2000-DECIDE-ACTION - eight arms, WHEN OTHER last")
    class DecideAction2000 {
        @Test
        @DisplayName("Arm 1: details not fetched and the filter valid triggers the read")
        void notFetchedReads() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
            task.wsReturnMsg = AccountUpdateController.atReturnWidth("STALE MESSAGE");

            controller.decideAction2000(task);

            assertThat(task.acupChangeAction.isShowDetails()).isTrue();
            assertThat(task.returnMsgOff())
                    .describedAs(":2573 discards the edit message before re-reading")
                    .isTrue();
        }

        @Test
        @DisplayName("Arm 1: an invalid filter reads nothing at all")
        void notFetchedWithBadFilterDoesNotRead() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_BLANK;

            controller.decideAction2000(task);

            verifyNoInteractions(xrefs, accounts, customers);
            assertThat(task.acupChangeAction.isDetailsNotFetched()).isTrue();
        }

        @Test
        @DisplayName("Arm 2: PF12 re-reads even when the details are already on the screen")
        void pf12ReReads() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            task.ccWorkArea.setCcardAidCondition(
                    com.vsergeychik.carddemo.common.PfKeyResolver.AidKey.PFK12);
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;

            controller.decideAction2000(task);

            verify(xrefs).readByAccountIdViaAltIndex(anyString());
            assertThat(task.acupChangeAction.isShowDetails()).isTrue();
        }

        @Test
        @DisplayName("Arm 3: details shown and edits clean promotes to awaiting confirmation")
        void showDetailsPromotes() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            task.wsInputFlag = AccountUpdateController.INPUT_OK;

            controller.decideAction2000(task);

            assertThat(task.acupChangeAction.isChangesOkNotConfirmed()).isTrue();
        }

        @Test
        @DisplayName("Arm 3: an edit error keeps the screen where it is")
        void showDetailsWithAnErrorStays() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            task.wsInputFlag = AccountUpdateController.INPUT_ERROR;

            controller.decideAction2000(task);

            assertThat(task.acupChangeAction.isShowDetails()).isTrue();
        }

        @Test
        @DisplayName("Arm 3: no detected change also keeps the screen where it is")
        void showDetailsWithNoChangeStays() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            task.wsInputFlag = AccountUpdateController.INPUT_OK;
            task.wsReturnMsg = AccountUpdateController.atReturnWidth(
                    AccountUpdateController.MSG_NO_CHANGES_DETECTED);

            controller.decideAction2000(task);

            assertThat(task.acupChangeAction.isShowDetails()).isTrue();
        }

        @Test
        @DisplayName("Arm 4: CHANGES-NOT-OK is an explicit CONTINUE, not the abending WHEN OTHER")
        void changesNotOkContinues() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesNotOk();

            controller.decideAction2000(task);

            assertThat(task.acupChangeAction.isChangesNotOk()).isTrue();
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("Arm 5: awaiting confirmation plus PF5 performs the write through the service")
        void pf5Writes() {
            AccountUpdateController.Conversation task = warmTask();
            stageConfirmableScreen(task);
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            task.ccWorkArea.setCcardAidCondition(
                    com.vsergeychik.carddemo.common.PfKeyResolver.AidKey.PFK05);
            stubWrite(AccountUpdateService.WriteOutcome.CHANGES_OKAYED_AND_DONE);

            controller.decideAction2000(task);

            verify(service).writeProcessing(anyString(), any(), any(), any(), anyString(), any());
            assertThat(task.acupChangeAction.isChangesOkayedAndDone()).isTrue();
        }

        @Test
        @DisplayName("Arm 5 re-runs the edit pass first, and a screen that no longer edits clean is "
                + "repainted rather than written")
        void pf5RefusesValuesThatDoNotEditClean() {
            AccountUpdateController.Conversation task = warmTask();
            stageConfirmableScreen(task);
            // A value only a composed payload can present: the state the arm claims says the edits passed,
            // and the field says they cannot have. On a 3270 the two are the same screen image.
            task.acupNewAcct.activeStatus = "Q";
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            task.ccWorkArea.setCcardAidCondition(
                    com.vsergeychik.carddemo.common.PfKeyResolver.AidKey.PFK05);

            controller.decideAction2000(task);

            verify(service, never()).writeProcessing(anyString(), any(), any(), any(), anyString(),
                    any());
            // :1471 is where a failed pass leaves the state, and the field carries the edit's own verdict,
            // so 3000-SEND-MAP paints exactly the screen 1200 paints for this input.
            assertThat(task.acupChangeAction.isChangesNotOk()).isTrue();
            assertThat(task.inputError()).isTrue();
            assertThat(task.flagNotOk(AccountUpdateResponse.ScreenField.ACSTTUS)).isTrue();
        }

        @Test
        @DisplayName("Arm 5's re-run is invisible when the values do edit clean: the flags, the message "
                + "and the action are the image the turn arrived with")
        void pf5ReEditLeavesACleanScreenUntouched() {
            AccountUpdateController.Conversation task = warmTask();
            stageConfirmableScreen(task);
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            task.ccWorkArea.setCcardAidCondition(
                    com.vsergeychik.carddemo.common.PfKeyResolver.AidKey.PFK05);
            task.clearNonKeyFlags();
            task.wsReturnMsg = AccountUpdateController.WS_RETURN_MSG_OFF;
            stubWrite(AccountUpdateService.WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE);

            controller.decideAction2000(task);

            // The write was reached, so the guard was transparent; and nothing the edit pass writes was
            // left behind - the flags are still empty and the message is still the one 9600 set, not one
            // an edit set.
            verify(service).writeProcessing(anyString(), any(), any(), any(), anyString(), any());
            assertThat(task.inputError()).isFalse();
            assertThat(task.wsNonKeyFlags).isEmpty();
        }

        @Test
        @DisplayName("Arm 5 hands the service the ACTIVE dataset code page, on either page")
        void pf5WritesThroughTheActiveCodePage() {
            ArgumentCaptor<FixedWidthCodec> passed = ArgumentCaptor.forClass(FixedWidthCodec.class);

            AccountUpdateController.Conversation task = warmTask();
            stageConfirmableScreen(task);
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            task.ccWorkArea.setCcardAidCondition(
                    com.vsergeychik.carddemo.common.PfKeyResolver.AidKey.PFK05);
            stubWrite(AccountUpdateService.WriteOutcome.CHANGES_OKAYED_AND_DONE);
            controller.decideAction2000(task);

            verify(service).writeProcessing(anyString(), any(), any(), any(), anyString(),
                    passed.capture());
            assertThat(passed.getValue().charset()).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(passed.getValue()).isSameAs(controller.codec());

            Charset ebcdic = Charset.forName("IBM037");
            AccountUpdateController onEbcdic = new AccountUpdateController(accounts, xrefs, customers,
                    service, dates, lookups, CLOCK, ConversationStateSealFixture.seal(), ebcdic);

            assertThat(onEbcdic.codec().charset()).isEqualTo(ebcdic);
            assertThat(new AccountUpdateController(accounts, xrefs, customers, service, dates, lookups,
                    CLOCK, ConversationStateSealFixture.seal(), StandardCharsets.US_ASCII)
                    .codec().charset())
                    .isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("Arm 6: awaiting confirmation without PF5 waits, and 3250 prompts again")
        void withoutPf5Waits() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();

            controller.decideAction2000(task);

            assertThat(task.acupChangeAction.isChangesOkNotConfirmed()).isTrue();
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("Arm 7: a committed update returns to the detail view and forgets the account")
        void committedForgetsTheAccountWhenEnteredDirectly() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkayedAndDone();
            task.carddemoCommarea = task.carddemoCommarea.withFromTranid("    ");

            controller.decideAction2000(task);

            assertThat(task.acupChangeAction.isShowDetails()).isTrue();
            assertThat(task.carddemoCommarea.acctId()).isZero();
            assertThat(task.carddemoCommarea.cardNum()).isZero();
        }

        @Test
        @DisplayName("Arm 7: entered from another transaction, the account is remembered")
        void committedKeepsTheAccountWhenEnteredFromElsewhere() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkayedAndDone();
            task.carddemoCommarea = task.carddemoCommarea.withFromTranid("CCLI").withAcctId(11L);

            controller.decideAction2000(task);

            assertThat(task.acupChangeAction.isShowDetails()).isTrue();
            assertThat(task.carddemoCommarea.acctId())
                    .describedAs(":2630 only clears when the origin is unset")
                    .isEqualTo(11L);
        }

        @Test
        @DisplayName("Arm 8, WHEN OTHER: an impossible state abends rather than painting a screen")
        void unreachableStateAbends() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.of('?');

            assertThatThrownBy(() -> controller.decideAction2000(task))
                    .isInstanceOf(AbendException.class)
                    .hasMessageContaining(AccountUpdateController.ABEND_MSG_UNEXPECTED_DATA_SCENARIO);
        }

        private void stubWrite(AccountUpdateService.WriteOutcome outcome) {
            boolean reachedRewrites =
                    outcome == AccountUpdateService.WriteOutcome.LOCKED_BUT_UPDATE_FAILED
                    || outcome == AccountUpdateService.WriteOutcome.CHANGES_OKAYED_AND_DONE;
            Optional<AccountUpdateService.ChangeCheck> check = outcome.isLockFailure()
                    ? Optional.empty()
                    : Optional.of(outcome
                            == AccountUpdateService.WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE
                            ? AccountUpdateService.ChangeCheck.changed(
                                    AccountUpdateService.Block.ACCOUNT_MASTER,
                                    java.util.EnumSet.of(
                                            AccountUpdateService.ComparedItem.ACCT_ACTIVE_STATUS))
                            : AccountUpdateService.ChangeCheck.unchanged());
            when(service.writeProcessing(anyString(), any(), any(), any(), anyString(), any()))
                    .thenReturn(new AccountUpdateService.WriteResult(outcome,
                            outcome.isLockFailure(), false,
                            AccountUpdateController.WS_RETURN_MSG_OFF, FileStatus.OK,
                            java.util.OptionalInt.empty(), Optional.empty(), Optional.empty(),
                            reachedRewrites ? Optional.of("A".repeat(
                                    AccountUpdateService.ACCT_UPDATE_RECORD_LENGTH))
                                    : Optional.empty(),
                            reachedRewrites ? Optional.of("C".repeat(
                                    AccountUpdateService.CUST_UPDATE_RECORD_LENGTH))
                                    : Optional.empty(),
                            check));
        }

        @ParameterizedTest(name = "write outcome {0}")
        @CsvSource({
            "COULD_NOT_LOCK_ACCT_FOR_UPDATE, LOCK",
            "LOCKED_BUT_UPDATE_FAILED,       FAILED",
            "DATA_WAS_CHANGED_BEFORE_UPDATE, SHOW",
            "COULD_NOT_LOCK_CUST_FOR_UPDATE, DONE",
            "CHANGES_OKAYED_AND_DONE,        DONE",
        })
        @DisplayName("9600: the four write arms, and the customer-lock outcome falling to WHEN OTHER")
        void writeOutcomes(AccountUpdateService.WriteOutcome outcome, String expected) {
            AccountUpdateController.Conversation task = warmTask();
            stubWrite(outcome);

            controller.writeProcessing9600(task);

            switch (expected) {
                case "LOCK" -> assertThat(task.acupChangeAction.isChangesOkayedLockError()).isTrue();
                case "FAILED" ->
                        assertThat(task.acupChangeAction.isChangesOkayedButFailed()).isTrue();
                case "SHOW" -> assertThat(task.acupChangeAction.isShowDetails()).isTrue();
                default -> assertThat(task.acupChangeAction.isChangesOkayedAndDone()).isTrue();
            }
        }

        @Test
        @DisplayName("9600: the customer-lock failure is reported as success - the inner EVALUATE tests "
                + "only the account lock")
        void customerLockFailureIsReportedAsSuccess() {
            AccountUpdateController.Conversation task = warmTask();
            stubWrite(AccountUpdateService.WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE);

            controller.writeProcessing9600(task);

            assertThat(task.acupChangeAction.isChangesOkayedAndDone()).isTrue();
            assertThat(task.acupChangeAction.isChangesFailed()).isFalse();
        }

        @Test
        @DisplayName("9600: the service's message and its input-error verdict both come back")
        void writeResultIsAdopted() {
            AccountUpdateController.Conversation task = warmTask();
            when(service.writeProcessing(anyString(), any(), any(), any(), anyString(), any()))
                    .thenReturn(new AccountUpdateService.WriteResult(
                            AccountUpdateService.WriteOutcome.LOCKED_BUT_UPDATE_FAILED, false, false,
                            AccountUpdateController.atReturnWidth("UPDATE FAILED"), FileStatus.OK,
                            java.util.OptionalInt.of(13), Optional.of("REWRITE"),
                            Optional.of("ACCTDAT"),
                            Optional.of("A".repeat(
                                    AccountUpdateService.ACCT_UPDATE_RECORD_LENGTH)),
                            Optional.of("C".repeat(
                                    AccountUpdateService.CUST_UPDATE_RECORD_LENGTH)),
                            Optional.of(AccountUpdateService.ChangeCheck.unchanged())));

            controller.writeProcessing9600(task);

            assertThat(returnMessageOf(task)).isEqualTo("UPDATE FAILED");
            assertThat(task.acupChangeAction.isChangesOkayedButFailed()).isTrue();
        }
    }

    @Nested
    @DisplayName("The read path - the 9000 driver, 9200 on CXACAIX, 9300, 9400 and 9500")
    class ReadPath {
        @Test
        @DisplayName("All three reads succeed: 9500 stores both records into the OLD group")
        void happyPath() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.foundAcctInMaster()).isTrue();
            assertThat(task.foundCustInMaster()).isTrue();
            assertThat(task.acupOldAcct.activeStatus).isEqualTo("Y");
            assertThat(task.acupOldAcct.openYear()).isEqualTo("2020");
            assertThat(task.acupOldAcct.openMon()).isEqualTo("01");
            assertThat(task.acupOldAcct.openDay()).isEqualTo("15");
            assertThat(AccountUpdateController.trim(task.acupOldCust.firstName)).isEqualTo("ANNE");
            assertThat(task.acupOldCust.dobYyyyMmDd).isEqualTo("19800229");
            assertThat(task.acupOldCust.ficoScoreN()).isEqualTo(750);
        }

        @Test
        @DisplayName("9200 reads the cross-reference by ACCOUNT id, on the CXACAIX path (G45)")
        void xrefIsReadOnTheAlternateIndex() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            verify(xrefs).readByAccountIdViaAltIndex(ACCT);
            assertThat(CardXrefRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CXACAIX");
            assertThat(task.carddemoCommarea.custId()).isEqualTo(CUST_ID);
            assertThat(task.carddemoCommarea.cardNum())
                    .isEqualTo(Long.parseLong(XREF_CARD));
        }

        @Test
        @DisplayName("9200 not found: the filter is marked NOT-OK and 9300 is never reached")
        void xrefNotFoundStopsTheChain() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.notFound(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME));
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(task.inputError()).isTrue();
            verifyNoInteractions(accounts, customers);
            assertThat(returnMessageOf(task))
                    .startsWith(AccountUpdateController.STRING_ACCOUNT_PREFIX)
                    .contains(AccountUpdateController.STRING_CROSS_REF_FILE.trim());
        }

        @Test
        @DisplayName("9200 other: the file-error message is composed and is NOT guarded by the message "
                + "already being off")
        void xrefOtherComposesTheFileError() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.other(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME, "37"));
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);
            task.wsReturnMsg = AccountUpdateController.atReturnWidth("EARLIER MESSAGE");

            controller.readAcct9000(task);

            assertThat(task.flgAcctfilterNotOk()).isTrue();
            assertThat(returnMessageOf(task))
                    .describedAs("the WHEN OTHER arm overwrites an earlier message, unlike NOTFND")
                    .startsWith(AccountUpdateController.FILE_ERROR_PREFIX.trim());
        }

        @Test
        @DisplayName("9300 not found: the read stops before the customer read")
        void accountNotFound() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.notFound());
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.found(customer(), CUST_IMAGE));
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.foundAcctInMaster()).isFalse();
            assertThat(task.inputError()).isTrue();
        }

        @Test
        @DisplayName("9300's DID-NOT-FIND guard can never fire, because the SET is commented out")
        void accountGuardIsIneffective() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.notFound());
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.found(customer(), CUST_IMAGE));
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.didNotFindAcctInAcctdat())
                    .describedAs("the message the guard tests for is never set")
                    .isFalse();
            verify(customers).readByKey(anyString());
        }

        @Test
        @DisplayName("9300 other: the account file name is named in the error")
        void accountOtherStatus() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.of("37"));
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.found(customer(), CUST_IMAGE));
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.inputError()).isTrue();
        }

        @Test
        @DisplayName("9400 not found: the customer message uses its own literals and REAS in capitals")
        void customerNotFound() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.notFound());
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.foundCustInMaster()).isFalse();
            assertThat(task.flgCustfilterNotOk()).isTrue();
            assertThat(returnMessageOf(task))
                    .startsWith(AccountUpdateController.STRING_CUSTID_PREFIX)
                    .contains(AccountUpdateController.STRING_REAS_UPPER.trim());
        }

        @Test
        @DisplayName("9400 other: the customer file name is named in the error")
        void customerOtherStatus() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.of("37"));
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.inputError()).isTrue();
            assertThat(returnMessageOf(task))
                    .contains(AccountUpdateController.LIT_CUSTFILENAME.trim());
        }

        @Test
        @DisplayName("The file-error message is composed to exactly eighty bytes before truncation")
        void fileErrorMessageWidth() {
            AccountUpdateController.Conversation task = warmTask();
            task.errorOpname = at(AccountUpdateController.READ_OPERATION_NAME,
                    AccountUpdateController.ERROR_OPNAME_LENGTH);
            task.errorFile = at(AccountUpdateController.LIT_ACCTFILENAME,
                    AccountUpdateController.ERROR_FILE_LENGTH);
            task.errorResp = AccountUpdateController.respImage(13);
            task.errorResp2 = AccountUpdateController.respImage(0);

            String composed = controller.fileErrorMessage(task);

            assertThat(composed)
                    .hasSize(AccountUpdateController.FILE_ERROR_MESSAGE_LENGTH)
                    .startsWith(AccountUpdateController.FILE_ERROR_PREFIX);
            assertThat(AccountUpdateController.atReturnWidth(composed))
                    .hasSize(AccountUpdateController.WS_RETURN_MSG_LENGTH);
        }

        @Test
        @DisplayName("A RESP is staged as nine digits left-justified into PIC X(10)")
        void respStagingInTheMessage() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.notFound(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME));
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.errorResp).hasSize(AccountUpdateController.ERROR_RESP_LENGTH);
            assertThat(task.errorResp).endsWith(" ");
        }

        @Test
        @DisplayName("Both record areas exist from construction, at their COBOL starting image")
        void recordAreasStartAtTheirCobolImage() {
            AccountUpdateController.Conversation fresh =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);

            assertThat(fresh.accountRecord)
                    .describedAs("an 01-level WORKING-STORAGE area is never absent")
                    .isNotNull();
            assertThat(fresh.customerRecord).isNotNull();
            assertThat(fresh.accountRecord.getAcctId()).isZero();
            assertThat(fresh.accountRecord.getAcctActiveStatus()).isBlank();
            assertThat(fresh.accountRecord.toByteArray()).hasSize(AccountRepository.RECORD_LENGTH);
            assertThat(fresh.customerRecord.getCustId()).isZero();
        }

        @Test
        @DisplayName("The area carries the active dataset code page, because it is byte-backed")
        void theAreaCarriesTheActiveCodePage() {
            Charset ebcdic = Charset.forName("IBM037");
            AccountUpdateController.Conversation onEbcdic =
                    new AccountUpdateController.Conversation(ebcdic);
            onEbcdic.accountRecord.setAcctActiveStatus("Y");

            assertThat(onEbcdic.accountRecord.toByteArray()[AccountRecord.ACCT_ACTIVE_STATUS_OFFSET])
                    .isEqualTo("Y".getBytes(ebcdic)[0]);
            assertThatNullPointerException()
                    .describedAs("a record area cannot be established without a code page")
                    .isThrownBy(() -> new AccountUpdateController.Conversation(null));
        }

        @Test
        @DisplayName("Neither INITIALIZE reaches the record areas - they are separate 01-level items")
        void initializeLeavesTheRecordAreasAlone() {
            AccountUpdateController.Conversation task =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            task.accountRecord = account();
            task.customerRecord = customer();

            controller.initializeStorage(request(ACCT, enter()), task,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(task.accountRecord.getAcctId())
                    .describedAs("INITIALIZE WS-MISC-STORAGE at :867 does not name ACCOUNT-RECORD")
                    .isEqualTo(11L);
            assertThat(task.customerRecord.getCustId()).isEqualTo(CUST_ID);

            controller.initializeMiscStorage(task);

            assertThat(task.accountRecord.getAcctId())
                    .describedAs("nor does the one at :983")
                    .isEqualTo(11L);
            assertThat(task.customerRecord.getCustId()).isEqualTo(CUST_ID);
        }

        @Test
        @DisplayName("A not-found read leaves the area unchanged rather than emptying it")
        void anUnsuccessfulReadLeavesTheAreaUnchanged() {
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.notFound());
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);
            task.accountRecord = account();

            controller.getAcctDataByAcct9300(task);

            assertThat(task.accountRecord.getAcctId()).isEqualTo(11L);
            assertThat(task.foundAcctInMaster()).isFalse();
            assertThat(task.inputError()).isTrue();
        }

        @Test
        @DisplayName("9500 stores the unchanged area after a failed read: the source's stale-area defect")
        void storeFetchedDataStoresAStaleAreaRatherThanAbending() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.notFound());
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.notFound());
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            assertThatNoException()
                    .describedAs("the guards are ineffective, so 9500 runs - and must not abend")
                    .isThrownBy(() -> controller.readAcct9000(task));

            assertThat(task.foundAcctInMaster()).isFalse();
            assertThat(task.foundCustInMaster()).isFalse();
            assertThat(task.carddemoCommarea.acctId()).isZero();
            assertThat(task.carddemoCommarea.custId()).isZero();
            assertThat(task.carddemoCommarea.custLname()).isBlank();
            assertThat(task.acupOldAcct.acctIdN()).isZero();
            assertThat(task.acupOldCust.custIdN()).isZero();
        }
    }

    @Nested
    @DisplayName("The paint and the attribute layer - 3100 to 3400")
    class PaintAndAttributes {
        @Test
        @DisplayName("3100: the titles, the transaction name and the fixed clock's date and time")
        void screenInitHeader() {
            AccountUpdateController.Conversation task = warmTask();
            controller.screenInit3100(task);

            assertThat(task.cactupao.getTitle01())
                    .isEqualTo(com.vsergeychik.carddemo.common.ScreenTitles.CCDA_TITLE01);
            assertThat(task.cactupao.getTitle02())
                    .isEqualTo(com.vsergeychik.carddemo.common.ScreenTitles.CCDA_TITLE02);
            assertThat(task.cactupao.getTrnname())
                    .isEqualTo(AccountUpdateController.LIT_THISTRANID);
            assertThat(task.cactupao.getCurdate()).isEqualTo("07/19/22");
            assertThat(task.cactupao.getCurtime()).isEqualTo("23:12:32");
        }

        @Test
        @DisplayName("3200: CDEMO-PGM-ENTER paints nothing at all")
        void enterPaintsNothing() {
            AccountUpdateController.Conversation task = warmTask();
            task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
            controller.screenInit3100(task);
            AccountUpdateResponse before = task.cactupao;

            controller.setupScreenVars3200(task);

            assertThat(task.cactupao).isSameAs(before);
        }

        @Test
        @DisplayName("3201: initial values - every one of the forty-two fields goes to LOW-VALUES")
        void initialValuesMode() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            task.ccWorkArea.setCcAcctId(ACCT);
            controller.screenInit3100(task);

            controller.setupScreenVars3200(task);

            for (AccountUpdateResponse.ScreenField field
                    : AccountUpdateController.INITIAL_VALUE_FIELDS) {
                assertThat(task.cactupao.value(field))
                        .describedAs("%s", field)
                        .isEqualTo(AccountUpdateController.lowValues(
                                AccountUpdateResponse.declaredLength(field)));
            }
        }

        @Test
        @DisplayName("3202: original values - the fetched record, and the change flags are cleared")
        void originalValuesMode() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);
            controller.readAcct9000(task);
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            controller.screenInit3100(task);

            controller.setupScreenVars3200(task);

            assertThat(task.cactupao.getAcsttus()).isEqualTo("Y");
            assertThat(task.cactupao.getOpnyear()).isEqualTo("2020");
            assertThat(task.cactupao.getAcsfnam()).startsWith("ANNE");
            assertThat(task.flagIsvalid(AccountUpdateResponse.ScreenField.ACSTTUS)).isTrue();
            assertThat(task.flagNotOk(AccountUpdateResponse.ScreenField.ACSTTUS)).isFalse();
            assertThat(task.wsInfoMsg.trim())
                    .isEqualTo(AccountUpdateController.INFO_PROMPT_FOR_CHANGES.trim());
        }

        @Test
        @DisplayName("3203: updated values - what the operator typed, not what the record holds")
        void updatedValuesMode() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            task.ccWorkArea.setCcAcctId(ACCT);
            task.acupNewAcct.activeStatus = "N";
            task.acupNewAcct.setOpenYear("2021");
            controller.screenInit3100(task);

            controller.setupScreenVars3200(task);

            assertThat(task.cactupao.getAcsttus()).isEqualTo("N");
            assertThat(task.cactupao.getOpnyear()).isEqualTo("2021");
        }

        @Test
        @DisplayName("3200 WHEN OTHER falls back to the original values, like the SHOW-DETAILS arm")
        void whenOtherPaintsOriginalValues() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.of('?');
            task.ccWorkArea.setCcAcctId(ACCT);
            task.wsAccountMasterReadFlag = AccountUpdateController.FOUND_IN_MASTER;
            task.acupOldAcct.activeStatus = "Y";
            controller.screenInit3100(task);

            controller.setupScreenVars3200(task);

            assertThat(task.cactupao.getAcsttus()).isEqualTo("Y");
        }

        @Test
        @DisplayName("3200: a zero filter with a valid flag blanks ACCTSID rather than showing zeroes")
        void zeroFilterBlanksTheKey() {
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId("00000000000");
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
            controller.screenInit3100(task);

            controller.setupScreenVars3200(task);

            assertThat(task.cactupao.value(AccountUpdateResponse.ScreenField.ACCTSID))
                    .isEqualTo(AccountUpdateController.lowValues(
                            AccountUpdateRequest.ACCTSID_LENGTH));
        }

        @Test
        @DisplayName("paintMonetary: the mask when the field is valid, the raw keying when it is not")
        void monetaryPaintingBothArms() {
            AccountUpdateController.Conversation valid = warmTask();
            controller.screenInit3100(valid);
            valid.setFlag(AccountUpdateResponse.ScreenField.ACRDLIM,
                    AccountUpdateController.FLG_ISVALID);
            controller.paintMonetary(valid, AccountUpdateResponse.ScreenField.ACRDLIM,
                    new BigDecimal("5000.00"), "IGNORED");
            assertThat(valid.cactupao.getAcrdlim()).isEqualTo("+      5,000.00");

            AccountUpdateController.Conversation invalid = warmTask();
            controller.screenInit3100(invalid);
            invalid.setFlag(AccountUpdateResponse.ScreenField.ACRDLIM,
                    AccountUpdateController.FLG_NOT_OK);
            controller.paintMonetary(invalid, AccountUpdateResponse.ScreenField.ACRDLIM,
                    new BigDecimal("5000.00"), "12.3.4         ");
            assertThat(invalid.cactupao.getAcrdlim())
                    .describedAs("a rejected amount is shown back as typed, not reformatted")
                    .isEqualTo("12.3.4         ");
        }

        @ParameterizedTest(name = "3250 for {0}")
        @CsvSource({
            "ENTER,          PROMPT_FOR_SEARCH_KEYS",
            "NOT_FETCHED,    PROMPT_FOR_SEARCH_KEYS",
            "SHOW_DETAILS,   PROMPT_FOR_CHANGES",
            "CHANGES_NOT_OK, PROMPT_FOR_CHANGES",
            "OK_NOT_CONFIRMED, PROMPT_FOR_CONFIRMATION",
            "OKAYED_AND_DONE, CONFIRM_UPDATE_SUCCESS",
            "LOCK_ERROR,     INFORM_FAILURE",
            "BUT_FAILED,     INFORM_FAILURE",
        })
        @DisplayName("3250: the eight matching arms, in source order")
        void informationMessageArms(String state, String expected) {
            AccountUpdateController.Conversation task = warmTask();
            controller.screenInit3100(task);
            switch (state) {
                case "ENTER" -> task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
                case "NOT_FETCHED" ->
                        task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
                case "SHOW_DETAILS" ->
                        task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
                case "CHANGES_NOT_OK" ->
                        task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesNotOk();
                case "OK_NOT_CONFIRMED" -> task.acupChangeAction =
                        AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
                case "OKAYED_AND_DONE" -> task.acupChangeAction =
                        AccountUpdateRequest.ChangeAction.changesOkayedAndDone();
                case "LOCK_ERROR" -> task.acupChangeAction =
                        AccountUpdateRequest.ChangeAction.changesOkayedLockError();
                default -> task.acupChangeAction =
                        AccountUpdateRequest.ChangeAction.changesOkayedButFailed();
            }

            controller.setupInfomsg3250(task);

            String text = switch (expected) {
                case "PROMPT_FOR_SEARCH_KEYS" ->
                        AccountUpdateController.INFO_PROMPT_FOR_SEARCH_KEYS;
                case "PROMPT_FOR_CHANGES" -> AccountUpdateController.INFO_PROMPT_FOR_CHANGES;
                case "PROMPT_FOR_CONFIRMATION" ->
                        AccountUpdateController.INFO_PROMPT_FOR_CONFIRMATION;
                case "CONFIRM_UPDATE_SUCCESS" ->
                        AccountUpdateController.INFO_CONFIRM_UPDATE_SUCCESS;
                default -> AccountUpdateController.INFO_INFORM_FAILURE;
            };
            assertThat(task.wsInfoMsg.trim()).isEqualTo(text.trim());
            assertThat(task.cactupao.getInfomsg().trim()).isEqualTo(text.trim());
        }

        @Test
        @DisplayName("3250 has no WHEN OTHER, so an unmatched state leaves the message untouched")
        void informationMessageHasNoDefault() {
            AccountUpdateController.Conversation task = warmTask();
            controller.screenInit3100(task);
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.of('?');
            task.wsInfoMsg = AccountUpdateController.atInfoWidth("A MESSAGE FROM EARLIER");

            controller.setupInfomsg3250(task);

            assertThat(task.wsInfoMsg.trim()).isEqualTo("A MESSAGE FROM EARLIER");
        }

        @Test
        @DisplayName("3250: an empty message on an unmatched state falls to the last arm")
        void emptyInformationMessageFallsToTheLastArm() {
            AccountUpdateController.Conversation task = warmTask();
            controller.screenInit3100(task);
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.of('?');
            task.wsInfoMsg = AccountUpdateController.atInfoWidth("");

            controller.setupInfomsg3250(task);

            assertThat(task.wsInfoMsg.trim())
                    .isEqualTo(AccountUpdateController.INFO_PROMPT_FOR_SEARCH_KEYS.trim());
        }

        @Test
        @DisplayName("3310 protects everything, then 3320 re-enables the forty editable fields")
        void protectThenUnprotect() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            controller.screenInit3100(task);

            controller.protectAllAttrs3310(task);
            for (AccountUpdateResponse.ScreenField field
                    : AccountUpdateController.PROTECTABLE_FIELDS) {
                assertThat(metadataFor(task, field).attribute())
                        .describedAs("%s", field)
                        .isEqualTo(BmsAttributes.DFHBMPRF);
            }

            controller.unprotectFewAttrs3320(task);
            for (AccountUpdateResponse.ScreenField field
                    : AccountUpdateController.UNPROTECTED_FIELDS) {
                assertThat(metadataFor(task, field).attribute())
                        .describedAs("%s", field)
                        .isEqualTo(BmsAttributes.DFHBMFSE);
            }
            assertThat(metadataFor(task, AccountUpdateResponse.ScreenField.ACSTNUM).attribute())
                    .isEqualTo(BmsAttributes.DFHBMPRF);
        }

        @Test
        @DisplayName("The protection context: four arms, and the deliberately empty one")
        void protectionByContext() {
            AccountUpdateController.Conversation notFetched = warmTask();
            notFetched.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            controller.screenInit3100(notFetched);
            controller.unprotectByContext3300(notFetched);
            assertThat(metadataFor(notFetched, AccountUpdateResponse.ScreenField.ACCTSID)
                    .attribute()).isEqualTo(BmsAttributes.DFHBMFSE);

            AccountUpdateController.Conversation shown = warmTask();
            shown.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            controller.screenInit3100(shown);
            controller.unprotectByContext3300(shown);
            assertThat(metadataFor(shown, AccountUpdateResponse.ScreenField.ACSTTUS).attribute())
                    .isEqualTo(BmsAttributes.DFHBMFSE);

            for (AccountUpdateRequest.ChangeAction frozen : List.of(
                    AccountUpdateRequest.ChangeAction.changesOkNotConfirmed(),
                    AccountUpdateRequest.ChangeAction.changesOkayedAndDone())) {
                AccountUpdateController.Conversation task = warmTask();
                task.acupChangeAction = frozen;
                controller.screenInit3100(task);
                controller.unprotectByContext3300(task);
                assertThat(metadataFor(task, AccountUpdateResponse.ScreenField.ACCTSID).attribute())
                        .describedAs("the CONTINUE arm writes nothing")
                        .isEqualTo(AccountUpdateRequest.FieldMetadata.ATTRIBUTE_UNSET);
            }

            AccountUpdateController.Conversation other = warmTask();
            other.acupChangeAction = AccountUpdateRequest.ChangeAction.of('?');
            controller.screenInit3100(other);
            controller.unprotectByContext3300(other);
            assertThat(metadataFor(other, AccountUpdateResponse.ScreenField.ACCTSID).attribute())
                    .isEqualTo(BmsAttributes.DFHBMFSE);
        }

        @Test
        @DisplayName("The cursor lands on the status when the record is healthy, on the key when it is not")
        void cursorPlacement() {
            AccountUpdateController.Conversation healthy = warmTask();
            controller.screenInit3100(healthy);
            healthy.wsInfoMsg = AccountUpdateController.atInfoWidth(
                    AccountUpdateController.INFO_FOUND_ACCOUNT_DATA);
            controller.positionCursor3300(healthy);
            assertThat(healthy.cursorField)
                    .isEqualTo(AccountUpdateResponse.ScreenField.ACSTTUS.label());

            AccountUpdateController.Conversation noChange = warmTask();
            controller.screenInit3100(noChange);
            noChange.wsReturnMsg = AccountUpdateController.atReturnWidth(
                    AccountUpdateController.MSG_NO_CHANGES_DETECTED);
            controller.positionCursor3300(noChange);
            assertThat(noChange.cursorField)
                    .isEqualTo(AccountUpdateResponse.ScreenField.ACSTTUS.label());

            AccountUpdateController.Conversation badKey = warmTask();
            controller.screenInit3100(badKey);
            badKey.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_NOT_OK;
            controller.positionCursor3300(badKey);
            assertThat(badKey.cursorField)
                    .isEqualTo(AccountUpdateResponse.ScreenField.ACCTSID.label());

            AccountUpdateController.Conversation blankKey = warmTask();
            controller.screenInit3100(blankKey);
            blankKey.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_BLANK;
            controller.positionCursor3300(blankKey);
            assertThat(blankKey.cursorField)
                    .isEqualTo(AccountUpdateResponse.ScreenField.ACCTSID.label());
        }

        @Test
        @DisplayName("The cursor otherwise lands on the first offending field in screen order")
        void cursorLandsOnTheFirstOffender() {
            AccountUpdateController.Conversation task = warmTask();
            controller.screenInit3100(task);
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
            task.setFlag(AccountUpdateResponse.ScreenField.ACSFNAM,
                    AccountUpdateController.FLG_NOT_OK);
            task.setFlag(AccountUpdateResponse.ScreenField.ACSTTUS,
                    AccountUpdateController.FLG_NOT_OK);

            controller.positionCursor3300(task);

            assertThat(task.cursorField)
                    .isEqualTo(AccountUpdateResponse.ScreenField.ACSTTUS.label());
        }

        @Test
        @DisplayName("The middle name's cursor arm tests NOT-OK only - there is no BLANK arm")
        void middleNameCursorArmHasNoBlankTest() {
            AccountUpdateController.Conversation notOk = warmTask();
            assertThat(controller.cursorArmMatches(notOk,
                    AccountUpdateResponse.ScreenField.ACSMNAM)).isFalse();
            notOk.setFlag(AccountUpdateResponse.ScreenField.ACSMNAM,
                    AccountUpdateController.FLG_NOT_OK);
            assertThat(controller.cursorArmMatches(notOk,
                    AccountUpdateResponse.ScreenField.ACSMNAM)).isTrue();

            AccountUpdateController.Conversation blank = warmTask();
            blank.setFlag(AccountUpdateResponse.ScreenField.ACSMNAM,
                    AccountUpdateController.FLG_BLANK);
            assertThat(controller.cursorArmMatches(blank,
                    AccountUpdateResponse.ScreenField.ACSMNAM))
                    .describedAs("a blank middle name never takes the cursor")
                    .isFalse();

            AccountUpdateController.Conversation other = warmTask();
            other.setFlag(AccountUpdateResponse.ScreenField.ACSFNAM,
                    AccountUpdateController.FLG_BLANK);
            assertThat(controller.cursorArmMatches(other,
                    AccountUpdateResponse.ScreenField.ACSFNAM)).isTrue();
        }

        @Test
        @DisplayName("The cursor's WHEN OTHER falls back to the account filter")
        void cursorWhenOtherFallsBackToTheKey() {
            AccountUpdateController.Conversation task = warmTask();
            controller.screenInit3100(task);
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
            task.clearNonKeyFlags();
            controller.positionCursor3300(task);

            assertThat(task.cursorField)
                    .isEqualTo(AccountUpdateResponse.ScreenField.ACCTSID.label());
        }

        @Test
        @DisplayName("Arriving from the card list restores the filter's default colour")
        void arrivingFromTheCardListResetsTheColour() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
            task.carddemoCommarea = task.carddemoCommarea.withLastMapset(
                    at(AccountUpdateController.LIT_CCLISTMAPSET,
                            NavigationContext.LAST_MAPSET_LENGTH));
            controller.screenInit3100(task);

            controller.setupScreenAttrs3300(task);

            assertThat(task.cactupao.attributes(AccountUpdateResponse.ScreenField.ACCTSID)
                    .getColour()).isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("The filter's NOT-OK colour is written WITHOUT the REENTER guard, unlike CSSETATY")
        void filterColourIsNotGuardedByReenter() {
            AccountUpdateController.Conversation task = warmTask();
            task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_NOT_OK;
            controller.screenInit3100(task);

            controller.setupScreenAttrs3300(task);

            assertThat(task.cactupao.attributes(AccountUpdateResponse.ScreenField.ACCTSID)
                    .getColour())
                    .describedAs("red even on a first entry")
                    .isEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("The filter's BLANK case does carry the guard, and writes the asterisk as well")
        void filterBlankIsGuardedAndWritesAnAsterisk() {
            AccountUpdateController.Conversation reentered = warmTask();
            reentered.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_BLANK;
            controller.screenInit3100(reentered);
            controller.setupScreenAttrs3300(reentered);
            assertThat(reentered.cactupao.value(AccountUpdateResponse.ScreenField.ACCTSID))
                    .startsWith(com.vsergeychik.carddemo.common.FieldAttributeSetter.ASTERISK);
            assertThat(reentered.cactupao.attributes(AccountUpdateResponse.ScreenField.ACCTSID)
                    .getColour()).isEqualTo(BmsAttributes.DFHRED);

            AccountUpdateController.Conversation entered = warmTask();
            entered.carddemoCommarea = entered.carddemoCommarea.withPgmEnter();
            entered.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_BLANK;
            controller.screenInit3100(entered);
            controller.setupScreenAttrs3300(entered);
            assertThat(entered.cactupao.value(AccountUpdateResponse.ScreenField.ACCTSID))
                    .describedAs("no asterisk on a first entry")
                    .doesNotStartWith(
                            com.vsergeychik.carddemo.common.FieldAttributeSetter.ASTERISK);
        }

        @Test
        @DisplayName("A bad filter short-circuits before the thirty-nine highlight sites run")
        void badFilterSkipsTheHighlights() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_NOT_OK;
            task.setFlag(AccountUpdateResponse.ScreenField.ACSTTUS,
                    AccountUpdateController.FLG_NOT_OK);
            controller.screenInit3100(task);

            controller.setupScreenAttrs3300(task);

            assertThat(task.cactupao.attributes(AccountUpdateResponse.ScreenField.ACSTTUS)
                    .isRedHighlighted())
                    .describedAs(":3186-3191 returns before the CSSETATY block")
                    .isFalse();
        }

        @Test
        @DisplayName("G38: the DFHRED highlight is applied only in REENTER state")
        void highlightOnlyInReenter() {
            AccountUpdateController.Conversation reentered = warmTask();
            reentered.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            reentered.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
            reentered.setFlag(AccountUpdateResponse.ScreenField.ACSTTUS,
                    AccountUpdateController.FLG_NOT_OK);
            controller.screenInit3100(reentered);
            controller.setupScreenAttrs3300(reentered);
            assertThat(reentered.cactupao.attributes(AccountUpdateResponse.ScreenField.ACSTTUS)
                    .getColour()).isEqualTo(BmsAttributes.DFHRED);

            AccountUpdateController.Conversation entered = warmTask();
            entered.carddemoCommarea = entered.carddemoCommarea.withPgmEnter();
            entered.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            entered.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
            entered.setFlag(AccountUpdateResponse.ScreenField.ACSTTUS,
                    AccountUpdateController.FLG_NOT_OK);
            controller.screenInit3100(entered);
            controller.setupScreenAttrs3300(entered);
            assertThat(entered.cactupao.attributes(AccountUpdateResponse.ScreenField.ACSTTUS)
                    .getColour())
                    .describedAs("CSSETATY's guard is CDEMO-PGM-REENTER")
                    .isNotEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("G38: the asterisk appears for BLANK only, never for NOT-OK")
        void asteriskOnlyForBlank() {
            AccountUpdateController.Conversation blank = warmTask();
            blank.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            blank.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
            blank.setFlag(AccountUpdateResponse.ScreenField.ACSTTUS,
                    AccountUpdateController.FLG_BLANK);
            controller.screenInit3100(blank);
            controller.setupScreenAttrs3300(blank);
            assertThat(blank.cactupao.getAcsttus())
                    .isEqualTo(com.vsergeychik.carddemo.common.FieldAttributeSetter.ASTERISK);

            AccountUpdateController.Conversation notOk = warmTask();
            notOk.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            notOk.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
            notOk.setFlag(AccountUpdateResponse.ScreenField.ACSTTUS,
                    AccountUpdateController.FLG_NOT_OK);
            controller.screenInit3100(notOk);
            controller.setupScreenAttrs3300(notOk);
            assertThat(notOk.cactupao.getAcsttus())
                    .describedAs("red, but no asterisk")
                    .isNotEqualTo(com.vsergeychik.carddemo.common.FieldAttributeSetter.ASTERISK);
        }

        @Test
        @DisplayName("3390: the information line is hidden when empty and revealed when not")
        void informationLineVisibility() {
            AccountUpdateController.Conversation empty = warmTask();
            controller.screenInit3100(empty);
            empty.wsInfoMsg = AccountUpdateController.atInfoWidth("");
            controller.setupInfomsgAttrs3390(empty);

            AccountUpdateController.Conversation shown = warmTask();
            controller.screenInit3100(shown);
            shown.wsInfoMsg = AccountUpdateController.atInfoWidth(
                    AccountUpdateController.INFO_PROMPT_FOR_CHANGES);
            controller.setupInfomsgAttrs3390(shown);

            assertThat(empty.cactupao.attributes(AccountUpdateResponse.ScreenField.INFOMSG)
                    .getHilight())
                    .isEqualTo(BmsAttributes.DFHBMDAR);
            assertThat(shown.cactupao.attributes(AccountUpdateResponse.ScreenField.INFOMSG)
                    .getHilight())
                    .isEqualTo(BmsAttributes.DFHBMASB);
        }

        @Test
        @DisplayName("3390: the cancel legend appears while a change is pending, the save one at confirm")
        void functionKeyLegends() {
            AccountUpdateController.Conversation pending = warmTask();
            controller.screenInit3100(pending);
            pending.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            pending.wsInfoMsg = AccountUpdateController.atInfoWidth(
                    AccountUpdateController.INFO_PROMPT_FOR_CONFIRMATION);
            controller.setupInfomsgAttrs3390(pending);
            assertThat(pending.cactupao.isSaveLegendRevealed()).isTrue();
            assertThat(pending.cactupao.isCancelLegendRevealed()).isTrue();

            AccountUpdateController.Conversation done = warmTask();
            controller.screenInit3100(done);
            done.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkayedAndDone();
            done.wsInfoMsg = AccountUpdateController.atInfoWidth(
                    AccountUpdateController.INFO_CONFIRM_UPDATE_SUCCESS);
            controller.setupInfomsgAttrs3390(done);
            assertThat(done.cactupao.isCancelLegendRevealed())
                    .describedAs(":3573 excludes the committed state")
                    .isFalse();
            assertThat(done.cactupao.isSaveLegendRevealed()).isFalse();
        }

        @Test
        @DisplayName("3400 names this screen's mapset and map, truncating the eight-byte literal to seven")
        void sendScreenSetsTheNextTarget() {
            AccountUpdateController.Conversation task = warmTask();
            controller.sendScreen3400(task);

            assertThat(task.ccWorkArea.getCcardNextMapset())
                    .isEqualTo(AccountUpdateController.LIT_THISMAPSET.trim());
            assertThat(task.ccWorkArea.getCcardNextMap())
                    .isEqualTo(AccountUpdateController.LIT_THISMAP);
        }

        private AccountUpdateRequest.FieldMetadata metadataFor(
                AccountUpdateController.Conversation task,
                AccountUpdateResponse.ScreenField field) {
            return task.cactupai.metadata(
                    AccountUpdateRequest.ScreenField.valueOf(field.name()));
        }
    }

    @Nested
    @DisplayName("1100-RECEIVE-MAP and 1200-EDIT-MAP-INPUTS end to end")
    class ReceiveAndEdit {
        @Test
        @DisplayName("notSupplied: spaces and the asterisk marker both mean the operator typed nothing")
        void notSuppliedArms() {
            assertThat(AccountUpdateController.notSupplied("   ", 3)).isTrue();
            assertThat(AccountUpdateController.notSupplied("*  ", 3)).isTrue();
            assertThat(AccountUpdateController.notSupplied(null, 3)).isTrue();
            assertThat(AccountUpdateController.notSupplied("Y  ", 3)).isFalse();
            assertThat(AccountUpdateController.notSupplied("*", 1)).isTrue();
        }

        @Test
        @DisplayName("receiveField: an unsupplied field becomes LOW-VALUES, a supplied one is moved")
        void receiveFieldArms() {
            AccountUpdateRequest received = AccountUpdateRequest.initial()
                    .withValue(AccountUpdateRequest.ScreenField.ACSTTUS, "Y");
            assertThat(controller.receiveField(received,
                    AccountUpdateRequest.ScreenField.ACSTTUS, 1)).isEqualTo("Y");

            AccountUpdateRequest blank = AccountUpdateRequest.initial()
                    .withValue(AccountUpdateRequest.ScreenField.ACSTTUS, " ");
            assertThat(controller.receiveField(blank,
                    AccountUpdateRequest.ScreenField.ACSTTUS, 1))
                    .isEqualTo(AccountUpdateController.lowValues(1));
        }

        @Test
        @DisplayName("A full valid screen edits clean and is promoted to awaiting confirmation")
        void aFullValidScreenPassesEveryEdit() {
            stubAllFound();
            AccountUpdateController.PaintedScreen painted = controller.handle(
                    fullyValidScreen().withValue(AccountUpdateRequest.ScreenField.ACSTTUS, "N"),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(painted.response().changeAction().isChangesOkNotConfirmed())
                    .describedAs("edits clean and a change detected")
                    .isTrue();
            assertThat(painted.response().getErrmsg().trim()).isEmpty();
            assertThat(painted.response().getInfomsg().trim())
                    .isEqualTo(AccountUpdateController.INFO_PROMPT_FOR_CONFIRMATION.trim());
            assertThat(painted.response().isSaveLegendRevealed()).isTrue();
        }

        @Test
        @DisplayName("Two turns: the first fetches, the second echoes it back and reports no change")
        void anUnchangedScreenReportsNoChange() {
            stubAllFound();
            AccountUpdateController.PaintedScreen first = controller.handle(
                    request(ACCT, reenter()).withCommArea(
                            AccountUpdateRequest.CommArea.initialised().withChangeAction(
                                    AccountUpdateRequest.ChangeAction.spacesState())),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            assertThat(first.response().changeAction().isShowDetails()).isTrue();

            AccountUpdateController.PaintedScreen second = controller.handle(
                    echoBack(first.response()),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(second.response().getErrmsg().trim())
                    .isEqualTo(AccountUpdateController.MSG_NO_CHANGES_DETECTED.trim());
            assertThat(second.response().changeAction().isShowDetails())
                    .describedAs("2000 keeps the screen where it is when nothing changed")
                    .isTrue();
        }

        @Test
        @DisplayName("A ten-character stored zip can never round-trip through the five-character field")
        void zipCannotRoundTripThroughTheScreen() {
            assertThat(AccountUpdateRequest.ACSZIPC_LENGTH).isEqualTo(5);
            assertThat(AccountUpdateRequest.CustSnapshot.ADDR_ZIP_LENGTH).isEqualTo(10);

            CustomerRecord wide = customer();
            wide.setCustAddrZip("9810112345");
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.found(wide, CUST_IMAGE));

            AccountUpdateController.PaintedScreen first = controller.handle(
                    request(ACCT, reenter()).withCommArea(
                            AccountUpdateRequest.CommArea.initialised().withChangeAction(
                                    AccountUpdateRequest.ChangeAction.spacesState())),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            AccountUpdateController.PaintedScreen second = controller.handle(
                    echoBack(first.response()),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(second.response().changeAction().isChangesOkNotConfirmed())
                    .describedAs("the truncated zip reads as a change")
                    .isTrue();
        }

        @Test
        @DisplayName("An optional field left blank comes back as LOW-VALUES and reads as a change")
        void anOptionalBlankFieldReadsAsAChange() {
            CustomerRecord blankMiddle = customer();
            blankMiddle.setCustMiddleName(" ");
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.found(blankMiddle, CUST_IMAGE));

            AccountUpdateController.PaintedScreen first = controller.handle(
                    request(ACCT, reenter()).withCommArea(
                            AccountUpdateRequest.CommArea.initialised().withChangeAction(
                                    AccountUpdateRequest.ChangeAction.spacesState())),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            AccountUpdateController.PaintedScreen second = controller.handle(
                    echoBack(first.response()),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(second.response().changeAction().isChangesOkNotConfirmed()).isTrue();
            assertThat(second.response().getErrmsg().trim())
                    .describedAs("the middle name is optional, so no edit rejects it")
                    .isEmpty();
        }

        @Test
        @DisplayName("Two turns: changing one field on the echoed screen is detected")
        void changingOneFieldOnTheEchoedScreenIsDetected() {
            stubAllFound();
            AccountUpdateController.PaintedScreen first = controller.handle(
                    request(ACCT, reenter()).withCommArea(
                            AccountUpdateRequest.CommArea.initialised().withChangeAction(
                                    AccountUpdateRequest.ChangeAction.spacesState())),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            AccountUpdateController.PaintedScreen second = controller.handle(
                    echoBack(first.response())
                            .withValue(AccountUpdateRequest.ScreenField.ACSTTUS, "N"),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(second.response().changeAction().isChangesOkNotConfirmed()).isTrue();
            assertThat(second.response().getErrmsg().trim()).isEmpty();
            assertThat(second.response().getInfomsg().trim())
                    .isEqualTo(AccountUpdateController.INFO_PROMPT_FOR_CONFIRMATION.trim());
        }

        @Test
        @DisplayName("Three turns: echo, change, then PF5 saves through the service")
        void pf5OnTheThirdTurnSaves() {
            stubAllFound();
            when(service.writeProcessing(anyString(), any(), any(), any(), anyString(), any()))
                    .thenReturn(new AccountUpdateService.WriteResult(
                            AccountUpdateService.WriteOutcome.CHANGES_OKAYED_AND_DONE, false, false,
                            AccountUpdateController.WS_RETURN_MSG_OFF, FileStatus.OK,
                            java.util.OptionalInt.empty(), Optional.empty(), Optional.empty(),
                            Optional.of("A".repeat(
                                    AccountUpdateService.ACCT_UPDATE_RECORD_LENGTH)),
                            Optional.of("C".repeat(
                                    AccountUpdateService.CUST_UPDATE_RECORD_LENGTH)),
                            Optional.of(AccountUpdateService.ChangeCheck.unchanged())));

            AccountUpdateController.PaintedScreen fetched = controller.handle(
                    request(ACCT, reenter()).withCommArea(
                            AccountUpdateRequest.CommArea.initialised().withChangeAction(
                                    AccountUpdateRequest.ChangeAction.spacesState())),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            AccountUpdateController.PaintedScreen edited = controller.handle(
                    echoBack(fetched.response())
                            .withValue(AccountUpdateRequest.ScreenField.ACSTTUS, "N"),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            AccountUpdateController.PaintedScreen saved = controller.handle(
                    echoBack(edited.response()),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF5);

            verify(service).writeProcessing(anyString(), any(), any(), any(), anyString(), any());
            assertThat(saved.response().changeAction().isChangesOkayedAndDone()).isTrue();
            assertThat(saved.response().getInfomsg().trim())
                    .isEqualTo(AccountUpdateController.INFO_CONFIRM_UPDATE_SUCCESS.trim());
        }

        @Test
        @DisplayName("The first failing edit owns the message, in 1200's source order")
        void theFirstFailureOwnsTheMessage() {
            stubAllFound();
            AccountUpdateRequest received = fullyValidScreen()
                    .withValue(AccountUpdateRequest.ScreenField.ACSTTUS, "X")
                    .withValue(AccountUpdateRequest.ScreenField.ACSFNAM, "ANN3");
            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(painted.response().getErrmsg().trim())
                    .isEqualTo(AccountUpdateController.trim(
                            AccountUpdateController.NAME_ACCOUNT_STATUS
                                    + AccountUpdateController.MSG_MUST_BE_Y_OR_N));
        }

        @Test
        @DisplayName("An invalid date reaches CSUTLDPY and the message names the date field")
        void anInvalidDateIsReportedByTheValidator() {
            stubAllFound();
            AccountUpdateRequest received = fullyValidScreen()
                    .withValue(AccountUpdateRequest.ScreenField.OPNMON, "13");
            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(painted.response().getErrmsg().trim())
                    .describedAs("the date edit is delegated to AccountDateValidator")
                    .isNotEmpty()
                    .contains(AccountUpdateController.NAME_OPEN_DATE);
        }

        @Test
        @DisplayName("A rejected monetary field is echoed back exactly as it was keyed")
        void aRejectedAmountIsEchoedBack() {
            stubAllFound();
            AccountUpdateRequest received = fullyValidScreen()
                    .withValue(AccountUpdateRequest.ScreenField.ACRDLIM, "12.3.4");
            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(painted.response().getErrmsg().trim())
                    .contains(AccountUpdateController.NAME_CREDIT_LIMIT);
            assertThat(painted.response().getAcrdlim().trim()).isEqualTo("12.3.4");
        }

        @Test
        @DisplayName("The five monetary fields are separate storage, so a blank one is left alone (G34)")
        void unsuppliedMonetarySpansAreLeftAlone() {
            stubAllFound();
            AccountUpdateRequest received = fullyValidScreen()
                    .withValue(AccountUpdateRequest.ScreenField.ACRDLIM, " ");
            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(painted.response().getErrmsg().trim())
                    .isEqualTo(AccountUpdateController.trim(
                            AccountUpdateController.NAME_CREDIT_LIMIT
                                    + AccountUpdateController.MSG_MUST_BE_SUPPLIED));
        }

        @Test
        @DisplayName("The X and -N views of a monetary span round-trip through one another (G34)")
        void monetaryRedefinesRoundTrip() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewAcct.setCreditLimitN(new BigDecimal("5000.00"));
            assertThat(task.acupNewAcct.creditLimit)
                    .hasSize(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            assertThat(task.acupNewAcct.creditLimitN())
                    .isEqualByComparingTo(new BigDecimal("5000.00"));

            task.acupNewAcct.setCashCreditLimitN(new BigDecimal("-250.00"));
            assertThat(task.acupNewAcct.cashCreditLimitN())
                    .isEqualByComparingTo(new BigDecimal("-250.00"));

            task.acupNewAcct.currBal =
                    AccountUpdateController.spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            assertThat(task.acupNewAcct.currBalN()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("The account id's X and 9 views agree, and a blank key reads as zero")
        void accountIdRedefinesRoundTrip() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewAcct.setAcctIdN(11L);
            assertThat(task.acupNewAcct.acctIdX).isEqualTo(ACCT);
            assertThat(task.acupNewAcct.acctIdN()).isEqualTo(11L);

            task.acupNewAcct.acctIdX =
                    AccountUpdateController.spaces(AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
            assertThat(task.acupNewAcct.acctIdN()).isZero();
        }

        @Test
        @DisplayName("The composite fields stay split on the wire - dates, SSN, DOB and both phones")
        void compositeFieldsStaySplit() {
            stubAllFound();
            AccountUpdateController.PaintedScreen painted = controller.handle(fullyValidScreen(),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            AccountUpdateResponse response = painted.response();

            assertThat(response.getOpnyear()).isEqualTo("2020");
            assertThat(response.getOpnmon()).isEqualTo("01");
            assertThat(response.getOpnday()).isEqualTo("15");
            assertThat(response.getActssn1()).isEqualTo("123");
            assertThat(response.getActssn2()).isEqualTo("45");
            assertThat(response.getActssn3()).isEqualTo("6789");
            assertThat(response.getDobyear()).isEqualTo("1980");
            assertThat(response.getAcsph1a()).isEqualTo("206");
            assertThat(response.getAcsph1b()).isEqualTo("555");
            assertThat(response.getAcsph1c()).isEqualTo("1234");
            assertThat(response.getAcsph2a()).isEqualTo("206");
        }
    }

    private AccountUpdateRequest echoBack(AccountUpdateResponse response) {
        AccountUpdateRequest next = AccountUpdateRequest.initial()
                .withCommArea(response.getCommArea())
                .withCardScreenState(response.getCardScreenState())
                .withNavigationContext(response.getNavigationContext());
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.FIELDS) {
            next = next.withValue(AccountUpdateRequest.ScreenField.valueOf(field.name()),
                    response.value(field));
        }
        return next;
    }

    private AccountUpdateRequest fullyValidScreen() {
        return request(ACCT, reenter())
                .withCommArea(AccountUpdateRequest.CommArea.initialised()
                        .withChangeAction(AccountUpdateRequest.ChangeAction.showDetails()))
                .withValue(AccountUpdateRequest.ScreenField.ACSTTUS, "Y")
                .withValue(AccountUpdateRequest.ScreenField.OPNYEAR, "2020")
                .withValue(AccountUpdateRequest.ScreenField.OPNMON, "01")
                .withValue(AccountUpdateRequest.ScreenField.OPNDAY, "15")
                .withValue(AccountUpdateRequest.ScreenField.EXPYEAR, "2025")
                .withValue(AccountUpdateRequest.ScreenField.EXPMON, "01")
                .withValue(AccountUpdateRequest.ScreenField.EXPDAY, "14")
                .withValue(AccountUpdateRequest.ScreenField.RISYEAR, "2022")
                .withValue(AccountUpdateRequest.ScreenField.RISMON, "06")
                .withValue(AccountUpdateRequest.ScreenField.RISDAY, "30")
                .withValue(AccountUpdateRequest.ScreenField.ACRDLIM, "5000.00")
                .withValue(AccountUpdateRequest.ScreenField.ACSHLIM, "-250.00")
                .withValue(AccountUpdateRequest.ScreenField.ACURBAL, "1234.56")
                .withValue(AccountUpdateRequest.ScreenField.ACRCYCR, "0.00")
                .withValue(AccountUpdateRequest.ScreenField.ACRCYDB, "87.05")
                .withValue(AccountUpdateRequest.ScreenField.AADDGRP, "ZEROPCT")
                .withValue(AccountUpdateRequest.ScreenField.ACTSSN1, "123")
                .withValue(AccountUpdateRequest.ScreenField.ACTSSN2, "45")
                .withValue(AccountUpdateRequest.ScreenField.ACTSSN3, "6789")
                .withValue(AccountUpdateRequest.ScreenField.DOBYEAR, "1980")
                .withValue(AccountUpdateRequest.ScreenField.DOBMON, "02")
                .withValue(AccountUpdateRequest.ScreenField.DOBDAY, "29")
                .withValue(AccountUpdateRequest.ScreenField.ACSTFCO, "750")
                .withValue(AccountUpdateRequest.ScreenField.ACSFNAM, "ANNE")
                .withValue(AccountUpdateRequest.ScreenField.ACSMNAM, "Q")
                .withValue(AccountUpdateRequest.ScreenField.ACSLNAM, "ARCHER")
                .withValue(AccountUpdateRequest.ScreenField.ACSADL1, "1 MAIN STREET")
                .withValue(AccountUpdateRequest.ScreenField.ACSSTTE, "WA")
                .withValue(AccountUpdateRequest.ScreenField.ACSADL2, "APT 2B")
                .withValue(AccountUpdateRequest.ScreenField.ACSZIPC, "98101")
                .withValue(AccountUpdateRequest.ScreenField.ACSCITY, "SEATTLE")
                .withValue(AccountUpdateRequest.ScreenField.ACSCTRY, "USA")
                .withValue(AccountUpdateRequest.ScreenField.ACSPH1A, "206")
                .withValue(AccountUpdateRequest.ScreenField.ACSPH1B, "555")
                .withValue(AccountUpdateRequest.ScreenField.ACSPH1C, "1234")
                .withValue(AccountUpdateRequest.ScreenField.ACSGOVT, "WA-DL-0099887766")
                .withValue(AccountUpdateRequest.ScreenField.ACSPH2A, "206")
                .withValue(AccountUpdateRequest.ScreenField.ACSPH2B, "555")
                .withValue(AccountUpdateRequest.ScreenField.ACSPH2C, "9876")
                .withValue(AccountUpdateRequest.ScreenField.ACSEFTC, "0000000001")
                .withValue(AccountUpdateRequest.ScreenField.ACSPFLG, "Y");
    }

    @Nested
    @DisplayName("ABEND-ROUTINE and the HANDLE ABEND wrapper")
    class Abends {
        @Test
        @DisplayName("An explicit abend carries its code and its message")
        void explicitAbend() {
            AccountUpdateController.Conversation task = warmTask();

            AbendException abend = controller.abendRoutine(task,
                    AccountUpdateController.ABEND_CODE_UNEXPECTED_DATA,
                    AccountUpdateController.ABEND_MSG_UNEXPECTED_DATA_SCENARIO);

            assertThat(abend.getProgram()).isEqualTo(AccountUpdateController.LIT_THISPGM);
            assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(abend).hasMessageContaining(
                    AccountUpdateController.ABEND_MSG_UNEXPECTED_DATA_SCENARIO);
        }

        @Test
        @DisplayName("The default message is unreachable: ABEND-MSG is declared VALUE SPACES")
        void theDefaultMessageIsDeadCode() {
            AccountUpdateController.Conversation task = warmTask();
            assertThat(task.abendData.abendMsg())
                    .isEqualTo(AccountUpdateController.spaces(
                            SystemMessages.ABEND_MSG_LENGTH));

            AbendException abend = controller.abendRoutine(task,
                    AccountUpdateController.ABEND_CODE_UNEXPECTED_DATA,
                    AccountUpdateController.ABEND_MSG_UNEXPECTED_DATA_SCENARIO);
            assertThat(abend.getMessage())
                    .doesNotContain(AccountUpdateController.UNEXPECTED_ABEND_OCCURRED);
        }

        @Test
        @DisplayName("An unexpected runtime failure is translated by the HANDLE ABEND wrapper")
        void runtimeFailureBecomesAnAbend() {
            when(xrefs.readByAccountIdViaAltIndex(anyString()))
                    .thenThrow(new IllegalStateException("backend unavailable"));
            AccountUpdateRequest received = request(ACCT, reenter())
                    .withCommArea(AccountUpdateRequest.CommArea.initialised()
                            .withChangeAction(AccountUpdateRequest.ChangeAction.spacesState()));

            assertThatThrownBy(() -> controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER))
                    .isInstanceOf(AbendException.class)
                    .hasRootCauseMessage("backend unavailable");
        }

        @Test
        @DisplayName("An abend already in flight is rethrown unchanged, not wrapped twice")
        void abendsAreNotWrappedTwice() {
            AbendException inFlight = AbendException.withoutAbendParameters(
                    AccountUpdateController.LIT_THISPGM, AbendException.RETURN_CODE_IO_ERROR,
                    "already abending");
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenThrow(inFlight);
            AccountUpdateRequest received = request(ACCT, reenter())
                    .withCommArea(AccountUpdateRequest.CommArea.initialised()
                            .withChangeAction(AccountUpdateRequest.ChangeAction.spacesState()));

            assertThatThrownBy(() -> controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER))
                    .isSameAs(inFlight);
        }
    }

    @Nested
    @DisplayName("Statelessness and the HTTP contract")
    class StatelessnessAndHttp {
        @Test
        @DisplayName("G37: two concurrent turns cannot see one another's work area")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void concurrentTurnsAreIndependent() {
            stubAllFound();

            List<AccountUpdateResponse> responses = ConcurrentTasks.runBoth(
                    () -> controller.handle(
                            fullyValidScreen().withValue(AccountUpdateRequest.ScreenField.ACSTTUS, "N"),
                            AccountUpdateController.PASSED_COMMAREA_LENGTH,
                            CicsAid.DFHENTER).response(),
                    () -> controller.handle(
                            request(" ", null), AccountUpdateController.NO_COMMAREA_LENGTH,
                            CicsAid.DFHENTER).response());

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).changeAction().isChangesOkNotConfirmed())
                    .as("the first turn typed a whole valid screen, so it awaits confirmation").isTrue();
            assertThat(responses.get(1).changeAction().isDetailsNotFetched())
                    .as("the second is a cold start with no commarea, so it has fetched nothing")
                    .isTrue();
        }

        @Test
        @DisplayName("G37: the whole conversation travels in the payload, so a turn can be replayed")
        void theConversationIsCarriedInThePayload() {
            stubAllFound();
            AccountUpdateController.PaintedScreen first = controller.handle(
                    request(ACCT, reenter()).withCommArea(
                            AccountUpdateRequest.CommArea.initialised().withChangeAction(
                                    AccountUpdateRequest.ChangeAction.spacesState())),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(first.response().hasNavigationContext()).isTrue();
            assertThat(first.response().commareaLength())
                    .isEqualTo(AccountUpdateController.PASSED_COMMAREA_LENGTH);
            assertThat(first.response().changeAction().isShowDetails()).isTrue();

            AccountUpdateController.PaintedScreen replay = controller.handle(
                    request(ACCT, reenter()).withCommArea(
                            AccountUpdateRequest.CommArea.initialised().withChangeAction(
                                    AccountUpdateRequest.ChangeAction.spacesState())),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            assertThat(replay.response().fieldValues())
                    .isEqualTo(first.response().fieldValues());
        }

        private MockMvc http() {
            return MockMvcBuilders.standaloneSetup(controller)
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                    .build();
        }

        @Test
        @DisplayName("The endpoint is PUT /api/accounts/{acctId}, the CSD's CAUP transaction")
        void endpointMapping() throws Exception {
            stubAllFound();
            MockMvc mockMvc = http();

            mockMvc.perform(put(AccountUpdateController.ACCOUNT_UPDATE_PATH, ACCT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(MAPPER.writeValueAsString(fullyValidScreen())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trnname").value(AccountUpdateController.LIT_THISTRANID))
                    .andExpect(jsonPath("$.acctsid").value(ACCT))
                    .andExpect(jsonPath("$.screenMetadata").exists());
        }

        @Test
        @DisplayName("MockMvc: all fifty-four response fields are present in the payload")
        void allFieldsAreSerialised() throws Exception {
            stubAllFound();
            MockMvc mockMvc = http();

            String body = mockMvc.perform(put(AccountUpdateController.ACCOUNT_UPDATE_PATH, ACCT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(MAPPER.writeValueAsString(fullyValidScreen())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = MAPPER.readTree(body);
            assertThat(AccountUpdateResponse.FIELDS).hasSize(54);
            for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.FIELDS) {
                assertThat(json.has(field.label().toLowerCase(Locale.ROOT)))
                        .describedAs("%s is a payload field", field.label())
                        .isTrue();
            }
            assertThat(json.has("acctsidl")).isFalse();
            assertThat(json.has("acctsida")).isFalse();
            assertThat(json.has("acctsidc")).isFalse();
        }

        @Test
        @DisplayName("screenMetadataOf publishes the cursor, the attribute quads and the message colour")
        void screenMetadataProjection() {
            stubAllFound();
            AccountUpdateController.PaintedScreen painted = controller.handle(
                    fullyValidScreen().withValue(AccountUpdateRequest.ScreenField.ACSTTUS, "X"),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            ScreenMetadata metadata = controller.screenMetadataOf(
                    painted.response(), painted.request(), painted.cursorField());

            assertThat(metadata.cursorField())
                    .isEqualTo(AccountUpdateResponse.ScreenField.ACSTTUS.label());
            assertThat(metadata.field(AccountUpdateResponse.ScreenField.ACSTTUS.label()))
                    .isNotNull();
            assertThat(metadata.fields()).isNotEmpty();
        }

        @Test
        @DisplayName("A null cursor field - the PF3 path, where 3300 never ran - is still projected")
        void metadataWithoutACursor() {
            AccountUpdateController.PaintedScreen painted = controller.handle(
                    request(ACCT, enter()), AccountUpdateController.PASSED_COMMAREA_LENGTH,
                    CicsAid.DFHPF3);

            assertThat(painted.cursorField()).isNull();
            ScreenMetadata metadata = controller.screenMetadataOf(
                    painted.response(), painted.request(), painted.cursorField());
            assertThat(metadata).isNotNull();
        }

        @Test
        @DisplayName("There is no session state of any kind on this controller")
        void noSessionState() throws Exception {
            for (Field field : AccountUpdateController.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .describedAs("static field %s must be final", field.getName())
                            .isTrue();
                } else {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .describedAs("instance field %s must be final", field.getName())
                            .isTrue();
                }
            }
            assertThat(AccountUpdateController.class.getAnnotations())
                    .noneMatch(a -> a.annotationType().getSimpleName()
                            .equals("SessionAttributes"));
            assertThat(AccountUpdateController.class.getAnnotations())
                    .noneMatch(a -> a.annotationType().getSimpleName().equals("Scope")
                            || a.annotationType().getSimpleName().equals("SessionScope"));
            for (Field field : AccountUpdateController.class.getDeclaredFields()) {
                assertThat(ThreadLocal.class.isAssignableFrom(field.getType()))
                        .describedAs("field %s must not be a ThreadLocal", field.getName())
                        .isFalse();
            }
            for (Method method : AccountUpdateController.class.getDeclaredMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName())
                            .describedAs("%s takes no session-bearing parameter", method.getName())
                            .doesNotContain("HttpSession")
                            .doesNotContain("HttpServletRequest")
                            .doesNotContain("WebRequest");
                }
            }
        }
    }

    @Nested
    @DisplayName("Remaining arms")
    class RemainingArms {
        @Test
        @DisplayName("1200 on the search-key turn edits only the filter and blanks the OLD group")
        void searchKeyTurnEditsOnlyTheFilter() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            task.acupOldAcct.activeStatus = "Y";

            controller.editMapInputs1200(task);

            assertThat(task.acupOldAcct.activeStatus)
                    .isEqualTo(AccountUpdateController.lowValues(1));
            assertThat(task.flgAcctfilterIsvalid()).isTrue();
            assertThat(task.inputError()).isFalse();
        }

        @Test
        @DisplayName("1200 on the search-key turn with a blank filter reports 'No input received'")
        void searchKeyTurnWithNoInput() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            task.ccWorkArea.setCcAcctIdToLowValues();

            controller.editMapInputs1200(task);

            assertThat(task.flgAcctfilterBlank()).isTrue();
            assertThat(returnMessageOf(task))
                    .isEqualTo(AccountUpdateController.MSG_NO_SEARCH_CRITERIA_RECEIVED);
        }

        @ParameterizedTest(name = "1200 short-circuits for {0}")
        @ValueSource(strings = {"NO_CHANGE", "OK_NOT_CONFIRMED", "OKAYED_AND_DONE"})
        @DisplayName("1200 stops after 1205 in the three states that must not be re-edited")
        void editDriverShortCircuits(String state) {
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = switch (state) {
                case "OK_NOT_CONFIRMED" ->
                        AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
                case "OKAYED_AND_DONE" ->
                        AccountUpdateRequest.ChangeAction.changesOkayedAndDone();
                default -> AccountUpdateRequest.ChangeAction.showDetails();
            };
            controller.editMapInputs1200(task);

            assertThat(task.inputError())
                    .describedAs("the edits were skipped, so no field can have been rejected")
                    .isFalse();
            assertThat(task.flagIsvalid(AccountUpdateResponse.ScreenField.ACSTTUS)).isTrue();
        }

        @Test
        @DisplayName("1200 promotes to awaiting confirmation only when every edit passed")
        void editDriverPromotesOnlyWhenClean() {
            AccountUpdateController.Conversation clean = warmTask();
            stageConfirmableScreen(clean);
            clean.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();

            controller.editMapInputs1200(clean);

            assertThat(clean.inputError()).isFalse();
            assertThat(clean.acupChangeAction.isChangesOkNotConfirmed()).isTrue();
        }

        @Test
        @DisplayName("3202 paints when either half was read, and paints nothing when neither was")
        void originalValuesRequiresAReadHalf() {
            AccountUpdateController.Conversation neither = warmTask();
            controller.screenInit3100(neither);
            controller.showOriginalValues3202(neither);
            assertThat(neither.cactupao.getAcsttus())
                    .isEqualTo(AccountUpdateController.lowValues(1));

            AccountUpdateController.Conversation custOnly = warmTask();
            custOnly.wsCustMasterReadFlag = AccountUpdateController.FOUND_IN_MASTER;
            controller.screenInit3100(custOnly);
            controller.showOriginalValues3202(custOnly);
            assertThat(custOnly.cactupao.getAcurbal()).isEqualTo("+           .00");
        }

        @Test
        @DisplayName("1100 turns a wholly blank screen into LOW-VALUES throughout")
        void receiveMapWithNothingKeyed() {
            AccountUpdateController.Conversation task = warmTask();
            task.cactupai = AccountUpdateRequest.initial();

            controller.receiveMap1100(task);

            assertThat(task.acupNewAcct.activeStatus)
                    .isEqualTo(AccountUpdateController.lowValues(1));
            assertThat(task.acupNewCust.firstName).isEqualTo(AccountUpdateController.lowValues(
                    AccountUpdateRequest.CustSnapshot.NAME_LENGTH));
            assertThat(task.acupNewAcct.creditLimit)
                    .isEqualTo(AccountUpdateController.spaces(
                            AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH));
        }

        @Test
        @DisplayName("1100 moves every supplied field, and the staging items keep what was keyed")
        void receiveMapWithEverythingKeyed() {
            AccountUpdateController.Conversation task = warmTask();
            task.cactupai = fullyValidScreen();

            controller.receiveMap1100(task);

            assertThat(task.acupNewAcct.activeStatus).isEqualTo("Y");
            assertThat(task.acupNewAcct.openDate).isEqualTo("20200115");
            assertThat(task.acupNewCust.ssnX).isEqualTo("123456789");
            assertThat(task.acupNewCust.dobYyyyMmDd).isEqualTo("19800229");
            assertThat(AccountUpdateController.trim(task.acupNewCreditLimitX)).isEqualTo("5000.00");
            assertThat(task.acupNewAcct.creditLimitN())
                    .isEqualByComparingTo(new BigDecimal("5000.00"));
            assertThat(task.acupNewCust.phoneNum1A()).isEqualTo("206");
        }

        @Test
        @DisplayName("2000 arm 1 leaves the state alone when the customer read failed")
        void notFetchedWithAFailedCustomerRead() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.notFound());
            AccountUpdateController.Conversation task = warmTask();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;

            controller.decideAction2000(task);

            assertThat(task.acupChangeAction.isDetailsNotFetched())
                    .describedAs(":2576 promotes only when the customer was found")
                    .isTrue();
        }

        @Test
        @DisplayName("PF3: a set origin transaction with an unset origin program, and the reverse")
        void transferControlMixedOrigins() {
            AccountUpdateController.PaintedScreen tranidOnly = controller.handle(
                    request(ACCT, NavigationContext.empty().withPgmReenter()
                            .withFromTranid("CCLI")),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3);
            assertThat(tranidOnly.response().getNextProgram())
                    .describedAs("an unset program still falls back to the menu")
                    .isEqualTo(AccountUpdateController.LIT_MENUPGM);

            AccountUpdateController.PaintedScreen programOnly = controller.handle(
                    request(ACCT, NavigationContext.empty().withPgmReenter()
                            .withFromProgram(AccountUpdateController.LIT_CCLISTPGM)),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3);
            assertThat(programOnly.response().getNextProgram())
                    .isEqualTo(AccountUpdateController.LIT_CCLISTPGM);
        }

        @Test
        @DisplayName("The carried area is discarded on a cold start and on arrival from the menu")
        void restoreCommareaArms() {
            AccountUpdateController.Conversation cold =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            controller.initializeStorage(request(ACCT, reenter()), cold,
                    AccountUpdateController.NO_COMMAREA_LENGTH, CicsAid.DFHENTER);
            cold.acupOldAcct.activeStatus = "Y";
            controller.restoreCommarea(cold);
            assertThat(cold.acupOldAcct.activeStatus)
                    .describedAs("EIBCALEN of zero discards whatever the payload carried")
                    .isEqualTo(" ");
            assertThat(cold.carddemoCommarea.isEnter()).isTrue();

            AccountUpdateController.Conversation fromMenu =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            controller.initializeStorage(request(ACCT, NavigationContext.empty()
                            .withFromProgram(AccountUpdateController.LIT_MENUPGM).withPgmEnter()),
                    fromMenu, AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            fromMenu.acupOldAcct.activeStatus = "Y";
            controller.restoreCommarea(fromMenu);
            assertThat(fromMenu.acupOldAcct.activeStatus).isEqualTo(" ");
            assertThat(fromMenu.acupChangeAction.isDetailsNotFetched()).isTrue();

            AccountUpdateController.Conversation carried =
                    new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
            controller.initializeStorage(request(ACCT, reenter()), carried,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            carried.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            carried.acupOldAcct.activeStatus = "Y";
            controller.restoreCommarea(carried);
            assertThat(carried.acupOldAcct.activeStatus)
                    .describedAs("a genuine second turn keeps what it carried")
                    .isEqualTo("Y");
        }

        @Test
        @DisplayName("The date of birth takes CSUTLDPY's own not-in-the-future path")
        void dateOfBirthIsEditedAgainstTheClock() {
            stubAllFound();
            AccountUpdateController.PaintedScreen painted = controller.handle(
                    fullyValidScreen()
                            .withValue(AccountUpdateRequest.ScreenField.DOBYEAR, "2099"),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);

            assertThat(painted.response().getErrmsg().trim())
                    .describedAs("the fixed clock makes the verdict deterministic")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("An invalid SSN first group skips the exclusion test, whose guard needs a clean one")
        void ssnExclusionGuard() {
            AccountUpdateController.Conversation notNumeric = warmTask();
            notNumeric.acupNewCust.ssnX = "12X456789";
            controller.editUsSsn1265(notNumeric);
            assertThat(returnMessageOf(notNumeric)).isEqualTo("SSN: First 3 chars"
                    + AccountUpdateController.MSG_MUST_BE_ALL_NUMERIC);

            AccountUpdateController.Conversation excluded = warmTask();
            excluded.acupNewCust.ssnX = "666456789";
            controller.editUsSsn1265(excluded);
            assertThat(returnMessageOf(excluded)).isEqualTo("SSN: First 3 chars"
                    + AccountUpdateController.MSG_SSN_PART1_RANGE);
        }

        @Test
        @DisplayName("A phone part of LOW-VALUES reaches the blank arm of each of the three stages")
        void phoneLowValuesInEveryStage() {
            String base = "(206)555-1234  ";
            AccountUpdateController.Conversation prefix = warmTask();
            prefix.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_PHONE_NUMBER_2);
            prefix.wsEditUsPhoneNum =
                    AccountUpdateController.withPhonePrefix(base, "\u0000\u0000\u0000");
            controller.editUsPhoneNum1260(prefix);
            assertThat(prefix.wsEditEditUsPhoneb).isEqualTo(AccountUpdateController.FLG_BLANK);

            AccountUpdateController.Conversation line = warmTask();
            line.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_PHONE_NUMBER_2);
            line.wsEditUsPhoneNum = AccountUpdateController.withPhoneLine(base,
                    "\u0000\u0000\u0000\u0000");
            controller.editUsPhoneNum1260(line);
            assertThat(line.wsEditEditPhonec).isEqualTo(AccountUpdateController.FLG_BLANK);
        }

        @Test
        @DisplayName("The all-blank guard needs all three conditions, so a lone LOW-VALUES area code "
                + "still reaches the stages")
        void phoneGuardNeedsEveryCondition() {
            AccountUpdateController.Conversation task = warmTask();
            task.wsEditVariableName = AccountUpdateController.editVariableName(
                    AccountUpdateController.NAME_PHONE_NUMBER_1);
            task.wsEditUsPhoneNum = AccountUpdateController.withPhoneArea("(206)555-1234  ",
                    "\u0000\u0000\u0000");

            controller.editUsPhoneNum1260(task);

            assertThat(task.wsEditUsPhoneaFlg).isEqualTo(AccountUpdateController.FLG_BLANK);
            assertThat(task.wsEditEditUsPhoneb).isEqualTo(AccountUpdateController.FLG_ISVALID);
        }

        @Test
        @DisplayName("The reference-modification helpers accept a short, an empty and a null operand")
        void referenceModificationBoundaries() {
            assertThat(AccountUpdateController.slice("AB", 0, 4)).isEqualTo("AB  ");
            assertThat(AccountUpdateController.slice(null, 0, 2)).isEqualTo("  ");
            assertThat(AccountUpdateController.splice("AB", 2, 2, "CD")).isEqualTo("ABCD");
            assertThat(AccountUpdateController.splice(null, 0, 2, "CD")).isEqualTo("CD");
            assertThat(AccountUpdateController.splice("ABCDEF", 2, 2, null)).isEqualTo("AB  EF");
            assertThat(AccountUpdateController.splice("ABCDEF", 2, 2, "X")).isEqualTo("ABX EF");
            assertThat(AccountUpdateController.withPhoneArea(null, "425")).startsWith(" 425");
        }

        @Test
        @DisplayName("paint accepts an absent value and writes the field's declared width of spaces")
        void paintWithoutAValue() {
            AccountUpdateController.Conversation task = warmTask();
            controller.screenInit3100(task);
            controller.paint(task, AccountUpdateResponse.ScreenField.ACSTTUS, null);
            assertThat(task.cactupao.getAcsttus()).isEqualTo(" ");
        }

        @Test
        @DisplayName("The empty-information-message test accepts both spaces and LOW-VALUES")
        void noInformationMessageAcceptsBothFigurativeConstants() {
            AccountUpdateController.Conversation spaces = warmTask();
            spaces.wsInfoMsg = AccountUpdateController.spaces(
                    AccountUpdateController.WS_INFO_MSG_LENGTH);
            assertThat(spaces.wsNoInfoMessage()).isTrue();

            AccountUpdateController.Conversation low = warmTask();
            low.wsInfoMsg = AccountUpdateController.lowValues(
                    AccountUpdateController.WS_INFO_MSG_LENGTH);
            assertThat(low.wsNoInfoMessage()).isTrue();

            AccountUpdateController.Conversation text = warmTask();
            text.wsInfoMsg = AccountUpdateController.atInfoWidth("SOMETHING");
            assertThat(text.wsNoInfoMessage()).isFalse();
        }

        @Test
        @DisplayName("windowNotSupplied treats a mixed figurative window as supplied")
        void windowNotSuppliedMixedCase() {
            assertThat(AccountUpdateController.windowNotSupplied("\u0000 ")).isFalse();
            assertThat(AccountUpdateController.windowNotSupplied("\u0000\u0000")).isTrue();
        }

        @Test
        @DisplayName("An abend raised from a runtime failure records the triggering cause")
        void abendFromACause() {
            AccountUpdateController.Conversation task = warmTask();
            AbendException abend = controller.abendRoutine(task,
                    new IllegalStateException("backend unavailable"));

            assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(abend).hasRootCauseMessage("backend unavailable");
        }
    }

    @Nested
    @DisplayName("The code-page judgement is the JSON boundary's, not this program's")
    class ScreenInputRefusal {
        @Test
        @DisplayName("A character the code page cannot represent is NOT refused here: COACTUPC has no "
                + "such test, and a sweep placed in the flow ran ahead of the program's own decision "
                + "about whether it receives a map at all")
        void anUnrepresentableCharacterIsNotRefusedByTheProgram() {
            AccountUpdateRequest received = AccountUpdateRequest.initial()
                    .withValue(AccountUpdateRequest.ScreenField.ACSFNAM, "JOS\u00C9");

            assertThat(controller.handle(received, 0, CicsAid.DFHENTER)).isNotNull();
            verifyNoInteractions(accounts, xrefs, customers, service);
        }

        @Test
        @DisplayName("A payload whose every character the code page represents behaves identically, so "
                + "nothing about an accepted value changed with the sweep's removal")
        void aRepresentablePayloadIsUntouched() {
            AccountUpdateRequest received = AccountUpdateRequest.initial()
                    .withValue(AccountUpdateRequest.ScreenField.ACSFNAM, "JOSE");
            Map<String, String> before = received.fieldValues();

            assertThatCode(() -> controller.handle(received, 0, CicsAid.DFHENTER))
                    .doesNotThrowAnyException();

            assertThat(received.value(AccountUpdateRequest.ScreenField.ACSFNAM))
                    .as("the sweep must not have rewritten the field it examined")
                    .isEqualTo("JOSE");
            assertThat(received.fieldValues())
                    .as("and no other field moved either")
                    .isEqualTo(before);

        }
    }

    @Nested
    @DisplayName("The BMS field contract - 54 named DFHMDF entries of 128, every one PIC X(n)")
    class BmsFieldContract {
        private final List<AccountUpdateRequest.ScreenField> composites = List.of(
                AccountUpdateRequest.ScreenField.OPNYEAR, AccountUpdateRequest.ScreenField.OPNMON,
                AccountUpdateRequest.ScreenField.OPNDAY, AccountUpdateRequest.ScreenField.EXPYEAR,
                AccountUpdateRequest.ScreenField.EXPMON, AccountUpdateRequest.ScreenField.EXPDAY,
                AccountUpdateRequest.ScreenField.RISYEAR, AccountUpdateRequest.ScreenField.RISMON,
                AccountUpdateRequest.ScreenField.RISDAY, AccountUpdateRequest.ScreenField.ACTSSN1,
                AccountUpdateRequest.ScreenField.ACTSSN2, AccountUpdateRequest.ScreenField.ACTSSN3,
                AccountUpdateRequest.ScreenField.DOBYEAR, AccountUpdateRequest.ScreenField.DOBMON,
                AccountUpdateRequest.ScreenField.DOBDAY, AccountUpdateRequest.ScreenField.ACSPH1A,
                AccountUpdateRequest.ScreenField.ACSPH1B, AccountUpdateRequest.ScreenField.ACSPH1C,
                AccountUpdateRequest.ScreenField.ACSPH2A, AccountUpdateRequest.ScreenField.ACSPH2B,
                AccountUpdateRequest.ScreenField.ACSPH2C);

        private MockMvc dispatcher() {
            return MockMvcBuilders.standaloneSetup(controller)
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                    .build();
        }

        @Test
        @DisplayName("Fifty-four named fields of the mapset's 128 DFHMDF entries, request and response "
                + "agreeing label for label")
        void fiftyFourNamedFieldsOfOneHundredAndTwentyEight() {
            assertThat(AccountUpdateRequest.FIELD_COUNT).isEqualTo(54);
            assertThat(AccountUpdateRequest.DFHMDF_ENTRY_COUNT).isEqualTo(128);
            assertThat(AccountUpdateRequest.ScreenField.values()).hasSize(54);
            assertThat(AccountUpdateResponse.FIELDS).hasSize(54);

            List<String> inbound = new ArrayList<>();
            for (AccountUpdateRequest.ScreenField field : AccountUpdateRequest.ScreenField.values()) {
                inbound.add(field.label());
            }
            List<String> outbound = new ArrayList<>();
            for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.FIELDS) {
                outbound.add(field.label());
            }
            assertThat(outbound).containsExactlyElementsOf(inbound);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(AccountUpdateRequest.ScreenField.class)
        @DisplayName("Every field is PIC X(n) at its declared width, and traces to a DFHMDF entry")
        void everyFieldIsPicXAtItsDeclaredWidth(AccountUpdateRequest.ScreenField field) {
            assertThat(field.picture()).isEqualTo("X(" + field.length() + ")");
            assertThat(field.isAlphanumeric()).isTrue();
            assertThat(field.length()).isPositive();
            assertThat(AccountUpdateRequest.declaredLength(field)).isEqualTo(field.length());
            assertThat(field.copybookLine()).isPositive();
            assertThat(field.mapsetLine()).isPositive();
            assertThat(field.screenRow()).isBetween(1, AccountUpdateRequest.SCREEN_ROWS);
            assertThat(field.screenColumn()).isBetween(1, AccountUpdateRequest.SCREEN_COLUMNS);
            assertThat(AccountUpdateResponse.declaredLength(
                            AccountUpdateResponse.ScreenField.valueOf(field.name())))
                    .isEqualTo(field.length());
        }

        @Test
        @DisplayName("There is no edited and no numeric item anywhere: COACTUP.bms declares zero PICIN "
                + "and zero PICOUT")
        void noEditedOrNumericItemAnywhere() {
            for (AccountUpdateRequest.ScreenField field : AccountUpdateRequest.ScreenField.values()) {
                assertThat(field.picture())
                        .describedAs("%s is PIC X only - no PICIN/PICOUT in app/bms/COACTUP.bms",
                                field.label())
                        .matches("X\\(\\d+\\)");
                for (char mask : new char[] {'Z', '+', '-', '.', ',', 'S', 'V', '*', 'C', 'D', 'B'}) {
                    assertThat(field.picture().indexOf(mask))
                            .describedAs("%s carries no '%s' mask character", field.label(), mask)
                            .isEqualTo(-1);
                }
            }
        }

        @Test
        @DisplayName("The 54 widths sum to the group's 705 data bytes")
        void theWidthsSumToTheDeclaredPayload() {
            int total = 0;
            for (AccountUpdateRequest.ScreenField field : AccountUpdateRequest.ScreenField.values()) {
                total += field.length();
            }
            assertThat(total).isEqualTo(AccountUpdateRequest.PAYLOAD_LENGTH).isEqualTo(705);
        }

        @Test
        @DisplayName("The three function-key legends: FKEYS 21, FKEY05 7, FKEY12 10")
        void theThreeFunctionKeyLegends() {
            assertThat(AccountUpdateRequest.ScreenField.FKEYS.length())
                    .isEqualTo(AccountUpdateRequest.FKEYS_LENGTH).isEqualTo(21);
            assertThat(AccountUpdateRequest.ScreenField.FKEY05.length())
                    .isEqualTo(AccountUpdateRequest.FKEY05_LENGTH).isEqualTo(7);
            assertThat(AccountUpdateRequest.ScreenField.FKEY12.length())
                    .isEqualTo(AccountUpdateRequest.FKEY12_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("The twenty-one composite components each stay a separate member at their own width")
        void compositesStayDecomposed() {
            assertThat(composites).hasSize(21).doesNotHaveDuplicates();
            for (AccountUpdateRequest.ScreenField part : composites) {
                assertThat(part.isAlphanumeric()).isTrue();
                assertThat(part.length()).isIn(2, 3, 4);
            }
            assertThat(AccountUpdateRequest.ScreenField.OPNYEAR.length()).isEqualTo(4);
            assertThat(AccountUpdateRequest.ScreenField.OPNMON.length()).isEqualTo(2);
            assertThat(AccountUpdateRequest.ScreenField.OPNDAY.length()).isEqualTo(2);
            assertThat(AccountUpdateRequest.ScreenField.ACTSSN1.length()).isEqualTo(3);
            assertThat(AccountUpdateRequest.ScreenField.ACTSSN2.length()).isEqualTo(2);
            assertThat(AccountUpdateRequest.ScreenField.ACTSSN3.length()).isEqualTo(4);
            assertThat(AccountUpdateRequest.ScreenField.ACSPH1A.length()).isEqualTo(3);
            assertThat(AccountUpdateRequest.ScreenField.ACSPH1B.length()).isEqualTo(3);
            assertThat(AccountUpdateRequest.ScreenField.ACSPH1C.length()).isEqualTo(4);
        }

        @Test
        @DisplayName("No xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item is a JSON member of any of the "
                + "fifty-four fields")
        void metadataIsNeverAJsonMember() throws Exception {
            stubAllFound();

            String body = dispatcher()
                    .perform(put(AccountUpdateController.ACCOUNT_UPDATE_PATH, ACCT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(MAPPER.writeValueAsString(fullyValidScreen())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode json = MAPPER.readTree(body);

            for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.FIELDS) {
                String stem = field.label().toLowerCase(Locale.ROOT);
                assertThat(json.has(stem))
                        .describedAs("%s is a payload member", field.label()).isTrue();
                for (String suffix : List.of("l", "f", "a", "c", "p", "h", "v")) {
                    assertThat(json.has(stem + suffix))
                            .describedAs("%s%s is metadata, not a payload member",
                                    field.label(), suffix.toUpperCase(Locale.ROOT))
                            .isFalse();
                }
            }
            assertThat(json.has("screenMetadata")).isTrue();
        }

        @Test
        @DisplayName("MOVE -1 TO xxxL is representable: the length item is a signed PIC S9(4) halfword")
        void minusOneIsRepresentableInTheLengthItem() {
            assertThat(AccountUpdateRequest.FieldMetadata.CURSOR_HERE).isEqualTo(-1);
            assertThat(AccountUpdateRequest.FieldMetadata.LENGTH_ITEM_MIN).isEqualTo(-9999);
            assertThat(AccountUpdateRequest.FieldMetadata.LENGTH_ITEM_MAX).isEqualTo(9999);
            assertThat(AccountUpdateRequest.FieldMetadata.CURSOR_HERE)
                    .isGreaterThanOrEqualTo(AccountUpdateRequest.FieldMetadata.LENGTH_ITEM_MIN);

            AccountUpdateRequest.FieldMetadata cursor =
                    AccountUpdateRequest.FieldMetadata.unset().withCursorHere();
            assertThat(cursor.lengthItem()).isEqualTo(-1);
            assertThat(cursor.isCursorHere()).isTrue();
            assertThat(AccountUpdateRequest.FieldMetadata.unset().isCursorHere()).isFalse();

            AccountUpdateRequest received = AccountUpdateRequest.initial()
                    .withCursorOn(AccountUpdateRequest.ScreenField.ACCTSID);
            assertThat(received.metadata(AccountUpdateRequest.ScreenField.ACCTSID).lengthItem())
                    .isEqualTo(AccountUpdateRequest.FieldMetadata.CURSOR_HERE);
        }

        @Test
        @DisplayName("A bare asterisk is a legal whole value, because CSSETATY moves '*' into xxxO")
        void anAsteriskIsALegalWholeValue() {
            AccountUpdateResponse response = AccountUpdateResponse.initial()
                    .withValue(AccountUpdateResponse.ScreenField.ACSTTUS, "*");

            assertThat(response.value(AccountUpdateResponse.ScreenField.ACSTTUS)).isEqualTo("*");
            assertThat(response.getAcsttus()).isEqualTo("*");
        }

        @Test
        @DisplayName("The symbolic-map group is 1095 bytes: 12 + 54 x 7 + 705, and round-trips")
        void theGroupImageIsOneThousandAndNinetyFiveBytes() {
            assertThat(AccountUpdateRequest.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(AccountUpdateRequest.FIELD_OVERHEAD).isEqualTo(7);
            assertThat(AccountUpdateRequest.GROUP_LENGTH)
                    .isEqualTo(AccountUpdateRequest.TIOAPFX_LENGTH
                            + AccountUpdateRequest.FIELD_COUNT * AccountUpdateRequest.FIELD_OVERHEAD
                            + AccountUpdateRequest.PAYLOAD_LENGTH)
                    .isEqualTo(1095);

            AccountUpdateRequest received = fullyValidScreen()
                    .withCursorOn(AccountUpdateRequest.ScreenField.ACSTTUS);
            byte[] image = received.toGroupImage(CODEC);

            assertThat(image).hasSize(1095);
            AccountUpdateRequest decoded = AccountUpdateRequest.fromGroupImage(image, CODEC);
            for (AccountUpdateRequest.ScreenField field : AccountUpdateRequest.ScreenField.values()) {
                assertThat(decoded.value(field))
                        .describedAs("%s survives the group image", field.label())
                        .isEqualTo(received.image(field, CODEC));
            }
            assertThat(decoded.metadata(AccountUpdateRequest.ScreenField.ACSTTUS).isCursorHere())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("WS-THIS-PROGCOMMAREA - 873 bytes, nine change-action states, asymmetric SSN spans")
    class WorkAreaShape {
        @Test
        @DisplayName("873 bytes: one action byte plus two 436-byte detail groups of 106 + 330")
        void theWorkAreaIsEightHundredAndSeventyThreeBytes() {
            assertThat(AccountUpdateRequest.AcctSnapshot.RECORD_LENGTH).isEqualTo(106);
            assertThat(AccountUpdateRequest.CustSnapshot.RECORD_LENGTH).isEqualTo(330);
            assertThat(AccountUpdateRequest.Details.RECORD_LENGTH).isEqualTo(436);
            assertThat(AccountUpdateRequest.ChangeAction.RECORD_LENGTH).isEqualTo(1);
            assertThat(AccountUpdateRequest.CommArea.RECORD_LENGTH)
                    .isEqualTo(AccountUpdateRequest.ChangeAction.RECORD_LENGTH
                            + 2 * AccountUpdateRequest.Details.RECORD_LENGTH)
                    .isEqualTo(873);
            assertThat(AccountUpdateRequest.CommArea.CHANGE_ACTION_OFFSET).isZero();
            assertThat(AccountUpdateRequest.CommArea.OLD_DETAILS_OFFSET).isEqualTo(1);
            assertThat(AccountUpdateRequest.CommArea.NEW_DETAILS_OFFSET).isEqualTo(437);

            byte[] image = AccountUpdateRequest.CommArea.initialised().encode(CODEC);
            assertThat(image).hasSize(873);
            assertThat(AccountUpdateRequest.CommArea.decode(image, CODEC))
                    .isEqualTo(AccountUpdateRequest.CommArea.initialised());
        }

        @Test
        @DisplayName("The OLD SSN span is one flat X(09); the NEW span is X(03) + X(02) + X(04)")
        void theSsnSpansAreStructurallyAsymmetric() {
            assertThat(AccountUpdateRequest.DetailGroup.OLD.declaresSsnParts()).isFalse();
            assertThat(AccountUpdateRequest.DetailGroup.NEW.declaresSsnParts()).isTrue();
            assertThat(AccountUpdateRequest.CustSnapshot.SSN_LENGTH).isEqualTo(9);
            assertThat(AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH
                    + AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH
                    + AccountUpdateRequest.CustSnapshot.SSN_PART_3_LENGTH)
                    .isEqualTo(AccountUpdateRequest.CustSnapshot.SSN_LENGTH);
            assertThat(AccountUpdateRequest.DetailGroup.OLD.declaresFicoRangeCondition()).isFalse();
            assertThat(AccountUpdateRequest.DetailGroup.NEW.declaresFicoRangeCondition()).isTrue();
        }

        @Test
        @DisplayName("1100 feeds ACTSSN1/2/3 into the NEW parts while OLD keeps the flat nine")
        void theThreePartsFeedTheNewGroupAndTheFlatSpanTheOld() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);
            controller.readAcct9000(task);

            assertThat(task.acupOldCust.ssnX).isEqualTo("123456789");

            AccountUpdateController.Conversation typed = task(fullyValidScreen()
                    .withValue(AccountUpdateRequest.ScreenField.ACTSSN1, "987")
                    .withValue(AccountUpdateRequest.ScreenField.ACTSSN2, "65")
                    .withValue(AccountUpdateRequest.ScreenField.ACTSSN3, "4321"),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHENTER);
            controller.receiveMap1100(typed);

            assertThat(typed.acupNewCust.ssn1()).isEqualTo("987");
            assertThat(typed.acupNewCust.ssn2()).isEqualTo("65");
            assertThat(typed.acupNewCust.ssn3()).isEqualTo("4321");
            assertThat(typed.acupNewCust.ssnX).isEqualTo("987654321");
        }

        @ParameterizedTest(name = "ACUP-CHANGE-ACTION = {0}")
        @CsvSource(delimiter = '|', value = {
            "LOW-VALUES | true  | false | false | false | false | false | false | false | false | false",
            "SPACE      | true  | false | false | false | false | false | false | false | false | false",
            "S          | false | true  | false | false | false | false | false | false | false | false",
            "E          | false | false | true  | true  | false | false | false | false | false | false",
            "N          | false | false | true  | false | true  | false | false | false | false | false",
            "C          | false | false | true  | false | false | true  | false | false | false | false",
            "L          | false | false | true  | false | false | false | true  | true  | false | false",
            "F          | false | false | true  | false | false | false | true  | false | true  | false",
            "?          | false | false | false | false | false | false | false | false | false | true",
        })
        @DisplayName("All nine 88-level condition names, both states, for every declared byte")
        void everyChangeActionConditionInBothStates(String token, boolean notFetched,
                boolean showDetails, boolean changesMade, boolean notOk, boolean okNotConfirmed,
                boolean okayedDone, boolean failed, boolean lockError, boolean butFailed,
                boolean unrecognised) {
            String value = switch (token) {
                case "LOW-VALUES" -> AccountUpdateRequest.ChangeAction.LOW_VALUES;
                case "SPACE" -> AccountUpdateRequest.ChangeAction.SPACES;
                default -> token;
            };
            AccountUpdateRequest.ChangeAction action = AccountUpdateRequest.ChangeAction.of(value);

            assertThat(action.isDetailsNotFetched()).isEqualTo(notFetched);
            assertThat(action.isShowDetails()).isEqualTo(showDetails);
            assertThat(action.isChangesMade()).isEqualTo(changesMade);
            assertThat(action.isChangesNotOk()).isEqualTo(notOk);
            assertThat(action.isChangesOkNotConfirmed()).isEqualTo(okNotConfirmed);
            assertThat(action.isChangesOkayedAndDone()).isEqualTo(okayedDone);
            assertThat(action.isChangesFailed()).isEqualTo(failed);
            assertThat(action.isChangesOkayedLockError()).isEqualTo(lockError);
            assertThat(action.isChangesOkayedButFailed()).isEqualTo(butFailed);
            assertThat(action.isUnrecognised()).isEqualTo(unrecognised);
        }

        @Test
        @DisplayName("The nine named factories agree with the nine condition names")
        void theNamedFactoriesAgreeWithTheConditions() {
            assertThat(AccountUpdateRequest.ChangeAction.initial().isDetailsNotFetched()).isTrue();
            assertThat(AccountUpdateRequest.ChangeAction.spacesState().isDetailsNotFetched()).isTrue();
            assertThat(AccountUpdateRequest.ChangeAction.showDetails().isShowDetails()).isTrue();
            assertThat(AccountUpdateRequest.ChangeAction.changesNotOk().isChangesNotOk()).isTrue();
            assertThat(AccountUpdateRequest.ChangeAction.changesOkNotConfirmed()
                    .isChangesOkNotConfirmed()).isTrue();
            assertThat(AccountUpdateRequest.ChangeAction.changesOkayedAndDone()
                    .isChangesOkayedAndDone()).isTrue();
            assertThat(AccountUpdateRequest.ChangeAction.changesOkayedLockError()
                    .isChangesOkayedLockError()).isTrue();
            assertThat(AccountUpdateRequest.ChangeAction.changesOkayedButFailed()
                    .isChangesOkayedButFailed()).isTrue();
            assertThat(AccountUpdateRequest.ChangeAction.DETAILS_NOT_FETCHED_VALUES).hasSize(2);
            assertThat(AccountUpdateRequest.ChangeAction.CHANGES_MADE_VALUES).hasSize(5);
            assertThat(AccountUpdateRequest.ChangeAction.CHANGES_FAILED_VALUES).hasSize(2);
        }

        @Test
        @DisplayName("The 873-byte area travels in the payload and comes back unchanged when nothing "
                + "touched it")
        void theWorkAreaTravelsInThePayload() {
            AccountUpdateRequest.CommArea carried = AccountUpdateRequest.CommArea.initialised()
                    .withChangeAction(AccountUpdateRequest.ChangeAction.changesOkNotConfirmed());
            AccountUpdateRequest received = request(ACCT, reenter()).withCommArea(carried);

            AccountUpdateController.PaintedScreen painted = controller.handle(received,
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3);

            assertThat(painted.response().getCommArea().encode(CODEC)).hasSize(873);
            assertThat(painted.response().getCommArea().changeAction())
                    .isEqualTo(AccountUpdateRequest.ChangeAction.changesOkNotConfirmed());
        }
    }

    @Nested
    @DisplayName("DFHCOMMAREA - OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN, at :856")
    class VariableLengthCommarea {
        @Test
        @DisplayName("The span's declared bounds, and the length this program is entered with")
        void theDeclaredBounds() {
            assertThat(AccountUpdateController.PASSED_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + AccountUpdateRequest.CommArea.RECORD_LENGTH)
                    .isEqualTo(1033)
                    .isBetween(1, 32767);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(AccountUpdateRequest.COMMAREA_CAPACITY).isEqualTo(2000).isLessThan(32767);
        }

        @Test
        @DisplayName("COBOL subscript 1 is Java index 0, and subscript EIBCALEN is index EIBCALEN - 1")
        void theFirstAndLastElementOfTheSpan() {
            String navigation = new String(reenter().toFixedWidth(CODEC), StandardCharsets.US_ASCII);
            String workArea = new String(AccountUpdateRequest.CommArea.initialised()
                    .withChangeAction(AccountUpdateRequest.ChangeAction.showDetails()).encode(CODEC),
                    StandardCharsets.US_ASCII);
            String dfhcommarea = navigation + workArea;
            int eibcalen = dfhcommarea.length();

            assertThat(eibcalen).isEqualTo(AccountUpdateController.PASSED_COMMAREA_LENGTH);

            assertThat(AccountUpdateController.slice(dfhcommarea, 0,
                    NavigationContext.COMMAREA_LENGTH)).isEqualTo(navigation);
            assertThat(AccountUpdateController.slice(dfhcommarea, 0, 1))
                    .isEqualTo(dfhcommarea.substring(0, 1));

            assertThat(AccountUpdateController.slice(dfhcommarea, NavigationContext.COMMAREA_LENGTH,
                    AccountUpdateRequest.CommArea.RECORD_LENGTH)).isEqualTo(workArea);

            assertThat(AccountUpdateController.slice(dfhcommarea, 0, eibcalen))
                    .isEqualTo(dfhcommarea);

            assertThat(AccountUpdateController.slice(dfhcommarea, eibcalen - 1, 1))
                    .isEqualTo(workArea.substring(workArea.length() - 1));
            assertThat(AccountUpdateController.slice(dfhcommarea, eibcalen, 1)).isEqualTo(" ");
        }

        @Test
        @DisplayName("The shorter accepted length addresses only the CARDDEMO-COMMAREA half")
        void theCarddemoCommareaOnlyLength() {
            String navigation = new String(enter().toFixedWidth(CODEC), StandardCharsets.US_ASCII);

            assertThat(navigation).hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(AccountUpdateController.slice(navigation, 0,
                    NavigationContext.COMMAREA_LENGTH)).isEqualTo(navigation);
            assertThat(AccountUpdateController.slice(navigation,
                    NavigationContext.COMMAREA_LENGTH - 1, 1))
                    .isEqualTo(navigation.substring(NavigationContext.COMMAREA_LENGTH - 1));
            assertThat(AccountUpdateController.slice(navigation,
                    NavigationContext.COMMAREA_LENGTH, 1)).isEqualTo(" ");
            assertThat(AccountUpdateController.resolveEibcalen(NavigationContext.COMMAREA_LENGTH,
                    request(ACCT, enter()))).isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are X(7), inside the 160-byte context")
        void theNavigationContextIsOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(AccountUpdateController.LIT_THISMAPSET).hasSize(8);
        }
    }

    /**
     * A folded function key and the key it folds onto: {@code DFHPF13} through {@code DFHPF24} against
     * {@code DFHPF1} through {@code DFHPF12}.
     *
     * @param name the folded key's name, for the test's display name
     * @param upper the {@code DFHPF13}-{@code DFHPF24} byte
     * @param lower the {@code DFHPF1}-{@code DFHPF12} byte it folds onto
     * @param token the five-character {@code CCARD-AID} token both produce
     */
    private record FoldedKey(String name, byte upper, byte lower, String token) {
    }

    private static List<FoldedKey> foldedKeys() {
        return List.of(
                new FoldedKey("PF13/PF1", CicsAid.DFHPF13, CicsAid.DFHPF1, "PFK01"),
                new FoldedKey("PF14/PF2", CicsAid.DFHPF14, CicsAid.DFHPF2, "PFK02"),
                new FoldedKey("PF15/PF3", CicsAid.DFHPF15, CicsAid.DFHPF3, "PFK03"),
                new FoldedKey("PF16/PF4", CicsAid.DFHPF16, CicsAid.DFHPF4, "PFK04"),
                new FoldedKey("PF17/PF5", CicsAid.DFHPF17, CicsAid.DFHPF5, "PFK05"),
                new FoldedKey("PF18/PF6", CicsAid.DFHPF18, CicsAid.DFHPF6, "PFK06"),
                new FoldedKey("PF19/PF7", CicsAid.DFHPF19, CicsAid.DFHPF7, "PFK07"),
                new FoldedKey("PF20/PF8", CicsAid.DFHPF20, CicsAid.DFHPF8, "PFK08"),
                new FoldedKey("PF21/PF9", CicsAid.DFHPF21, CicsAid.DFHPF9, "PFK09"),
                new FoldedKey("PF22/PF10", CicsAid.DFHPF22, CicsAid.DFHPF10, "PFK10"),
                new FoldedKey("PF23/PF11", CicsAid.DFHPF23, CicsAid.DFHPF11, "PFK11"),
                new FoldedKey("PF24/PF12", CicsAid.DFHPF24, CicsAid.DFHPF12, "PFK12"));
    }

    @Nested
    @DisplayName("CSSTRPFY folds PF13-PF24 onto PF1-PF12, has no DFHPA3 arm and no WHEN OTHER")
    class AttentionIdentifierFolding {
        @ParameterizedTest(name = "{0} -> {3}")
        @MethodSource("com.vsergeychik.carddemo.account.AccountUpdateControllerTest#foldedKeys")
        @DisplayName("Both halves of the fold reach one token")
        void bothHalvesOfTheFoldReachOneToken(FoldedKey fold) {
            assertThat(PfKeyResolver.resolve(fold.lower())).isPresent();
            assertThat(PfKeyResolver.resolve(fold.upper())).isPresent();
            assertThat(PfKeyResolver.resolve(fold.lower()).orElseThrow().token())
                    .isEqualTo(fold.token());
            assertThat(PfKeyResolver.resolve(fold.upper()).orElseThrow().token())
                    .isEqualTo(fold.token());
            assertThat(fold.token()).hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("The three folds this screen's legends name: PF3/PF15, PF5/PF17, PF12/PF24")
        void theThreeFoldsThisScreenCaresAbout() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15).orElseThrow())
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF3).orElseThrow())
                    .isEqualTo(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF17).orElseThrow())
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF5).orElseThrow())
                    .isEqualTo(PfKeyResolver.AidKey.PFK05);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24).orElseThrow())
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF12).orElseThrow())
                    .isEqualTo(PfKeyResolver.AidKey.PFK12);
        }

        @Test
        @DisplayName("PF15 drives the same 0000-MAIN arm as PF3: control transfers (G40)")
        void aFoldedKeyDrivesTheSameArm() {
            AccountUpdateController.PaintedScreen viaPf3 = controller.handle(request(ACCT, reenter()),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF3);
            AccountUpdateController.PaintedScreen viaPf15 = controller.handle(request(ACCT, reenter()),
                    AccountUpdateController.PASSED_COMMAREA_LENGTH, CicsAid.DFHPF15);

            assertThat(viaPf15.response().getNextProgram())
                    .isEqualTo(viaPf3.response().getNextProgram());
            assertThat(viaPf15.response().getNextMapset())
                    .isEqualTo(viaPf3.response().getNextMapset());
            assertThat(viaPf15.response().getNextMap()).isEqualTo(viaPf3.response().getNextMap());
            verifyNoInteractions(accounts, xrefs, customers, service);
        }

        @Test
        @DisplayName("No DFHPA3 arm, no WHEN OTHER: an unmapped AID has an explicit no-match outcome")
        void noDfhpa3AndNoWhenOther() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).isPresent();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).isPresent();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER).orElseThrow())
                    .isEqualTo(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR).orElseThrow())
                    .isEqualTo(PfKeyResolver.AidKey.CLEAR);
            assertThat(PfKeyResolver.resolve((byte) 0x00)).isEmpty();

            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.empty())).isEmpty();
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3,
                    Optional.of(PfKeyResolver.AidKey.PFK03)))
                    .contains(PfKeyResolver.AidKey.PFK03);
        }

        @Test
        @DisplayName("Every one of the twenty-eight arms resolves, and only those")
        void theTwentyEightArms() {
            byte[] arms = {CicsAid.DFHENTER, CicsAid.DFHCLEAR, CicsAid.DFHPA1, CicsAid.DFHPA2,
                CicsAid.DFHPF1, CicsAid.DFHPF2, CicsAid.DFHPF3, CicsAid.DFHPF4, CicsAid.DFHPF5,
                CicsAid.DFHPF6, CicsAid.DFHPF7, CicsAid.DFHPF8, CicsAid.DFHPF9, CicsAid.DFHPF10,
                CicsAid.DFHPF11, CicsAid.DFHPF12, CicsAid.DFHPF13, CicsAid.DFHPF14, CicsAid.DFHPF15,
                CicsAid.DFHPF16, CicsAid.DFHPF17, CicsAid.DFHPF18, CicsAid.DFHPF19, CicsAid.DFHPF20,
                CicsAid.DFHPF21, CicsAid.DFHPF22, CicsAid.DFHPF23, CicsAid.DFHPF24};

            assertThat(arms).hasSize(28);
            for (byte arm : arms) {
                assertThat(PfKeyResolver.resolve(arm))
                        .describedAs("AID x'%02X' is one of the copybook's arms", arm)
                        .isPresent();
            }
            assertThat(PfKeyResolver.AidKey.values()).hasSize(16);
        }
    }

    @Nested
    @DisplayName("EDIT-DATE-CCYYMMDD delegation - the four labelled sites of 1200")
    class DateEditorDelegation {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "Open Date,     20200115, OPNYEAR, OPNMON, OPNDAY",
            "Expiry Date,   20250114, EXPYEAR, EXPMON, EXPDAY",
            "Reissue Date,  20220630, RISYEAR, RISMON, RISDAY",
            "Date of Birth, 19800229, DOBYEAR, DOBMON, DOBDAY",
        })
        @DisplayName("Each site names its own field and reads the three flags back")
        void eachSiteNamesItsFieldAndReadsTheFlagsBack(String label, String date, String yearField,
                String monthField, String dayField) {
            AccountUpdateController.Conversation task = warmTask();
            AccountUpdateResponse.ScreenField year =
                    AccountUpdateResponse.ScreenField.valueOf(yearField);
            AccountUpdateResponse.ScreenField month =
                    AccountUpdateResponse.ScreenField.valueOf(monthField);
            AccountUpdateResponse.ScreenField day = AccountUpdateResponse.ScreenField.valueOf(dayField);

            controller.editDate(task, label, date, year, month, day,
                    "Date of Birth".equals(label));

            assertThat(task.wsEditVariableName)
                    .isEqualTo(AccountUpdateController.editVariableName(label))
                    .hasSize(AccountUpdateController.WS_EDIT_VARIABLE_NAME_LENGTH);
            assertThat(task.wsEditVariableName.trim()).isEqualTo(label);
            assertThat(task.flag(year)).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.flag(month)).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(task.flag(day)).isEqualTo(AccountUpdateController.FLG_ISVALID);
            assertThat(returnMessageOf(task)).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a thirteenth month")
        @CsvSource({
            "Open Date,     OPNYEAR, OPNMON, OPNDAY",
            "Expiry Date,   EXPYEAR, EXPMON, EXPDAY",
            "Reissue Date,  RISYEAR, RISMON, RISDAY",
            "Date of Birth, DOBYEAR, DOBMON, DOBDAY",
        })
        @DisplayName("A rejected date carries its own label into the message and its own flags out")
        void aRejectedDateCarriesItsLabel(String label, String yearField, String monthField,
                String dayField) {
            AccountUpdateController.Conversation task = warmTask();
            AccountUpdateResponse.ScreenField month =
                    AccountUpdateResponse.ScreenField.valueOf(monthField);

            controller.editDate(task, label, "20201315",
                    AccountUpdateResponse.ScreenField.valueOf(yearField), month,
                    AccountUpdateResponse.ScreenField.valueOf(dayField),
                    "Date of Birth".equals(label));

            assertThat(returnMessageOf(task))
                    .startsWith(label)
                    .contains(AccountDateValidator.MSG_MONTH_MUST_BE_1_TO_12.trim());
            assertThat(task.flag(month)).isEqualTo(AccountUpdateController.FLG_NOT_OK);
            assertThat(task.inputError()).isTrue();
        }

        @Test
        @DisplayName("Only the date-of-birth site runs the second range, EDIT-DATE-OF-BIRTH")
        void onlyTheDateOfBirthSiteRunsTheSecondRange() {
            AccountUpdateController.Conversation openDate = warmTask();
            controller.editDate(openDate, "Open Date", "20990101",
                    AccountUpdateResponse.ScreenField.OPNYEAR,
                    AccountUpdateResponse.ScreenField.OPNMON,
                    AccountUpdateResponse.ScreenField.OPNDAY, false);
            assertThat(returnMessageOf(openDate)).isEmpty();

            AccountUpdateController.Conversation birth = warmTask();
            controller.editDate(birth, "Date of Birth", "20990101",
                    AccountUpdateResponse.ScreenField.DOBYEAR,
                    AccountUpdateResponse.ScreenField.DOBMON,
                    AccountUpdateResponse.ScreenField.DOBDAY, true);
            assertThat(returnMessageOf(birth))
                    .contains(AccountDateValidator.MSG_CANNOT_BE_IN_THE_FUTURE.trim());
        }

        @Test
        @DisplayName("The label is moved at PIC X(25), so a longer name loses its tail")
        void theLabelIsMovedAtItsDeclaredWidth() {
            assertThat(AccountUpdateController.editVariableName("Current Cycle Credit Limit"))
                    .hasSize(25)
                    .isEqualTo("Current Cycle Credit Limi");
            assertThat(AccountUpdateController.editVariableName("Open Date"))
                    .isEqualTo(at("Open Date", 25));
        }
    }

    @Nested
    @DisplayName("Read ordering - 9000's three guards drive 9200, 9300 then 9400 (G47)")
    class ReadOrdering {
        @Test
        @DisplayName("The three reads happen in source order, on the three named datasets")
        void theThreeReadsHappenInSourceOrder() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            InOrder reads = inOrder(xrefs, accounts, customers);
            reads.verify(xrefs).readByAccountIdViaAltIndex(ACCT);
            reads.verify(accounts).readByKey(11L);
            reads.verify(customers).readByKey(anyString());
            reads.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("A cross-reference failure short-circuits both remaining reads")
        void aXrefFailureShortCircuitsTheRest() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.notFound(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME));
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            InOrder reads = inOrder(xrefs, accounts, customers);
            reads.verify(xrefs).readByAccountIdViaAltIndex(ACCT);
            reads.verifyNoMoreInteractions();
            verify(accounts, never()).readByKey(anyLong());
            verify(customers, never()).readByKey(anyString());
        }

        @Test
        @DisplayName("An account failure still lets 9400 run, because the DID-NOT-FIND guard is dead")
        void anAccountFailureStillReachesTheCustomerRead() {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            new CardXrefRecord(XREF_CARD, CUST_ID, 11L), XREF_IMAGE));
            when(accounts.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.notFound());
            when(customers.readByKey(anyString()))
                    .thenReturn(CustomerRepository.ReadResult.found(customer(), CUST_IMAGE));
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            InOrder reads = inOrder(xrefs, accounts, customers);
            reads.verify(xrefs).readByAccountIdViaAltIndex(ACCT);
            reads.verify(accounts).readByKey(11L);
            reads.verify(customers).readByKey(anyString());
            assertThat(task.foundAcctInMaster()).isFalse();
        }

        @Test
        @DisplayName("The write path is the service's, and it is not touched by a read turn")
        void aReadTurnNeverWrites() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("9000 is a driver: four ranges and three IF ... GO TO -EXIT guards, no EVALUATE")
        void theDriverIsFourRangesAndThreeGuards() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            InOrder reads = inOrder(xrefs, accounts, customers);
            reads.verify(xrefs).readByAccountIdViaAltIndex(ACCT);
            reads.verify(accounts).readByKey(11L);
            reads.verify(customers).readByKey(anyString());
            assertThat(task.acupOldAcct.acctIdX).isEqualTo(ACCT);
            assertThat(task.wsCardRidAcctId).isEqualTo(ACCT);
            assertThat(task.wsNoInfoMessage()).isTrue();
        }
    }

    @Nested
    @DisplayName("The two records the read path decodes - 300 and 500 bytes (G19, G21)")
    class RecordDecoding {
        @Test
        @DisplayName("The account record is 300 bytes with FILLER X(178) space-filled")
        void theAccountRecordIsThreeHundredBytes() {
            AccountRecord record = account();
            byte[] image = record.toByteArray();

            assertThat(image).hasSize(AccountRecord.RECORD_LENGTH).hasSize(300);
            assertThat(AccountRecord.FILLER_OFFSET).isEqualTo(122);
            assertThat(AccountRecord.FILLER_LENGTH).isEqualTo(178);
            assertThat(AccountRecord.FILLER_OFFSET + AccountRecord.FILLER_LENGTH).isEqualTo(300);

            byte[] filler = record.rawBytes(AccountRecord.SPAN_FILLER);
            assertThat(filler).hasSize(178);
            for (byte each : filler) {
                assertThat((char) each).isEqualTo(' ');
            }
        }

        @Test
        @DisplayName("The misspelled ACCT-EXPIRAION-DATE survives the round trip, unrenamed")
        void theMisspelledExpirationDateSurvives() {
            AccountRecord decoded =
                    AccountRecord.decode(account().toByteArray(), StandardCharsets.US_ASCII);

            assertThat(decoded.getAcctExpiraionDate()).isEqualTo("2025-01-14");
            assertThat(decoded.getAcctOpenDate()).isEqualTo("2020-01-15");
            assertThat(decoded.getAcctReissueDate()).isEqualTo("2022-06-30");
            assertThat(decoded.toByteArray()).isEqualTo(account().toByteArray());
        }

        @Test
        @DisplayName("The customer record is 500 bytes")
        void theCustomerRecordIsFiveHundredBytes() {
            assertThat(CustomerRecord.RECORD_LENGTH).isEqualTo(500);
            assertThat(customer().encode(StandardCharsets.US_ASCII)).hasSize(500);
            assertThat(CustomerRepository.RECORD_LENGTH).isEqualTo(500);

            CustomerRecord decoded = CustomerRecord.decode(
                    customer().encode(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
            assertThat(decoded.getCustDobYyyyMmDd()).isEqualTo("1980-02-29");
            assertThat(decoded.getCustSsn()).isEqualTo(123456789);
        }

        @Test
        @DisplayName("9500 stores what 9700 later compares, so the concurrency check has a baseline")
        void nineFiveHundredStoresTheBaselineNineSevenHundredCompares() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.acupOldAcct.acctIdX).isEqualTo(ACCT);
            assertThat(task.acupOldAcct.activeStatus).isEqualTo("Y");
            assertThat(task.acupOldAcct.currBalN()).isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(task.acupOldCust.ssnX).isEqualTo("123456789");
            assertThat(task.acupOldCust.ficoScoreN()).isEqualTo(750);
            assertThat(task.acupOldCust.dobYyyyMmDd).isEqualTo("19800229");

            assertThat(task.acupNewAcct.acctIdX)
                    .isEqualTo(AccountUpdateController.spaces(
                            AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH));
            assertThat(task.acupNewCust.ssnX)
                    .isEqualTo(AccountUpdateController.spaces(
                            AccountUpdateRequest.CustSnapshot.SSN_LENGTH));
        }
    }

    @Nested
    @DisplayName("The remaining REDEFINES pairs - two views over one span (G34)")
    class RedefinitionRoundTrips {
        @Test
        @DisplayName("WS-EDIT-US-PHONE-NUM-X: the three parts and their PIC 9 views, both ways")
        void thePhoneNumberPartsAndTheirNumericViews() {
            String span = AccountUpdateController.spaces(
                    AccountUpdateController.WS_EDIT_US_PHONE_NUM_LENGTH);
            span = AccountUpdateController.withPhoneArea(span, "206");
            span = AccountUpdateController.withPhonePrefix(span, "555");
            span = AccountUpdateController.withPhoneLine(span, "1234");

            assertThat(span).hasSize(15);
            assertThat(AccountUpdateController.phoneArea(span)).isEqualTo("206");
            assertThat(AccountUpdateController.phonePrefix(span)).isEqualTo("555");
            assertThat(AccountUpdateController.phoneLine(span)).isEqualTo("1234");
            assertThat(CODEC.decodePic9(AccountUpdateController.phoneArea(span))).isEqualTo(206L);
            assertThat(CODEC.decodePic9(AccountUpdateController.phonePrefix(span))).isEqualTo(555L);
            assertThat(CODEC.decodePic9(AccountUpdateController.phoneLine(span))).isEqualTo(1234L);

            String renumbered = AccountUpdateController.withPhoneArea(span, CODEC.movePic9(917L, 3));
            assertThat(AccountUpdateController.phoneArea(renumbered)).isEqualTo("917");
            assertThat(AccountUpdateController.phonePrefix(renumbered)).isEqualTo("555");
        }

        @Test
        @DisplayName("A non-numeric value in the alphanumeric view is what IS NOT NUMERIC tests for")
        void aNonNumericValueInTheAlphanumericView() {
            String span = AccountUpdateController.withPhoneArea(AccountUpdateController.spaces(
                    AccountUpdateController.WS_EDIT_US_PHONE_NUM_LENGTH), "2O6");

            assertThat(AccountUpdateController.phoneArea(span)).isEqualTo("2O6");
            assertThat(AccountUpdateController.isNumericPicX(
                    AccountUpdateController.phoneArea(span))).isFalse();
            assertThat(AccountUpdateController.isNumericPicX("206")).isTrue();
            assertThat(AccountUpdateController.isNumericPicX("   ")).isFalse();
        }

        @Test
        @DisplayName("WS-EDIT-US-SSN: three parts, and the PIC 9(09) view over the whole group")
        void theSsnPartsAndTheGroupNumericView() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewCust.setSsn1("123");
            task.acupNewCust.setSsn2("45");
            task.acupNewCust.setSsn3("6789");

            assertThat(task.acupNewCust.ssn1()).isEqualTo("123");
            assertThat(task.acupNewCust.ssn2()).isEqualTo("45");
            assertThat(task.acupNewCust.ssn3()).isEqualTo("6789");
            assertThat(task.acupNewCust.ssnX).isEqualTo("123456789");
            assertThat(CODEC.decodePic9(task.acupNewCust.ssnX)).isEqualTo(123456789L);

            task.acupNewCust.ssnX = "987654321";
            assertThat(task.acupNewCust.ssn1()).isEqualTo("987");
            assertThat(task.acupNewCust.ssn2()).isEqualTo("65");
            assertThat(task.acupNewCust.ssn3()).isEqualTo("4321");

            task.acupNewCust.setSsn1("66A");
            assertThat(AccountUpdateController.isNumericPicX(task.acupNewCust.ssn1())).isFalse();
        }

        @Test
        @DisplayName("WS-CARD-RID-CUST-ID and -ACCT-ID: PIC 9 storage read through their -X views")
        void theCardRidViews() {
            stubAllFound();
            AccountUpdateController.Conversation task = warmTask();
            task.ccWorkArea.setCcAcctId(ACCT);

            controller.readAcct9000(task);

            assertThat(task.wsCardRidAcctId)
                    .hasSize(AccountUpdateController.WS_CARD_RID_ACCT_ID_LENGTH)
                    .isEqualTo(ACCT);
            assertThat(task.wsCardRidAcctIdN()).isEqualTo(11L);
            assertThat(task.wsCardRidCustId)
                    .hasSize(AccountUpdateController.WS_CARD_RID_CUST_ID_LENGTH);
            assertThat(task.wsCardRidCustIdN()).isEqualTo(CUST_ID);
            assertThat(task.wsCardRidCustId).isEqualTo(CODEC.movePic9((long) CUST_ID, 9));
        }

        @Test
        @DisplayName("The five monetary spans: the X view and the PIC S9(10)V99 view agree")
        void theMonetarySpansAndTheirSignedViews() {
            AccountUpdateController.Conversation task = warmTask();
            task.acupNewAcct.currBal =
                    AccountUpdateController.monetaryImage(new BigDecimal("1234.56"));

            assertThat(task.acupNewAcct.currBal)
                    .hasSize(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            assertThat(task.acupNewAcct.currBalN()).isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(task.acupNewAcct.currBalN().scale()).isEqualTo(2);
            assertThat(AccountUpdateController.storeMonetary(new BigDecimal("1234.569")))
                    .isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(AccountUpdateController.storeMonetary(new BigDecimal("-1234.569")))
                    .isEqualByComparingTo(new BigDecimal("-1234.56"));
        }
    }

}
