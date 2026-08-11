package com.vsergeychik.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.WebConfig;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
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
 *
 * <h2>Two source quirks recorded here on purpose (practice B5)</h2>
 *
 * <ol>
 *   <li><strong>{@code 0000-MAIN-EXIT.} is declared TWICE</strong> - at
 *       {@code app/cbl/COACTVWC.cbl:408} and again at {@code :411}, both bodies nothing but
 *       {@code EXIT}. Nothing references either label, so the duplication is harmless and there is no
 *       behaviour to assert; it is written down here so a later reader of
 *       {@link AccountViewController} does not conclude that a paragraph was dropped in translation.
 *       Nothing in the Java was "deduplicated" to tidy it away.</li>
 *   <li><strong>The exit arm forces the user type to {@code 'U'} unconditionally.</strong>
 *       {@code SET CDEMO-USRTYP-USER TO TRUE} at {@code :344} sits inside
 *       {@code WHEN CCARD-AID-PFK03} with no guard of any kind, so an administrator who presses PF3
 *       leaves this screen carrying {@code CDEMO-USER-TYPE = 'U'}. That is asserted as-is - see
 *       {@link DispatchArmOrder#theExitArmDowngradesAnAdministratorUnconditionally()} - and is
 *       neither weakened nor "hardened", because hardening it would change behaviour and would need
 *       Spring Security, which AAP §0.5.6 excludes (practice B6).</li>
 * </ol>
 *
 * <h2>Governing rules</h2>
 *
 * <p>{@code review_rules} reports <em>"No user rules provided."</em> - that one line is the whole
 * document, so no project rule constrains this file and none has been invented. Per UR4 their absence
 * is not licence to lower the bar, and the enterprise-practice substitutes of AAP §0.10.2 are held as
 * binding instead: <strong>B1/B2</strong> the closed dependency set (JUnit Jupiter, Mockito, AssertJ
 * and Spring Test's servlet {@code MockMvc} only - no WebFlux, no Testcontainers, no Lombok),
 * <strong>B3</strong> the COBOL, BMS, copybook and CSD inputs are read and never written,
 * <strong>B5</strong> quirks are asserted rather than fixed, <strong>B6</strong> the security posture
 * is left exactly as the source has it, <strong>B7</strong> a fixed {@link Clock} and no wall clock
 * anywhere, <strong>B8</strong> every import explicit - there is no {@code .*} import in this file,
 * static imports included (gate <strong>G52</strong>), <strong>B9</strong> no static mutable state,
 * asserted reflectively (gate <strong>G53</strong>), <strong>B10</strong> nothing {@code @Disabled}
 * and no deferred work, and <strong>B12</strong> every expected literal and width carries the source
 * or copybook line it was derived from.
 *
 * <h2>Name divergence (rule R1)</h2>
 *
 * <p>None to reconcile. {@code AccountViewController} describes what {@code COACTVWC} does - its
 * source header reads {@code Function: Accept and process Account View request} - so unlike sixteen
 * other classes in this migration the prompt-mandated name and the verified source function agree.
 */
@DisplayName("AccountViewController - COACTVWC, transaction CAVW, the view-account screen")
class AccountViewControllerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:32Z"), ZoneOffset.UTC);
    private static final String ACCT = "00000000011";

    /**
     * Bytes in {@code WS-THIS-PROGCOMMAREA} - {@code CA-FROM-PROGRAM PIC X(08)} plus
     * {@code CA-FROM-TRANID PIC X(04)} at {@code app/cbl/COACTVWC.cbl:213-216}.
     */
    private static final int THIS_PROGCOMMAREA =
            NavigationContext.FROM_PROGRAM_LENGTH + NavigationContext.FROM_TRANID_LENGTH;

    /**
     * {@code EIBCALEN} for a turn that carries both areas: the
     * {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} of
     * {@code app/cpy/COCOM01Y.cpy} followed by the {@value #THIS_PROGCOMMAREA}-byte trailer, which is
     * exactly what {@code :288-292} reads back out of {@code DFHCOMMAREA}.
     */
    private static final int PASSED_COMMAREA =
            NavigationContext.COMMAREA_LENGTH + THIS_PROGCOMMAREA;

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
        // The copy-not-mutate property, probed with a body value that AGREES with the URI: a body naming
        // a different account is refused outright (see aDisagreeingAccountFilterIsRefused), so it cannot
        // be used to observe the copy.
        AccountViewRequest caller = request("11", reenter());
        String asTheCallerLeftIt = caller.getAcctsid();
        AccountViewRequest bound = controller.bind("11         ", caller);
        assertThat(caller.getAcctsid()).as("the caller's object is never altered")
                .isEqualTo(asTheCallerLeftIt);
        assertThat(bound).as("a copy, not the same object").isNotSameAs(caller);
        assertThat(bound.getAcctsid()).isEqualTo("11         ");
    }

    @Test
    @DisplayName("bind: a body whose ACCTSID names a different account is refused, naming the member")
    void aDisagreeingAccountFilterIsRefused() {
        // The URI and ACCTSIDI state the same key, and a terminal has one key field. Two different keys
        // in one request used to have the typed one silently discarded; it is now refused at the
        // boundary, before any read, with neither value echoed.
        assertThatThrownBy(() -> controller.bind("11", request("99999999999", reenter())))
                .isInstanceOf(ScreenInputRejectedException.class)
                .hasMessageContaining("acctsid")
                .hasMessageNotContaining("99999999999");
    }

    @ParameterizedTest(name = "a body stating ACCTSID as \"{0}\" lets the URI supply it")
    @ValueSource(strings = {"", "           ", "*", "11", "11         ",
        "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"})
    @DisplayName("bind: blank, LOW-VALUES, the asterisk COACTVWC paints, and the URI's own key all agree")
    void theStatesThatAgreeWithTheUriAreAccepted(String stated) {
        // app/cbl/COACTVWC.cbl:563 MOVEs '*' TO ACCTSIDO when nothing was supplied and :628 reads = '*'
        // back as exactly that, so an asterisk names no account and a client echoing that painted screen
        // must bind rather than be refused.
        assertThat(controller.bind("11", request(stated, reenter())).getAcctsid())
                .isEqualTo("11         ");
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

    // =============================================================================================
    // Shared fixtures for the parameterized groups below.
    // =============================================================================================

    /**
     * A {@code CDEMO-PGM-CONTEXT} value that satisfies neither {@code CDEMO-PGM-ENTER} nor
     * {@code CDEMO-PGM-REENTER}.
     *
     * <p>{@code app/cpy/COCOM01Y.cpy:29-31} declares the item {@code PIC 9(01)} with exactly two
     * condition names, {@code VALUE 0} and {@code VALUE 1}, so eight of its ten representable values
     * match no arm at all. Two is the lowest of them and stands for all eight.
     */
    private static final int UNMATCHED_PGM_CONTEXT = 2;

    /**
     * The four {@code WHEN}s of the dispatch {@code EVALUATE TRUE} at
     * {@code app/cbl/COACTVWC.cbl:323-383}, declared in the order the source declares them.
     *
     * <p>The declaration order is the whole point of gate <strong>G30</strong>: {@code EVALUATE} takes
     * the <em>first</em> matching {@code WHEN} and {@code WHEN OTHER} is last, so an arm can shadow a
     * later one whose condition is equally true. {@link #WHEN_CCARD_AID_PFK03} therefore carries a
     * {@code REENTER} context deliberately - both arm 1 and arm 3 match, and arm 1 must win.
     */
    enum DispatchArm {

        /** {@code :324-352} - PF3, transfer control away. Also REENTER, which arm 3 would match. */
        WHEN_CCARD_AID_PFK03(1, CicsAid.DFHPF3, NavigationContext.PGM_CONTEXT_REENTER),

        /** {@code :353-360} - first entry; paint the screen and gather the selection criteria. */
        WHEN_CDEMO_PGM_ENTER(2, CicsAid.DFHENTER, NavigationContext.PGM_CONTEXT_ENTER),

        /** {@code :361-374} - something was keyed; edit it, then read only if the edits passed. */
        WHEN_CDEMO_PGM_REENTER(3, CicsAid.DFHENTER, NavigationContext.PGM_CONTEXT_REENTER),

        /** {@code :375-382} - the default arm; record abend data and send plain text. */
        WHEN_OTHER(4, CicsAid.DFHENTER, UNMATCHED_PGM_CONTEXT);

        private final int sourceOrder;
        private final byte eibAid;
        private final int pgmContext;

        DispatchArm(int sourceOrder, byte eibAid, int pgmContext) {
            this.sourceOrder = sourceOrder;
            this.eibAid = eibAid;
            this.pgmContext = pgmContext;
        }

        /** @return the arm's one-based position in {@code :323-383}, 1 through 4 */
        int sourceOrder() {
            return sourceOrder;
        }

        /** @return the {@code EIBAID} byte that selects this arm */
        byte eibAid() {
            return eibAid;
        }

        /** @return the {@code CDEMO-PGM-CONTEXT} value that selects this arm */
        int pgmContext() {
            return pgmContext;
        }

        /** @return a carried commarea in the state this arm needs */
        NavigationContext context() {
            return NavigationContext.empty().withPgmContext(pgmContext);
        }
    }

    @Nested
    @DisplayName("The dispatch EVALUATE TRUE at :323-383 - four arms, source order, WHEN OTHER last")
    class DispatchArmOrder {

        @ParameterizedTest(name = "arm {0}")
        @EnumSource(DispatchArm.class)
        @DisplayName("Every WHEN is reached and each does the work its own arm declares (G30)")
        void everyArmIsReachedAndDoesItsOwnWork(DispatchArm arm) {
            stubAllFound();
            AccountViewResponse painted = controller.handle(
                    request(ACCT, arm.context()), PASSED_COMMAREA, arm.eibAid());
            switch (arm) {
                case WHEN_CCARD_AID_PFK03 -> {
                    // :330 and :336 - neither CDEMO-FROM-TRANID nor CDEMO-FROM-PROGRAM was carried, so
                    // both fall back to the menu literals of :168-171.
                    assertThat(painted.getNextProgram()).isEqualTo("COMEN01C");
                    assertThat(painted.getNavigationContext().toTranid()).isEqualTo("CM00");
                    // :346-347 - the two X(7) items of app/cpy/COCOM01Y.cpy:43-44.
                    assertThat(painted.getNavigationContext().lastMapset()).isEqualTo("COACTVW");
                    assertThat(painted.getNavigationContext().lastMap()).isEqualTo("CACTVWA");
                    // :345 - SET CDEMO-PGM-ENTER TO TRUE.
                    assertThat(painted.getNavigationContext().isEnter()).isTrue();
                    // The ordering proof: this arm's context is REENTER, so arm 3 at :361 matches too.
                    // EVALUATE takes the first match, so nothing was read.
                    verify(xrefs, never()).readByAccountIdViaAltIndex(anyString());
                    verify(accounts, never()).readByKey(anyLong());
                    verify(customers, never()).readByKey(anyString());
                }
                case WHEN_CDEMO_PGM_ENTER -> {
                    // :358-359 - PERFORM 1000-SEND-MAP only; :528-530 refills the prompt.
                    assertThat(painted.getInfomsg()).isEqualTo(controller.codec()
                            .movePicX("Enter or update id of account to display", 45));
                    assertThat(painted.getErrmsg()).isEqualTo(" ".repeat(78));
                    verify(xrefs, never()).readByAccountIdViaAltIndex(anyString());
                    verify(accounts, never()).readByKey(anyLong());
                    verify(customers, never()).readByKey(anyString());
                }
                case WHEN_CDEMO_PGM_REENTER -> {
                    // :369-370 - PERFORM 9000-READ-ACCT, all three reads, then paint.
                    verify(xrefs).readByAccountIdViaAltIndex(ACCT);
                    verify(accounts).readByKey(11L);
                    verify(customers).readByKey("123456789");
                    assertThat(painted.getAcsttus()).isEqualTo("Y");
                    assertThat(painted.getErrmsg()).isEqualTo(" ".repeat(78));
                }
                case WHEN_OTHER -> {
                    // :379-380 then :381-382 - the literal, sent as plain text.
                    assertThat(painted.getErrmsg())
                            .isEqualTo(pad(pad("UNEXPECTED DATA SCENARIO", 75), 78));
                    verify(xrefs, never()).readByAccountIdViaAltIndex(anyString());
                    verify(accounts, never()).readByKey(anyLong());
                    verify(customers, never()).readByKey(anyString());
                }
                default -> throw new AssertionError("app/cbl/COACTVWC.cbl:323-383 declares exactly "
                        + DispatchArm.values().length + " WHENs and every one is handled above; " + arm
                        + " is not one of them");
            }
        }

        @ParameterizedTest(name = "arm {0} is number {1} in the source")
        @CsvSource({
            "WHEN_CCARD_AID_PFK03, 1",
            "WHEN_CDEMO_PGM_ENTER, 2",
            "WHEN_CDEMO_PGM_REENTER, 3",
            "WHEN_OTHER, 4",
        })
        @DisplayName("The four arms are enumerated in source order, with WHEN OTHER last (G30)")
        void theArmsAreEnumeratedInSourceOrder(DispatchArm arm, int expectedPosition) {
            assertThat(arm.sourceOrder()).isEqualTo(expectedPosition);
            assertThat(arm.ordinal() + 1)
                    .as("the enum's own order is the source's order, so @EnumSource drives the arms in "
                            + "the sequence app/cbl/COACTVWC.cbl:323-383 declares them")
                    .isEqualTo(expectedPosition);
            assertThat(DispatchArm.WHEN_OTHER.ordinal())
                    .as("WHEN OTHER is last at :375, after every named condition")
                    .isEqualTo(DispatchArm.values().length - 1);
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT = {0}")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("WHEN OTHER catches every PIC 9(01) value the two 88s do not name")
        void whenOtherCatchesEveryUnnamedContextValue(int pgmContext) {
            AccountViewResponse painted = controller.handle(
                    request(ACCT, NavigationContext.empty().withPgmContext(pgmContext)),
                    PASSED_COMMAREA, CicsAid.DFHENTER);
            assertThat(painted.getErrmsg())
                    .isEqualTo(pad(pad("UNEXPECTED DATA SCENARIO", 75), 78));
            verifyNoInteractions(accounts, xrefs, customers);
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT = {0}")
        @ValueSource(ints = {NavigationContext.PGM_CONTEXT_ENTER, NavigationContext.PGM_CONTEXT_REENTER})
        @DisplayName("A named context never reaches WHEN OTHER, because its arm is declared earlier")
        void aNamedContextNeverReachesWhenOther(int pgmContext) {
            stubAllFound();
            AccountViewResponse painted = controller.handle(
                    request(ACCT, NavigationContext.empty().withPgmContext(pgmContext)),
                    PASSED_COMMAREA, CicsAid.DFHENTER);
            assertThat(painted.getErrmsg())
                    .doesNotContain("UNEXPECTED DATA SCENARIO")
                    .isEqualTo(" ".repeat(78));
        }

        @ParameterizedTest(name = "PF3 with CDEMO-PGM-CONTEXT = {0}")
        @ValueSource(ints = {NavigationContext.PGM_CONTEXT_ENTER, NavigationContext.PGM_CONTEXT_REENTER,
            UNMATCHED_PGM_CONTEXT})
        @DisplayName("Arm 1 shadows every later arm, whatever the context says")
        void theFirstArmShadowsEveryLaterArm(int pgmContext) {
            stubAllFound();
            AccountViewResponse painted = controller.handle(
                    request(ACCT, NavigationContext.empty().withPgmContext(pgmContext)),
                    PASSED_COMMAREA, CicsAid.DFHPF3);
            assertThat(painted.getNextProgram())
                    .as(":324 is tested before :353, :361 and :375, so PF3 always transfers")
                    .isEqualTo("COMEN01C");
            assertThat(painted.getErrmsg()).isEqualTo(CardScreenState.lowValues(78));
            verify(xrefs, never()).readByAccountIdViaAltIndex(anyString());
        }

        @Test
        @DisplayName("The exit arm downgrades an administrator unconditionally - source, not a slip")
        void theExitArmDowngradesAnAdministratorUnconditionally() {
            // app/cbl/COACTVWC.cbl:344 - SET CDEMO-USRTYP-USER TO TRUE - is the eighth statement of the
            // WHEN CCARD-AID-PFK03 arm and carries no guard, so an incoming 'A' leaves as 'U'. The
            // condition names are app/cpy/COCOM01Y.cpy:27-28. Asserted as-is (practice B5); adding a
            // guard would change behaviour and would need Spring Security, excluded by AAP §0.5.6 (B6).
            NavigationContext administrator = NavigationContext.empty()
                    .withPgmReenter()
                    .withUserType(NavigationContext.USER_TYPE_ADMIN)
                    .withUserId("ADMIN001");
            assertThat(administrator.isAdmin()).isTrue();

            AccountViewResponse painted =
                    controller.handle(request(ACCT, administrator), PASSED_COMMAREA, CicsAid.DFHPF3);

            assertThat(painted.getNavigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(painted.getNavigationContext().isUser()).isTrue();
            assertThat(painted.getNavigationContext().isAdmin()).isFalse();
            assertThat(painted.getNavigationContext().userId())
                    .as(":344 rewrites the TYPE only; CDEMO-USER-ID is untouched by the arm")
                    .isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("WHEN OTHER is the only arm that can reach the trailing IF INPUT-ERROR at :387")
        void onlyWhenOtherCanReachTheTrailingGuard() {
            // Arms 2 and 3 both GO TO COMMON-RETURN (:360, :367, :373) and arm 1 XCTLs away (:349), so
            // the guard at :387-392 is reachable only by falling out of WHEN OTHER. Driven here through
            // the arm itself rather than by calling the guard directly, so the reachability - not just
            // the guard's two sides - is what is asserted.
            AccountViewController.Conversation task = conversation();
            task.carddemoCommarea = task.carddemoCommarea.withPgmContext(UNMATCHED_PGM_CONTEXT);
            AccountViewRequest received = request(ACCT, null);

            controller.dispatch0000(received, task);

            assertThat(task.returned)
                    .as(":381-382 PERFORM SEND-PLAIN-TEXT, which issues EXEC CICS RETURN")
                    .isTrue();
            assertThat(task.wsReturnMsg).isEqualTo(AccountViewController.UNEXPECTED_DATA_SCENARIO);
            assertThat(task.inputError())
                    .as("INITIALIZE left WS-INPUT-FLAG unset and WHEN OTHER never sets INPUT-ERROR, so "
                            + "the guard is evaluated and falls through")
                    .isFalse();

            controller.trailingInputErrorGuard(received, task);

            assertThat(task.cactvwao.build().getErrmsg())
                    .as("the plain text already painted ERRMSGO; the guard added nothing")
                    .isEqualTo(pad(pad("UNEXPECTED DATA SCENARIO", 75), 78));
        }
    }

    @Nested
    @DisplayName("The three EVALUATE WS-RESP-CD reads at :737, :786 and :836 - every arm (G47)")
    class ReadOutcomeMatrix {

        /**
         * The nine outcomes: three reads &times; the three arms each declares.
         *
         * <p>Each read has exactly {@code WHEN DFHRESP(NORMAL)}, {@code WHEN DFHRESP(NOTFND)} and
         * {@code WHEN OTHER} - there is <strong>no</strong> {@code ENDFILE} arm anywhere - so nine rows
         * is the complete matrix. The expected text of each row is the literal its own arm composes,
         * read character by character from the source:
         *
         * <ul>
         *   <li>{@code 9200} {@code NOTFND} - {@code :748-754}, and note {@code ' Cross ref file.
         *       Resp:'} carries <strong>two</strong> spaces after the full stop.</li>
         *   <li>{@code 9300} {@code NOTFND} - {@code :797-803}, and {@code ' Acct Master file.Resp:'}
         *       carries <strong>none</strong>.</li>
         *   <li>{@code 9400} {@code NOTFND} - {@code :847-853}, {@code ' in customer master.Resp: '}
         *       with a <strong>trailing</strong> space, and {@code ' REAS:'} in upper case where the
         *       other two read {@code ' Reas:'}.</li>
         *   <li>every {@code WHEN OTHER} - {@code WS-FILE-ERROR-MESSAGE} of {@code :86-105}, whose
         *       {@code ERROR-FILE} is {@code PIC X(9)} and so pads each eight-character file literal
         *       with one further space.</li>
         * </ul>
         *
         * <p>{@code DELIMITED BY SIZE} means every operand contributes its full declared width, which is
         * why the account identifier appears as all eleven characters of
         * {@code WS-CARD-RID-ACCT-ID-X PIC X(11)} and the customer identifier as all nine of
         * {@code WS-CARD-RID-CUST-ID-X PIC X(09)}.
         *
         * @return one row per read and arm
         */
        static Stream<Arguments> readOutcomes() {
            String acctInFull = ACCT;
            String custInFull = "123456789";
            return Stream.of(
                    Arguments.of("CXACAIX", "NORMAL", ""),
                    Arguments.of("CXACAIX", "NOTFND",
                            "Account:" + acctInFull + " not found in Cross ref file.  Resp:"),
                    Arguments.of("CXACAIX", "OTHER",
                            "File Error: READ     on CXACAIX   returned RESP "),
                    Arguments.of("ACCTDAT", "NORMAL", ""),
                    Arguments.of("ACCTDAT", "NOTFND",
                            "Account:" + acctInFull + " not found in Acct Master file.Resp:"),
                    Arguments.of("ACCTDAT", "OTHER",
                            "File Error: READ     on ACCTDAT   returned RESP "),
                    Arguments.of("CUSTDAT", "NORMAL", ""),
                    Arguments.of("CUSTDAT", "NOTFND",
                            "CustId:" + custInFull + " not found in customer master.Resp: "),
                    Arguments.of("CUSTDAT", "OTHER",
                            "File Error: READ     on CUSTDAT   returned RESP "));
        }

        @ParameterizedTest(name = "{0} answers {1}")
        @MethodSource("readOutcomes")
        @DisplayName("Nine outcomes: three reads by three arms, each with its own byte-exact text")
        void everyArmOfEveryReadIsDriven(String dataset, String arm, String expectedPrefix) {
            stubUpTo(dataset, arm);

            AccountViewResponse painted =
                    controller.handle(request(ACCT, reenter()), PASSED_COMMAREA, CicsAid.DFHENTER);

            assertThat(painted.getErrmsg()).hasSize(AccountViewResponse.ERRMSG_LENGTH);
            if ("NORMAL".equals(arm)) {
                // A read that succeeded contributes no message at all: the NORMAL arms only SET flags
                // and MOVE keys (:739-740, :788, :838).
                assertThat(painted.getErrmsg())
                        .isEqualTo(" ".repeat(AccountViewResponse.ERRMSG_LENGTH));
            } else {
                assertThat(painted.getErrmsg()).startsWith(expectedPrefix);
            }

            // Which flag the arm set decides whether ACCTSID reddens: 9200 and 9300 set
            // FLG-ACCTFILTER-NOT-OK (:743, :791, :761, :811) but 9400 sets FLG-CUSTFILTER-NOT-OK
            // (:841, :860), and CSSETATY only looks at the account flag for this field.
            byte expectedColour = "NORMAL".equals(arm) || "CUSTDAT".equals(dataset)
                    ? BmsAttributes.DFHDFCOL
                    : BmsAttributes.DFHRED;
            assertThat(painted.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                    .isEqualTo(expectedColour);
        }

        @ParameterizedTest(name = "{0} answers {1}")
        @MethodSource("readOutcomes")
        @DisplayName("A cross-reference failure short-circuits; the later two never do (:697-715)")
        void onlyTheFirstGuardShortCircuits(String dataset, String arm, String unusedPrefix) {
            assertThat(unusedPrefix).isNotNull();
            stubUpTo(dataset, arm);

            controller.handle(request(ACCT, reenter()), PASSED_COMMAREA, CicsAid.DFHENTER);

            boolean xrefFailed = "CXACAIX".equals(dataset) && !"NORMAL".equals(arm);
            if (xrefFailed) {
                // :697-699 - IF FLG-ACCTFILTER-NOT-OK GO TO 9000-READ-ACCT-EXIT. The only guard that
                // fires, so neither later read is attempted.
                verify(accounts, never()).readByKey(anyLong());
                verify(customers, never()).readByKey(anyString());
            } else {
                // :704 and :713 test condition names nothing ever sets, so a failed account read still
                // falls through to the customer read.
                verify(accounts).readByKey(11L);
                verify(customers).readByKey("123456789");
            }
        }

        @Test
        @DisplayName("The reads run CXACAIX then ACCTDAT then CUSTDAT, in that order and once each")
        void theReadsRunInSourceOrder() {
            stubAllFound();

            controller.handle(request(ACCT, reenter()), PASSED_COMMAREA, CicsAid.DFHENTER);

            // 9000-READ-ACCT performs 9200 at :693, 9300 at :701 and 9400 at :710. The order is not
            // incidental: 9200 supplies CDEMO-CUST-ID (:739) which :708 moves into the key 9400 reads
            // with, so a reordering could not work at all - and InOrder is what proves it stayed put.
            InOrder reads = inOrder(xrefs, accounts, customers);
            reads.verify(xrefs).readByAccountIdViaAltIndex(ACCT);
            reads.verify(accounts).readByKey(11L);
            reads.verify(customers).readByKey("123456789");
            reads.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("An account miss still reaches the customer read, and in the same order")
        void aMissedAccountKeepsTheOrder() {
            stubUpTo("ACCTDAT", "NOTFND");

            controller.handle(request(ACCT, reenter()), PASSED_COMMAREA, CicsAid.DFHENTER);

            InOrder reads = inOrder(xrefs, accounts, customers);
            reads.verify(xrefs).readByAccountIdViaAltIndex(ACCT);
            reads.verify(accounts).readByKey(11L);
            reads.verify(customers).readByKey("123456789");
            reads.verifyNoMoreInteractions();
        }

        @ParameterizedTest(name = "{0} answers {1}")
        @MethodSource("readOutcomes")
        @DisplayName("IF WS-RETURN-MSG-OFF: the first error wins and no arm overwrites it")
        void theFirstErrorWins(String dataset, String arm, String unusedPrefix) {
            assertThat(unusedPrefix).isNotNull();
            stubUpTo(dataset, arm);
            AccountViewRequest received = request(ACCT, reenter());

            // A message already in WS-RETURN-MSG is what the guards at :744, :793 and :845 protect. It
            // is planted by pre-loading the conversation rather than by contriving two failures, so the
            // guard is asserted in isolation from whatever set the earlier message.
            AccountViewController.Conversation task = new AccountViewController.Conversation();
            controller.initializeStorage(received, task, PASSED_COMMAREA, CicsAid.DFHENTER);
            task.carddemoCommarea = reenter().withAcctId(11L).withCustId(123456789);
            task.wsReturnMsg = AccountViewController.CODING_TO_BE_DONE;

            controller.readAcct9000(task);

            if ("OTHER".equals(arm)) {
                // The WHEN OTHER arms carry no IF WS-RETURN-MSG-OFF at all (:759-766, :809-816,
                // :858-865), so they DO overwrite - which is itself the behaviour under test.
                assertThat(task.wsReturnMsg).startsWith("File Error: READ");
            } else {
                assertThat(task.wsReturnMsg)
                        .as("the NOTFND arms compose a message only IF WS-RETURN-MSG-OFF, and NORMAL "
                                + "composes none at all")
                        .isEqualTo(pad("Looks Good.... so far", 75));
            }
        }

        /**
         * Stubs the three repositories so that {@code dataset} answers {@code arm} and every read
         * before it succeeds.
         *
         * @param dataset {@code CXACAIX}, {@code ACCTDAT} or {@code CUSTDAT}
         * @param arm     {@code NORMAL}, {@code NOTFND} or {@code OTHER}
         */
        private void stubUpTo(String dataset, String arm) {
            boolean xrefFails = "CXACAIX".equals(dataset) && !"NORMAL".equals(arm);
            when(xrefs.readByAccountIdViaAltIndex(anyString())).thenReturn(
                    xrefFails
                            ? xrefOutcome(arm)
                            : CardXrefRepository.ReadResult.found(
                                    CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                                    new CardXrefRecord(XREF_CARD, 123456789, 11L), XREF_IMAGE));
            if (xrefFails) {
                return;
            }
            boolean accountFails = "ACCTDAT".equals(dataset) && !"NORMAL".equals(arm);
            when(accounts.readByKey(anyLong())).thenReturn(accountFails
                    ? accountOutcome(arm)
                    : AccountRepository.ReadResult.found(account()));
            boolean customerFails = "CUSTDAT".equals(dataset) && !"NORMAL".equals(arm);
            when(customers.readByKey(anyString())).thenReturn(customerFails
                    ? customerOutcome(arm)
                    : CustomerRepository.ReadResult.found(customer(), CUST_IMAGE));
        }

        private CardXrefRepository.ReadResult xrefOutcome(String arm) {
            return "NOTFND".equals(arm)
                    ? CardXrefRepository.ReadResult.notFound(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME)
                    : CardXrefRepository.ReadResult.other(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                            CardXrefRepository.PERMANENT_ERROR_STATUS);
        }

        private AccountRepository.ReadResult accountOutcome(String arm) {
            return "NOTFND".equals(arm)
                    ? AccountRepository.ReadResult.notFound()
                    : AccountRepository.ReadResult.of(AccountRepository.PERMANENT_ERROR_STATUS);
        }

        private CustomerRepository.ReadResult customerOutcome(String arm) {
            return "NOTFND".equals(arm)
                    ? CustomerRepository.ReadResult.notFound()
                    : CustomerRepository.ReadResult.of(CustomerRepository.PERMANENT_ERROR_STATUS);
        }
    }

    @Nested
    @DisplayName("No server-side state, structurally and behaviourally (G37, G53)")
    class NoServerSideState {

        /**
         * Types that would give a request somewhere to leave state behind. A CICS
         * pseudo-conversation keeps nothing on the server between turns - rule <strong>R6</strong> - so
         * none of these may appear in this controller's signature.
         *
         * <p>Matched on the type's simple name rather than by importing each one, deliberately: the
         * assertion then holds whichever package supplies the type, and cannot be defeated by a
         * different servlet or Spring artifact bringing in a same-named class.
         */
        private static final Set<String> FORBIDDEN_PARAMETER_TYPES = Set.of(
                "HttpSession", "HttpServletRequest", "HttpServletResponse", "SessionStatus",
                "WebRequest", "NativeWebRequest", "Model", "ModelMap", "RedirectAttributes");

        /** Annotations that would attach state to the bean or to a session. */
        private static final Set<String> FORBIDDEN_ANNOTATIONS = Set.of(
                "SessionAttributes", "SessionAttribute", "Scope", "SessionScope", "RequestScope");

        @Test
        @DisplayName("Every declared field is final, so the bean cannot hold state (G53)")
        void everyFieldIsFinal() {
            List<String> mutable = new ArrayList<>();
            for (Field field : AccountViewController.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                if (!Modifier.isFinal(field.getModifiers())) {
                    mutable.add((Modifier.isStatic(field.getModifiers()) ? "static " : "instance ")
                            + field.getType().getSimpleName() + " " + field.getName());
                }
            }
            assertThat(mutable)
                    .as("COBOL WORKING-STORAGE must not have become a Java field: every field of "
                            + "app/cbl/COACTVWC.cbl's WS-MISC-STORAGE lives in a per-call Conversation "
                            + "instead (practice B9, gate G53)")
                    .isEmpty();
        }

        @Test
        @DisplayName("The only instance fields are the three repositories and the clock")
        void theOnlyInstanceFieldsAreCollaborators() {
            Set<Class<?>> collaborators = Set.of(AccountRepository.class, CardXrefRepository.class,
                    CustomerRepository.class, Clock.class);
            List<Field> instanceFields = new ArrayList<>();
            for (Field field : AccountViewController.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    instanceFields.add(field);
                }
            }
            assertThat(instanceFields).hasSize(collaborators.size());
            for (Field field : instanceFields) {
                assertThat(collaborators)
                        .as("field " + field.getName() + " is not an injected collaborator")
                        .contains(field.getType());
            }
        }

        @Test
        @DisplayName("No field is a ThreadLocal and no static field holds per-request state")
        void nothingIsThreadLocalAndNoStaticHoldsRequestState() {
            Set<Class<?>> perRequestTypes = Set.of(AccountViewController.Conversation.class,
                    AccountViewRequest.class, AccountViewResponse.class, NavigationContext.class,
                    CardScreenState.class, AccountRecord.class, CustomerRecord.class,
                    CardXrefRecord.class);
            for (Field field : AccountViewController.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(ThreadLocal.class.isAssignableFrom(field.getType()))
                        .as("field " + field.getName() + " is a ThreadLocal, which is server-side state "
                                + "by another name")
                        .isFalse();
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(perRequestTypes)
                            .as("static field " + field.getName() + " holds a per-request type")
                            .doesNotContain(field.getType());
                }
            }
        }

        @Test
        @DisplayName("No session annotation and no session-scoped bean declaration")
        void noSessionAnnotationAnywhere() {
            List<String> found = new ArrayList<>();
            for (Annotation annotation : AccountViewController.class.getAnnotations()) {
                String name = annotation.annotationType().getSimpleName();
                if (FORBIDDEN_ANNOTATIONS.contains(name)) {
                    found.add("class @" + name);
                }
            }
            for (Method method : AccountViewController.class.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    String name = annotation.annotationType().getSimpleName();
                    if (FORBIDDEN_ANNOTATIONS.contains(name)) {
                        found.add(method.getName() + " @" + name);
                    }
                }
            }
            assertThat(found)
                    .as("the whole conversation travels in the payload - CARDDEMO-COMMAREA as "
                            + "NavigationContext, CC-WORK-AREA as CardScreenState and EIBAID as a query "
                            + "parameter - so nothing may be pinned to a session (rule R6, gate G37)")
                    .isEmpty();
        }

        @Test
        @DisplayName("No method takes a session, a servlet request or a view model")
        void noMethodTakesASessionOrAServletRequest() {
            List<String> found = new ArrayList<>();
            for (Method method : AccountViewController.class.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    String name = parameter.getType().getSimpleName();
                    if (FORBIDDEN_PARAMETER_TYPES.contains(name)) {
                        found.add(method.getName() + "(" + name + ")");
                    }
                }
            }
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("Interleaved requests with different contexts cannot see each other")
        void interleavedRequestsAreIsolated() {
            // Three turns through the ONE controller instance, A then B then A again. The PF3 arm is
            // used because it is the most leak-sensitive path in the program: :328-339 derive
            // CDEMO-TO-TRANID and CDEMO-TO-PROGRAM from what the caller carried, so any state surviving
            // between turns would show up as B's caller in A's answer.
            NavigationContext fromCardList = NavigationContext.empty()
                    .withPgmReenter()
                    .withFromProgram("COCRDLIC")
                    .withFromTranid("CCLI")
                    .withUserId("USERAAAA");
            NavigationContext coldCaller = NavigationContext.empty()
                    .withPgmReenter()
                    .withUserId("USERBBBB");

            AccountViewResponse firstA = controller.handle(
                    request(ACCT, fromCardList), PASSED_COMMAREA, CicsAid.DFHPF3);
            AccountViewResponse onlyB = controller.handle(
                    request(ACCT, coldCaller), PASSED_COMMAREA, CicsAid.DFHPF3);
            AccountViewResponse secondA = controller.handle(
                    request(ACCT, fromCardList), PASSED_COMMAREA, CicsAid.DFHPF3);

            assertThat(firstA.getNextProgram()).isEqualTo("COCRDLIC");
            assertThat(firstA.getNavigationContext().toTranid()).isEqualTo("CCLI");
            assertThat(firstA.getNavigationContext().userId()).isEqualTo("USERAAAA");
            assertThat(onlyB.getNextProgram())
                    .as("B named no caller, so :336 falls back to LIT-MENUPGM")
                    .isEqualTo("COMEN01C");
            assertThat(onlyB.getNavigationContext().toTranid()).isEqualTo("CM00");
            assertThat(onlyB.getNavigationContext().userId()).isEqualTo("USERBBBB");
            assertThat(secondA.getNavigationContext())
                    .as("A's second turn is identical to its first: B's turn in between left nothing")
                    .isEqualTo(firstA.getNavigationContext());
            assertThat(secondA.fieldImages()).isEqualTo(firstA.fieldImages());
        }

        @Test
        @DisplayName("Interleaved reads answer each turn's own key, never the previous turn's")
        void interleavedReadsAnswerTheirOwnKey() {
            String otherAccount = "00000000022";
            stubAllFound();

            AccountViewResponse firstEleven = controller.handle(
                    request(ACCT, reenter().withUserId("USERAAAA")), PASSED_COMMAREA,
                    CicsAid.DFHENTER);
            AccountViewResponse twentyTwo = controller.handle(
                    request(otherAccount, reenter().withUserId("USERBBBB")), PASSED_COMMAREA,
                    CicsAid.DFHENTER);
            AccountViewResponse secondEleven = controller.handle(
                    request(ACCT, reenter().withUserId("USERAAAA")), PASSED_COMMAREA,
                    CicsAid.DFHENTER);

            assertThat(firstEleven.getAcctsid()).isEqualTo(ACCT);
            assertThat(twentyTwo.getAcctsid()).isEqualTo(otherAccount);
            assertThat(secondEleven.getAcctsid()).isEqualTo(ACCT);
            assertThat(secondEleven.fieldImages()).isEqualTo(firstEleven.fieldImages());
            verify(xrefs).readByAccountIdViaAltIndex(otherAccount);
            verify(xrefs, times(2)).readByAccountIdViaAltIndex(ACCT);
        }
    }

    @Nested
    @DisplayName("The screen contract - 37 DFHMDF fields, and nothing else on the wire (G9)")
    class ScreenFieldContract {

        /**
         * Labels that exist on {@code COACTUP} and are absent from {@code COACTVW}.
         *
         * <p>{@code grep FKEY app/bms/COACTVW.bms} returns nothing: this screen has no function-key
         * legend line at all, so a request or response field for one would trace to no {@code DFHMDF}
         * definition and would break the gate.
         */
        private static final String[] FIELDS_THIS_SCREEN_DOES_NOT_HAVE = {"FKEYS", "FKEY05", "FKEY12"};

        /**
         * Every field's width, transcribed one row at a time from the {@code xxxI} items of
         * {@code 01 CACTVWAI} in {@code app/cpy-bms/COACTVW.CPY} (the group opens at line 17, the items
         * run to line 240) and cross-checked against the {@code LENGTH=} operand of the same field's
         * {@code DFHMDF} in {@code app/bms/COACTVW.bms}. Written out rather than derived, so the table a
         * reviewer reads is independent of the code it checks.
         *
         * @return {@code label,width} for all 37 name-labelled fields, in {@code DFHMDF} order
         */
        static Stream<Arguments> declaredFieldWidths() {
            return Stream.of(
                    Arguments.of("TRNNAME", 4), Arguments.of("TITLE01", 40),
                    Arguments.of("CURDATE", 8), Arguments.of("PGMNAME", 8),
                    Arguments.of("TITLE02", 40), Arguments.of("CURTIME", 8),
                    Arguments.of("ACCTSID", 11), Arguments.of("ACSTTUS", 1),
                    Arguments.of("ADTOPEN", 10), Arguments.of("ACRDLIM", 15),
                    Arguments.of("AEXPDT", 10), Arguments.of("ACSHLIM", 15),
                    Arguments.of("AREISDT", 10), Arguments.of("ACURBAL", 15),
                    Arguments.of("ACRCYCR", 15), Arguments.of("AADDGRP", 10),
                    Arguments.of("ACRCYDB", 15), Arguments.of("ACSTNUM", 9),
                    Arguments.of("ACSTSSN", 12), Arguments.of("ACSTDOB", 10),
                    Arguments.of("ACSTFCO", 3), Arguments.of("ACSFNAM", 25),
                    Arguments.of("ACSMNAM", 25), Arguments.of("ACSLNAM", 25),
                    Arguments.of("ACSADL1", 50), Arguments.of("ACSSTTE", 2),
                    Arguments.of("ACSADL2", 50), Arguments.of("ACSZIPC", 5),
                    Arguments.of("ACSCITY", 50), Arguments.of("ACSCTRY", 3),
                    Arguments.of("ACSPHN1", 13), Arguments.of("ACSGOVT", 20),
                    Arguments.of("ACSPHN2", 13), Arguments.of("ACSEFTC", 10),
                    Arguments.of("ACSPFLG", 1), Arguments.of("INFOMSG", 45),
                    Arguments.of("ERRMSG", 78));
        }

        @ParameterizedTest(name = "{0} is PIC X({1})")
        @MethodSource("declaredFieldWidths")
        @DisplayName("Each field carries its copybook width on both the input and the output side")
        void everyFieldCarriesItsDeclaredWidth(String label, int width) {
            AccountViewRequest.ScreenField input = AccountViewRequest.ScreenField.byLabel(label);
            AccountViewResponse.ScreenField output = AccountViewResponse.ScreenField.byLabel(label);

            assertThat(input.length()).as(input.describe()).isEqualTo(width);
            assertThat(output.length())
                    .as("CACTVWAO REDEFINES CACTVWAI at app/cpy-bms/COACTVW.CPY:241, so the two sides "
                            + "are the same bytes and must agree: " + output.describe())
                    .isEqualTo(width);
            // The xxxI item name is the label with an I suffix, and the payload field's name derives
            // from the label rather than from anything invented here.
            assertThat(input.symbolicItemName()).isEqualTo(label + "I");
            // Fields sit on the 24x80 screen the single DFHMDI declares.
            assertThat(input.screenRow()).isBetween(1, 24);
            assertThat(input.screenColumn()).isBetween(1, 80);
        }

        @Test
        @DisplayName("There are exactly 37 fields, and they total the copybook's 955-byte group")
        void thereAreExactlyThirtySevenFieldsTotallingTheGroup() {
            assertThat(declaredFieldWidths().count()).isEqualTo(AccountViewRequest.FIELD_COUNT);
            assertThat(AccountViewRequest.FIELD_COUNT)
                    .as("app/bms/COACTVW.bms declares 100 DFHMDF entries of which 37 carry a name; the "
                            + "other 63 are unnamed INITIAL literals and are not fields")
                    .isEqualTo(37)
                    .isEqualTo(AccountViewResponse.FIELD_COUNT)
                    .isEqualTo(AccountViewRequest.ScreenField.values().length)
                    .isEqualTo(AccountViewResponse.ScreenField.values().length);
            int declared = declaredFieldWidths()
                    .mapToInt(row -> (Integer) row.get()[1])
                    .sum();
            assertThat(declared)
                    .as("the sum of the 37 widths is the copybook's payload")
                    .isEqualTo(AccountViewRequest.PAYLOAD_LENGTH)
                    .isEqualTo(684);
            assertThat(AccountViewRequest.GROUP_LENGTH)
                    .as("12 bytes of TIOAPFX FILLER (COACTVW.CPY:18) + 37 x 7 bytes of per-field "
                            + "overhead + 684 bytes of data")
                    .isEqualTo(AccountViewRequest.TIOAPFX_LENGTH
                            + AccountViewRequest.FIELD_COUNT * AccountViewRequest.FIELD_OVERHEAD
                            + declared)
                    .isEqualTo(955);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"FKEYS", "FKEY05", "FKEY12"})
        @DisplayName("COACTVW has no function-key legend, so those labels resolve to nothing")
        void thereIsNoFunctionKeyLegendOnThisScreen(String absentLabel) {
            assertThat(FIELDS_THIS_SCREEN_DOES_NOT_HAVE).contains(absentLabel);
            assertThatThrownBy(() -> AccountViewRequest.ScreenField.byLabel(absentLabel))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AccountViewResponse.ScreenField.byLabel(absentLabel))
                    .isInstanceOf(IllegalArgumentException.class);
            for (AccountViewRequest.ScreenField field : AccountViewRequest.ScreenField.values()) {
                assertThat(field.label()).isNotEqualTo(absentLabel);
            }
        }

        @Test
        @DisplayName("The 955-byte group image round-trips through the request DTO unchanged")
        void theGroupImageRoundTrips() {
            FixedWidthCodec codec = controller.codec();
            AccountViewRequest sent = request(ACCT, reenter());
            sent.metadata(AccountViewRequest.ScreenField.ACCTSID)
                    .setLength(AccountViewRequest.ACCTSID_LENGTH);
            sent.metadata(AccountViewRequest.ScreenField.ACCTSID)
                    .setAttribute(BmsAttributes.DFHBMFSE);

            byte[] image = sent.toGroupImage(codec);

            assertThat(image).hasSize(AccountViewRequest.GROUP_LENGTH);
            AccountViewRequest restored = AccountViewRequest.fromGroupImage(image, codec);
            assertThat(restored.getAcctsid()).isEqualTo(ACCT);
            assertThat(restored.metadata(AccountViewRequest.ScreenField.ACCTSID).getLength())
                    .isEqualTo(AccountViewRequest.ACCTSID_LENGTH);
            assertThat(restored.metadata(AccountViewRequest.ScreenField.ACCTSID).getAttribute())
                    .isEqualTo(BmsAttributes.DFHBMFSE);
            assertThat(restored.toGroupImage(codec)).isEqualTo(image);
            // The 12 TIOAPFX bytes of COACTVW.CPY:18 carry no application data and are written as
            // spaces, and the field data begins immediately after them.
            assertThat(AccountViewRequest.ScreenField.TRNNAME.lengthItemOffset())
                    .isEqualTo(AccountViewRequest.TIOAPFX_LENGTH);
            assertThat(AccountViewRequest.ScreenField.ERRMSG.endOffsetExclusive())
                    .isEqualTo(AccountViewRequest.GROUP_LENGTH);
        }

        @Test
        @DisplayName("ACCTSIDL carries -1 as a cursor request, which is not a length (:549, :551)")
        void theLengthItemCarriesTheCursorSignal() {
            // app/cpy-bms/COACTVW.CPY:55 declares ACCTSIDL COMP PIC S9(4) - signed, which is what makes
            // -1 representable at all. Both arms of the EVALUATE at :546-552 move the same -1, so
            // whichever way it goes the cursor lands on ACCTSID, the only UNPROT field on the screen.
            AccountViewRequest.ScreenFieldMetadata item =
                    new AccountViewRequest.ScreenFieldMetadata();
            assertThat(item.getLength())
                    .isEqualTo(AccountViewRequest.ScreenFieldMetadata.LENGTH_UNSET);
            assertThat(item.isLengthUnset()).isTrue();
            assertThat(item.isCursorHere()).isFalse();

            item.positionCursorHere();

            assertThat(item.getLength())
                    .isEqualTo(AccountViewRequest.ScreenFieldMetadata.CURSOR_HERE)
                    .isEqualTo(-1);
            assertThat(item.isCursorHere())
                    .as("-1 is a cursor request, not a report that minus one character was typed")
                    .isTrue();
            assertThat(item.isLengthUnset())
                    .as("and it is emphatically not 'the field was left blank', which is zero")
                    .isFalse();
            assertThat(AccountViewRequest.ScreenFieldMetadata.LENGTH_ITEM_MIN)
                    .as("S9(4) holds four digits and a sign, so -1 is comfortably inside the PICTURE")
                    .isLessThan(AccountViewRequest.ScreenFieldMetadata.CURSOR_HERE);

            // And the flow itself asks for the cursor there, on both an accepted and a rejected filter.
            AccountViewController.Conversation accepted = conversation();
            AccountViewRequest received = request(ACCT, reenter());
            controller.setupScreenAttrs1300(received, accepted);
            assertThat(received.metadata(AccountViewRequest.ScreenField.ACCTSID).isCursorHere())
                    .isTrue();
            assertThat(received.metadata(AccountViewRequest.ScreenField.ACCTSID).getAttribute())
                    .as(":543 MOVE DFHBMFSE TO ACCTSIDA OF CACTVWAI")
                    .isEqualTo(BmsAttributes.DFHBMFSE);
        }

        @ParameterizedTest(name = "ACCTSIDI = [{0}]")
        @ValueSource(strings = {"*          ", "           ", "00000000011"})
        @DisplayName("ACCTSID is a String on both sides, so it carries '*' and spaces as typed")
        void theAccountFilterIsCharacterDataOnBothSides(String typed) {
            // app/cpy-bms/COACTVW.CPY:60 declares ACCTSIDI PIC 99999999999 and the DFHMDF adds
            // PICIN='99999999999' VALIDN=(MUSTFILL), but :628-629 compares the received value to '*'
            // and to SPACES, and :466 and :563 move LOW-VALUES and a bare '*' into ACCTSIDO at
            // COACTVW.CPY:284's PIC X(11). Neither value is a number, so both sides are text.
            AccountViewRequest sent = new AccountViewRequest();
            sent.initializeMapArea();
            sent.setAcctsid(typed);
            assertThat(sent.getAcctsid()).isEqualTo(typed).hasSize(11);

            AccountViewResponse painted = AccountViewResponse.builder()
                    .acctsid(typed)
                    .build();
            assertThat(painted.getAcctsid()).isEqualTo(typed);
        }

        @Test
        @DisplayName("ACCTSID also carries LOW-VALUES, which is what :466 moves into it")
        void theAccountFilterCarriesLowValues() {
            String lowValues = CardScreenState.lowValues(AccountViewResponse.ACCTSID_LENGTH);
            AccountViewResponse painted = AccountViewResponse.builder().acctsid(lowValues).build();
            assertThat(painted.getAcctsid()).isEqualTo(lowValues).hasSize(11);
            assertThat(painted.getAcctsid().charAt(0)).isEqualTo('\u0000');
            // A cold start reaches :462-463 rather than :466 and leaves the field at the LOW-VALUES
            // MOVE LOW-VALUES TO CACTVWAO of :432 put there.
            AccountViewResponse coldStart =
                    controller.handle(request(ACCT, null), 0, CicsAid.DFHENTER);
            assertThat(coldStart.getAcctsid()).isEqualTo(lowValues);
        }
    }

    @Nested
    @DisplayName("The five PIC +ZZZ,ZZZ,ZZZ.99 items - the mask, position by position")
    class EditedAmountMask {

        /** The five numeric-edited items, at their {@code app/cpy-bms/COACTVW.CPY} lines. */
        static Stream<Arguments> theFiveEditedItems() {
            return Stream.of(
                    Arguments.of("ACRDLIM", 302), Arguments.of("ACSHLIM", 314),
                    Arguments.of("ACURBAL", 326), Arguments.of("ACRCYCR", 332),
                    Arguments.of("ACRCYDB", 344));
        }

        @ParameterizedTest(name = "{0}O at COACTVW.CPY:{1}")
        @MethodSource("theFiveEditedItems")
        @DisplayName("Exactly five items are numeric-edited, and each is 15 characters wide")
        void fiveItemsAreNumericEdited(String label, int copybookLine) {
            AccountViewResponse.ScreenField field = AccountViewResponse.ScreenField.byLabel(label);
            assertThat(field.length())
                    .as("+ZZZ,ZZZ,ZZZ.99 is 1 sign + 9 Z + 2 commas + 1 point + 2 forced digits")
                    .isEqualTo(AccountViewResponse.AMOUNT_MASK_WIDTH)
                    .isEqualTo(15);
            assertThat(field.copybookLine()).isEqualTo(copybookLine);
            assertThat(AccountViewResponse.AMOUNT_PICTURE).isEqualTo("+ZZZ,ZZZ,ZZZ.99");
            assertThat(theFiveEditedItems().count())
                    .isEqualTo(AccountViewResponse.AMOUNT_MASK_FIELD_COUNT);
        }

        @ParameterizedTest(name = "{0} renders [{1}]")
        @CsvSource(value = {
            // Zero: the sign is still emitted and .99 forces both decimals, so the item is NOT blank.
            "0.00           | +           .00",
            "0.01           | +           .01",
            "-0.01          | -           .01",
            // Z suppression runs to the decimal point, and the comma left of a wholly suppressed group
            // is suppressed with it.
            "123.45         | +        123.45",
            "1234.56        | +      1,234.56",
            "1234567.89     | +  1,234,567.89",
            "999999999.99   | +999,999,999.99",
            "-999999999.99  | -999,999,999.99",
            "-250.00        | -        250.00",
            // Ten integer digits into nine positions: the leftmost digit is discarded, and nothing
            // overflows into the fixed sign position.
            "1234567890.12  | +234,567,890.12",
            "-1234567890.12 | -234,567,890.12",
            // Once the tenth digit is gone the remainder is all zeros, so suppression blanks the whole
            // integer part and this renders identically to 0.00 - except for the sign.
            "1000000000.00  | +           .00",
            "-1000000000.00 | -           .00",
            // A third decimal digit is truncated, never rounded: ROUNDED appears zero times in all 28
            // programs, so RoundingMode.DOWN is the only faithful choice (rule R2, gate G24).
            "1.239          | +          1.23",
            "1.231          | +          1.23",
            "-1.239         | -          1.23",
        }, delimiter = '|', ignoreLeadingAndTrailingWhitespace = true)
        @DisplayName("Every rule of the mask, asserted character for character")
        void theMaskAppliesEveryRule(String amount, String expected) {
            String edited = AccountViewResponse.editAmount(new BigDecimal(amount));
            assertThat(edited).isEqualTo(expected).hasSize(15);
            assertThat(edited.charAt(0))
                    .as("position 0 is a fixed insertion character and is never suppressed")
                    .isIn('+', '-');
            assertThat(edited.charAt(12)).as("position 12 is the literal decimal point").isEqualTo('.');
            assertThat(edited.substring(13))
                    .as("positions 13 and 14 are 9s, not Zs, so they always carry digits")
                    .matches("\\d\\d");
        }

        @Test
        @DisplayName("The separators are literal, so a non-US default Locale changes nothing")
        void theSeparatorsAreLiteralAndLocaleIndependent() {
            // Germany writes 1.234,56 for what the US writes 1,234.56. A DecimalFormat or a
            // String.format("%,.2f") would follow the Locale and produce the wrong bytes; the
            // hand-written formatter (practice B11) appends ',' and '.' literally, so it does not.
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY);
                assertThat(AccountViewResponse.editAmount(new BigDecimal("1234.56")))
                        .isEqualTo("+      1,234.56");
                assertThat(AccountViewResponse.editAmount(new BigDecimal("999999999.99")))
                        .isEqualTo("+999,999,999.99");
                assertThat(AccountViewResponse.editAmount(BigDecimal.ZERO))
                        .isEqualTo("+           .00");
                stubAllFound();
                assertThat(controller
                        .handle(request(ACCT, reenter()), PASSED_COMMAREA, CicsAid.DFHENTER)
                        .getAcurbal())
                        .as("and the whole screen renders the same under that Locale")
                        .isEqualTo("+      1,234.56");
            } finally {
                Locale.setDefault(original);
            }
            assertThat(Locale.getDefault())
                    .as("the default Locale is restored, so no later test inherits it")
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("A money item the program never wrote stays LOW-VALUES rather than rendering zero")
        void anUnwrittenMoneyItemIsNotRenderedAsZero() {
            // :471-491 project the account's amounts only when FOUND-ACCT-IN-MASTER or
            // FOUND-CUST-IN-MASTER, and :432 left LOW-VALUES behind. So a screen painted before any read
            // carries LOW-VALUES, which is an image and not a number - editAmount is never called for it.
            AccountViewResponse coldStart =
                    controller.handle(request(ACCT, null), 0, CicsAid.DFHENTER);
            assertThat(coldStart.getAcurbal())
                    .isEqualTo(CardScreenState.lowValues(AccountViewResponse.ACURBAL_LENGTH));
            assertThatThrownBy(() -> AccountViewResponse.editAmount(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("The carried conversation - COMMAREA, AID, OCCURS and REDEFINES")
    class CarriedConversationState {

        @Test
        @DisplayName("CARDDEMO-COMMAREA is 160 bytes and its last two items are X(7), not X(8)")
        void theCommareaIsOneHundredAndSixtyBytes() {
            // app/cpy/COCOM01Y.cpy: 05 CDEMO-GENERAL-INFO 34, CDEMO-CUSTOMER-INFO 84,
            // CDEMO-ACCOUNT-INFO 12, CDEMO-CARD-INFO 16, CDEMO-MORE-INFO 14 = 160.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.GENERAL_INFO_LENGTH
                            + NavigationContext.CUSTOMER_INFO_LENGTH
                            + NavigationContext.ACCOUNT_INFO_LENGTH
                            + NavigationContext.CARD_INFO_LENGTH
                            + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(160);
            // COCOM01Y.cpy:43-44 - CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are both PIC X(7). An X(8)
            // assumption would shift the tail of the area and put both items in the wrong place.
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_OFFSET)
                    .isEqualTo(NavigationContext.LAST_MAP_OFFSET + 7);
            assertThat(NavigationContext.LAST_MAPSET_OFFSET + NavigationContext.LAST_MAPSET_LENGTH)
                    .as("the mapset item is the last thing in the area")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            byte[] image = reenter().withLastMap("CACTVWA").withLastMapset("COACTVW")
                    .toFixedWidth(controller.codec());
            assertThat(image).hasSize(160);
            assertThat(NavigationContext.fromFixedWidth(controller.codec(), image).lastMapset())
                    .isEqualTo("COACTVW");
        }

        @ParameterizedTest(name = "CDEMO-USER-TYPE = {0}")
        @CsvSource({"A, true, false", "U, false, true", "' ', false, false"})
        @DisplayName("Both 88s of CDEMO-USER-TYPE answer both ways (G50)")
        void theUserTypeConditionNamesAnswerBothWays(String userType, boolean admin, boolean user) {
            // app/cpy/COCOM01Y.cpy:27-28 - 88 CDEMO-USRTYP-ADMIN VALUE 'A', 88 CDEMO-USRTYP-USER
            // VALUE 'U'. A value that is neither leaves both false, which is a state the area can hold
            // because CDEMO-USER-TYPE is PIC X(01) with no VALUE clause.
            NavigationContext context = NavigationContext.empty().withUserType(userType);
            assertThat(context.isAdmin()).isEqualTo(admin);
            assertThat(context.isUser()).isEqualTo(user);
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT = {0}")
        @CsvSource({"0, true, false", "1, false, true", "2, false, false"})
        @DisplayName("Both 88s of CDEMO-PGM-CONTEXT answer both ways (G50)")
        void theProgramContextConditionNamesAnswerBothWays(int pgmContext, boolean enter,
                boolean reenterState) {
            // app/cpy/COCOM01Y.cpy:30-31 - 88 CDEMO-PGM-ENTER VALUE 0, 88 CDEMO-PGM-REENTER VALUE 1.
            NavigationContext context = NavigationContext.empty().withPgmContext(pgmContext);
            assertThat(context.isEnter()).isEqualTo(enter);
            assertThat(context.isReenter()).isEqualTo(reenterState);
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);
        }

        @Test
        @DisplayName("The DFHCOMMAREA span of :258-259: first and last byte, 1-based against 0-based")
        void theVariableLengthSpanIsIndexedCorrectly() {
            // app/cbl/COACTVWC.cbl:257-259 declares
            //     01 DFHCOMMAREA.
            //        05 FILLER PIC X(1) OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.
            // and :288-292 read it by reference modification: DFHCOMMAREA(1:160) is CARDDEMO-COMMAREA and
            // DFHCOMMAREA(161:12) is WS-THIS-PROGCOMMAREA. COBOL counts from 1 and Java from 0, so
            // element 1 is index 0 and element 172 is index 171 - the off-by-one this asserts.
            AccountViewController.Conversation task = conversation();
            task.eibcalen = PASSED_COMMAREA;
            task.carddemoCommarea = reenter()
                    .withFromTranid("CCLI")
                    .withFromProgram("COCRDLIC")
                    .withLastMap("CACTVWA")
                    .withLastMapset("COACTVW");
            task.thisProgCommarea = new AccountViewController.ThisProgCommarea("COCRDLIC", "CCLI");

            controller.commonReturn(task);

            String area = task.wsCommarea;
            assertThat(area).hasSize(2000);
            assertThat(area.charAt(0))
                    .as("element 1 of the OCCURS span is index 0: the first byte of CDEMO-FROM-TRANID")
                    .isEqualTo('C');
            assertThat(area.substring(0, NavigationContext.COMMAREA_LENGTH))
                    .as("elements 1 through 160 of the span are CARDDEMO-COMMAREA")
                    .isEqualTo(controller.codec().decodeImage(
                            task.carddemoCommarea.toFixedWidth(controller.codec()),
                            "CARDDEMO-COMMAREA"));
            assertThat(area.substring(NavigationContext.COMMAREA_LENGTH, PASSED_COMMAREA))
                    .as("element 161 through element 172 is WS-THIS-PROGCOMMAREA")
                    .isEqualTo("COCRDLICCCLI");
            assertThat(area.charAt(PASSED_COMMAREA - 1))
                    .as("element 172 of the span is index 171: the last byte of CA-FROM-TRANID")
                    .isEqualTo('I');
            assertThat(area.charAt(PASSED_COMMAREA))
                    .as("index 172 is one past the span, and INITIALIZE left a space there")
                    .isEqualTo(' ');
            assertThat(area.substring(PASSED_COMMAREA))
                    .as("WS-COMMAREA is PIC X(2000) and the rest of it stays blank")
                    .isEqualTo(" ".repeat(2000 - PASSED_COMMAREA));

            // And the same span read back the other way: what commonReturn wrote, restoreCommarea reads.
            AccountViewController.Conversation next = conversation();
            next.eibcalen = PASSED_COMMAREA;
            next.carddemoCommarea = task.carddemoCommarea;
            next.thisProgCommarea = task.thisProgCommarea;
            controller.restoreCommarea(next);
            assertThat(next.carddemoCommarea.fromProgram()).isEqualTo("COCRDLIC");
            assertThat(next.thisProgCommarea.caFromTranid()).isEqualTo("CCLI");
        }

        @Test
        @DisplayName("WS-CARD-RID-ACCT-ID-X REDEFINES at :79 - one span, two typed views")
        void theAccountKeyRedefinitionRoundTrips() {
            // :78-80 declare WS-CARD-RID-ACCT-ID PIC 9(11) and WS-CARD-RID-ACCT-ID-X PIC X(11) over the
            // same eleven bytes. :691 writes through the numeric view and :729 reads the character view.
            AccountViewController.Conversation task = conversation();

            task.wsCardRidAcctId = ACCT;
            assertThat(task.wsCardRidAcctIdN())
                    .as("written as characters, read as a number")
                    .isEqualTo(11L);

            task.wsCardRidAcctId = controller.codec().movePic9(99999999999L, 11);
            assertThat(task.wsCardRidAcctId)
                    .as("written as a number, read as characters")
                    .isEqualTo("99999999999");
            assertThat(task.wsCardRidAcctIdN()).isEqualTo(99999999999L);
            assertThat(task.wsCardRidAcctId).hasSize(11);
        }

        @Test
        @DisplayName("WS-CARD-RID-CUST-ID-X REDEFINES at :76 - one span, two typed views")
        void theCustomerKeyRedefinitionRoundTrips() {
            // :75-77 declare WS-CARD-RID-CUST-ID PIC 9(09) and WS-CARD-RID-CUST-ID-X PIC X(09). :708
            // writes through the numeric view and :828 reads the character view.
            AccountViewController.Conversation task = conversation();

            task.wsCardRidCustId = "123456789";
            assertThat(task.wsCardRidCustIdN()).isEqualTo(123456789L);

            task.wsCardRidCustId = controller.codec().movePic9(7L, 9);
            assertThat(task.wsCardRidCustId)
                    .as("a numeric MOVE fills on the LEFT, so seven becomes 000000007")
                    .isEqualTo("000000007");
            assertThat(task.wsCardRidCustIdN()).isEqualTo(7L);
            assertThat(task.wsCardRidCustId).hasSize(9);
        }

        @ParameterizedTest(name = "EIBAID 0x{0}")
        @CsvSource({"F3, PFK03", "C3, PFK03", "F1, PFK01", "C1, PFK01", "7C, PFK12", "4C, PFK12",
            "7D, ENTER", "6D, CLEAR"})
        @DisplayName("CSSTRPFY maps 26 AIDs onto 16 tokens, folding PF13-24 onto PF1-12")
        void theResolverFoldsTheHigherFunctionKeys(String eibAidHex, String expectedToken) {
            byte eibAid = (byte) Integer.parseInt(eibAidHex, 16);
            Optional<PfKeyResolver.AidKey> resolved = PfKeyResolver.resolve(eibAid);
            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().token())
                    .as("app/cpy/CSSTRPFY.cpy declares the token as a five-character literal")
                    .isEqualTo(expectedToken)
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("The PA1 and PA2 tokens keep their two trailing spaces")
        void theProgramAccessTokensAreSpacePadded() {
            // app/cpy/CVCRD01Y.cpy declares 88 CCARD-AID-PA1 VALUE 'PA1  ' - the field is PIC X(5), so
            // the literal is padded and a trimmed value would be the wrong width on the wire.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(PfKeyResolver.AidKey.PA1);
            assertThat(PfKeyResolver.AidKey.PA1.token()).isEqualTo("PA1  ").hasSize(5);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(PfKeyResolver.AidKey.PA2);
            assertThat(PfKeyResolver.AidKey.PA2.token()).isEqualTo("PA2  ").hasSize(5);
        }

        @Test
        @DisplayName("PF3 and PF15 both reach the exit arm, because :58-59 folds PF15 onto PFK03")
        void bothPf3AndPf15TakeTheExitArm() {
            // app/cpy/CSSTRPFY.cpy:34-35 SET CCARD-AID-PFK03 for DFHPF3 and :58-59 SET the same
            // condition for DFHPF15. COACTVWC only ever asks CCARD-AID-PFK03, so the two keys are
            // indistinguishable to it - and both must leave the screen.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .contains(PfKeyResolver.AidKey.PFK03);

            AccountViewResponse viaPf3 =
                    controller.handle(request(ACCT, reenter()), PASSED_COMMAREA, CicsAid.DFHPF3);
            AccountViewResponse viaPf15 =
                    controller.handle(request(ACCT, reenter()), PASSED_COMMAREA, CicsAid.DFHPF15);

            assertThat(viaPf15.getNextProgram()).isEqualTo(viaPf3.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(viaPf15.getCardScreenState().isCcardAidPfk03()).isTrue();
            assertThat(viaPf15.fieldImages()).isEqualTo(viaPf3.fieldImages());
            assertThat(viaPf15.getNavigationContext()).isEqualTo(viaPf3.getNavigationContext());
            verifyNoInteractions(accounts, xrefs, customers);
        }

        @ParameterizedTest(name = "EIBAID 0x{0} matches no WHEN")
        @CsvSource({"6B", "7E", "E6", "40"})
        @DisplayName("The EVALUATE has no WHEN OTHER, so an unmapped AID has a no-match outcome")
        void anUnmappedAidHasAnExplicitNoMatchOutcome(String eibAidHex) {
            // app/cpy/CSSTRPFY.cpy:21-78 is 26 WHENs with no WHEN OTHER and no DFHPA3 branch, and it does
            // not clear CCARD-AID first. So an AID it has no arm for leaves the field exactly as it was -
            // represented here as an empty Optional, which cannot be mistaken for a seventeenth token.
            byte unmapped = (byte) Integer.parseInt(eibAidHex, 16);
            assertThat(PfKeyResolver.resolve(unmapped)).isEmpty();
            assertThat(PfKeyResolver.storePfKey(unmapped, Optional.of(PfKeyResolver.AidKey.PFK03)))
                    .as("no arm matched, so the carried value survives untouched")
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.storePfKey(unmapped, Optional.empty())).isEmpty();

            // COACTVWC then rewrites it: :306-314 SET PFK-INVALID, and an AID that is neither ENTER nor
            // PFK03 is turned into ENTER, so this screen answers rather than rejecting.
            AccountViewResponse painted =
                    controller.handle(request(ACCT, null), 0, unmapped);
            assertThat(painted.getCardScreenState().isCcardAidEnter()).isTrue();
            assertThat(painted.getInfomsg()).isEqualTo(controller.codec()
                    .movePicX("Enter or update id of account to display", 45));
        }
    }

    @Nested
    @DisplayName("CSSETATY highlighting - red and asterisk, and only when REENTER (G38)")
    class ErrorHighlighting {

        @ParameterizedTest(name = "notOk={0} blank={1} reenter={2}")
        @CsvSource({
            // app/cpy/CSSETATY.cpy:18-27 - IF (NOT-OK OR BLANK) AND CDEMO-PGM-REENTER, MOVE DFHRED into
            // the xxxC colour item; and nested inside that, only IF BLANK, MOVE '*' into the xxxO item.
            "true,  false, true,  true,  false",
            "false, true,  true,  true,  true",
            "true,  true,  true,  true,  true",
            "false, false, true,  false, false",
            // ENTER never highlights, whatever the field flag says: the AND at :20 fails first.
            "true,  false, false, false, false",
            "false, true,  false, false, false",
            "true,  true,  false, false, false",
            "false, false, false, false, false",
        })
        @DisplayName("Both levels of the nested IF, over both program contexts")
        void theTwoLevelRuleIsAppliedExactly(boolean notOk, boolean blank, boolean reenterState,
                boolean expectRed, boolean expectAsterisk) {
            FieldAttributeSetter.FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(
                    notOk, blank, reenterState, "ACCTSID", "CACTVWA");

            assertThat(highlight.colourItemAssigned()).isEqualTo(expectRed);
            assertThat(highlight.outputItemAssigned())
                    .as("the asterisk move is nested inside the colour move and guarded by BLANK alone")
                    .isEqualTo(expectAsterisk);
            assertThat(highlight.untouched()).isEqualTo(!expectRed);
            if (expectRed) {
                assertThat(highlight.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            } else {
                assertThatThrownBy(highlight::colourItemValue)
                        .as("nothing is moved, so there is no value to report")
                        .isInstanceOf(IllegalStateException.class);
            }
            if (expectAsterisk) {
                assertThat(highlight.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            } else {
                assertThatThrownBy(highlight::outputItemValue)
                        .isInstanceOf(IllegalStateException.class);
            }
            // The resolver agrees with the flag-pair form, which is the same decision reached the other
            // way round: FLG-xxx-NOT-OK and FLG-xxx-BLANK are two 88s over one PIC X(1).
            assertThat(FieldAttributeSetter.resolve(
                    FieldAttributeSetter.FieldValidationState.of(notOk, blank), reenterState,
                    "ACCTSID", "CACTVWA"))
                    .isEqualTo(highlight);
        }

        @Test
        @DisplayName("A blank filter in REENTER reddens ACCTSID and marks it with '*' (:561-565)")
        void aBlankFilterInReenterIsRedAndMarked() {
            AccountViewResponse painted =
                    controller.handle(request("*", reenter()), PASSED_COMMAREA, CicsAid.DFHENTER);
            assertThat(painted.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(painted.getAcctsid())
                    .as(":563 MOVE '*' TO ACCTSIDO, space padded to PIC X(11)")
                    .isEqualTo(pad(FieldAttributeSetter.ASTERISK, 11));
            verifyNoInteractions(accounts, xrefs, customers);
        }

        @Test
        @DisplayName("A blank field in ENTER is neither red nor marked, because :562 requires REENTER")
        void aBlankFieldInEnterIsNeitherRedNorMarked() {
            // The cold start is the ENTER path: :462-463 sets the prompt and no edit has run, so the
            // account filter is blank - and still not highlighted, because the AND at :562 fails.
            AccountViewResponse painted =
                    controller.handle(request("", null), 0, CicsAid.DFHENTER);
            assertThat(painted.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                    .as(":555 moved DFHDFCOL and nothing overwrote it")
                    .isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(painted.getAcctsid())
                    .as("no '*' marker: :563 is inside the REENTER guard")
                    .doesNotContain(FieldAttributeSetter.ASTERISK)
                    .isEqualTo(CardScreenState.lowValues(11));
            assertThat(painted.attributeItems().values())
                    .as("no attribute item on the whole screen carries DFHRED in ENTER state")
                    .doesNotContain(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("An ENTER-state screen carries no DFHRED even when a filter would fail an edit")
        void enterStateNeverHighlightsEvenWithAnUnusableFilter() {
            // Same unusable value as the REENTER case above - a bare asterisk - but delivered on the
            // ENTER path, where 2200-EDIT-MAP-INPUTS is never performed at all (:353-360).
            AccountViewResponse painted = controller.handle(
                    request("*", NavigationContext.empty().withPgmContext(
                            NavigationContext.PGM_CONTEXT_ENTER)),
                    PASSED_COMMAREA, CicsAid.DFHENTER);
            assertThat(painted.attributeItems().values()).doesNotContain(BmsAttributes.DFHRED);
            assertThat(painted.getErrmsg()).isEqualTo(" ".repeat(78));
        }
    }

    @Nested
    @DisplayName("ACCOUNT-RECORD of app/cpy/CVACT01Y.cpy - 300 bytes, FILLER included (G19, G21)")
    class AccountRecordImage {

        @Test
        @DisplayName("The record serialises to 300 bytes with FILLER X(178) written as spaces")
        void theRecordIsThreeHundredBytesWithItsFillerWritten() {
            String image = account().toFixedWidthString();

            assertThat(image)
                    .as("CVACT01Y declares ACCOUNT-RECORD at 300 bytes and the repository reads "
                            + "INTO(ACCOUNT-RECORD) at app/cbl/COACTVWC.cbl:780")
                    .hasSize(AccountRecord.RECORD_LENGTH)
                    .hasSize(300);
            assertThat(AccountRecord.FILLER_OFFSET + AccountRecord.FILLER_LENGTH)
                    .as("the FILLER is the last span, so omitting it would shorten the record")
                    .isEqualTo(AccountRecord.RECORD_LENGTH);
            assertThat(image.substring(AccountRecord.FILLER_OFFSET))
                    .as("FILLER X(178) is emitted as spaces, never dropped")
                    .isEqualTo(" ".repeat(AccountRecord.FILLER_LENGTH))
                    .hasSize(178);
        }

        @Test
        @DisplayName("The copybook's misspelled ACCT-EXPIRAION-DATE keeps its name")
        void theMisspelledFieldNameIsPreserved() {
            // app/cpy/CVACT01Y.cpy spells it ACCT-EXPIRAION-DATE, and app/cbl/COACTVWC.cbl:488 moves
            // that item to AEXPDTO. Correcting the spelling would break field-for-field diffing, so the
            // Java accessor carries the typo too (implicit requirement I1).
            AccountRecord record = account();
            assertThat(record.getAcctExpiraionDate()).isEqualTo("2025-01-14");

            List<String> accessorNames = new ArrayList<>();
            for (Method method : AccountRecord.class.getDeclaredMethods()) {
                if (method.getName().toLowerCase(Locale.ROOT).contains("expira")) {
                    accessorNames.add(method.getName());
                }
            }
            assertThat(accessorNames).isNotEmpty();
            Set<String> distinctSpellings = new LinkedHashSet<>();
            for (String name : accessorNames) {
                assertThat(name)
                        .as("no accessor may spell it the corrected way")
                        .doesNotContain("Expiration");
                distinctSpellings.add(name.contains("Expiraion") ? "Expiraion" : name);
            }
            assertThat(distinctSpellings).containsExactly("Expiraion");
        }

        @Test
        @DisplayName("A successful read projects the record onto the screen, FILLER excluded")
        void aSuccessfulReadProjectsTheRecord() {
            stubAllFound();

            AccountViewResponse painted =
                    controller.handle(request(ACCT, reenter()), PASSED_COMMAREA, CicsAid.DFHENTER);

            // :487-490 - the four dates and the group identifier, each at the screen field's own width.
            assertThat(painted.getAdtopen()).isEqualTo("2020-01-15");
            assertThat(painted.getAexpdt())
                    .as(":488 MOVE ACCT-EXPIRAION-DATE TO AEXPDTO")
                    .isEqualTo("2025-01-14");
            assertThat(painted.getAreisdt()).isEqualTo("2022-06-30");
            assertThat(painted.getAaddgrp()).isEqualTo(pad("ZEROPCT", 10));
            // The FILLER is not a screen field: none of the 37 payload items maps to it.
            Map<String, String> images = painted.fieldImages();
            assertThat(images).hasSize(AccountViewResponse.FIELD_COUNT);
            for (String name : images.keySet()) {
                assertThat(name.toUpperCase(Locale.ROOT)).doesNotContain("FILLER");
            }
        }
    }
}
