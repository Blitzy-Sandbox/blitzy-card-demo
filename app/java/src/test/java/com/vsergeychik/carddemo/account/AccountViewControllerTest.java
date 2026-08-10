package com.vsergeychik.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.account.dto.AccountViewRequest;
import com.vsergeychik.carddemo.account.dto.AccountViewResponse;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link AccountViewController} - the {@code COACTVWC} / {@code CAVW} view-account screen.
 *
 * <p>Every test that asserts a <em>decision</em> instantiates the controller <strong>directly</strong>
 * with mocked repositories and a fixed {@link Clock}. There is no Spring context, no {@code MockMvc} and
 * no {@code JobLauncher} anywhere in the decision path, which is gate <strong>G51</strong>: the guard
 * chains and the field projections are asserted where they live, so a failure names the paragraph rather
 * than an HTTP status. The endpoint's own contract - the mapping, the path variable, the two optional
 * query parameters and the JSON shape - is covered through the package-visible seams
 * {@link AccountViewController#bind}, {@link AccountViewController#resolveEibcalen} and
 * {@link AccountViewController#resolveAttentionIdentifier} that {@code getAccount} delegates to, for the
 * same reason.
 *
 * <p>The clock is fixed because {@code 1200-SETUP-SCREEN-VARS} renders {@code CURDATE} and {@code CURTIME}
 * from {@code FUNCTION CURRENT-DATE}, and a screen carrying a live clock could not be compared
 * byte-for-byte against anything.
 *
 * <p>Expectations here are <strong>statically derived</strong> from {@code app/cbl/COACTVWC.cbl},
 * {@code app/cpy-bms/COACTVW.CPY}, {@code app/bms/COACTVW.bms}, {@code app/csd/CARDDEMO.CSD},
 * {@code app/cpy/CVCRD01Y.cpy}, {@code app/cpy/COCOM01Y.cpy}, {@code app/cpy/CSSTRPFY.cpy} and
 * {@code app/cpy/CVACT01Y.cpy}. The legacy COBOL cannot be executed in this environment (AAP risk R-A),
 * so no captured baseline exists and none is claimed - each assertion cites the line it was read from so
 * it can be checked against the source by eye.
 *
 * <p>Five source behaviours the {@code 88}-level declarations actively hide are pinned here deliberately,
 * because a later reader would otherwise "fix" them and break parity (practice B5):
 * <ol>
 *   <li>{@code 2210-EDIT-ACCOUNT} at {@code :671-673} moves a <em>different</em> literal from the one
 *       {@code SEARCHED-ACCT-NOT-NUMERIC} declares - two spaces after "must", and a hyphen in
 *       "non-zero".</li>
 *   <li>All three read paragraphs leave their {@code SET DID-NOT-FIND-*} either commented out
 *       ({@code :792}, {@code :842}) or absent ({@code :741-758}), composing a {@code STRING} message
 *       instead.</li>
 *   <li>So the guards at {@code :704} and {@code :713} are value tests that never fire, and a missing
 *       account still falls through to the customer read.</li>
 *   <li>An unsupported AID is rewritten to {@code ENTER} at {@code :312-314}, so PF7 behaves exactly like
 *       Enter.</li>
 *   <li>{@code app/cpy/CSMSG02Y.cpy} declares {@code ABEND-MSG PIC X(72) VALUE SPACES}, not
 *       {@code LOW-VALUES}, so {@code ABEND-ROUTINE}'s default-message move at {@code :918-919} never
 *       fires.</li>
 * </ol>
 */
@DisplayName("AccountViewController - COACTVWC, transaction CAVW, the view-account screen")
class AccountViewControllerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:32Z"), ZoneOffset.UTC);
    private static final String ACCT = "00000000011";
    private static final String XREF_CARD = "1234567890123456";
    private static final String XREF_IMAGE = " ".repeat(CardXrefRepository.RECORD_LENGTH);
    private static final String CUST_IMAGE = " ".repeat(CustomerRepository.RECORD_LENGTH);

    private AccountRepository accounts;
    private CardXrefRepository xrefs;
    private CustomerRepository customers;
    private AccountViewController controller;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        xrefs = mock(CardXrefRepository.class);
        customers = mock(CustomerRepository.class);
        controller = new AccountViewController(accounts, xrefs, customers, CLOCK);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures.
    // ---------------------------------------------------------------------------------------------

    private AccountViewRequest request(String acctsid, NavigationContext context) {
        AccountViewRequest request = new AccountViewRequest();
        request.initializeMapArea();
        request.setAcctsid(controller.codec().movePicX(acctsid, AccountViewRequest.ACCTSID_LENGTH));
        if (context != null) {
            request.setNavigationContext(context);
        }
        return request;
    }

    private static NavigationContext reenter() {
        return NavigationContext.empty().withPgmReenter();
    }

    private static AccountRecord account() {
        AccountRecord record = new AccountRecord(StandardCharsets.US_ASCII);
        record.setAcctId(11L);
        record.setAcctActiveStatus("Y");
        record.setAcctCurrBal(new BigDecimal("1234.56"));
        record.setAcctCreditLimit(new BigDecimal("999999999.99"));
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
        record.setCustId(123456789);
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
        record.setCustAddrZip("9810112345");
        record.setCustAddrCountryCd("USA");
        record.setCustPhoneNum1("(206)555-1234XY");
        record.setCustPhoneNum2("(206)555-9876ZZ");
        record.setCustGovtIssuedId("WA-DL-0099887766");
        record.setCustEftAccountId("EFT0000001");
        record.setCustPriCardHolderInd("Y");
        return record;
    }

    private void stubAllFound() {
        when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                CardXrefRepository.ReadResult.found(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        new CardXrefRecord(XREF_CARD, 123456789, 11L), XREF_IMAGE));
        when(accounts.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.found(account()));
        when(customers.readByKey(anyString()))
                .thenReturn(CustomerRepository.ReadResult.found(customer(), CUST_IMAGE));
    }

    // ---------------------------------------------------------------------------------------------
    // Parameter resolution.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("EIBCALEN: derived, and the four accepted statements")
    void eibcalenResolution() {
        AccountViewRequest cold = request(ACCT, null);
        AccountViewRequest warm = request(ACCT, reenter());
        assertThat(AccountViewController.resolveEibcalen(null, cold)).isZero();
        assertThat(AccountViewController.resolveEibcalen(null, warm)).isEqualTo(172);
        assertThat(AccountViewController.resolveEibcalen(0, cold)).isZero();
        assertThat(AccountViewController.resolveEibcalen(160, warm)).isEqualTo(160);
        assertThat(AccountViewController.resolveEibcalen(172, warm)).isEqualTo(172);
        assertThatThrownBy(() -> AccountViewController.resolveEibcalen(99, warm))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountViewController.resolveEibcalen(0, warm))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountViewController.resolveEibcalen(172, cold))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EIBAID: absent is DFHENTER, both spellings accepted, disagreement refused")
    void aidResolution() {
        assertThat(AccountViewController.resolveAttentionIdentifier(null))
                .isEqualTo(CicsAid.DFHENTER);
        assertThat(AccountViewController.resolveAttentionIdentifier(0xF3)).isEqualTo((byte) 0xF3);
        assertThatThrownBy(() -> AccountViewController.resolveAttentionIdentifier(256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountViewController.resolveAttentionIdentifier(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AccountViewController.resolveAidParameter(null, null)).isNull();
        assertThat(AccountViewController.resolveAidParameter(13, null)).isEqualTo(13);
        assertThat(AccountViewController.resolveAidParameter(null, 13)).isEqualTo(13);
        assertThat(AccountViewController.resolveAidParameter(13, 13)).isEqualTo(13);
        assertThatThrownBy(() -> AccountViewController.resolveAidParameter(13, 14))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("bind: the URI pads to ACCTSIDI's eleven characters and never truncates")
    void bindPadsAndRefusesOverlongKeys() {
        assertThat(controller.bind("11", null).getAcctsid()).isEqualTo("11         ");
        assertThatThrownBy(() -> controller.bind("123456789012", null))
                .isInstanceOf(IllegalArgumentException.class);
        AccountViewRequest caller = request("99999999999", reenter());
        AccountViewRequest bound = controller.bind("11", caller);
        assertThat(caller.getAcctsid()).isEqualTo("99999999999");
        assertThat(bound.getAcctsid()).isEqualTo("11         ");
    }

    // ---------------------------------------------------------------------------------------------
    // The four EVALUATE TRUE arms - gate G30.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("WHEN CDEMO-PGM-ENTER: paint the prompt, read nothing")
    void enterPaintsWithoutReading() {
        AccountViewResponse painted =
                controller.handle(request(ACCT, null), 0, CicsAid.DFHENTER);
        assertThat(painted.getInfomsg())
                .isEqualTo(controller.codec().movePicX(
                        "Enter or update id of account to display", 45));
        assertThat(painted.getErrmsg()).isEqualTo(" ".repeat(78));
        assertThat(painted.getTrnname()).isEqualTo("CAVW");
        assertThat(painted.getPgmname()).isEqualTo("COACTVWC");
        assertThat(painted.getCurdate()).isEqualTo("07/19/22");
        assertThat(painted.getCurtime()).isEqualTo("23:12:32");
        assertThat(painted.getNextMapset()).isEqualTo("COACTVW");
        assertThat(painted.getNextMap()).isEqualTo("CACTVWA");
        assertThat(painted.getNavigationContext().isReenter()).isTrue();
        assertThat(painted.getAcctsid()).isEqualTo(CardScreenState.lowValues(11));
        assertThat(painted.fieldImages()).hasSize(37);
        verifyNoInteractions(accounts, xrefs, customers);
    }

    @Test
    @DisplayName("WHEN CDEMO-PGM-REENTER: three reads, 37 fields, five edited amounts")
    void reenterReadsAndProjects() {
        stubAllFound();
        AccountViewResponse painted =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        assertThat(painted.getAcctsid()).isEqualTo(ACCT);
        assertThat(painted.getAcsttus()).isEqualTo("Y");
        assertThat(painted.getAdtopen()).isEqualTo("2020-01-15");
        assertThat(painted.getAexpdt()).isEqualTo("2025-01-14");
        assertThat(painted.getAreisdt()).isEqualTo("2022-06-30");
        assertThat(painted.getAaddgrp()).isEqualTo("ZEROPCT   ");
        assertThat(painted.getAcurbal()).isEqualTo("+      1,234.56");
        assertThat(painted.getAcrdlim()).isEqualTo("+999,999,999.99");
        assertThat(painted.getAcshlim()).isEqualTo("-        250.00");
        assertThat(painted.getAcrcycr()).isEqualTo("+           .00");
        assertThat(painted.getAcrcydb()).isEqualTo("+         87.05");
        assertThat(painted.getAcstnum()).isEqualTo("123456789");
        assertThat(painted.getAcstssn()).isEqualTo("123-45-6789\u0000");
        assertThat(painted.getAcstfco()).isEqualTo("750");
        assertThat(painted.getAcstdob()).isEqualTo("1980-02-29");
        assertThat(painted.getAcsfnam()).isEqualTo(pad("ANNE", 25));
        assertThat(painted.getAcsmnam()).isEqualTo(pad("Q", 25));
        assertThat(painted.getAcslnam()).isEqualTo(pad("ARCHER", 25));
        assertThat(painted.getAcsadl1()).isEqualTo(pad("1 MAIN STREET", 50));
        assertThat(painted.getAcsadl2()).isEqualTo(pad("APT 2B", 50));
        assertThat(painted.getAcscity()).isEqualTo(pad("SEATTLE", 50));
        assertThat(painted.getAcsstte()).isEqualTo("WA");
        assertThat(painted.getAcszipc()).isEqualTo("98101");
        assertThat(painted.getAcsctry()).isEqualTo("USA");
        assertThat(painted.getAcsphn1()).isEqualTo("(206)555-1234");
        assertThat(painted.getAcsphn2()).isEqualTo("(206)555-9876");
        assertThat(painted.getAcsgovt()).isEqualTo(pad("WA-DL-0099887766", 20));
        assertThat(painted.getAcseftc()).isEqualTo("EFT0000001");
        assertThat(painted.getAcspflg()).isEqualTo("Y");
        assertThat(painted.getErrmsg()).isEqualTo(" ".repeat(78));
        assertThat(painted.getNavigationContext().custId()).isEqualTo(123456789);
        assertThat(painted.getNavigationContext().cardNum()).isEqualTo(1234567890123456L);
        assertThat(painted.getNavigationContext().acctId()).isEqualTo(11L);
        assertThat(painted.getNextProgram()).isEqualTo("COACTVWC");
        verify(xrefs).readByAccountIdViaAltIndex(ACCT);
        verify(accounts).readByKey(11L);
        verify(customers).readByKey("123456789");
    }

    @Test
    @DisplayName("WHEN CCARD-AID-PFK03: menu by default, the caller when it named itself")
    void pfk03NavigatesBack() {
        AccountViewResponse toMenu =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHPF3);
        assertThat(toMenu.getNextProgram()).isEqualTo("COMEN01C");
        assertThat(toMenu.getNavigationContext().toTranid()).isEqualTo("CM00");
        assertThat(toMenu.getNavigationContext().fromProgram()).isEqualTo("COACTVWC");
        assertThat(toMenu.getNavigationContext().fromTranid()).isEqualTo("CAVW");
        assertThat(toMenu.getNavigationContext().userType()).isEqualTo("U");
        assertThat(toMenu.getNavigationContext().isEnter()).isTrue();
        assertThat(toMenu.getNavigationContext().lastMapset()).isEqualTo("COACTVW");
        assertThat(toMenu.getNavigationContext().lastMap()).isEqualTo("CACTVWA");
        assertThat(toMenu.getErrmsg()).isEqualTo(CardScreenState.lowValues(78));
        verifyNoInteractions(accounts, xrefs, customers);

        NavigationContext fromList = reenter()
                .withFromProgram("COCRDLIC")
                .withFromTranid("CCLI")
                .withUserType("A");
        AccountViewResponse back =
                controller.handle(request(ACCT, fromList), 172, CicsAid.DFHPF3);
        assertThat(back.getNextProgram()).isEqualTo("COCRDLIC");
        assertThat(back.getNavigationContext().toTranid()).isEqualTo("CCLI");
        assertThat(back.getNavigationContext().userType())
                .as("SET CDEMO-USRTYP-USER TO TRUE at :344 downgrades an admin unconditionally")
                .isEqualTo("U");
    }

    @Test
    @DisplayName("WHEN OTHER: abend code 0001 and UNEXPECTED DATA SCENARIO, without abending")
    void whenOtherSendsPlainText() {
        NavigationContext odd = NavigationContext.empty().withPgmContext(2);
        AccountViewResponse painted = controller.handle(request(ACCT, odd), 172, CicsAid.DFHENTER);
        assertThat(painted.getErrmsg())
                .isEqualTo(pad(pad("UNEXPECTED DATA SCENARIO", 75), 78));
        assertThat(painted.getAcctsid()).isEqualTo(CardScreenState.lowValues(11));
        assertThat(painted.getTrnname()).isEqualTo(CardScreenState.lowValues(4));
        verifyNoInteractions(accounts, xrefs, customers);
        assertThat(AccountViewController.UNEXPECTED_DATA_ABEND_CODE).isEqualTo("0001");
    }

    @Test
    @DisplayName("An unsupported AID is rewritten to ENTER, so PF7 behaves exactly like ENTER")
    void unsupportedAidBecomesEnter() {
        stubAllFound();
        AccountViewResponse viaEnter =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        AccountViewResponse viaPf7 =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHPF7);
        assertThat(viaPf7.fieldImages()).isEqualTo(viaEnter.fieldImages());
        assertThat(viaPf7.getCardScreenState().isCcardAidEnter()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // 2210-EDIT-ACCOUNT and the message texts.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A blank filter: the cross-field edit overwrites the field message")
    void blankFilterReportsNoInput() {
        AccountViewResponse painted =
                controller.handle(request("*", reenter()), 172, CicsAid.DFHENTER);
        assertThat(painted.getErrmsg()).isEqualTo(pad(pad("No input received", 75), 78));
        assertThat(painted.getAcctsid()).isEqualTo(pad("*", 11));
        assertThat(painted.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(painted.getNavigationContext().acctId()).isZero();
        verifyNoInteractions(accounts, xrefs, customers);
    }

    @Test
    @DisplayName("A non-numeric or zero filter reports the literal 2210 actually moves")
    void nonNumericFilterReportsTheMovedLiteral() {
        String expected = pad(pad("Account Filter must  be a non-zero 11 digit number", 75), 78);
        assertThat(controller.handle(request("1234567890X", reenter()), 172, CicsAid.DFHENTER)
                .getErrmsg()).isEqualTo(expected);
        assertThat(controller.handle(request("00000000000", reenter()), 172, CicsAid.DFHENTER)
                .getErrmsg()).isEqualTo(expected);
        assertThat(controller.handle(request("123", reenter()), 172, CicsAid.DFHENTER)
                .getErrmsg())
                .as("a short entry is space padded and so is not numeric, exactly as MUSTFILL implies")
                .isEqualTo(expected);
        verifyNoInteractions(accounts, xrefs, customers);
    }

    @Test
    @DisplayName("editAccount2210 in isolation: both guards, and the accepting path")
    void editAccountDrivesBothGuards() {
        AccountViewController.Conversation blank = conversation();
        blank.ccWorkArea.setCcAcctIdToLowValues();
        controller.editAccount2210(blank);
        assertThat(blank.inputError()).isTrue();
        assertThat(blank.flgAcctfilterBlank()).isTrue();
        assertThat(blank.promptForAcct()).isTrue();
        assertThat(blank.wsReturnMsg).isEqualTo(pad("Account number not provided", 75));

        AccountViewController.Conversation kept = conversation();
        kept.ccWorkArea.setCcAcctIdToLowValues();
        kept.wsReturnMsg = pad("Prior message", 75);
        controller.editAccount2210(kept);
        assertThat(kept.wsReturnMsg)
                .as("both guards only set the message IF WS-RETURN-MSG-OFF")
                .isEqualTo(pad("Prior message", 75));

        AccountViewController.Conversation good = conversation();
        good.ccWorkArea.setCcAcctId(ACCT);
        controller.editAccount2210(good);
        assertThat(good.inputError()).isFalse();
        assertThat(good.flgAcctfilterIsvalid()).isTrue();
        assertThat(good.carddemoCommarea.acctId()).isEqualTo(11L);
    }

    // ---------------------------------------------------------------------------------------------
    // 9000-READ-ACCT: every outcome at every site - gate G47.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A cross-reference miss stops the sequence and composes its own message")
    void xrefNotFoundStopsTheSequence() {
        when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                CardXrefRepository.ReadResult.notFound(
                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME));
        AccountViewResponse painted =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        assertThat(painted.getErrmsg().trim())
                .startsWith("Account:" + ACCT + " not found in Cross ref file.  Resp:");
        assertThat(painted.getErrmsg()).hasSize(78);
        verify(accounts, never()).readByKey(anyLong());
        verify(customers, never()).readByKey(anyString());
    }

    @Test
    @DisplayName("An account miss does NOT stop the sequence, because the guard at :704 never fires")
    void accountNotFoundStillReadsTheCustomer() {
        when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                CardXrefRepository.ReadResult.found(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        new CardXrefRecord(XREF_CARD, 123456789, 11L), XREF_IMAGE));
        when(accounts.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.notFound());
        when(customers.readByKey(anyString()))
                .thenReturn(CustomerRepository.ReadResult.found(customer(), CUST_IMAGE));
        AccountViewResponse painted =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        verify(customers).readByKey("123456789");
        assertThat(painted.getErrmsg().trim())
                .startsWith("Account:" + ACCT + " not found in Acct Master file.Resp:");
        assertThat(painted.getAcslnam())
                .as("the customer projection runs even though the account was never read")
                .isEqualTo(pad("ARCHER", 25));
        assertThat(painted.getAcsttus())
                .as("and the account projection runs against an untouched WORKING-STORAGE group")
                .isEqualTo(" ");
        assertThat(painted.getAcurbal()).isEqualTo("+           .00");
    }

    @Test
    @DisplayName("A customer miss keeps the earlier message and flags the customer, not the account")
    void customerNotFoundUsesTheCustomerFlag() {
        when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                CardXrefRepository.ReadResult.found(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        new CardXrefRecord(XREF_CARD, 123456789, 11L), XREF_IMAGE));
        when(accounts.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.found(account()));
        when(customers.readByKey(anyString())).thenReturn(CustomerRepository.ReadResult.notFound());
        AccountViewResponse painted =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        assertThat(painted.getErrmsg().trim())
                .startsWith("CustId:123456789 not found in customer master.Resp: ");
        assertThat(painted.getAcsttus())
                .as("FOUND-ACCT-IN-MASTER is set, so the account fields are still projected")
                .isEqualTo("Y");
        assertThat(painted.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                .as("the customer flag was set, not the account flag, so the field is not reddened")
                .isEqualTo(BmsAttributes.DFHDFCOL);
    }

    @Test
    @DisplayName("WS-FILE-ERROR-MESSAGE is composed byte for byte and truncated to 75 on the move")
    void otherOutcomeComposesTheFileErrorMessage() {
        when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                CardXrefRepository.ReadResult.other(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        CardXrefRepository.PERMANENT_ERROR_STATUS));
        AccountViewResponse painted =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        String expected = "File Error: READ     on CXACAIX   returned RESP "
                + AccountViewController.responseCodeImage(FileStatus.NOTOPEN)
                + ",RESP2 " + AccountViewController.responseCodeImage(0);
        assertThat(expected).hasSize(75);
        assertThat(painted.getErrmsg()).isEqualTo(pad(expected, 78));
        assertThat(painted.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                .isEqualTo(BmsAttributes.DFHRED);
    }

    @Test
    @DisplayName("fileErrorMessage: eighty characters, and every part in its declared place")
    void fileErrorMessageIsEightyCharacters() {
        AccountViewController.Conversation task = conversation();
        task.errorOpname = pad("READ", 8);
        task.errorFile = pad("ACCTDAT", 9);
        task.errorResp = AccountViewController.responseCodeImage(13);
        task.errorResp2 = AccountViewController.responseCodeImage(0);
        String message = controller.fileErrorMessage(task);
        assertThat(message).hasSize(80).isEqualTo("File Error: READ     on ACCTDAT   returned RESP "
                + "000000013 ,RESP2 000000000      ");
        assertThat(message.substring(75)).isEqualTo("     ");
    }

    @Test
    @DisplayName("A response code the backend never reported is not rendered as zero")
    void unreportedResponseCodeIsNotZero() {
        assertThat(AccountViewController.responseCodeImage(FileStatus.RESP_NOT_REPORTED))
                .isEqualTo("********* ");
        assertThat(AccountViewController.responseCodeImage(0)).isEqualTo("000000000 ");
        assertThat(AccountViewController.cicsResp(OptionalInt.of(13), FileStatus.NOT_FOUND))
                .isEqualTo(13);
        assertThat(AccountViewController.cicsResp(OptionalInt.empty(), FileStatus.NOT_FOUND))
                .isEqualTo(FileStatus.NOTFND);
    }

    // ---------------------------------------------------------------------------------------------
    // Statelessness - gate G37 - and the abend path.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Two sequential interactions cannot see each other's state")
    void interactionsAreIndependent() {
        stubAllFound();
        AccountViewResponse first =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        AccountViewResponse second = controller.handle(request("*", reenter()), 172, CicsAid.DFHENTER);
        AccountViewResponse third =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        assertThat(second.getErrmsg()).isEqualTo(pad(pad("No input received", 75), 78));
        assertThat(second.getAcsttus()).isEqualTo(CardScreenState.lowValues(1));
        assertThat(third.fieldImages()).isEqualTo(first.fieldImages());
    }

    @Test
    @DisplayName("A failure inside the flow reaches ABEND-ROUTINE and abends with 9999")
    void failureInsideTheFlowAbends() {
        when(xrefs.readByAccountIdViaAltIndex(anyString()))
                .thenThrow(new IllegalStateException("driver refused the row"));
        AbendException abend = catchThrowableOfType(AbendException.class,
                () -> controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER));
        assertThat(abend).isNotNull();
        assertThat(abend.getMessage()).contains("ABCODE 9999");
        assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(abend.getProgram()).isEqualTo(AccountViewController.LIT_THISPGM);
        assertThat(abend.getSourceDiagnostic()).isPresent();
        assertThat(abend.getSourceDiagnostic().orElseThrow())
                .contains(AccountViewController.LIT_THISPGM);
        // app/cpy/CSMSG02Y.cpy declares ABEND-MSG PIC X(72) VALUE SPACES, so the LOW-VALUES test at
        // COACTVWC.cbl:918 can never match and this default is transcribed but never stored. The
        // rendered reason therefore carries the ABCODE and nothing else.
        assertThat(abend.getMessage()).doesNotContain("UNEXPECTED ABEND OCCURRED.");
        assertThat(AccountViewController.UNEXPECTED_ABEND_OCCURRED)
                .isEqualTo(pad("UNEXPECTED ABEND OCCURRED.", 72));
    }

    @Test
    @DisplayName("abendDataImage renders the four items at their declared widths")
    void abendDataImageIsDeclaredWidth() {
        String image = AccountViewController.abendDataImage(SystemMessages.AbendData.spaces()
                .withAbendCode("0001").withAbendCulprit("COACTVWC"));
        assertThat(image).hasSize(SystemMessages.ABEND_CODE_LENGTH
                + SystemMessages.ABEND_CULPRIT_LENGTH + SystemMessages.ABEND_REASON_LENGTH
                + SystemMessages.ABEND_MSG_LENGTH);
        assertThat(image).startsWith("0001COACTVWC");
    }

    // ---------------------------------------------------------------------------------------------
    // The transcribed literals, and all 28 condition names in both directions - gate G50.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The ten WS-RETURN-MSG texts and the two WS-INFO-MSG texts are byte exact")
    void messageLiteralsAreByteExact() {
        assertThat(AccountViewController.WS_RETURN_MSG_OFF).isEqualTo(" ".repeat(75));
        assertThat(AccountViewController.WS_EXIT_MESSAGE)
                .isEqualTo(pad("PF03 pressed.Exiting              ", 75))
                .startsWith("PF03 pressed.Exiting              ");
        assertThat(AccountViewController.WS_PROMPT_FOR_ACCT)
                .isEqualTo(pad("Account number not provided", 75));
        assertThat(AccountViewController.NO_SEARCH_CRITERIA_RECEIVED)
                .isEqualTo(pad("No input received", 75));
        assertThat(AccountViewController.SEARCHED_ACCT_ZEROES)
                .isEqualTo(pad("Account number must be a non zero 11 digit number", 75))
                .isEqualTo(AccountViewController.SEARCHED_ACCT_NOT_NUMERIC);
        assertThat(AccountViewController.DID_NOT_FIND_ACCT_IN_CARDXREF)
                .isEqualTo(pad("Did not find this account in account card xref file", 75));
        assertThat(AccountViewController.DID_NOT_FIND_ACCT_IN_ACCTDAT)
                .isEqualTo(pad("Did not find this account in account master file", 75));
        assertThat(AccountViewController.DID_NOT_FIND_CUST_IN_CUSTDAT)
                .isEqualTo(pad("Did not find associated customer in master file", 75));
        assertThat(AccountViewController.XREF_READ_ERROR)
                .isEqualTo(pad("Error reading account card xref File", 75));
        assertThat(AccountViewController.CODING_TO_BE_DONE)
                .isEqualTo(pad("Looks Good.... so far", 75));
        assertThat(AccountViewController.ACCOUNT_FILTER_NOT_NUMERIC)
                .isEqualTo(pad("Account Filter must  be a non-zero 11 digit number", 75));
        assertThat(AccountViewController.WS_PROMPT_FOR_INPUT)
                .isEqualTo("Enter or update id of account to display").hasSize(40);
        assertThat(AccountViewController.WS_INFORM_OUTPUT)
                .isEqualTo(pad("Displaying details of given Account", 40));
        assertThat(AccountViewController.LIT_THISMAPSET).isEqualTo("COACTVW ");
        assertThat(AccountViewController.LIT_CARDUPDATEMAPSET).isEqualTo("COCRDUP ");
        assertThat(AccountViewController.LIT_ACCTFILENAME).isEqualTo("ACCTDAT ");
        assertThat(AccountViewController.LIT_CUSTFILENAME).isEqualTo("CUSTDAT ");
        assertThat(AccountViewController.LIT_CARDXREFNAME_ACCT_PATH).isEqualTo("CXACAIX ");
        assertThat(AccountViewController.LIT_CARDFILENAME).isEqualTo("CARDDAT ");
        assertThat(AccountViewController.LIT_CARDFILENAME_ACCT_PATH).isEqualTo("CARDAIX ");
        assertThat(AccountViewController.LIT_ALL_ALPHA_FROM).hasSize(52);
        assertThat(AccountViewController.LIT_ALL_SPACES_TO).isEqualTo(" ".repeat(52));
        assertThat(AccountViewController.LIT_UPPER).hasSize(26);
        assertThat(AccountViewController.LIT_LOWER).hasSize(26);
        assertThat(AccountViewController.LIT_CCLISTPGM).isEqualTo("COCRDLIC");
        assertThat(AccountViewController.LIT_CCLISTTRANID).isEqualTo("CCLI");
        assertThat(AccountViewController.LIT_CCLISTMAPSET).isEqualTo("COCRDLI");
        assertThat(AccountViewController.LIT_CCLISTMAP).isEqualTo("CCRDSLA");
        assertThat(AccountViewController.LIT_CARDUPDATEPGM).isEqualTo("COCRDUPC");
        assertThat(AccountViewController.LIT_CARDUDPATETRANID).isEqualTo("CCUP");
        assertThat(AccountViewController.LIT_CARDUPDATEMAP).isEqualTo("CCRDUPA");
        assertThat(AccountViewController.LIT_MENUMAPSET).isEqualTo("COMEN01");
        assertThat(AccountViewController.LIT_MENUMAP).isEqualTo("COMEN1A");
        assertThat(AccountViewController.LIT_CARDDTLPGM).isEqualTo("COCRDSLC");
        assertThat(AccountViewController.LIT_CARDDTLTRANID).isEqualTo("CCDL");
        assertThat(AccountViewController.LIT_CARDDTLMAPSET).isEqualTo("COCRDSL");
        assertThat(AccountViewController.LIT_CARDDTLMAP).isEqualTo("CCRDSLA");
    }

    @Test
    @DisplayName("All 28 condition names answer both true and false")
    void everyConditionNameIsDrivenBothWays() {
        AccountViewController.Conversation task = conversation();

        task.wsInputFlag = AccountViewController.INPUT_OK;
        assertThat(task.inputOk()).isTrue();
        assertThat(task.inputError()).isFalse();
        assertThat(task.inputPending()).isFalse();
        task.wsInputFlag = AccountViewController.INPUT_ERROR;
        assertThat(task.inputError()).isTrue();
        assertThat(task.inputOk()).isFalse();
        task.wsInputFlag = AccountViewController.INPUT_PENDING;
        assertThat(task.inputPending()).isTrue();

        task.wsPfkFlag = AccountViewController.PFK_VALID;
        assertThat(task.pfkValid()).isTrue();
        assertThat(task.pfkInvalid()).isFalse();
        assertThat(task.pfkeyInputPending()).isFalse();
        task.wsPfkFlag = AccountViewController.PFK_INVALID;
        assertThat(task.pfkInvalid()).isTrue();
        assertThat(task.pfkValid()).isFalse();
        task.wsPfkFlag = AccountViewController.INPUT_PENDING;
        assertThat(task.pfkeyInputPending()).isTrue();

        task.wsEditAcctFlag = AccountViewController.FLG_FILTER_NOT_OK;
        assertThat(task.flgAcctfilterNotOk()).isTrue();
        assertThat(task.flgAcctfilterIsvalid()).isFalse();
        assertThat(task.flgAcctfilterBlank()).isFalse();
        task.wsEditAcctFlag = AccountViewController.FLG_FILTER_ISVALID;
        assertThat(task.flgAcctfilterIsvalid()).isTrue();
        task.wsEditAcctFlag = AccountViewController.FLG_FILTER_BLANK;
        assertThat(task.flgAcctfilterBlank()).isTrue();
        assertThat(task.flgAcctfilterNotOk()).isFalse();

        task.wsEditCustFlag = AccountViewController.FLG_FILTER_NOT_OK;
        assertThat(task.flgCustfilterNotOk()).isTrue();
        assertThat(task.flgCustfilterIsvalid()).isFalse();
        assertThat(task.flgCustfilterBlank()).isFalse();
        task.wsEditCustFlag = AccountViewController.FLG_FILTER_ISVALID;
        assertThat(task.flgCustfilterIsvalid()).isTrue();
        task.wsEditCustFlag = AccountViewController.FLG_FILTER_BLANK;
        assertThat(task.flgCustfilterBlank()).isTrue();

        assertThat(task.foundAcctInMaster()).isFalse();
        assertThat(task.foundCustInMaster()).isFalse();
        task.wsAccountMasterReadFlag = AccountViewController.FOUND_IN_MASTER;
        task.wsCustMasterReadFlag = AccountViewController.FOUND_IN_MASTER;
        assertThat(task.foundAcctInMaster()).isTrue();
        assertThat(task.foundCustInMaster()).isTrue();

        task.wsInfoMsg = AccountViewController.WS_INFO_MSG_SPACES;
        assertThat(task.noInfoMessage()).isTrue();
        task.wsInfoMsg = AccountViewController.WS_INFO_MSG_LOW_VALUES;
        assertThat(task.noInfoMessage()).isTrue();
        task.wsInfoMsg = AccountViewController.WS_PROMPT_FOR_INPUT;
        assertThat(task.noInfoMessage()).isFalse();
        assertThat(task.promptForInput()).isTrue();
        assertThat(task.informOutput()).isFalse();
        task.wsInfoMsg = AccountViewController.WS_INFORM_OUTPUT;
        assertThat(task.informOutput()).isTrue();
        assertThat(task.promptForInput()).isFalse();

        task.wsReturnMsg = AccountViewController.WS_RETURN_MSG_OFF;
        assertThat(task.returnMessageOff()).isTrue();
        assertThat(task.exitMessage()).isFalse();
        assertThat(task.promptForAcct()).isFalse();
        assertThat(task.noSearchCriteriaReceived()).isFalse();
        assertThat(task.searchedAcctZeroes()).isFalse();
        assertThat(task.searchedAcctNotNumeric()).isFalse();
        assertThat(task.didNotFindAcctInCardxref()).isFalse();
        assertThat(task.didNotFindAcctInAcctdat()).isFalse();
        assertThat(task.didNotFindCustInCustdat()).isFalse();
        assertThat(task.xrefReadError()).isFalse();
        assertThat(task.codingToBeDone()).isFalse();

        task.wsReturnMsg = AccountViewController.WS_EXIT_MESSAGE;
        assertThat(task.exitMessage()).isTrue();
        task.wsReturnMsg = AccountViewController.WS_PROMPT_FOR_ACCT;
        assertThat(task.promptForAcct()).isTrue();
        task.wsReturnMsg = AccountViewController.NO_SEARCH_CRITERIA_RECEIVED;
        assertThat(task.noSearchCriteriaReceived()).isTrue();
        task.wsReturnMsg = AccountViewController.SEARCHED_ACCT_ZEROES;
        assertThat(task.searchedAcctZeroes()).isTrue();
        assertThat(task.searchedAcctNotNumeric()).isTrue();
        task.wsReturnMsg = AccountViewController.DID_NOT_FIND_ACCT_IN_CARDXREF;
        assertThat(task.didNotFindAcctInCardxref()).isTrue();
        task.wsReturnMsg = AccountViewController.DID_NOT_FIND_ACCT_IN_ACCTDAT;
        assertThat(task.didNotFindAcctInAcctdat()).isTrue();
        task.wsReturnMsg = AccountViewController.DID_NOT_FIND_CUST_IN_CUSTDAT;
        assertThat(task.didNotFindCustInCustdat()).isTrue();
        task.wsReturnMsg = AccountViewController.XREF_READ_ERROR;
        assertThat(task.xrefReadError()).isTrue();
        task.wsReturnMsg = AccountViewController.CODING_TO_BE_DONE;
        assertThat(task.codingToBeDone()).isTrue();

        task.wsCardRidAcctId = ACCT;
        task.wsCardRidCustId = "123456789";
        assertThat(task.wsCardRidAcctIdN()).isEqualTo(11L);
        assertThat(task.wsCardRidCustIdN()).isEqualTo(123456789L);
    }

    @Test
    @DisplayName("The trailer, the helpers and the never-performed paragraph")
    void remainingUnitsBehave() {
        AccountViewController.ThisProgCommarea trailer =
                AccountViewController.ThisProgCommarea.initialized();
        assertThat(trailer.toImage()).isEqualTo(" ".repeat(12));
        assertThat(AccountViewController.ThisProgCommarea.fromImage("COMEN01CCM00"))
                .isEqualTo(new AccountViewController.ThisProgCommarea("COMEN01C", "CM00"));
        assertThatThrownBy(() -> AccountViewController.ThisProgCommarea.fromImage("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountViewController.ThisProgCommarea("SHORT", "CM00"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(AccountViewController.isLowValuesOrSpaces("    ", 4)).isTrue();
        assertThat(AccountViewController.isLowValuesOrSpaces(CardScreenState.lowValues(4), 4)).isTrue();
        assertThat(AccountViewController.isLowValuesOrSpaces("CM00", 4)).isFalse();
        assertThat(AccountViewController.isAsteriskOrSpaces("*", 11)).isTrue();
        assertThat(AccountViewController.isAsteriskOrSpaces(" ".repeat(11), 11)).isTrue();
        assertThat(AccountViewController.isAsteriskOrSpaces(ACCT, 11)).isFalse();
        assertThat(AccountViewController.carriedCardNumber(XREF_CARD)).isEqualTo(1234567890123456L);
        assertThat(AccountViewController.carriedCardNumber(" ".repeat(16))).isZero();
        assertThat(AccountViewController.carriedCardNumber("12345678901234AB")).isZero();

        AccountViewController.Conversation task = conversation();
        task.wsLongMsg = pad("a long diagnostic", 500);
        controller.sendLongText(task);
        assertThat(task.returned).isTrue();
        assertThat(task.cactvwao.build().getErrmsg()).isEqualTo(pad("a long diagnostic", 78));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    private AccountViewController.Conversation conversation() {
        AccountViewController.Conversation task = new AccountViewController.Conversation();
        controller.initializeStorage(request(ACCT, null), task, 0, CicsAid.DFHENTER);
        return task;
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value.substring(0, width)
                : value + " ".repeat(width - value.length());
    }

    @Test
    @DisplayName("The five edited amounts render exactly per PIC +ZZZ,ZZZ,ZZZ.99")
    void editedAmountsRenderExactly() {
        assertThat(AccountViewResponse.editAmount(new BigDecimal("0.00")))
                .isEqualTo("+           .00").hasSize(15);
        assertThat(AccountViewResponse.editAmount(new BigDecimal("-250.00")))
                .isEqualTo("-        250.00");
        assertThat(AccountViewResponse.editAmount(new BigDecimal("999999999.99")))
                .isEqualTo("+999,999,999.99");
        assertThat(AccountViewResponse.editAmount(new BigDecimal("1234.56")))
                .isEqualTo("+      1,234.56");
    }

    @Test
    @DisplayName("A found outcome without a record cannot even be constructed")
    void foundWithoutARecordCannotExist() {
        assertThatThrownBy(() -> new CardXrefRepository.ReadResult(
                CardXrefRepository.ALTERNATE_INDEX_DD_NAME, FileStatus.OK,
                FileStatus.Outcome.OK, Optional.empty(), Optional.empty(),
                FileStatus.NORMAL, 0, Optional.empty()))
                .as("the repository's own record enforces the invariant that getCardXrefByAcct9200 "
                        + "guards, so its orElseThrow is defence in depth rather than a live path")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // restoreCommarea - the compound condition at :282-293.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A first entry from the menu discards the passed context, even with EIBCALEN set")
    void freshEntryFromTheMenuInitializesTheCommarea() {
        // :283 - (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER) is the second disjunct, so
        // both operands of the AND are evaluated and both must be true.
        NavigationContext fromMenu = NavigationContext.empty()
                .withFromProgram("COMEN01C")
                .withFromTranid("CM00")
                .withUserType("A");
        AccountViewResponse painted =
                controller.handle(request(ACCT, fromMenu), 172, CicsAid.DFHENTER);
        assertThat(painted.getNavigationContext().fromProgram())
                .as("INITIALIZE CARDDEMO-COMMAREA at :285 blanks what the menu passed")
                .isEqualTo(" ".repeat(8));
        assertThat(painted.getNavigationContext().userType()).isEqualTo(" ");
        assertThat(painted.getInfomsg())
                .as("an initialised context is CDEMO-PGM-ENTER, so the prompt is painted")
                .isEqualTo(controller.codec().movePicX(
                        "Enter or update id of account to display", 45));
        verifyNoInteractions(accounts, xrefs, customers);
    }

    @Test
    @DisplayName("A re-entry from the menu keeps the passed context, because NOT REENTER is false")
    void reentryFromTheMenuRestoresTheCommarea() {
        stubAllFound();
        NavigationContext reenteringFromMenu = NavigationContext.empty()
                .withFromProgram("COMEN01C")
                .withFromTranid("CM00")
                .withPgmReenter();
        AccountViewResponse painted =
                controller.handle(request(ACCT, reenteringFromMenu), 172, CicsAid.DFHENTER);
        assertThat(painted.getNavigationContext().fromProgram())
                .as("the second disjunct is false, so :288-292 restores rather than :285 initialising")
                .isEqualTo("COMEN01C");
        assertThat(painted.getAcsttus())
                .as("and the REENTER arm runs, so the three reads happened")
                .isEqualTo("Y");
        verify(xrefs).readByAccountIdViaAltIndex(ACCT);
    }

    @Test
    @DisplayName("restoreCommarea in isolation: a short program name comes back padded to PIC X(08)")
    void restoreCommareaImposesDeclaredWidths() {
        AccountViewController.Conversation task = new AccountViewController.Conversation();
        controller.initializeStorage(request(ACCT, NavigationContext.empty()
                .withFromProgram("CO")
                .withPgmReenter()), task, 172, CicsAid.DFHENTER);
        controller.restoreCommarea(task);
        assertThat(task.carddemoCommarea.fromProgram()).isEqualTo("CO      ");
        assertThat(task.thisProgCommarea.toImage()).hasSize(12);
    }

    // ---------------------------------------------------------------------------------------------
    // The trailing guard at :387-392 - reachable only from WHEN OTHER, which sets no error flag.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The trailing INPUT-ERROR guard: silent when input is clean, paints when it is not")
    void trailingGuardDrivesBothSides() {
        AccountViewController.Conversation clean = conversation();
        clean.wsInputFlag = AccountViewController.INPUT_OK;
        controller.trailingInputErrorGuard(request(ACCT, null), clean);
        assertThat(clean.returned)
                .as(":387 IF INPUT-ERROR is false, so the paragraph falls straight through")
                .isFalse();

        AccountViewController.Conversation broken = conversation();
        broken.wsInputFlag = AccountViewController.INPUT_ERROR;
        broken.wsReturnMsg = AccountViewController.NO_SEARCH_CRITERIA_RECEIVED;
        controller.trailingInputErrorGuard(request(ACCT, null), broken);
        assertThat(broken.returned).isTrue();
        assertThat(broken.ccWorkArea.getCcardErrorMsg())
                .as(":388 MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG")
                .isEqualTo(pad("No input received", CardScreenState.CCARD_ERROR_MSG_LENGTH));
        assertThat(broken.cactvwao.build().getErrmsg())
                .isEqualTo(pad(pad("No input received", 75), 78));
    }

    // ---------------------------------------------------------------------------------------------
    // 1300-SETUP-SCREEN-ATTRS - the informational line's colour at :567-571.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("INFOMSG is always neutral, because :528-530 refills the line before :567 reads it")
    void informationalLineIsAlwaysNeutral() {
        // :528-530 - IF WS-NO-INFO-MESSAGE SET WS-PROMPT-FOR-INPUT TO TRUE - runs at the END of
        // 1200-SETUP-SCREEN-VARS, and 1300-SETUP-SCREEN-ATTRS runs after it. So by the time :567 asks
        // IF WS-NO-INFO-MESSAGE the field has already been refilled and the answer is always no. The
        // DFHBMDAR arm of :568 is therefore dead code in the source, and is translated anyway because
        // whether it fires depends on WS-INFO-MSG and not on this class's opinion (practice B5).
        String prompt = controller.codec()
                .movePicX("Enter or update id of account to display", 45);

        AccountViewResponse coldStart = controller.handle(request(ACCT, null), 0, CicsAid.DFHENTER);
        assertThat(coldStart.attributes(AccountViewResponse.ScreenField.INFOMSG).getColour())
                .isEqualTo(BmsAttributes.DFHNEUTR);
        assertThat(coldStart.getInfomsg()).isEqualTo(prompt);

        AccountViewResponse blankFilter =
                controller.handle(request("*", reenter()), 172, CicsAid.DFHENTER);
        assertThat(blankFilter.attributes(AccountViewResponse.ScreenField.INFOMSG).getColour())
                .as("even a failed edit leaves the line neutral, because the prompt was refilled")
                .isEqualTo(BmsAttributes.DFHNEUTR);
        assertThat(blankFilter.getInfomsg()).isEqualTo(prompt);

        stubAllFound();
        AccountViewResponse afterASuccessfulRead =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        assertThat(afterASuccessfulRead.getInfomsg())
                .as("9000-READ-ACCT blanks the line at :689 and nothing ever sets WS-INFORM-OUTPUT, so "
                        + "'Displaying details of given Account' is declared and never shown")
                .isEqualTo(prompt);
        assertThat(afterASuccessfulRead.attributes(AccountViewResponse.ScreenField.INFOMSG)
                .getColour()).isEqualTo(BmsAttributes.DFHNEUTR);
    }

    // ---------------------------------------------------------------------------------------------
    // 2210-EDIT-ACCOUNT - the remaining sides of its four decisions.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A field of spaces is blank too, not only one of LOW-VALUES")
    void spacesCountAsBlank() {
        AccountViewController.Conversation task = conversation();
        task.ccWorkArea.setCcAcctId(" ".repeat(11));
        controller.editAccount2210(task);
        assertThat(task.flgAcctfilterBlank())
                .as(":653 IF CC-ACCT-ID EQUAL LOW-VALUES OR SPACES - the second disjunct")
                .isTrue();
        assertThat(task.wsReturnMsg).isEqualTo(pad("Account number not provided", 75));
        assertThat(task.carddemoCommarea.acctId()).isZero();
    }

    @Test
    @DisplayName("The not-numeric arm also respects IF WS-RETURN-MSG-OFF and keeps a prior message")
    void notNumericArmKeepsAnEarlierMessage() {
        AccountViewController.Conversation task = conversation();
        task.ccWorkArea.setCcAcctId("1234567890X");
        task.wsReturnMsg = AccountViewController.XREF_READ_ERROR;
        controller.editAccount2210(task);
        assertThat(task.wsReturnMsg)
                .as(":670 only stores when WS-RETURN-MSG-OFF, exactly like :657")
                .isEqualTo(pad("Error reading account card xref File", 75));
        assertThat(task.flgAcctfilterNotOk()).isTrue();
        assertThat(task.inputError()).isTrue();
        assertThat(task.carddemoCommarea.acctId()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 9000-READ-ACCT - the two value tests that never fire, driven directly.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The :704 guard does stop the sequence when WS-RETURN-MSG happens to hold its literal")
    void theAccountValueTestStopsTheSequenceWhenItsLiteralIsPresent() {
        stubAllFound();
        AccountViewController.Conversation task = conversation();
        task.ccWorkArea.setCcAcctId(ACCT);
        controller.editAccount2210(task);
        // Nothing in the program ever stores this literal (its SET is commented out at :792), so the
        // guard is dead in practice. Placing the value there by hand is the only way to prove the
        // translated test is the value test the source wrote, and not a flag test.
        task.wsReturnMsg = AccountViewController.DID_NOT_FIND_ACCT_IN_ACCTDAT;
        controller.readAcct9000(task);
        verify(xrefs).readByAccountIdViaAltIndex(ACCT);
        verify(accounts).readByKey(11L);
        verify(customers, never()).readByKey(anyString());
    }

    @Test
    @DisplayName("The :713 guard is the paragraph's last statement, so firing it changes nothing visible")
    void theCustomerValueTestIsTheLastStatement() {
        stubAllFound();
        AccountViewController.Conversation task = conversation();
        task.ccWorkArea.setCcAcctId(ACCT);
        controller.editAccount2210(task);
        task.wsReturnMsg = AccountViewController.DID_NOT_FIND_CUST_IN_CUSTDAT;
        controller.readAcct9000(task);
        assertThat(task.foundAcctInMaster()).isTrue();
        assertThat(task.foundCustInMaster()).isTrue();
        verify(customers).readByKey("123456789");
    }

    // ---------------------------------------------------------------------------------------------
    // The WHEN OTHER arm of each of the three reads - gate G47.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("An account-master WHEN OTHER composes WS-FILE-ERROR-MESSAGE against ACCTDAT")
    void accountOtherOutcomeNamesAcctdat() {
        when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                CardXrefRepository.ReadResult.found(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        new CardXrefRecord(XREF_CARD, 123456789, 11L), XREF_IMAGE));
        when(accounts.readByKey(anyLong())).thenReturn(
                AccountRepository.ReadResult.of(AccountRepository.PERMANENT_ERROR_STATUS));
        when(customers.readByKey(anyString()))
                .thenReturn(CustomerRepository.ReadResult.found(customer(), CUST_IMAGE));
        AccountViewResponse painted =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        assertThat(painted.getErrmsg())
                .startsWith("File Error: READ     on ACCTDAT   returned RESP ")
                .hasSize(78);
        assertThat(painted.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                .as(":810-811 sets the ACCOUNT filter flag, so the field reddens")
                .isEqualTo(BmsAttributes.DFHRED);
    }

    @Test
    @DisplayName("A customer-master WHEN OTHER names CUSTDAT and reddens nothing")
    void customerOtherOutcomeNamesCustdat() {
        when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                CardXrefRepository.ReadResult.found(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        new CardXrefRecord(XREF_CARD, 123456789, 11L), XREF_IMAGE));
        when(accounts.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.found(account()));
        when(customers.readByKey(anyString())).thenReturn(
                CustomerRepository.ReadResult.of(CustomerRepository.PERMANENT_ERROR_STATUS));
        AccountViewResponse painted =
                controller.handle(request(ACCT, reenter()), 172, CicsAid.DFHENTER);
        assertThat(painted.getErrmsg())
                .startsWith("File Error: READ     on CUSTDAT   returned RESP ")
                .hasSize(78);
        assertThat(painted.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                .as(":859-860 sets the CUSTOMER filter flag, so the account field stays default")
                .isEqualTo(BmsAttributes.DFHDFCOL);
        assertThat(painted.getAcsttus())
                .as("FOUND-ACCT-IN-MASTER was set before the customer read failed")
                .isEqualTo("Y");
    }

    // ---------------------------------------------------------------------------------------------
    // IF WS-RETURN-MSG-OFF at each of the three NOTFND arms.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Each NOTFND arm leaves an earlier WS-RETURN-MSG alone")
    void everyNotFoundArmRespectsAnEarlierMessage() {
        AccountViewController.Conversation xrefTask = conversation();
        xrefTask.wsCardRidAcctId = ACCT;
        xrefTask.wsReturnMsg = AccountViewController.CODING_TO_BE_DONE;
        when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                CardXrefRepository.ReadResult.notFound(
                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME));
        controller.getCardXrefByAcct9200(xrefTask);
        assertThat(xrefTask.wsReturnMsg).isEqualTo(pad("Looks Good.... so far", 75));
        assertThat(xrefTask.flgAcctfilterNotOk()).isTrue();

        AccountViewController.Conversation acctTask = conversation();
        acctTask.wsCardRidAcctId = ACCT;
        acctTask.wsReturnMsg = AccountViewController.CODING_TO_BE_DONE;
        when(accounts.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.notFound());
        controller.getAcctDataByAcct9300(acctTask);
        assertThat(acctTask.wsReturnMsg).isEqualTo(pad("Looks Good.... so far", 75));
        assertThat(acctTask.foundAcctInMaster()).isFalse();

        AccountViewController.Conversation custTask = conversation();
        custTask.wsCardRidCustId = "123456789";
        custTask.wsReturnMsg = AccountViewController.CODING_TO_BE_DONE;
        when(customers.readByKey(anyString())).thenReturn(CustomerRepository.ReadResult.notFound());
        controller.getCustDataByCust9400(custTask);
        assertThat(custTask.wsReturnMsg).isEqualTo(pad("Looks Good.... so far", 75));
        assertThat(custTask.errorResp)
                .as(":843-844 move ERROR-RESP OUTSIDE the guard, unlike the other two paragraphs")
                .isEqualTo(AccountViewController.responseCodeImage(FileStatus.NOTFND));
        assertThat(custTask.flgCustfilterNotOk()).isTrue();
        assertThat(custTask.flgAcctfilterNotOk()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // ABEND-ROUTINE against a partly built conversation - :912-936.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ABEND-ROUTINE survives a conversation that abended before INITIALIZE ran")
    void abendRoutineToleratesAnUninitialisedConversation() {
        AccountViewController.Conversation bare = new AccountViewController.Conversation();
        AbendException abend =
                controller.abendRoutine(bare, new IllegalStateException("the driver went away"));
        assertThat(abend.getMessage()).contains("ABCODE 9999");
        assertThat(bare.returned)
                .as("HANDLE ABEND still ends the transaction")
                .isTrue();
        assertThat(bare.abendData).isNotNull();
        assertThat(bare.abendData.abendCulprit().trim()).isEqualTo("COACTVWC");
    }

    @Test
    @DisplayName("A LOW-VALUES ABEND-MSG does take the default the copybook's SPACES never allows")
    void abendRoutineStoresTheDefaultWhenTheMessageIsLowValues() {
        AccountViewController.Conversation task = conversation();
        task.abendData = SystemMessages.AbendData.spaces()
                .withAbendMsg(CardScreenState.lowValues(SystemMessages.ABEND_MSG_LENGTH));
        task.ccWorkArea = null;
        AbendException abend = controller.abendRoutine(task, new IllegalStateException("boom"));
        assertThat(task.abendData.abendMsg())
                .as(":918-919 IF ABEND-MSG EQUAL LOW-VALUES - true only if the field is LOW-VALUES, "
                        + "which app/cpy/CSMSG02Y.cpy's VALUE SPACES never produces")
                .isEqualTo(AccountViewController.UNEXPECTED_ABEND_OCCURRED);
        assertThat(abend.getMessage()).contains("UNEXPECTED ABEND OCCURRED.");
        assertThat(task.cactvwao.build().getCardScreenState()).isNotNull();
    }

    // ---------------------------------------------------------------------------------------------
    // The remaining helper edges.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A card number containing a character below '0' is moved as zero, not rejected")
    void carriedCardNumberHandlesBothSidesOfTheDigitRange() {
        assertThat(AccountViewController.carriedCardNumber("1234-6789012345")).isZero();
        assertThat(AccountViewController.carriedCardNumber("12345678901234:6")).isZero();
        assertThat(AccountViewController.carriedCardNumber("0000000000000001")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Both trailer widths are validated, not only the program name's")
    void trailerValidatesTheTranidWidthToo() {
        assertThatThrownBy(() -> new AccountViewController.ThisProgCommarea("COACTVWC", "XX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CA-FROM-TRANID");
        assertThatThrownBy(() -> new AccountViewController.ThisProgCommarea("COACTVWC", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AccountViewController.ThisProgCommarea(null, "CM00"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AccountViewController.ThisProgCommarea.fromImage(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // The HTTP contract. Mechanism only: routing, binding, status, and the JSON shape the symbolic map
    // defines. Not one branch of the program is asserted through MockMvc, so no behaviour has a slower
    // or less attributable second home (gate G51).
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The HTTP contract - GET /api/accounts/{acctId}, transaction CAVW")
    class HttpWiring {

        /** The 37 wire names, in the order app/bms/COACTVW.bms declares their DFHMDF definitions. */
        private static final String[] FIELDS = {
            "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "acctsid", "acsttus",
            "adtopen", "acrdlim", "aexpdt", "acshlim", "areisdt", "acurbal", "acrcycr", "aaddgrp",
            "acrcydb", "acstnum", "acstssn", "acstdob", "acstfco", "acsfnam", "acsmnam", "acslnam",
            "acsadl1", "acsstte", "acsadl2", "acszipc", "acscity", "acsctry", "acsphn1", "acsgovt",
            "acsphn2", "acseftc", "acspflg", "infomsg", "errmsg",
        };

        private MockMvc mockMvc() {
            return MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new WebConfig.CobolErrorHandler())
                    .build();
        }

        private String body(AccountViewRequest request) throws Exception {
            return new ObjectMapper().writeValueAsString(request);
        }

        @Test
        @DisplayName("The mapping routes, binds the path variable and the body, and answers 200 JSON")
        void theMappingRoutesAndBindsTheWholeRequest() throws Exception {
            stubAllFound();
            AccountViewRequest sent = request("", NavigationContext.empty()
                    .withFromProgram("COMEN01C")
                    .withFromTranid("CM00")
                    .withPgmReenter());

            mockMvc().perform(get("/api/accounts/{acctId}", ACCT)
                            .param("eibAid", String.valueOf((int) CicsAid.DFHENTER))
                            .param("eibcalen", "172")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(sent)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    // The path variable reached ACCTSIDI and came back on ACCTSIDO at PIC X(11).
                    .andExpect(jsonPath("$.acctsid").value(ACCT))
                    .andExpect(jsonPath("$.acsttus").value("Y"))
                    // The five edited items carry their PIC +ZZZ,ZZZ,ZZZ.99 mask over the wire.
                    .andExpect(jsonPath("$.acurbal").value("+      1,234.56"))
                    // The whole 01 CACTVWAI bound, so the commarea the body carried is the one that ran
                    // and came back. CDEMO-FROM-PROGRAM is rewritten only on the transfer arm at :342.
                    .andExpect(jsonPath("$.navigationContext.fromProgram").value("COMEN01C"))
                    .andExpect(jsonPath("$.cardScreenState").exists())
                    .andExpect(jsonPath("$.nextProgram").value("COACTVWC"));
        }

        @Test
        @DisplayName("All 37 DFHMDF fields are on the wire, and the xxxC/xxxP/xxxH/xxxV metadata is not")
        void everyScreenFieldIsOnTheWireAndNoMetadataIs() throws Exception {
            stubAllFound();
            ResultActions result = mockMvc().perform(get("/api/accounts/{acctId}", ACCT)
                            .param("eibaid", String.valueOf((int) CicsAid.DFHENTER))
                            .param("eibcalen", "172")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request("", reenter()))))
                    .andExpect(status().isOk());
            assertThat(FIELDS).hasSize(AccountViewResponse.FIELD_COUNT);
            for (String field : FIELDS) {
                result.andExpect(jsonPath("$." + field).exists());
            }
            // app/cpy-bms/COACTVW.CPY's length, flag and attribute items are validation and highlight
            // metadata, never payload members - so the maps that expose them are @JsonIgnore.
            result.andExpect(jsonPath("$.fieldImages").doesNotExist())
                    .andExpect(jsonPath("$.attributeItems").doesNotExist())
                    .andExpect(jsonPath("$.attributeQuads").doesNotExist());
        }

        @Test
        @DisplayName("A cold start needs no body at all, and paints the prompt")
        void theBodyIsOptional() throws Exception {
            mockMvc().perform(get("/api/accounts/{acctId}", ACCT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.infomsg")
                            .value(controller.codec().movePicX(
                                    "Enter or update id of account to display", 45)))
                    .andExpect(jsonPath("$.pgmname").value("COACTVWC"))
                    .andExpect(jsonPath("$.trnname").value("CAVW"));
            verifyNoInteractions(accounts, xrefs, customers);
        }

        @Test
        @DisplayName("A missing account answers 200 with the composed text, never an empty body")
        void aMissingAccountStillCarriesItsMessage() throws Exception {
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.notFound(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME));
            // The screen is the response. COACTVWC has no path that returns nothing: :741-758 composes a
            // message with STRING and 1000-SEND-MAP paints it, so the transport status stays 200 and the
            // diagnosis travels in ERRMSGO. Note the source's SET DID-NOT-FIND-ACCT-IN-ACCTDAT at :792 is
            // commented out, so the runtime text is this composition and not that condition name's
            // literal - the behaviour follows the source (rule R1).
            mockMvc().perform(get("/api/accounts/{acctId}", ACCT)
                            .param("eibcalen", "172")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request("", reenter()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.errmsg").value(startsWith(
                            "Account:" + ACCT + " not found in Cross ref file.  Resp:")))
                    .andExpect(jsonPath("$.acctsid").value(ACCT));
        }

        @Test
        @DisplayName("An over-long URI key is refused by the advice, not silently truncated")
        void anOverlongKeyIsRefused() throws Exception {
            mockMvc().perform(get("/api/accounts/{acctId}", "123456789012"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(accounts, xrefs, customers);
        }

        @Test
        @DisplayName("An abend inside the flow reaches WebConfig's advice rather than escaping raw")
        void anAbendIsMappedByTheAdvice() throws Exception {
            when(xrefs.readByAccountIdViaAltIndex(anyString()))
                    .thenThrow(new IllegalStateException("the driver went away"));
            mockMvc().perform(get("/api/accounts/{acctId}", ACCT)
                            .param("eibcalen", "172")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(request("", reenter()))))
                    .andExpect(status().is5xxServerError());
        }
    }
}
