package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vsergeychik.carddemo.account.AccountDateValidator;
import com.vsergeychik.carddemo.account.AccountDateValidator.EditDateState;
import com.vsergeychik.carddemo.account.AccountDateValidator.EditFlag;
import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.AccountUpdateController;
import com.vsergeychik.carddemo.account.AccountUpdateService;
import com.vsergeychik.carddemo.account.AccountUpdateService.AccountData;
import com.vsergeychik.carddemo.account.AccountUpdateService.AccountUpdateDetails;
import com.vsergeychik.carddemo.account.AccountUpdateService.Block;
import com.vsergeychik.carddemo.account.AccountUpdateService.ComparedItem;
import com.vsergeychik.carddemo.account.AccountUpdateService.Comparison;
import com.vsergeychik.carddemo.account.AccountUpdateService.CustomerData;
import com.vsergeychik.carddemo.account.AccountUpdateService.DetailGroup;
import com.vsergeychik.carddemo.account.AccountUpdateService.MonetaryEdit;
import com.vsergeychik.carddemo.account.AccountUpdateService.WriteOutcome;
import com.vsergeychik.carddemo.account.AccountUpdateService.WriteResult;
import com.vsergeychik.carddemo.account.AreaCodeLookup;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest;
import com.vsergeychik.carddemo.account.dto.AccountUpdateResponse;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedDataset;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenRequest;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.testsupport.ConversationStateSealFixture;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * The behavioural-parity gate for {@code COACTUPC} - the update-account screen, CSD transaction
 * {@code CAUP}, projected as {@code PUT /api/accounts/&#123;acctId&#125;}.
 */
@DisplayName("COACTUPC parity - the update-account screen, transaction CAUP")
class COACTUPCParityTest {
    private static final String PROGRAM = "COACTUPC";

    private static final String PINNED_CLOCK = "2022-07-19T23:15:59";

    private static final String PINNED_DATE_IMAGE = "07/19/22";

    private static final String PINNED_TIME_IMAGE = "23:15:59";

    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    private static final String CHARSET_NAME = "US-ASCII";

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(FIXTURE_CHARSET);

    private static final String ACCTDAT = AccountRepository.CICS_FILE_NAME;

    private static final String CUSTDAT = CustomerRepository.CICS_FILE_NAME;

    private static final String CARDDAT = CardRepository.BASE_DD_NAME;

    private static final String CXACAIX = CardXrefRepository.ALTERNATE_INDEX_DD_NAME;

    private static final int EIBCALEN_NONE = 0;

    private static final int EIBCALEN_FULL =
            NavigationContext.COMMAREA_LENGTH + AccountUpdateRequest.CommArea.RECORD_LENGTH;

    private static final String THIS_PGM = AccountUpdateResponse.THIS_PROGRAM;

    private static final String THIS_TRANID = AccountUpdateResponse.THIS_TRANSACTION;

    private static final String THIS_MAPSET = AccountUpdateResponse.MAPSET_NAME;

    private static final String THIS_MAP = AccountUpdateResponse.MAP_NAME;

    private static final String MENU_PGM = "COMEN01C";

    private static final String MENU_TRANID = "CM00";

    private static final int SEEDED_ROWS = 3;

    private static final String ACCT_KEY = "00000000001";

    private static final int CUST_KEY = 1;

    private static final String STORED_STATUS = "Y";

    private static final BigDecimal STORED_CURR_BAL = new BigDecimal("194.00");

    private static final BigDecimal STORED_CREDIT_LIMIT = new BigDecimal("2020.00");

    private static final BigDecimal STORED_CASH_LIMIT = new BigDecimal("1020.00");

    private static final BigDecimal STORED_CYC_CREDIT = new BigDecimal("0.00");

    private static final BigDecimal STORED_CYC_DEBIT = new BigDecimal("0.00");

    private static final String STORED_OPEN_DATE = "2014-11-20";

    private static final String STORED_EXPIRAION_DATE = "2025-05-20";

    private static final String STORED_REISSUE_DATE = "2025-05-20";

    private static final String STORED_ADDR_ZIP = "A000000000";

    private static final String STORED_GROUP_ID = " ".repeat(AccountRecord.ACCT_GROUP_ID_LENGTH);

    private static final String NEW_STATUS = "N";

    private static final BigDecimal NEW_CURR_BAL = new BigDecimal("1234.56");

    private static final BigDecimal NEW_CREDIT_LIMIT = new BigDecimal("7500.00");

    private static final BigDecimal NEW_CASH_LIMIT = new BigDecimal("1500.00");

    private static final BigDecimal NEW_CYC_CREDIT = new BigDecimal("11.11");

    private static final BigDecimal NEW_CYC_DEBIT = new BigDecimal("22.22");

    private static final String NEW_OPEN_DATE = "2015-01-02";

    private static final String NEW_EXPIRAION_DATE = "2026-11-30";

    private static final String NEW_REISSUE_DATE = "2023-05-01";

    private static final String NEW_GROUP_ID = "ZEROPCT";

    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    private static final String TITLE02 = "              CardDemo                  ";

    private static final String INFO_PROMPT_FOR_ACCT = "Enter or update id of account to update      ";

    private static final String FKEYS_LEGEND = "ENTER=Process F3=Exit";

    private static final String FKEY05_LEGEND = "F5=Save";

    private static final String FKEY12_LEGEND = "F12=Cancel";

    private static final String MSG_ACCT_NOT_ELEVEN_DIGITS =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    private static final String MSG_NO_INPUT_RECEIVED = "No input received";

    private static final String NON_NUMERIC_ACCT = "0000000000A";

    private static final String BLANK_ACCT = " ".repeat(AccountUpdateRequest.ACCTSID_LENGTH);

    private static final String MSG_ACCT_STATUS_MUST_BE_SUPPLIED =
            "Account Status must be supplied.";

    private static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";

    private static final String REJECTED_AREA_CODE = "199";

    private static final String NON_NUMERIC_PREFIX = "AB";

    private static final String VALID_LINE_NUMBER = "1234";

    private static final String EDIT_FIELD_NAME = "Open Date";

    private static final String PROGRAM_AREA_KEY = "WS-THIS-PROGCOMMAREA";

    private static final String LINKAGE_RETURN_MSG = "WS-RETURN-MSG";

    private static List<ParityScenario> cases() {
        List<ParityScenario> scenarios = ParityHarness.casesOf(PROGRAM).stream()
                .map(COACTUPCParityTest::bind)
                .toList();
        if (scenarios.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException("The parity gate for " + PROGRAM + " requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + " cases, named case01 through case"
                    + ParityHarness.CASES_PER_PROGRAM + ", but " + scenarios.size()
                    + " were declared. A short set is not a smaller gate, it is a missing one: an "
                    + "assertion nobody runs cannot fail.");
        }
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            String required = ParityHarness.caseId(ordinal);
            String declared = scenarios.get(ordinal - 1).caseId();
            if (!required.equals(declared)) {
                throw new IllegalStateException("Parity case " + ordinal + " for " + PROGRAM
                        + " is \"" + declared + "\" where the gate requires \"" + required
                        + "\". The identifiers are ordered and they name the fixture, so a mis-numbered "
                        + "case would silently take another case's place.");
            }
        }
        return scenarios;
    }

    private static ParityScenario bind(ParityCase parityCase) {
        return switch (parityCase.unitKind()) {
            case SERVICE -> new ParityScenario(parityCase, UnitKind.SERVICE,
                    COACTUPCParityTest::serviceUnit, "AccountUpdateService.writeProcessing");
            case CONTROLLER_POJO -> new ParityScenario(parityCase, UnitKind.CONTROLLER_POJO,
                    COACTUPCParityTest::controllerUnit, "AccountUpdateController.updateAccount");
            case BATCH_JOB, COMPONENT -> throw new IllegalStateException("Case " + PROGRAM + '/'
                    + parityCase.caseId() + " declares unitKind " + parityCase.unitKind()
                    + ", which COACTUPC has no unit for: it is a CICS online program, so its units are "
                    + "AccountUpdateController (CONTROLLER_POJO) and AccountUpdateService (SERVICE).");
        };
    }

    private static UnitOutcome serviceUnit(Invocation invocation) {
        AccountUpdateRequest.CommArea area = programAreaOf(invocation);
        requireReceivedFieldsMatch(invocation, area.newDetails());
        return serviceUnit(invocation,
                detailsOf(DetailGroup.OLD, area.oldDetails()),
                detailsOf(DetailGroup.NEW, area.newDetails()));
    }

    private static void requireReceivedFieldsMatch(Invocation invocation,
                                                   AccountUpdateRequest.Details newDetails) {
        Map<String, String> received = invocation.mapFields();
        if (received.isEmpty()) {
            return;
        }
        FixedWidthCodec codec = invocation.codec();
        AccountUpdateRequest.AcctSnapshot acct = newDetails.acct();
        AccountUpdateRequest.CustSnapshot cust = newDetails.cust();

        requireField(invocation, "ACCTSIDI", acct.acctIdX());
        requireField(invocation, "ACSTTUSI", acct.activeStatus());
        requireField(invocation, "AADDGRPI", acct.groupId());
        requireField(invocation, "ACSTNUMI", cust.custIdX());
        requireField(invocation, "ACSFNAMI", cust.firstName());
        requireField(invocation, "ACSMNAMI", cust.middleName());
        requireField(invocation, "ACSLNAMI", cust.lastName());
        requireField(invocation, "ACSADL1I", cust.addrLine1());
        requireField(invocation, "ACSADL2I", cust.addrLine2());
        requireField(invocation, "ACSCITYI", cust.addrLine3());
        requireField(invocation, "ACSSTTEI", cust.addrStateCd());
        requireField(invocation, "ACSCTRYI", cust.addrCountryCd());
        requireField(invocation, "ACSZIPCI", cust.addrZip());
        requireField(invocation, "ACSGOVTI", cust.govtIssuedId());
        requireField(invocation, "ACSEFTCI", cust.eftAccountId());
        requireField(invocation, "ACSPFLGI", cust.priHolderInd());
        requireField(invocation, "ACSTFCOI", cust.ficoScoreX());

        requireParts(invocation, cust.ssnX(), "ACUP-NEW-CUST-SSN-X",
                List.of("ACTSSN1I", "ACTSSN2I", "ACTSSN3I"), List.of(3, 2, 4), List.of(0, 3, 5));
        requireParts(invocation, acct.openDate(), "ACUP-NEW-OPEN-DATE",
                List.of("OPNYEARI", "OPNMONI", "OPNDAYI"), List.of(4, 2, 2), List.of(0, 4, 6));
        requireParts(invocation, acct.expiraionDate(), "ACUP-NEW-EXPIRAION-DATE",
                List.of("EXPYEARI", "EXPMONI", "EXPDAYI"), List.of(4, 2, 2), List.of(0, 4, 6));
        requireParts(invocation, acct.reissueDate(), "ACUP-NEW-REISSUE-DATE",
                List.of("RISYEARI", "RISMONI", "RISDAYI"), List.of(4, 2, 2), List.of(0, 4, 6));
        requireParts(invocation, cust.dobYyyyMmDd(), "ACUP-NEW-CUST-DOB-YYYY-MM-DD",
                List.of("DOBYEARI", "DOBMONI", "DOBDAYI"), List.of(4, 2, 2), List.of(0, 4, 6));
        requireParts(invocation, cust.phoneNum1(), "ACUP-NEW-CUST-PHONE-NUM-1-X",
                List.of("ACSPH1AI", "ACSPH1BI", "ACSPH1CI"), List.of(3, 3, 4), List.of(1, 5, 9));
        requireParts(invocation, cust.phoneNum2(), "ACUP-NEW-CUST-PHONE-NUM-2-X",
                List.of("ACSPH2AI", "ACSPH2BI", "ACSPH2CI"), List.of(3, 3, 4), List.of(1, 5, 9));

        requireComputed(invocation, "ACRDLIMI", acct.creditLimit(),
                AccountUpdateService.computeCreditLimit(received.get("ACRDLIMI"), null, codec));
        requireComputed(invocation, "ACSHLIMI", acct.cashCreditLimit(),
                AccountUpdateService.computeCashCreditLimit(received.get("ACSHLIMI"), null, codec));
        requireComputed(invocation, "ACURBALI", acct.currBal(),
                AccountUpdateService.computeCurrBal(received.get("ACURBALI"), null, codec));
        requireComputed(invocation, "ACRCYCRI", acct.currCycCredit(),
                AccountUpdateService.computeCurrCycCredit(received.get("ACRCYCRI"), null, codec));
        requireComputed(invocation, "ACRCYDBI", acct.currCycDebit(),
                AccountUpdateService.computeCurrCycDebit(received.get("ACRCYDBI"), null, codec));
    }

    private static void requireField(Invocation invocation, String field, String carried) {
        String typed = invocation.mapFields().get(field);
        if (typed == null) {
            return;
        }
        String expected = stagedImage(typed, carried.length());
        if (!expected.equals(carried)) {
            throw new IllegalStateException("Case " + invocation.program() + '/' + invocation.caseId()
                    + " declares " + field + " and the ACUP-NEW item it is moved into, and the two "
                    + "disagree at width " + carried.length() + ". The MOVE in 1100-RECEIVE-MAP is "
                    + "width-for-width and its blank arm moves LOW-VALUES rather than spaces, so an area "
                    + "that holds neither is an area no operator could have produced.");
        }
    }

    private static void requireParts(Invocation invocation, String carried, String cobolName,
                                     List<String> fields, List<Integer> widths,
                                     List<Integer> offsets) {
        for (int part = 0; part < fields.size(); part++) {
            String typed = invocation.mapFields().get(fields.get(part));
            if (typed == null) {
                continue;
            }
            int offset = offsets.get(part);
            int width = widths.get(part);
            String declared = carried.substring(offset, offset + width);
            String expected = stagedImage(typed, width);
            if (!expected.equals(declared)) {
                throw new IllegalStateException("Case " + invocation.program() + '/'
                        + invocation.caseId() + " declares " + fields.get(part) + " and the "
                        + cobolName + " part at offset " + offset + " it is moved into, and the two "
                        + "disagree. Each part of a grouped item is moved by its own guarded MOVE, so a "
                        + "part that does not hold what the screen supplied is a state the program cannot "
                        + "be in.");
            }
        }
    }

    private static String stagedImage(String typed, int width) {
        boolean notSupplied = AccountUpdateService.NOT_SUPPLIED_MARKER.equals(typed.trim())
                || typed.isBlank();
        return notSupplied ? "\u0000".repeat(width) : picX(typed, width);
    }

    private static void requireComputed(Invocation invocation, String field, String carried,
                                        AccountUpdateService.MonetaryEdit edit) {
        if (invocation.mapFields().get(field) == null) {
            return;
        }
        String rendered = invocation.codec().encodeSignedScaled(edit.value(),
                AccountUpdateService.MONETARY_INTEGER_DIGITS, CobolDecimal.MONETARY_SCALE);
        if (!rendered.equals(carried)) {
            throw new IllegalStateException("Case " + invocation.program() + '/' + invocation.caseId()
                    + " declares " + field + " and the twelve bytes of " + edit.cobolReceiver()
                    + " it computes to, and the two disagree. FUNCTION NUMVAL-C of the screen text stored "
                    + "into PIC S9(10)V99 truncates - ROUNDED appears zero times in COACTUPC and zero "
                    + "times across all twenty-eight programs - so a receiver image that does not match "
                    + "means either the case or the rounding mode is wrong (gates G23, G24, G28).");
        }
    }

    private static String picX(String value, int length) {
        String source = value == null ? "" : value;
        return source.length() >= length ? source.substring(0, length)
                : source + " ".repeat(length - source.length());
    }

    private static AccountUpdateDetails detailsOf(DetailGroup group,
                                                  AccountUpdateRequest.Details details) {
        AccountUpdateRequest.AcctSnapshot acct = details.acct();
        AccountUpdateRequest.CustSnapshot cust = details.cust();
        return new AccountUpdateDetails(group,
                new AccountData(acct.acctId(), acct.activeStatus(), acct.currBalN(),
                        acct.creditLimitN(), acct.cashCreditLimitN(),
                        acct.openYear(), acct.openMon(), acct.openDay(),
                        acct.expYear(), acct.expMon(), acct.expDay(),
                        acct.reissueYear(), acct.reissueMon(), acct.reissueDay(),
                        acct.currCycCreditN(), acct.currCycDebitN(), acct.groupId()),
                new CustomerData((int) cust.custId(), cust.firstName(), cust.middleName(),
                        cust.lastName(), cust.addrLine1(), cust.addrLine2(), cust.addrLine3(),
                        cust.addrStateCd(), cust.addrCountryCd(), cust.addrZip(), cust.phoneNum1(),
                        cust.phoneNum2(), (int) cust.ssn(), cust.govtIssuedId(), cust.dobYear(),
                        cust.dobMon(), cust.dobDay(), cust.eftAccountId(), cust.priHolderInd(),
                        cust.ficoScore()));
    }

    private static UnitOutcome controllerUnit(Invocation invocation) {
        return controllerUnit(invocation, accountKeyOf(invocation),
                invocation.eibcalen() == 0 ? null : navigationContextFrom(invocation.commarea()));
    }

    private static AccountUpdateRequest.CommArea programAreaOf(Invocation invocation) {
        String image = invocation.commarea().get(PROGRAM_AREA_KEY);
        if (image == null) {
            return AccountUpdateRequest.CommArea.initialised();
        }
        if (image.length() != AccountUpdateRequest.CommArea.RECORD_LENGTH) {
            throw new IllegalStateException("Case " + invocation.program() + '/' + invocation.caseId()
                    + " declares a " + PROGRAM_AREA_KEY + " image of " + image.length()
                    + " characters where WS-THIS-PROGCOMMAREA is "
                    + AccountUpdateRequest.CommArea.RECORD_LENGTH + " bytes - the change action followed "
                    + "by ACUP-OLD-DETAILS and ACUP-NEW-DETAILS, at app/cbl/COACTUPC.cbl:652-849. A short "
                    + "image would decode into a state the program cannot be in.");
        }
        return AccountUpdateRequest.CommArea.decode(image.getBytes(invocation.charset()),
                invocation.codec());
    }

    private static String accountKeyOf(Invocation invocation) {
        String typed = invocation.mapFields().get("ACCTSIDI");
        if (typed != null) {
            return typed;
        }
        String carried = invocation.commarea().get("CDEMO-ACCT-ID");
        return carried == null ? " ".repeat(AccountUpdateRequest.ACCTSID_LENGTH) : carried;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COACTUPC: every case diffs to zero against the COBOL-derived baseline")
    void theTranslationMatchesTheCobolFieldForField(ParityScenario scenario) {
        DiffResult result = ParityHarness.usAscii()
                .judge(scenario.parityCase(), scenario.adapterKind(), scenario.adapter());

        assertThat(result.count())
                .describedAs("%s/%s (%s) must diff to zero. %s", PROGRAM, scenario.caseId(),
                        scenario.unitName(), result.render())
                .isZero();
    }

    @Test
    @DisplayName("the class stem, the program name and the parity/COACTUPC convention agree")
    void theResourceConventionIsHonoured() {
        assertThat(COACTUPCParityTest.class.getSimpleName()).isEqualTo(PROGRAM + "ParityTest");
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(1)))
                .isEqualTo(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/case01"
                        + ParityHarness.CASE_RESOURCE_EXTENSION);
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(20)))
                .isEqualTo("parity/COACTUPC/case20.json");
        assertThat(ParityHarness.CASES_PER_PROGRAM).isEqualTo(20);
        assertThat(cases()).hasSize(ParityHarness.CASES_PER_PROGRAM);
    }

    @Test
    @DisplayName("the fifty-four payload fields carry the widths COACTUP.CPY declares")
    void theFiftyFourPayloadFieldsCarryTheirDeclaredWidths() {
        assertThat(AccountUpdateResponse.ScreenField.values()).hasSize(54);
        assertThat(AccountUpdateRequest.ScreenField.values()).hasSize(54);
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            assertThat(field.length())
                    .describedAs("%s is declared %s in app/cpy-bms/COACTUP.CPY", field.label(),
                            field.picture())
                    .isEqualTo(AccountUpdateResponse.declaredLength(field))
                    .isPositive();
            assertThat(field.isAlphanumeric())
                    .describedAs("%s must be PIC X: app/bms/COACTUP.bms declares no PICOUT, so every "
                            + "field arrives and leaves as characters", field.label())
                    .isTrue();
            assertThat(field.symbolicItemName())
                    .describedAs("%s's symbolic output item", field.label())
                    .isEqualTo(field.label() + "O");
        }
        assertThat(AccountUpdateResponse.SCREEN_ROWS).isEqualTo(24);
        assertThat(AccountUpdateResponse.SCREEN_COLUMNS).isEqualTo(80);
    }

    @Test
    @DisplayName("account 300, card 150, cross-reference 50, customer 500 - FILLER included")
    void everyRecordIsAsWideAsItsCopybookDeclares() {
        assertThat(AccountRecord.RECORD_LENGTH).isEqualTo(300);
        assertThat(AccountRecord.LAYOUT.recordLength()).isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(AccountRecord.FILLER_OFFSET + AccountRecord.FILLER_LENGTH)
                .isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(AccountRecord.FILLER_LENGTH).isEqualTo(178);

        assertThat(CustomerRecord.RECORD_LENGTH).isEqualTo(500);
        assertThat(CustomerRecord.LAYOUT.recordLength()).isEqualTo(CustomerRecord.RECORD_LENGTH);
        assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
        assertThat(CardXrefRecord.LAYOUT.recordLength()).isEqualTo(CardXrefRecord.RECORD_LENGTH);

        assertThat(fullNewAccountImage()).hasSize(AccountRecord.RECORD_LENGTH);
        assertThat(fullNewCustomerImage()).hasSize(CustomerRecord.RECORD_LENGTH);
        assertThat(fullNewAccountImage()
                .substring(AccountUpdateService.ACCT_UPDATE_FILLER_OFFSET))
                .describedAs("FILLER X(188) at app/cbl/COACTUPC.cbl:433 is emitted as spaces")
                .isBlank()
                .hasSize(AccountUpdateService.ACCT_UPDATE_FILLER_LENGTH);
    }

    @Test
    @DisplayName("ACCT-UPDATE-GROUP-ID sits at offset 102, over the stored ACCT-ADDR-ZIP")
    void theUpdateRecordWritesTheGroupIdOverTheStoredZip() {
        assertThat(AccountUpdateService.ACCT_UPDATE_GROUP_ID_OFFSET)
                .describedAs("ACCT-UPDATE-GROUP-ID at :432 begins where CVACT01Y puts ACCT-ADDR-ZIP")
                .isEqualTo(AccountRecord.ACCT_ADDR_ZIP_OFFSET)
                .isEqualTo(102);
        assertThat(AccountUpdateService.ACCT_UPDATE_FILLER_OFFSET)
                .describedAs("FILLER X(188) at :433 begins where CVACT01Y puts ACCT-GROUP-ID")
                .isEqualTo(AccountRecord.ACCT_GROUP_ID_OFFSET)
                .isEqualTo(112);
        assertThat(AccountUpdateService.ACCT_UPDATE_FILLER_LENGTH)
                .describedAs("FILLER X(188) is ten wider than CVACT01Y's FILLER X(178)")
                .isEqualTo(AccountRecord.FILLER_LENGTH + AccountRecord.ACCT_ADDR_ZIP_LENGTH)
                .isEqualTo(188);

        String written = fullNewAccountImage();
        assertThat(written.substring(AccountRecord.ACCT_ADDR_ZIP_OFFSET,
                AccountRecord.ACCT_ADDR_ZIP_OFFSET + AccountRecord.ACCT_ADDR_ZIP_LENGTH))
                .describedAs("the stored zip's ten bytes now hold the group identifier")
                .isEqualTo(CODEC.movePicX(NEW_GROUP_ID, AccountRecord.ACCT_ADDR_ZIP_LENGTH))
                .isNotEqualTo(STORED_ADDR_ZIP);
        assertThat(written.substring(AccountRecord.ACCT_GROUP_ID_OFFSET))
                .describedAs("the stored group's ten bytes, and everything after them, are blanked")
                .isBlank();
    }

    @Test
    @DisplayName("9700 compares 35 items in two ordered blocks and ignores both keys")
    void theConcurrencyCheckComparesThirtyFiveItemsAndNeitherKey() {
        List<ComparedItem> items = Arrays.asList(ComparedItem.values());
        assertThat(items).hasSize(35);

        Set<ComparedItem> accountBlock = EnumSet.noneOf(ComparedItem.class);
        Set<ComparedItem> customerBlock = EnumSet.noneOf(ComparedItem.class);
        for (ComparedItem item : items) {
            (item.block() == Block.ACCOUNT_MASTER ? accountBlock : customerBlock).add(item);
        }
        assertThat(accountBlock).hasSize(16);
        assertThat(customerBlock).hasSize(19);

        int lastAccount = -1;
        int firstCustomer = items.size();
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).block() == Block.ACCOUNT_MASTER) {
                lastAccount = index;
            } else if (firstCustomer == items.size()) {
                firstCustomer = index;
            }
        }
        assertThat(lastAccount).isLessThan(firstCustomer);

        List<String> names = items.stream().map(ComparedItem::cobolName).toList();
        assertThat(names)
                .describedAs("neither key and not the account zip: none of the three appears at "
                        + "app/cbl/COACTUPC.cbl:4115-4192")
                .doesNotContain(AccountRecord.ACCT_ID_NAME, AccountRecord.ACCT_ADDR_ZIP_NAME,
                        "CUST-ID")
                .contains(AccountRecord.ACCT_GROUP_ID_NAME, AccountRecord.ACCT_ACTIVE_STATUS_NAME,
                        "CUST-FIRST-NAME", "CUST-FICO-CREDIT-SCORE");

        assertThat(items.stream().filter(item -> item.comparison() == Comparison.LOWER_CASE_FOLDED)
                .map(ComparedItem::cobolName).toList())
                .describedAs("FUNCTION LOWER-CASE appears twice at :4139-4140 - once per operand - so it "
                        + "folds exactly one comparison")
                .containsExactly(AccountRecord.ACCT_GROUP_ID_NAME);
        assertThat(items.stream().filter(item -> item.comparison() == Comparison.UPPER_CASE_FOLDED)
                .map(ComparedItem::cobolName).toList())
                .describedAs("FUNCTION UPPER-CASE appears eighteen times between :4152 and :4173 - "
                        + "again once per operand - so it folds exactly nine comparisons, and which nine "
                        + "matters: the zip, both phone numbers, the SSN and the FICO score sit among "
                        + "them unfolded")
                .containsExactly("CUST-FIRST-NAME", "CUST-MIDDLE-NAME", "CUST-LAST-NAME",
                        "CUST-ADDR-LINE-1", "CUST-ADDR-LINE-2", "CUST-ADDR-LINE-3",
                        "CUST-ADDR-STATE-CD", "CUST-ADDR-COUNTRY-CD", "CUST-GOVT-ISSUED-ID");
        assertThat(items.stream().filter(item -> item.comparison() == Comparison.MONETARY)
                .map(ComparedItem::cobolName).toList())
                .describedAs("the five signed-decimal comparisons at :4117-4125 compare numerically "
                        + "rather than byte-wise, because COBOL compares numeric operands by value - a "
                        + "byte comparison would call a differently-zoned image of the same amount a "
                        + "concurrent change")
                .containsExactly(AccountRecord.ACCT_CURR_BAL_NAME,
                        AccountRecord.ACCT_CREDIT_LIMIT_NAME,
                        AccountRecord.ACCT_CASH_CREDIT_LIMIT_NAME,
                        AccountRecord.ACCT_CURR_CYC_CREDIT_NAME,
                        AccountRecord.ACCT_CURR_CYC_DEBIT_NAME);
        assertThat(items.stream().filter(item -> item.comparison() == Comparison.EXACT).count())
                .describedAs("the remaining twenty comparisons are byte-exact: ten date substrings, the "
                        + "account status, the zip, both phone numbers, the SSN, the three date "
                        + "substrings of the birth date, the EFT identifier, the primary-holder "
                        + "indicator and the FICO score")
                .isEqualTo(20L);
        assertThat(items.size())
                .describedAs("and the four kinds account for every item, with none left over")
                .isEqualTo(1 + 9 + 5 + 20);
    }

    @Test
    @DisplayName("the group compares lower-cased and the first name upper-cased, both operands")
    void theFoldedComparisonsFoldBothOperands() {
        AccountRecord stored = storedAccountRecordWith("ZeroPct");
        CustomerRecord customer = storedCustomerRecord();
        AccountUpdateService service = serviceOverEmptyDatasets();

        AccountUpdateDetails foldedBoth = new AccountUpdateDetails(DetailGroup.OLD,
                withGroupId(storedAccountSnapshot(), "zEROpCT"),
                foldFirstNameToUpper(storedCustomerSnapshot()));
        assertThat(service.checkChangeInRec(stored, customer, foldedBoth, CODEC).dataWasChanged())
                .describedAs("a group differing only in letter case, and a first name differing only "
                        + "in letter case, are not concurrent changes")
                .isFalse();

        AccountUpdateDetails genuinelyDifferent = new AccountUpdateDetails(DetailGroup.OLD,
                withGroupId(storedAccountSnapshot(), "PREMIUM"), storedCustomerSnapshot());
        assertThat(service.checkChangeInRec(stored, customer, genuinelyDifferent, CODEC)
                .dataWasChanged())
                .describedAs("a group that differs in more than letter case is a concurrent change")
                .isTrue();
    }

    @Test
    @DisplayName("all five COMPUTE sites truncate toward zero and none rounds")
    void theFiveComputeSitesTruncateRatherThanRound() {
        assertComputeTruncates(AccountUpdateService.computeCreditLimit("$7,500.567", null, CODEC),
                "7500.56", "7500.57");
        assertComputeTruncates(AccountUpdateService.computeCashCreditLimit("1500.999", null, CODEC),
                "1500.99", "1501.00");
        assertComputeTruncates(AccountUpdateService.computeCurrBal("-42.079", null, CODEC),
                "-42.07", "-42.08");
        assertComputeTruncates(AccountUpdateService.computeCurrCycCredit("11.115", null, CODEC),
                "11.11", "11.12");
        assertComputeTruncates(AccountUpdateService.computeCurrCycDebit("22.229", null, CODEC),
                "22.22", "22.23");
        assertThat(CobolDecimal.MONETARY_SCALE)
                .describedAs("every signed decimal picture in the codebase is scale 2")
                .isEqualTo(2);
    }

    private static void assertComputeTruncates(MonetaryEdit edit, String truncated, String rounded) {
        assertThat(edit.value())
                .describedAs("COMPUTE %s at app/cbl/COACTUPC.cbl:%s truncates rather than rounds",
                        edit.cobolReceiver(), edit.sourceLines())
                .isEqualByComparingTo(new BigDecimal(truncated))
                .isNotEqualByComparingTo(new BigDecimal(rounded));
        assertThat(edit.value().scale())
                .describedAs("%s is PIC S9(10)V99", edit.cobolReceiver())
                .isEqualTo(CobolDecimal.MONETARY_SCALE);
    }

    @Test
    @DisplayName("every FILE STATUS arm of 9600-WRITE-PROCESSING is reached")
    void everyFileStatusArmOfWriteProcessingIsReached() {
        WriteResult customerLockFailed = writeProcessingWith(true, null, null);
        assertThat(customerLockFailed.outcome())
                .isEqualTo(WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE);
        assertThat(customerLockFailed.returnMessage())
                .isEqualTo(CODEC.movePicX(AccountUpdateService.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE,
                        AccountUpdateService.RETURN_MESSAGE_LENGTH));
        assertThat(customerLockFailed.fileStatus()).isEqualTo(FileStatus.NOT_FOUND);
        assertThat(customerLockFailed.failedFileName())
                .contains(AccountUpdateService.CUST_CICS_FILE_NAME);
        assertThat(customerLockFailed.isRewritten()).isFalse();
        assertThat(customerLockFailed.syncpointRollbackRequested()).isFalse();

        WriteResult accountRewriteFailed = writeProcessingWith(false,
                AccountRepository.WriteResult.notFound(), null);
        assertThat(accountRewriteFailed.outcome()).isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
        assertThat(accountRewriteFailed.returnMessage())
                .isEqualTo(CODEC.movePicX(AccountUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED,
                        AccountUpdateService.RETURN_MESSAGE_LENGTH));
        assertThat(accountRewriteFailed.isRewritten()).isFalse();
        assertThat(accountRewriteFailed.syncpointRollbackRequested())
                .describedAs("nothing has been written, so :4076-4080 requests no rollback")
                .isFalse();

        WriteResult customerRewriteFailed = writeProcessingWith(false, null,
                CustomerRepository.WriteResult.notFound());
        assertThat(customerRewriteFailed.outcome()).isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
        assertThat(customerRewriteFailed.syncpointRollbackRequested())
                .describedAs("the account rewrite already succeeded, so :4099-4101 rolls it back")
                .isTrue();

        assertThat(FileStatus.Outcome.values())
                .describedAs("the five outcomes a repository can report")
                .containsExactlyInAnyOrder(FileStatus.Outcome.OK, FileStatus.Outcome.END_OF_FILE,
                        FileStatus.Outcome.NOT_FOUND, FileStatus.Outcome.DUPLICATE,
                        FileStatus.Outcome.OTHER);
    }

    @Test
    @DisplayName("WS-RETURN-MSG is PIC X(75) and its cleared state is 75 spaces")
    void theReturnMessageIsSeventyFiveBytesWide() {
        assertThat(AccountUpdateService.RETURN_MESSAGE_LENGTH).isEqualTo(75);
        assertThat(AccountUpdateService.RETURN_MESSAGE_OFF)
                .hasSize(AccountUpdateService.RETURN_MESSAGE_LENGTH)
                .isBlank();
        assertThat(AccountUpdateService.isReturnMessageOff(AccountUpdateService.RETURN_MESSAGE_OFF))
                .isTrue();
        assertThat(AccountUpdateService.isReturnMessageOff(
                CODEC.movePicX(AccountUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE,
                        AccountUpdateService.RETURN_MESSAGE_LENGTH)))
                .isFalse();
        assertThat(MessageChannel.WS_MESSAGE_80.declaration()).isNotEqualTo(
                MessageChannel.SCREEN_ERRMSG_78.declaration());
    }

    @Test
    @DisplayName("CSSTRPFY resolves every AID, folds PF13-PF24 and defaults nothing")
    void pfKeysResolveExactlyAsCsstrpfyMapsThem() {
        assertThat(CicsAid.DFHENTER).isEqualTo((byte) 0x7D);
        assertThat(CicsAid.DFHCLEAR).isEqualTo((byte) 0x6D);
        assertThat(CicsAid.DFHPA1).isEqualTo((byte) 0x6C);
        assertThat(CicsAid.DFHPA2).isEqualTo((byte) 0x6E);
        assertThat(CicsAid.DFHPF3).isEqualTo((byte) 0xF3);
        assertThat(CicsAid.DFHPF5).isEqualTo((byte) 0xF5);
        assertThat(CicsAid.DFHPF12).isEqualTo((byte) 0x7C);

        assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR)).contains(AidKey.CLEAR);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF1)).contains(AidKey.PFK01);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3)).contains(AidKey.PFK03);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF5)).contains(AidKey.PFK05);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF12)).contains(AidKey.PFK12);

        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13)).contains(AidKey.PFK01);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                .describedAs("PF15 folds onto PFK03, which is the exit key")
                .contains(AidKey.PFK03);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24)).contains(AidKey.PFK12);

        assertThat(PfKeyResolver.resolve(CicsAid.DFHPEN))
                .describedAs("DFHPEN is not one of CSSTRPFY's twenty-eight arms")
                .isEmpty();
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPEN, Optional.of(AidKey.PFK03)))
                .describedAs("an unnamed AID leaves the stored key untouched, because there is no "
                        + "WHEN OTHER to overwrite it")
                .contains(AidKey.PFK03);
    }

    @Test
    @DisplayName("PF9 and PF12 on the key screen behave exactly as ENTER, because :913-916 remaps them")
    void anUnrecognisedAidIsRemappedToEnterRatherThanRejected() {
        Map<String, String> withEnter = paintKeyScreen(CicsAid.DFHENTER);
        assertThat(paintKeyScreen(CicsAid.DFHPF9))
                .describedAs("PF9 is never a valid AID here, so :913-916 remaps it to ENTER")
                .isEqualTo(withEnter);
        assertThat(paintKeyScreen(CicsAid.DFHPF12))
                .describedAs("PF12 is valid only once details have been fetched, so here it is remapped "
                        + "to ENTER too")
                .isEqualTo(withEnter);
        assertThat(paintKeyScreen(CicsAid.DFHPF5))
                .describedAs("PF5 is valid only while changes are validated-but-unconfirmed")
                .isEqualTo(withEnter);
    }

    @Test
    @DisplayName("CSSETATY reddens only in REENTER and asterisks only a blank field")
    void theHighlightMatrixIsColourInReenterAndAsteriskOnlyWhenBlank() {
        assertHighlight(FieldValidationState.OK, false, false, false);
        assertHighlight(FieldValidationState.OK, true, false, false);
        assertHighlight(FieldValidationState.NOT_OK, false, false, false);
        assertHighlight(FieldValidationState.NOT_OK, true, true, false);
        assertHighlight(FieldValidationState.BLANK, false, false, false);
        assertHighlight(FieldValidationState.BLANK, true, true, true);

        assertThat(BmsAttributes.DFHRED)
                .describedAs("DFHBMSCA's extended-colour red, from IBM CICS documentation")
                .isEqualTo((byte) 0xF2);
        assertThat(FieldAttributeSetter.ASTERISK).isEqualTo("*");
        assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
        assertThat(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX).isEqualTo("O");
    }

    private static void assertHighlight(FieldValidationState state, boolean reenter,
                                        boolean expectColour, boolean expectAsterisk) {
        FieldHighlight highlight = FieldAttributeSetter.resolve(state, reenter);
        assertThat(highlight.colourItemAssigned())
                .describedAs("CSSETATY moves DFHRED for %s in %s", state,
                        reenter ? "REENTER" : "ENTER")
                .isEqualTo(expectColour);
        assertThat(highlight.outputItemAssigned())
                .describedAs("CSSETATY moves '*' for %s in %s", state, reenter ? "REENTER" : "ENTER")
                .isEqualTo(expectAsterisk);
    }

    @Test
    @DisplayName("CARDDEMO-COMMAREA is 160 bytes and no state is held server-side")
    void conversationStateTravelsInThePayload() {
        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(EIBCALEN_FULL)
                .isEqualTo(NavigationContext.COMMAREA_LENGTH
                        + AccountUpdateRequest.CommArea.RECORD_LENGTH)
                .isGreaterThan(NavigationContext.COMMAREA_LENGTH);
        assertThat(navigationImage(NavigationContext.empty())).hasSize(16);
        assertThat(NavigationContext.empty().toFixedWidth(CODEC))
                .hasSize(NavigationContext.COMMAREA_LENGTH);
    }

    @Test
    @DisplayName("no static mutable state in the units or in this class")
    void noStaticMutableStateExistsInThisClassOrTheUnitsItDrives() {
        for (Class<?> type : List.of(AccountUpdateController.class, AccountUpdateService.class,
                AccountDateValidator.class, AreaCodeLookup.class, COACTUPCParityTest.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .describedAs("%s.%s is static and must be final: COBOL WORKING-STORAGE is "
                                + "per-task storage and never becomes mutable static Java state, "
                                + "because that would break request isolation", type.getSimpleName(),
                                field.getName())
                        .isTrue();
                assertThat(field.getType())
                        .describedAs("%s.%s must not be a mutable collection implementation, final or "
                                + "not: shared mutable state is shared whether or not the reference "
                                + "can be reassigned", type.getSimpleName(), field.getName())
                        .isNotIn(ArrayList.class, LinkedHashMap.class, EnumSet.class);
            }
        }

        assertThat(editDate("20220719")).isNotSameAs(editDate("20220719"));
    }

    @Test
    @DisplayName("CARDAIX and CXACAIX are alternate-index finders on the base repositories")
    void theAlternateIndexesAreSecondFindersOnTheSameRepositories() {
        assertThat(CardXrefRepository.BASE_DD_NAME).isEqualTo("CCXREF");
        assertThat(CardXrefRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CXACAIX");
        assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");
        assertThat(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD)
                .describedAs("the CXACAIX path is keyed by the account identifier, which is what "
                        + "9200-GETCARDXREF-BYACCT reads it by")
                .isEqualTo(CardXrefRecord.XREF_ACCT_ID_NAME);

        assertThat(Arrays.stream(CardXrefRepository.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList())
                .contains("readByCardNumber", "readByAccountIdViaAltIndex");
    }

    @Test
    @DisplayName("no version column and no ORM annotation is introduced for concurrency control")
    void noVersionColumnOrSchemaArtefactIsIntroduced() {
        assertThat(AccountRecord.LAYOUT.spans()).hasSize(13);
        assertThat(AccountRecord.LAYOUT.recordLength()).isEqualTo(300);
        assertThat(AccountRecord.LAYOUT.spans().stream()
                .map(FixedWidthRecord.FieldSpan::name).toList())
                .describedAs("thirteen spans, exactly as app/cpy/CVACT01Y.cpy declares them")
                .doesNotContain("ACCT-VERSION", "VERSION", "ROW-VERSION", "OPTLOCK");

        for (Class<?> type : List.of(AccountRecord.class, CustomerRecord.class,
                CardXrefRecord.class)) {
            for (java.lang.annotation.Annotation annotation : type.getAnnotations()) {
                assertThat(annotation.annotationType().getName())
                        .describedAs("%s carries a persistence annotation; the plan forbids an ORM "
                                + "outright", type.getSimpleName())
                        .doesNotStartWith("jakarta.persistence")
                        .doesNotStartWith("javax.persistence")
                        .doesNotStartWith("org.hibernate");
            }
        }
    }

    @Test
    @DisplayName("every persisted numeric is zoned DISPLAY; COMP-3 is working-storage only")
    void packedDecimalNeverReachesARecord() {
        for (BigDecimal value : List.of(new BigDecimal("0.00"), new BigDecimal("194.00"),
                new BigDecimal("-42.07"), new BigDecimal("9999999999.99"))) {
            String image = monetary(value);
            assertThat(image)
                    .describedAs("a PIC S9(10)V99 span is twelve zoned characters, not six packed bytes")
                    .hasSize(AccountRecord.MONETARY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE)
                    .hasSize(12);
            assertThat(CODEC.decodeSignedScaled(image, CobolDecimal.MONETARY_SCALE))
                    .describedAs("the zoned image round-trips, sign overpunch included")
                    .isEqualByComparingTo(value);
        }
        assertThat(AccountRecord.MONETARY_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE);
    }

    @Test
    @DisplayName("the xxxP and xxxV attribute planes stay untouched on every screen")
    void theProgrammedSymbolAndValidationPlanesAreNeverWritten() {
        for (byte aid : List.of(CicsAid.DFHENTER, CicsAid.DFHPF9, CicsAid.DFHPF12)) {
            AccountUpdateResponse painted = paintedKeyScreen(aid);
            for (AccountUpdateResponse.ScreenField field
                    : AccountUpdateResponse.ScreenField.values()) {
                AccountUpdateResponse.FieldAttributes quad = painted.attributes(field);
                assertThat(quad.getPs())
                        .describedAs("%s: COACTUPC moves no programmed-symbol byte anywhere",
                                field.psItemName())
                        .isZero();
                assertThat(quad.getValidn())
                        .describedAs("%s: COACTUPC moves no validation byte anywhere",
                                field.validnItemName())
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("a failed date stage does not stop the next, and only the first diagnostic survives")
    void theDateEngineIsARangePerformSoAFailedStageDoesNotStopTheNextOne() {
        EditDateState blankYearGoodRest = editDate("    0413");
        assertThat(blankYearGoodRest.flagsImage())
                .describedAs("year blank, month and day validated anyway - the range perform fell "
                        + "through EDIT-YEAR-CCYY-EXIT into EDIT-MONTH")
                .isEqualTo(flags(EditFlag.BLANK, EditFlag.ISVALID, EditFlag.ISVALID));
        assertThat(blankYearGoodRest.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_YEAR_MUST_BE_SUPPLIED);
        assertThat(blankYearGoodRest.inputError()).isTrue();
        assertThat(blankYearGoodRest.wsEditDateIsValid()).isFalse();

        EditDateState blankYearBadMonth = editDate("    1301");
        assertThat(blankYearBadMonth.flagsImage())
                .describedAs("both stages flagged their own field")
                .isEqualTo(flags(EditFlag.BLANK, EditFlag.NOT_OK, EditFlag.ISVALID));
        assertThat(blankYearBadMonth.returnMessage().trim())
                .describedAs("the year's message, not the month's: IF WS-RETURN-MSG-OFF stopped being "
                        + "true once the year had spoken")
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_YEAR_MUST_BE_SUPPLIED)
                .doesNotContain(AccountDateValidator.MSG_MONTH_MUST_BE_1_TO_12);

        EditDateState badMonthOnly = editDate("20221301");
        assertThat(badMonthOnly.flagsImage())
                .describedAs("with a good year the month speaks for itself")
                .isEqualTo(flags(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.ISVALID));
        assertThat(badMonthOnly.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_MONTH_MUST_BE_1_TO_12);

        EditDateState valid = editDate("20220719");
        assertThat(valid.wsEditDateIsValid()).isTrue();
        assertThat(valid.inputError()).isFalse();
        assertThat(valid.flagsImage())
                .isEqualTo(flags(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.ISVALID));
        assertThat(valid.returnMessage()).isBlank();
    }

    @Test
    @DisplayName("the day/month/year guards fire in order and the leap rule uses 400 only on a century")
    void theLeapYearRuleDividesByFourHundredOnlyForACenturyYear() {
        assertThat(editDate("20240229").wsEditDateIsValid())
                .describedAs("2024 is divisible by four and its last two digits are not zero")
                .isTrue();
        assertThat(editDate("20000229").wsEditDateIsValid())
                .describedAs("2000 is a century year and is divisible by four hundred")
                .isTrue();

        EditDateState commonYear = editDate("20230229");
        assertThat(commonYear.wsEditDateIsValid()).isFalse();
        assertThat(commonYear.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_NOT_A_LEAP_YEAR);
        assertThat(commonYear.flagsImage())
                .describedAs("all three flagged: CSUTLDPY:262 blames the year for the day")
                .isEqualTo(flags(EditFlag.NOT_OK, EditFlag.NOT_OK, EditFlag.NOT_OK));

        EditDateState centuryCommonYear = editDate("19000229");
        assertThat(centuryCommonYear.wsEditDateIsValid())
                .describedAs("1900 is a century year and 1900 modulo 400 is 300, so it is not a leap "
                        + "year - the case a divisor of four alone would get wrong")
                .isFalse();
        assertThat(centuryCommonYear.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_NOT_A_LEAP_YEAR);

        EditDateState thirtyFirstOfApril = editDate("20220431");
        assertThat(thirtyFirstOfApril.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_CANNOT_HAVE_31_DAYS);
        assertThat(thirtyFirstOfApril.flagsImage())
                .describedAs("month and day, not the year")
                .isEqualTo(flags(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.NOT_OK));

        EditDateState thirtiethOfFebruary = editDate("20220230");
        assertThat(thirtiethOfFebruary.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_CANNOT_HAVE_30_DAYS);

        EditDateState thirtyFirstOfFebruary = editDate("20220231");
        assertThat(thirtyFirstOfFebruary.returnMessage().trim())
                .describedAs("2/31 satisfies the first guard as well as February's own, and the first "
                        + "guard is the one at CSUTLDPY:213 - so the generic message wins")
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_CANNOT_HAVE_31_DAYS)
                .doesNotContain(AccountDateValidator.MSG_CANNOT_HAVE_30_DAYS)
                .doesNotContain(AccountDateValidator.MSG_NOT_A_LEAP_YEAR);
    }

    @Test
    @DisplayName("CSLKPCDY's tables, and EDIT-AREA-CODE tests the general-purpose condition")
    void theAreaCodeTableIsCslkpcdyAndTheGeneralPurposeConditionIsTheOneTested() {
        AreaCodeLookup lookup = new AreaCodeLookup(CODEC);

        for (String hit : List.of("201", "202", "212")) {
            assertThat(lookup.isValidGeneralPurposeCode(hit))
                    .describedAs("%s is a general-purpose code, so EDIT-AREA-CODE accepts it", hit)
                    .isTrue();
        }
        assertThat(lookup.isValidGeneralPurposeCode("199"))
                .describedAs("199 appears in none of CSLKPCDY's three tables")
                .isFalse();
        assertThat(lookup.isValidPhoneAreaCode("199")).isFalse();

        for (String easyRecognition : List.of("200", "555", "911", "999")) {
            assertThat(lookup.isValidPhoneAreaCode(easyRecognition))
                    .describedAs("%s is in VALID-PHONE-AREA-CODE", easyRecognition)
                    .isTrue();
            assertThat(lookup.isValidEasyRecognitionAreaCode(easyRecognition)).isTrue();
            assertThat(lookup.isValidGeneralPurposeCode(easyRecognition))
                    .describedAs("%s is NOT in VALID-GENERAL-PURP-CODE, so EDIT-AREA-CODE rejects it "
                            + "even though the wider table contains it", easyRecognition)
                    .isFalse();
        }

        assertThat(lookup.validGeneralPurposeCodes())
                .hasSize(AreaCodeLookup.VALID_GENERAL_PURP_CODE_COUNT)
                .hasSize(410)
                .doesNotContainAnyElementsOf(lookup.validEasyRecognitionAreaCodes());
        assertThat(lookup.validEasyRecognitionAreaCodes())
                .hasSize(AreaCodeLookup.VALID_EASY_RECOG_AREA_CODE_COUNT)
                .hasSize(80);
        assertThat(lookup.validPhoneAreaCodes())
                .describedAs("the two disjoint subsets exhaust the phone-area table")
                .hasSize(AreaCodeLookup.VALID_PHONE_AREA_CODE_COUNT)
                .hasSize(410 + 80)
                .containsAll(lookup.validGeneralPurposeCodes())
                .containsAll(lookup.validEasyRecognitionAreaCodes());

        String storedState = storedCustomerRecord().getCustAddrStateCd();
        String storedZip = storedCustomerRecord().getCustAddrZip();
        assertThat(lookup.validUsStateCodes())
                .hasSize(AreaCodeLookup.VALID_US_STATE_CODE_COUNT)
                .hasSize(56)
                .describedAs("the seeded customer's state is one CSLKPCDY names")
                .contains(storedState);
        assertThat(lookup.isValidStateAndZipCombination(storedState, "27601"))
                .describedAs("CSLKPCDY admits NC27 and NC28")
                .isTrue();
        assertThat(lookup.isValidStateAndZipCombination(storedState, storedZip))
                .describedAs("the seeded customer's own state and zip do not form an admitted "
                        + "combination - custdata.txt is synthetic, and that is a property of the "
                        + "fixture rather than something to correct")
                .isFalse();
        assertThat(lookup.stateZipcodeGroupImage(storedState, "27601"))
                .describedAs("the combination is tested on a four-character group: the state code "
                        + "followed by the first two digits of the zip")
                .startsWith(storedState + "27")
                .hasSize(AreaCodeLookup.STATE_ZIPCODE_GROUP_LENGTH);
    }

    @Test
    @DisplayName("every phone stage runs after an earlier stage jumps, and one message reaches the screen")
    void theStagedPhoneGuardsRunEveryStageAndOnlyTheFirstDiagnosticSurvives() {
        AccountUpdateResponse painted = paintedScreen(
                AccountUpdateRequest.ChangeAction.SHOW_DETAILS,
                Map.of(AccountUpdateRequest.ScreenField.ACCTSID, ACCT_KEY,
                        AccountUpdateRequest.ScreenField.ACSPH1A, REJECTED_AREA_CODE,
                        AccountUpdateRequest.ScreenField.ACSPH1B, NON_NUMERIC_PREFIX,
                        AccountUpdateRequest.ScreenField.ACSPH1C, VALID_LINE_NUMBER),
                CicsAid.DFHENTER);

        assertThat(painted.attributes(AccountUpdateResponse.ScreenField.ACSPH1A).getColour())
                .describedAs("stage one rejected 199 and reddened the area code")
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(painted.value(AccountUpdateResponse.ScreenField.ACSPH1A))
                .describedAs("NOT-OK, not BLANK, so CSSETATY leaves the keyed value in place")
                .isEqualTo(CODEC.movePicX(REJECTED_AREA_CODE,
                        AccountUpdateResponse.ACSPH1A_LENGTH));

        assertThat(painted.attributes(AccountUpdateResponse.ScreenField.ACSPH1B).getColour())
                .describedAs("stage two reddened the prefix, which it could only do by running after "
                        + "stage one's GO TO EDIT-US-PHONE-PREFIX")
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(painted.value(AccountUpdateResponse.ScreenField.ACSPH1B))
                .isEqualTo(CODEC.movePicX(NON_NUMERIC_PREFIX,
                        AccountUpdateResponse.ACSPH1B_LENGTH));

        assertThat(painted.attributes(AccountUpdateResponse.ScreenField.ACSPH1C).getColour())
                .describedAs("stage three ran too, and accepted a valid line number - so the jumps "
                        + "advance through the stages rather than abandoning them")
                .isZero();
        assertThat(painted.value(AccountUpdateResponse.ScreenField.ACSPH1C))
                .isEqualTo(VALID_LINE_NUMBER);

        assertThat(painted.value(AccountUpdateResponse.ScreenField.ERRMSG).trim())
                .describedAs("the account status is edited first at :1472 and is blank, so its message "
                        + "claims WS-RETURN-MSG and neither phone message is ever assigned")
                .isEqualTo(MSG_ACCT_STATUS_MUST_BE_SUPPLIED)
                .doesNotContain("Area code")
                .doesNotContain("Prefix");

        assertThat(painted.attributes(AccountUpdateResponse.ScreenField.ACSTTUS).getColour())
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(painted.value(AccountUpdateResponse.ScreenField.ACSTTUS))
                .describedAs("BLANK, so CSSETATY assigns the asterisk as well as the colour")
                .startsWith(FieldAttributeSetter.ASTERISK);
        assertThat(painted.value(AccountUpdateResponse.ScreenField.INFOMSG))
                .describedAs("3250-SETUP-INFOMSG's prompt-for-changes text, :471")
                .isEqualTo(CODEC.movePicX(INFO_PROMPT_FOR_CHANGES,
                        AccountUpdateResponse.INFOMSG_LENGTH));
    }

    @Test
    @DisplayName("3000-SEND-MAP is reached after the clear and before the re-entry set, on both arms")
    void theTwoSendMapArmsInitialiseBeforePaintingAndSetReenterAfter() {
        AccountUpdateResponse afterCompletedUpdate = paintedScreen(
                AccountUpdateRequest.ChangeAction.CHANGES_OKAYED_AND_DONE,
                Map.of(AccountUpdateRequest.ScreenField.ACCTSID, ACCT_KEY), CicsAid.DFHENTER);

        assertThat(outputItems(afterCompletedUpdate))
                .describedAs("the completed-update arm at :980-989 repaints exactly the screen the "
                        + "fresh-entry arm at :964-975 does - the one case15 and case16 pin - because "
                        + "both INITIALIZE before performing 3000-SEND-MAP. Forty-two LOW-VALUES data "
                        + "fields and a blank error line, from a commarea that arrived carrying a "
                        + "completed update")
                .isEqualTo(promptScreen(null, null));

        for (AccountUpdateResponse.ScreenField field
                : AccountUpdateResponse.ScreenField.values()) {
            assertThat(afterCompletedUpdate.attributes(field).getColour())
                    .describedAs("%s must not be reddened: SET CDEMO-PGM-ENTER at :983 runs before the "
                            + "paint, so CSSETATY's REENTER test is false for every one of its "
                            + "thirty-nine sites", field.label())
                    .isZero();
        }

        assertThat(afterCompletedUpdate.getNavigationContext().pgmContext())
                .describedAs("SET CDEMO-PGM-REENTER at :987 runs after the paint, so the returned "
                        + "context says re-enter while the screen it accompanies was painted on the "
                        + "enter path")
                .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        assertThat(token(afterCompletedUpdate.getNextMap()))
                .describedAs("a map was named, so 3000-SEND-MAP was reached")
                .isEqualTo(THIS_MAP);
        assertThat(token(afterCompletedUpdate.getNextProgram()))
                .describedAs("GO TO COMMON-RETURN at :989, not an XCTL")
                .isNull();
    }

    private static AccountRecord storedAccountRecordWith(String groupId) {
        String row = fixtureRow("acctdata.txt", AccountRecord.RECORD_LENGTH, 0);
        String patched = row.substring(0, AccountRecord.ACCT_GROUP_ID_OFFSET)
                + CODEC.movePicX(groupId, AccountRecord.ACCT_GROUP_ID_LENGTH)
                + row.substring(AccountRecord.ACCT_GROUP_ID_OFFSET
                        + AccountRecord.ACCT_GROUP_ID_LENGTH);
        return AccountRecord.decode(patched, FIXTURE_CHARSET);
    }

    private static AccountUpdateService serviceOverEmptyDatasets() {
        return new AccountUpdateService(strictAccountRepository(), strictCustomerRepository(),
                unitOfWork("structural"));
    }

    private static WriteResult writeProcessingWith(boolean custLockFails,
                                                   AccountRepository.WriteResult forcedAcctWrite,
                                                   CustomerRepository.WriteResult forcedCustWrite) {
        List<String> accountRows = new ArrayList<>(
                List.of(fixtureRow("acctdata.txt", AccountRecord.RECORD_LENGTH, 0)));
        List<String> customerRows = new ArrayList<>(
                List.of(fixtureRow("custdata.txt", CustomerRecord.RECORD_LENGTH, 0)));

        AccountRepository accounts = fixtureAccountRepository(accountRows);
        if (forcedAcctWrite != null) {
            Mockito.doReturn(forcedAcctWrite).when(accounts).rewrite(ArgumentMatchers.any());
        }
        CustomerRepository customers = custLockFails
                ? fixtureCustomerRepository(new ArrayList<>())
                : fixtureCustomerRepository(customerRows);
        if (forcedCustWrite != null) {
            Mockito.doReturn(forcedCustWrite).when(customers)
                    .rewriteHeld(ArgumentMatchers.anyString(),
                            ArgumentMatchers.any(CustomerRecord.class));
        }

        AccountUpdateDetails oldDetails = new AccountUpdateDetails(DetailGroup.OLD,
                storedAccountSnapshot(), storedCustomerSnapshot());
        AccountUpdateDetails newDetails = new AccountUpdateDetails(DetailGroup.NEW,
                newAccountData(), newCustomerData());

        return new AccountUpdateService(accounts, customers, unitOfWork("structural"))
                .writeProcessing(ACCT_KEY, NavigationContext.empty().withCustId(CUST_KEY),
                        oldDetails, newDetails, AccountUpdateService.RETURN_MESSAGE_OFF, CODEC);
    }

    private static EditDateState editDate(String ccyymmdd) {
        AccountDateValidator validator = new AccountDateValidator(CODEC, new DateUtilityJob(),
                ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK)));
        EditDateState state = validator.newState();
        state.setEditVariableName(EDIT_FIELD_NAME);
        state.setEditDateCcyymmdd(ccyymmdd);
        state.setReturnMsgOff();
        validator.editDateCcyymmddThruExit(state);
        return state;
    }

    private static String flags(EditFlag year, EditFlag month, EditFlag day) {
        return String.valueOf(new char[] {year.flagByte(), month.flagByte(), day.flagByte()});
    }

    private static AccountUpdateResponse paintedScreen(
            String changeAction,
            Map<AccountUpdateRequest.ScreenField, String> keyed,
            byte aid) {
        Clock clock = ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK));
        AccountUpdateController controller = new AccountUpdateController(
                unreachableAccountRepository(), unreachableCardXrefRepository(),
                unreachableCustomerRepository(),
                new AccountUpdateService(unreachableAccountRepository(),
                        unreachableCustomerRepository(), unitOfWork("structural")),
                new AccountDateValidator(CODEC, new DateUtilityJob(), clock),
                new AreaCodeLookup(CODEC), clock, ConversationStateSealFixture.seal(), FIXTURE_CHARSET);

        AccountUpdateRequest request = AccountUpdateRequest.initial()
                .withCommArea(AccountUpdateRequest.CommArea.initialised()
                        .withChangeAction(new AccountUpdateRequest.ChangeAction(changeAction)))
                .withNavigationContext(NavigationContext.empty()
                        .withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM).withPgmReenter());
        for (Map.Entry<AccountUpdateRequest.ScreenField, String> entry : keyed.entrySet()) {
            request = request.withValue(entry.getKey(),
                    CODEC.movePicX(entry.getValue(), entry.getKey().length()));
        }

        String uriKey = keyed.getOrDefault(AccountUpdateRequest.ScreenField.ACCTSID, ACCT_KEY);
        ScreenResponse<AccountUpdateResponse> body = controller.updateAccount(uriKey, request, null,
                EIBCALEN_FULL, Integer.valueOf(Byte.toUnsignedInt(aid))).getBody();
        return Objects.requireNonNull(body, "every path answers with a body").screen();
    }

    private static AccountUpdateResponse paintedKeyScreen(byte aid) {
        Clock clock = ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK));
        AccountUpdateController controller = new AccountUpdateController(
                unreachableAccountRepository(), unreachableCardXrefRepository(),
                unreachableCustomerRepository(),
                new AccountUpdateService(unreachableAccountRepository(),
                        unreachableCustomerRepository(), unitOfWork("structural")),
                new AccountDateValidator(CODEC, new DateUtilityJob(), clock),
                new AreaCodeLookup(CODEC), clock, ConversationStateSealFixture.seal(), FIXTURE_CHARSET);

        NavigationContext context = NavigationContext.empty()
                .withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM).withPgmReenter();
        ScreenResponse<AccountUpdateResponse> body = controller.updateAccount(BLANK_ACCT,
                requestFrom(context, Map.of("ACCTSIDI", BLANK_ACCT), BLANK_ACCT), null,
                EIBCALEN_FULL, Integer.valueOf(Byte.toUnsignedInt(aid))).getBody();
        return Objects.requireNonNull(body, "every path answers with a body").screen();
    }

    private static Map<String, String> paintKeyScreen(byte aid) {
        return outputItems(paintedKeyScreen(aid));
    }

    private static UnitOutcome serviceUnit(Invocation invocation,
                                           AccountUpdateDetails oldDetails,
                                           AccountUpdateDetails newDetails) {
        SeededDataset seededAccounts = invocation.dataset(ACCTDAT);
        SeededDataset seededCustomers = invocation.dataset(CUSTDAT);
        List<String> accountRows = new ArrayList<>(seededAccounts.rows());
        List<String> customerRows = new ArrayList<>(seededCustomers.rows());
        ForcedOutcome forcedRewrite =
                invocation.hasForcedOutcome(RepositoryOperation.REWRITE)
                        ? invocation.forcedOutcome(RepositoryOperation.REWRITE)
                        : null;

        AccountUpdateService service = new AccountUpdateService(
                fixtureAccountRepository(accountRows, forcedRewrite),
                fixtureCustomerRepository(customerRows, forcedRewrite),
                unitOfWork(invocation.caseId()));

        WriteResult result = service.writeProcessing(accountKeyOf(invocation),
                navigationContextFrom(invocation.commarea()), oldDetails, newDetails,
                returnMessageOf(invocation), invocation.codec());

        UnitOutcome.Builder recorder = invocation.recorder();
        if (result.isRewritten()) {
            recorder.wrote(ACCTDAT, AccountRecord.LAYOUT, result.acctUpdateRecordImage()
                    .orElseThrow(() -> new IllegalStateException("A rewritten result carries the "
                            + "300-byte ACCT-UPDATE-RECORD image it wrote")));
            recorder.wrote(CUSTDAT, CustomerRecord.LAYOUT, result.custUpdateRecordImage()
                    .orElseThrow(() -> new IllegalStateException("A rewritten result carries the "
                            + "500-byte CUST-UPDATE-RECORD image it wrote")));
            recorder.finalState(ACCTDAT, AccountRecord.LAYOUT, accountRows);
            recorder.finalState(CUSTDAT, CustomerRecord.LAYOUT, customerRows);
        } else {
            recorder.openedWithoutWriting(ACCTDAT, AccountRecord.LAYOUT);
            recorder.openedWithoutWriting(CUSTDAT, CustomerRecord.LAYOUT);
            recorder.finalStateUnchanged(seededAccounts, AccountRecord.LAYOUT);
            recorder.finalStateUnchanged(seededCustomers, CustomerRecord.LAYOUT);
        }
        for (Map.Entry<String, SeededDataset> seeded : invocation.datasets().entrySet()) {
            if (ACCTDAT.equals(seeded.getKey()) || CUSTDAT.equals(seeded.getKey())) {
                continue;
            }
            RecordLayout layout = layoutOf(seeded.getKey());
            recorder.openedWithoutWriting(seeded.getKey(), layout);
            recorder.finalStateUnchanged(seeded.getValue(), layout);
        }
        recorder.display(result.returnMessage());
        recorder.returnCode(0);
        return recorder.build();
    }

    private static UnitOutcome controllerUnit(Invocation invocation, String acctId,
                                             NavigationContext context) {
        RecordingDateUtility dateUtility = new RecordingDateUtility();
        AccountUpdateController controller = new AccountUpdateController(
                screenAccountRepository(invocation),
                screenCardXrefRepository(invocation),
                screenCustomerRepository(invocation),
                new AccountUpdateService(unreachableAccountRepository(),
                        unreachableCustomerRepository(), unitOfWork(invocation.caseId())),
                new AccountDateValidator(invocation.codec(), dateUtility, invocation.clock()),
                new AreaCodeLookup(invocation.codec()), invocation.clock(),
                ConversationStateSealFixture.seal(), invocation.codec().charset());

        ScreenResponse<AccountUpdateResponse> body = controller.updateAccount(acctId,
                screenRequestFrom(invocation, context, acctId), null, invocation.eibcalen(),
                Integer.valueOf(Byte.toUnsignedInt(aidByte(invocation.aid())))).getBody();
        AccountUpdateResponse painted = Objects.requireNonNull(body,
                "COACTUPC ends in EXEC CICS XCTL at :955 or EXEC CICS RETURN at :1013 on every path, "
                        + "so the handler always answers with a body").screen();

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.response(observed(painted, body));
        for (Map.Entry<String, SeededDataset> seeded : invocation.datasets().entrySet()) {
            RecordLayout layout = layoutOf(seeded.getKey());
            recorder.openedWithoutWriting(seeded.getKey(), layout);
            recorder.finalStateUnchanged(seeded.getValue(), layout);
        }
        for (String message : dateUtility.results()) {
            recorder.message(new EmittedMessage(MessageChannel.WS_MESSAGE_80, message));
        }
        recorder.returnCode(0);
        return recorder.build();
    }

    private static RecordLayout layoutOf(String dataset) {
        return switch (dataset) {
            case ACCTDAT -> AccountRecord.LAYOUT;
            case CUSTDAT -> CustomerRecord.LAYOUT;
            case CARDDAT -> CardRecord.LAYOUT;
            case CXACAIX -> CardXrefRecord.LAYOUT;
            default -> throw new IllegalStateException("A COACTUPC case seeded \"" + dataset
                    + "\", which is not one of the five datasets the program names. Its CICS file "
                    + "literals are ACCTDAT, CARDDAT, CARDAIX, CUSTDAT and CXACAIX, and the four with a "
                    + "record layout here are the four a case can seed.");
        };
    }

    private static final class RecordingDateUtility extends DateUtilityJob {
        private final List<String> results = new ArrayList<>(4);

        @Override
        public DateValidationResult validateDate(String lsDate, String lsDateFormat) {
            DateValidationResult result = super.validateDate(lsDate, lsDateFormat);
            results.add(result.message());
            return result;
        }

        List<String> results() {
            return List.copyOf(results);
        }
    }

    private static AccountRepository screenAccountRepository(Invocation invocation) {
        AccountRepository repository = strictAccountRepository();
        List<String> rows = seededRows(invocation, ACCTDAT);
        OptionalInt drivenResp = callSiteResp(invocation, ACCTDAT);
        Mockito.doAnswer(read -> {
            if (drivenResp.isPresent()) {
                return AccountRepository.ReadResult.of(AccountRepository.PERMANENT_ERROR_STATUS,
                        CicsResponse.reported(drivenResp.getAsInt(), FileStatus.NO_REASON_CODE));
            }
            String key = CODEC.movePic9(((Number) read.getArgument(0)).longValue(),
                    AccountRecord.ACCT_ID_LENGTH);
            for (String row : rows) {
                if (row.startsWith(key)) {
                    return AccountRepository.ReadResult.found(
                            AccountRecord.decode(row, FIXTURE_CHARSET));
                }
            }
            return AccountRepository.ReadResult.notFound();
        }).when(repository).readByKey(ArgumentMatchers.anyLong());
        return repository;
    }

    private static CardXrefRepository screenCardXrefRepository(Invocation invocation) {
        List<String> rows = seededRows(invocation, CXACAIX);
        OptionalInt drivenResp = callSiteResp(invocation, CXACAIX);
        CardXrefRepository repository = unreachableCardXrefRepository();
        Mockito.doAnswer(read -> {
            if (drivenResp.isPresent()) {
                return CardXrefRepository.ReadResult.other(
                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                        CardXrefRepository.PERMANENT_ERROR_STATUS);
            }
            String accountKey = ((String) read.getArgument(0)).trim();
            for (String row : rows) {
                CardXrefRecord record = CardXrefRecord.decode(
                        picX(row, CardXrefRecord.RECORD_LENGTH).getBytes(FIXTURE_CHARSET),
                        FIXTURE_CHARSET);
                if (CODEC.movePic9(record.xrefAcctId(), CardXrefRecord.XREF_ACCT_ID_LENGTH)
                        .equals(CODEC.movePic9(Long.parseLong(accountKey),
                                CardXrefRecord.XREF_ACCT_ID_LENGTH))) {
                    return CardXrefRepository.ReadResult.found(
                            CardXrefRepository.ALTERNATE_INDEX_DD_NAME, record,
                            picX(row, CardXrefRecord.RECORD_LENGTH));
                }
            }
            return CardXrefRepository.ReadResult.notFound(
                    CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }).when(repository).readByAccountIdViaAltIndex(ArgumentMatchers.anyString());
        return repository;
    }

    private static CustomerRepository screenCustomerRepository(Invocation invocation) {
        List<String> rows = seededRows(invocation, CUSTDAT);
        OptionalInt drivenResp = callSiteResp(invocation, CUSTDAT);
        CustomerRepository repository = strictCustomerRepository();
        Mockito.doAnswer(read -> {
            if (drivenResp.isPresent()) {
                return CustomerRepository.ReadResult.of(CustomerRepository.PERMANENT_ERROR_STATUS);
            }
            String key = ((String) read.getArgument(0)).trim();
            for (String row : rows) {
                if (row.startsWith(CODEC.movePic9(Long.parseLong(key),
                        CustomerRepository.KEY_LENGTH))) {
                    return CustomerRepository.ReadResult.found(
                            CustomerRecord.decode(row, FIXTURE_CHARSET), row);
                }
            }
            return CustomerRepository.ReadResult.notFound();
        }).when(repository).readByKey(ArgumentMatchers.anyString());
        return repository;
    }

    private static List<String> seededRows(Invocation invocation, String dataset) {
        return invocation.hasDataset(dataset) ? invocation.dataset(dataset).rows() : List.of();
    }

    private static OptionalInt callSiteResp(Invocation invocation, String dataset) {
        return invocation.stimulus().callSite(dataset.trim())
                .map(outcome -> outcome.resp() == null ? OptionalInt.of(FileStatus.NOTOPEN)
                        : OptionalInt.of(outcome.resp()))
                .orElse(OptionalInt.empty());
    }

    private static ObservedResponse observed(AccountUpdateResponse painted,
                                             ScreenResponse<AccountUpdateResponse> body) {
        String nextProgram = token(painted.getNextProgram());
        String nextMapset = token(painted.getNextMapset());
        String nextMap = token(painted.getNextMap());

        List<ObservedSend> sends = new ArrayList<>(1);
        if (nextMap != null) {
            sends.add(new ObservedSend(outputItems(painted), attributeMnemonics(painted)));
        }

        String cursorStem = body.screenMetadata() == null ? null : body.screenMetadata().cursorField();
        String cursorField = cursorStem == null || cursorStem.isBlank() ? null : cursorStem + "L";

        Termination termination = nextProgram != null && sends.isEmpty()
                ? Termination.XCTL
                : Termination.RETURN_TRANSID;

        return new ObservedResponse(nextProgram, nextMapset, nextMap,
                navigationImage(painted.getNavigationContext()), sends, cursorField, termination);
    }

    private static Map<String, String> outputItems(AccountUpdateResponse painted) {
        Map<String, String> items = new LinkedHashMap<>();
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            items.put(field.symbolicItemName(),
                    picX(painted.value(field), field.length()));
        }
        return Map.copyOf(items);
    }

    private static Map<String, String> attributeMnemonics(AccountUpdateResponse painted) {
        Map<String, String> items = new LinkedHashMap<>();
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            AccountUpdateResponse.FieldAttributes quad = painted.attributes(field);
            items.put(field.colourItemName(), colourMnemonic(field.colourItemName(),
                    quad.getColour()));
            items.put(field.hilightItemName(), highlightMnemonic(field.hilightItemName(),
                    quad.getHilight()));
        }
        return Map.copyOf(items);
    }

    private static String colourMnemonic(String item, byte colour) {
        String mnemonic = BmsAttributes.COLOUR_MNEMONICS.get(colour);
        if (mnemonic == null) {
            throw new IllegalStateException(unnamedAttribute(item, colour, "extended-colour"));
        }
        return mnemonic;
    }

    private static String highlightMnemonic(String item, byte highlight) {
        String mnemonic = BmsAttributes.HIGHLIGHT_MNEMONICS.get(highlight);
        if (mnemonic != null) {
            return mnemonic;
        }
        String fieldAttribute = BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.get(highlight);
        if (fieldAttribute != null) {
            return fieldAttribute;
        }
        throw new IllegalStateException(unnamedAttribute(item, highlight,
                "extended-highlighting or field-attribute"));
    }

    private static String unnamedAttribute(String item, byte attribute, String section) {
        return PROGRAM + " moved 0x" + Integer.toHexString(Byte.toUnsignedInt(attribute)) + " into "
                + item + ", which DFHBMSCA's " + section + " table does not name. The copybook is "
                + "IBM-supplied and absent from this repository, so common.BmsAttributes is the single "
                + "reproduction of it and an unnamed value means the translation invented one.";
    }

    private static AccountData storedAccountSnapshot() {
        return new AccountData(Long.parseLong(ACCT_KEY), STORED_STATUS,
                STORED_CURR_BAL, STORED_CREDIT_LIMIT, STORED_CASH_LIMIT,
                year(STORED_OPEN_DATE), month(STORED_OPEN_DATE), day(STORED_OPEN_DATE),
                year(STORED_EXPIRAION_DATE), month(STORED_EXPIRAION_DATE), day(STORED_EXPIRAION_DATE),
                year(STORED_REISSUE_DATE), month(STORED_REISSUE_DATE), day(STORED_REISSUE_DATE),
                STORED_CYC_CREDIT, STORED_CYC_DEBIT, STORED_GROUP_ID);
    }

    private static CustomerData storedCustomerSnapshot() {
        CustomerRecord stored = storedCustomerRecord();
        String dob = stored.getCustDobYyyyMmDd();
        return new CustomerData(stored.getCustId(), stored.getCustFirstName(),
                stored.getCustMiddleName(), stored.getCustLastName(), stored.getCustAddrLine1(),
                stored.getCustAddrLine2(), stored.getCustAddrLine3(), stored.getCustAddrStateCd(),
                stored.getCustAddrCountryCd(), stored.getCustAddrZip(), stored.getCustPhoneNum1(),
                stored.getCustPhoneNum2(), stored.getCustSsn(), stored.getCustGovtIssuedId(),
                year(dob), month(dob), day(dob), stored.getCustEftAccountId(),
                stored.getCustPriCardHolderInd(), stored.getCustFicoCreditScore());
    }

    private static CustomerRecord storedCustomerRecord() {
        return CustomerRecord.decode(fixtureRow("custdata.txt", CustomerRecord.RECORD_LENGTH, 0),
                FIXTURE_CHARSET);
    }

    private static AccountData newAccountData() {
        return new AccountData(Long.parseLong(ACCT_KEY), NEW_STATUS,
                NEW_CURR_BAL, NEW_CREDIT_LIMIT, NEW_CASH_LIMIT,
                year(NEW_OPEN_DATE), month(NEW_OPEN_DATE), day(NEW_OPEN_DATE),
                year(NEW_EXPIRAION_DATE), month(NEW_EXPIRAION_DATE), day(NEW_EXPIRAION_DATE),
                year(NEW_REISSUE_DATE), month(NEW_REISSUE_DATE), day(NEW_REISSUE_DATE),
                NEW_CYC_CREDIT, NEW_CYC_DEBIT, NEW_GROUP_ID);
    }

    private static CustomerData newCustomerData() {
        return storedCustomerSnapshot();
    }

    private static AccountData withGroupId(AccountData data, String groupId) {
        return new AccountData(data.acctId(), data.activeStatus(), data.currBal(),
                data.creditLimit(), data.cashCreditLimit(), data.openYear(), data.openMon(),
                data.openDay(), data.expYear(), data.expMon(), data.expDay(), data.reissueYear(),
                data.reissueMon(), data.reissueDay(), data.currCycCredit(), data.currCycDebit(),
                CODEC.movePicX(groupId, AccountRecord.ACCT_GROUP_ID_LENGTH));
    }

    private static CustomerData withCustId(CustomerData data, int custId) {
        return new CustomerData(custId, data.firstName(), data.middleName(), data.lastName(),
                data.addrLine1(), data.addrLine2(), data.addrLine3(), data.addrStateCd(),
                data.addrCountryCd(), data.addrZip(), data.phoneNum1(), data.phoneNum2(),
                data.ssn(), data.govtIssuedId(), data.dobYear(), data.dobMon(), data.dobDay(),
                data.eftAccountId(), data.priHolderInd(), data.ficoScore());
    }

    private static CustomerData foldFirstNameToUpper(CustomerData data) {
        return new CustomerData(data.custId(), AccountUpdateService.upperCase(data.firstName()),
                data.middleName(), data.lastName(), data.addrLine1(), data.addrLine2(),
                data.addrLine3(), data.addrStateCd(), data.addrCountryCd(), data.addrZip(),
                data.phoneNum1(), data.phoneNum2(), data.ssn(), data.govtIssuedId(),
                data.dobYear(), data.dobMon(), data.dobDay(), data.eftAccountId(),
                data.priHolderInd(), data.ficoScore());
    }

    private static String year(String date) {
        return date.substring(0, AccountRecord.YEAR_LENGTH);
    }

    private static String month(String date) {
        return date.substring(AccountRecord.MONTH_START - 1,
                AccountRecord.MONTH_START - 1 + AccountRecord.MONTH_LENGTH);
    }

    private static String day(String date) {
        return date.substring(AccountRecord.DAY_START - 1,
                AccountRecord.DAY_START - 1 + AccountRecord.DAY_LENGTH);
    }

    private static String accountUpdateImage(AccountData data) {
        String image = CODEC.movePic9(data.acctId(), AccountRecord.ACCT_ID_LENGTH)
                + CODEC.movePicX(data.activeStatus(), AccountRecord.ACCT_ACTIVE_STATUS_LENGTH)
                + monetary(data.currBal())
                + monetary(data.creditLimit())
                + monetary(data.cashCreditLimit())
                + CODEC.movePicX(storedDate(data.openYear(), data.openMon(), data.openDay()),
                        AccountRecord.ACCT_OPEN_DATE_LENGTH)
                + CODEC.movePicX(storedDate(data.expYear(), data.expMon(), data.expDay()),
                        AccountRecord.ACCT_EXPIRAION_DATE_LENGTH)
                + CODEC.movePicX(storedDate(data.reissueYear(), data.reissueMon(), data.reissueDay()),
                        AccountRecord.ACCT_REISSUE_DATE_LENGTH)
                + monetary(data.currCycCredit())
                + monetary(data.currCycDebit())
                + CODEC.movePicX(data.groupId(), AccountRecord.ACCT_GROUP_ID_LENGTH)
                + " ".repeat(AccountUpdateService.ACCT_UPDATE_FILLER_LENGTH);
        if (image.length() != AccountRecord.RECORD_LENGTH) {
            throw new IllegalStateException("An ACCT-UPDATE-RECORD image composed from the spans "
                    + "app/cbl/COACTUPC.cbl:418-433 declares came out " + image.length()
                    + " characters wide where the record is " + AccountRecord.RECORD_LENGTH
                    + ". The composition above is that declaration read out loud, so a mismatch here is "
                    + "a transcription error in this class rather than a parity difference.");
        }
        return image;
    }

    private static String fullNewAccountImage() {
        return accountUpdateImage(newAccountData());
    }

    private static String fullNewCustomerImage() {
        String image = fixtureRow("custdata.txt", CustomerRecord.RECORD_LENGTH, 0);
        if (image.length() != CustomerRecord.RECORD_LENGTH) {
            throw new IllegalStateException("custdata.txt row 1 is " + image.length()
                    + " characters where CVCUS01Y declares " + CustomerRecord.RECORD_LENGTH);
        }
        return image;
    }

    private static String monetary(BigDecimal value) {
        return CODEC.encodeSignedScaled(value, AccountRecord.MONETARY_INTEGER_DIGITS,
                CobolDecimal.MONETARY_SCALE);
    }

    private static String storedDate(String year, String month, String day) {
        return AccountUpdateService.composeStoredDate(year, month, day, CODEC);
    }

    private static List<EmittedMessage> returnMessage(String text) {
        return List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                CODEC.movePicX(text, AccountUpdateService.RETURN_MESSAGE_LENGTH)));
    }

    private static String fixtureRow(String fixture, int recordWidth, int rowIndex) {
        String resource = DatasetInput.FIXTURE_ROOT + fixture;
        try (java.io.InputStream stream =
                     COACTUPCParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("The fixture " + resource + " is not on the test "
                        + "classpath. It is derived from app/data/ASCII/" + fixture + ", which is a "
                        + "reference input this migration never writes.");
            }
            String all = new String(stream.readAllBytes(), FIXTURE_CHARSET)
                    .replace("\r", "").replace("\n", "");
            int from = rowIndex * recordWidth;
            if (all.length() < from + recordWidth) {
                throw new IllegalStateException(resource + " holds " + all.length()
                        + " characters, which is fewer than the " + (from + recordWidth)
                        + " that row " + rowIndex + " at " + recordWidth + " bytes needs.");
            }
            return all.substring(from, from + recordWidth);
        } catch (java.io.IOException problem) {
            throw new IllegalStateException("The fixture " + resource + " could not be read", problem);
        }
    }

    private static Map<String, String> promptScreen(String acctsid, String errmsg) {
        Map<String, String> screen = new LinkedHashMap<>();
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            screen.put(field.symbolicItemName(), lowValues(field.length()));
        }
        screen.put(AccountUpdateResponse.ScreenField.TRNNAME.symbolicItemName(), THIS_TRANID);
        screen.put(AccountUpdateResponse.ScreenField.TITLE01.symbolicItemName(), TITLE01);
        screen.put(AccountUpdateResponse.ScreenField.CURDATE.symbolicItemName(), PINNED_DATE_IMAGE);
        screen.put(AccountUpdateResponse.ScreenField.PGMNAME.symbolicItemName(), THIS_PGM);
        screen.put(AccountUpdateResponse.ScreenField.TITLE02.symbolicItemName(), TITLE02);
        screen.put(AccountUpdateResponse.ScreenField.CURTIME.symbolicItemName(), PINNED_TIME_IMAGE);
        screen.put(AccountUpdateResponse.ScreenField.INFOMSG.symbolicItemName(),
                INFO_PROMPT_FOR_ACCT);
        screen.put(AccountUpdateResponse.ScreenField.ERRMSG.symbolicItemName(),
                CODEC.movePicX(errmsg == null ? "" : errmsg, AccountUpdateResponse.ERRMSG_LENGTH));
        screen.put(AccountUpdateResponse.ScreenField.FKEYS.symbolicItemName(), FKEYS_LEGEND);
        screen.put(AccountUpdateResponse.ScreenField.FKEY05.symbolicItemName(), FKEY05_LEGEND);
        screen.put(AccountUpdateResponse.ScreenField.FKEY12.symbolicItemName(), FKEY12_LEGEND);
        if (acctsid != null) {
            screen.put(AccountUpdateResponse.ScreenField.ACCTSID.symbolicItemName(),
                    CODEC.movePicX(acctsid, AccountUpdateResponse.ACCTSID_LENGTH));
        }
        if (screen.size() != AccountUpdateResponse.ScreenField.values().length) {
            throw new IllegalStateException("A COACTUP screen expectation named " + screen.size()
                    + " fields where app/cpy-bms/COACTUP.CPY declares "
                    + AccountUpdateResponse.ScreenField.values().length + " xxxI items. A name that "
                    + "does not match a declared field would be compared against nothing.");
        }
        return Map.copyOf(screen);
    }

    private static String lowValues(int length) {
        return String.valueOf('\u0000').repeat(length);
    }

    private static NavigationContext navigationContextFrom(Map<String, String> declared) {
        NavigationContext initial = NavigationContext.empty();
        if (declared.isEmpty()) {
            return initial;
        }
        return new NavigationContext(
                text(declared, NavigationContext.FROM_TRANID_FIELD, initial.fromTranid()),
                text(declared, NavigationContext.FROM_PROGRAM_FIELD, initial.fromProgram()),
                text(declared, NavigationContext.TO_TRANID_FIELD, initial.toTranid()),
                text(declared, NavigationContext.TO_PROGRAM_FIELD, initial.toProgram()),
                text(declared, NavigationContext.USER_ID_FIELD, initial.userId()),
                text(declared, NavigationContext.USER_TYPE_FIELD, initial.userType()),
                (int) number(declared, NavigationContext.PGM_CONTEXT_FIELD, initial.pgmContext()),
                (int) number(declared, NavigationContext.CUST_ID_FIELD, initial.custId()),
                text(declared, NavigationContext.CUST_FNAME_FIELD, initial.custFname()),
                text(declared, NavigationContext.CUST_MNAME_FIELD, initial.custMname()),
                text(declared, NavigationContext.CUST_LNAME_FIELD, initial.custLname()),
                number(declared, NavigationContext.ACCT_ID_FIELD, initial.acctId()),
                text(declared, NavigationContext.ACCT_STATUS_FIELD, initial.acctStatus()),
                number(declared, NavigationContext.CARD_NUM_FIELD, initial.cardNum()),
                text(declared, NavigationContext.LAST_MAP_FIELD, initial.lastMap()),
                text(declared, NavigationContext.LAST_MAPSET_FIELD, initial.lastMapset()));
    }

    private static String text(Map<String, String> declared, String field, String fallback) {
        String value = declared.get(field);
        return value == null ? fallback : value;
    }

    private static long number(Map<String, String> declared, String field, long fallback) {
        String value = declared.get(field);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Long.parseLong(value.trim());
    }

    private static Map<String, String> navigationImage(NavigationContext context) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put(NavigationContext.FROM_TRANID_FIELD, context.fromTranid());
        image.put(NavigationContext.FROM_PROGRAM_FIELD, context.fromProgram());
        image.put(NavigationContext.TO_TRANID_FIELD, context.toTranid());
        image.put(NavigationContext.TO_PROGRAM_FIELD, context.toProgram());
        image.put(NavigationContext.USER_ID_FIELD, context.userId());
        image.put(NavigationContext.USER_TYPE_FIELD, context.userType());
        image.put(NavigationContext.PGM_CONTEXT_FIELD,
                CODEC.movePic9(context.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
        image.put(NavigationContext.CUST_ID_FIELD,
                CODEC.movePic9(context.custId(), NavigationContext.CUST_ID_LENGTH));
        image.put(NavigationContext.CUST_FNAME_FIELD, context.custFname());
        image.put(NavigationContext.CUST_MNAME_FIELD, context.custMname());
        image.put(NavigationContext.CUST_LNAME_FIELD, context.custLname());
        image.put(NavigationContext.ACCT_ID_FIELD,
                CODEC.movePic9(context.acctId(), NavigationContext.ACCT_ID_LENGTH));
        image.put(NavigationContext.ACCT_STATUS_FIELD, context.acctStatus());
        image.put(NavigationContext.CARD_NUM_FIELD,
                CODEC.movePic9(context.cardNum(), NavigationContext.CARD_NUM_LENGTH));
        image.put(NavigationContext.LAST_MAP_FIELD, context.lastMap());
        image.put(NavigationContext.LAST_MAPSET_FIELD, context.lastMapset());
        return Map.copyOf(image);
    }

    private static AccountUpdateRequest requestFrom(NavigationContext context,
                                                    Map<String, String> mapFields,
                                                    String acctId) {
        if (context == null) {
            return null;
        }
        AccountUpdateRequest request = AccountUpdateRequest.initial()
                .withValue(AccountUpdateRequest.ScreenField.ACCTSID,
                        CODEC.movePicX(mapFields.getOrDefault("ACCTSIDI", acctId),
                                AccountUpdateRequest.ACCTSID_LENGTH))
                .withNavigationContext(context);
        for (Map.Entry<String, String> received : mapFields.entrySet()) {
            String item = received.getKey();
            if ("ACCTSIDI".equals(item)) {
                continue;
            }
            AccountUpdateRequest.ScreenField field = AccountUpdateRequest.ScreenField.ofLabel(
                    item.substring(0, item.length() - 1));
            request = request.withValue(field,
                    CODEC.movePicX(received.getValue(), field.length()));
        }
        return request;
    }

    private static AccountUpdateRequest screenRequestFrom(Invocation invocation,
                                                          NavigationContext context, String acctId) {
        AccountUpdateRequest request = requestFrom(context, invocation.mapFields(), acctId);
        if (request == null) {
            return null;
        }
        return invocation.commarea().containsKey(PROGRAM_AREA_KEY)
                ? request.withCommArea(programAreaOf(invocation))
                : request;
    }

    private static byte aidByte(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) {
            return CicsAid.DFHENTER;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("\"" + mnemonic + "\" is not a DFHAID mnemonic. The "
                + "copybook is IBM-supplied and absent from this repository, so common.CicsAid is the "
                + "single reproduction of it and the permitted names come from there.");
    }

    private static String token(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static AccountRepository fixtureAccountRepository(List<String> rows) {
        return fixtureAccountRepository(rows, null);
    }

    private static String returnMessageOf(Invocation invocation) {
        String declared = invocation.stimulus().linkageValue(LINKAGE_RETURN_MSG).orElse(null);
        if (declared == null) {
            return AccountUpdateService.RETURN_MESSAGE_OFF;
        }
        if (declared.length() != AccountUpdateService.RETURN_MESSAGE_LENGTH) {
            throw new IllegalStateException("Case " + invocation.program() + '/' + invocation.caseId()
                    + " declares a " + LINKAGE_RETURN_MSG + " of " + declared.length()
                    + " characters where WS-RETURN-MSG is PIC X("
                    + AccountUpdateService.RETURN_MESSAGE_LENGTH
                    + ") at app/cbl/COACTUPC.cbl:479. A message of another width is not a state the "
                    + "program can be in.");
        }
        return declared;
    }

    private static AccountRepository fixtureAccountRepository(List<String> rows,
                                                             ForcedOutcome forced) {
        AccountRepository repository = fixtureAccountRepositoryInternal(rows);
        if (forced != null) {
            Mockito.doAnswer(rewrite -> forcedAccountWrite(forced))
                    .when(repository).rewrite(ArgumentMatchers.any());
        }
        return repository;
    }

    private static AccountRepository.WriteResult forcedAccountWrite(ForcedOutcome forced) {
        return switch (forced.outcome()) {
            case OK -> AccountRepository.WriteResult.written();
            case NOT_FOUND -> AccountRepository.WriteResult.notFound();
            case DUPLICATE, END_OF_FILE, OTHER -> throw new IllegalStateException(
                    "COACTUPC's account REWRITE at app/cbl/COACTUPC.cbl:4064-4070 tests RESP for NORMAL "
                            + "and treats every other response as LOCKED-BUT-UPDATE-FAILED, so the only "
                            + "outcomes a case can force on it are OK and NOT_FOUND; " + forced.outcome()
                            + " is not one the paragraph distinguishes.");
        };
    }

    private static CustomerRepository fixtureCustomerRepository(List<String> rows,
                                                               ForcedOutcome forced) {
        CustomerRepository repository = fixtureCustomerRepositoryInternal(rows);
        if (forced != null) {
            Mockito.doAnswer(rewrite -> switch (forced.outcome()) {
                case OK -> CustomerRepository.WriteResult.written();
                case NOT_FOUND -> CustomerRepository.WriteResult.notFound();
                case DUPLICATE, END_OF_FILE, OTHER -> throw new IllegalStateException(
                        "COACTUPC's customer REWRITE at app/cbl/COACTUPC.cbl:4084-4090 tests RESP for "
                                + "NORMAL only, so the only outcomes a case can force on it are OK and "
                                + "NOT_FOUND; " + forced.outcome() + " is not one it distinguishes.");
            }).when(repository).rewriteHeld(ArgumentMatchers.anyString(),
                    ArgumentMatchers.any(CustomerRecord.class));
        }
        return repository;
    }

    private static AccountRepository fixtureAccountRepositoryInternal(List<String> rows) {
        AccountRepository repository = strictAccountRepository();
        Mockito.doAnswer(read -> {
            String key = read.getArgument(0);
            for (String row : rows) {
                if (row.startsWith(key)) {
                    return AccountRepository.ReadResult.found(
                            AccountRecord.decode(row, FIXTURE_CHARSET));
                }
            }
            return AccountRepository.ReadResult.notFound();
        }).when(repository).readForUpdate(ArgumentMatchers.anyString());
        Mockito.doAnswer(rewrite -> {
            AccountRecord record = rewrite.getArgument(0);
            String image = record.toFixedWidthString();
            String key = CODEC.movePic9(record.getAcctId(), AccountRecord.ACCT_ID_LENGTH);
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).startsWith(key)) {
                    rows.set(index, image);
                    return AccountRepository.WriteResult.written();
                }
            }
            return AccountRepository.WriteResult.notFound();
        }).when(repository).rewrite(ArgumentMatchers.any());
        return repository;
    }

    private static CustomerRepository fixtureCustomerRepository(List<String> rows) {
        return fixtureCustomerRepository(rows, null);
    }

    private static CustomerRepository fixtureCustomerRepositoryInternal(List<String> rows) {
        CustomerRepository repository = strictCustomerRepository();
        Mockito.doAnswer(read -> {
            String key = read.getArgument(0);
            for (String row : rows) {
                if (row.startsWith(key)) {
                    return CustomerRepository.ReadResult.found(
                            CustomerRecord.decode(row, FIXTURE_CHARSET), row);
                }
            }
            return CustomerRepository.ReadResult.notFound();
        }).when(repository).readForUpdate(ArgumentMatchers.anyString());
        // The REWRITE at :4084-4090 carries no RIDFLD, so it replaces the row the READ ... UPDATE above is
        // holding: the held image names the row, and the key inside the staged record does not. A staged
        // record whose CUST-ID disagrees with the held row's is the DFHRESP(INVREQ) a real REWRITE reports
        // when the record area no longer matches the record held - reproduced here so the stand-in cannot
        // accept a redirection the repository refuses.
        Mockito.doAnswer(rewrite -> {
            String heldImage = rewrite.getArgument(0);
            CustomerRecord record = rewrite.getArgument(1);
            String image = new String(record.encode(FIXTURE_CHARSET), FIXTURE_CHARSET);
            String key = CODEC.movePic9(record.getCustId(), CustomerRepository.KEY_LENGTH);
            if (!key.equals(heldImage.substring(0, CustomerRepository.KEY_LENGTH))) {
                return CustomerRepository.WriteResult.of(CustomerRepository.PERMANENT_ERROR_STATUS,
                        CicsResponse.of(FileStatus.INVREQ));
            }
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).equals(heldImage)) {
                    rows.set(index, image);
                    return CustomerRepository.WriteResult.written();
                }
            }
            return CustomerRepository.WriteResult.notFound();
        }).when(repository).rewriteHeld(ArgumentMatchers.anyString(),
                ArgumentMatchers.any(CustomerRecord.class));
        return repository;
    }

    private static AccountRepository strictAccountRepository() {
        AccountRepository repository = Mockito.mock(AccountRepository.class, unstubbed -> {
            throw new UnsupportedOperationException(PROGRAM + " called AccountRepository."
                    + unstubbed.getMethod().getName() + ", for which it has no statement. Its account "
                    + "file verbs are the EXEC CICS READ at app/cbl/COACTUPC.cbl:3705-3715, the "
                    + "READ ... UPDATE at :3893-3903 and the REWRITE at :4064-4070 - there is no WRITE, "
                    + "no DELETE and no browse anywhere in the program - so a call to anything else is a "
                    + "translation reaching for a verb the source does not contain.");
        });
        Mockito.doReturn(FIXTURE_CHARSET).when(repository).datasetCharset();
        return repository;
    }

    private static CustomerRepository strictCustomerRepository() {
        CustomerRepository strict = Mockito.mock(CustomerRepository.class, unstubbed -> {
            throw new UnsupportedOperationException(PROGRAM + " called CustomerRepository."
                    + unstubbed.getMethod().getName() + ", for which it has no statement. Its customer "
                    + "file verbs are the EXEC CICS READ at :3756-3766, the READ ... UPDATE at "
                    + ":3921-3931 and the REWRITE at :4084-4090.");
        });
        Mockito.doReturn(FIXTURE_CHARSET).when(strict).datasetCharset();
        return strict;
    }

    private static AccountRepository unreachableAccountRepository() {
        return strictAccountRepository();
    }

    private static CustomerRepository unreachableCustomerRepository() {
        return strictCustomerRepository();
    }

    private static CardXrefRepository unreachableCardXrefRepository() {
        return Mockito.mock(CardXrefRepository.class, unstubbed -> {
            throw new UnsupportedOperationException(PROGRAM + " called CardXrefRepository."
                    + unstubbed.getMethod().getName() + " on a path that reaches no file verb. "
                    + "9200-GETCARDXREF-BYACCT reads the CXACAIX path at app/cbl/COACTUPC.cbl:3654-3664 "
                    + "and is performed only from 9000-READ-ACCT.");
        });
    }

    private static DatasetUnitOfWork unitOfWork(String caseId) {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:coactupc-parity-" + caseId + "-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "", true);
        source.setSuppressClose(true);
        DataSource dataSource = source;
        return new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));
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
}
