package com.vsergeychik.carddemo.account;

import static com.vsergeychik.carddemo.account.AccountUpdateController.FLG_BLANK;
import static com.vsergeychik.carddemo.account.AccountUpdateController.FLG_ISVALID;
import static com.vsergeychik.carddemo.account.AccountUpdateController.FLG_NOT_OK;
import static com.vsergeychik.carddemo.account.AccountUpdateController.INITIALIZED_FLAG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest;
import com.vsergeychik.carddemo.account.dto.AccountUpdateResponse.ScreenField;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.testsupport.ConversationStateSealFixture;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code app/cbl/COACTUPC.cbl:191-355} declares a one-character flag per screen field, each carrying a
 * three-name condition set - {@code FLG-<field>-ISVALID}, {@code -NOT-OK} and {@code -BLANK}.
 */
@DisplayName("G50: COACTUPC's WS-NON-KEY-FLAGS - all 36 flags, all 108 conditions, both states")
class AccountUpdateConditionCensusTest {
    private static final int ELEMENTARY_FLAG_COUNT = 36;

    private static final int GENERIC_FLAG_COUNT = 11;

    private static final int CONDITION_COUNT = (ELEMENTARY_FLAG_COUNT + GENERIC_FLAG_COUNT) * 3;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:32Z"), ZoneOffset.UTC);

    private static final String ACCT = "00000000011";

    private static final String VALID_SSN = "123456789";

    private static final int CUST_ID = 123456789;

    private static final int SIGNED_STAGING_WIDTH =
            AccountUpdateController.WS_EDIT_SIGNED_NUMBER_LENGTH;

    private AccountUpdateController controller;

    @BeforeEach
    void setUp() {
        controller = new AccountUpdateController(mock(AccountRepository.class),
                mock(CardXrefRepository.class), mock(CustomerRepository.class),
                mock(AccountUpdateService.class),
                new AccountDateValidator(new FixedWidthCodec(StandardCharsets.US_ASCII),
                        new DateUtilityJob(), CLOCK),
                new AreaCodeLookup(new FixedWidthCodec(StandardCharsets.US_ASCII)), CLOCK,
                ConversationStateSealFixture.seal(), StandardCharsets.US_ASCII);
    }

    /**
     * One elementary flag of {@code WS-NON-KEY-FLAGS} and everything needed to drive its three arms.
     *
     * @param field the screen field the flag governs, and the key {@code WS-NON-KEY-FLAGS} uses
     * @param cobolDataName the flag's own COBOL data name
     * @param isvalid the {@code 88} name of its valid state
     * @param notOk the {@code 88} name of its rejected state
     * @param blank the {@code 88} name of its not-supplied state
     * @param declLine the line of {@code app/cbl/COACTUPC.cbl} declaring the flag
     * @param firstCondLine the line declaring the first of its three conditions
     * @param validInput an input the edit accepts, or {@code null} when the field is never edited
     * @param blankInput an input that reaches {@code -BLANK}, or {@code null} when unreachable
     * @param badInput an input that reaches {@code -NOT-OK}, or {@code null} when unreachable
     * @param isvalidImage the flag character the valid state holds
     */
    private record Flag(ScreenField field, String cobolDataName, String isvalid, String notOk,
                        String blank, int declLine, int firstCondLine, String validInput,
                        String blankInput, String badInput, String isvalidImage) {
        boolean isEdited() {
            return validInput != null;
        }

        @Override
        public String toString() {
            return cobolDataName + " (" + field.label() + ", :" + declLine + ")";
        }
    }

    private static final List<Flag> FLAGS = List.of(
            new Flag(ScreenField.ACSTTUS, "WS-EDIT-ACCT-STATUS", "FLG-ACCT-STATUS-ISVALID", "FLG-ACCT-STATUS-NOT-OK",
                    "FLG-ACCT-STATUS-BLANK", 192, 193, "Y", "", "Q", "Y"),
            new Flag(ScreenField.ACRDLIM, "WS-EDIT-CREDIT-LIMIT", "FLG-CRED-LIMIT-ISVALID", "FLG-CRED-LIMIT-NOT-OK",
                    "FLG-CRED-LIMIT-BLANK", 196, 197, "+00005000.00", "", "ABCDEFGHIJKL", FLG_ISVALID),
            new Flag(ScreenField.ACSHLIM, "WS-EDIT-CASH-CREDIT-LIMIT", "FLG-CASH-CREDIT-LIMIT-ISVALID", "FLG-CASH-CREDIT-LIMIT-NOT-OK",
                    "FLG-CASH-CREDIT-LIMIT-BLANK", 200, 201, "-00000250.00", "", "ABCDEFGHIJKL", FLG_ISVALID),
            new Flag(ScreenField.ACURBAL, "WS-EDIT-CURR-BAL", "FLG-CURR-BAL-ISVALID", "FLG-CURR-BAL-NOT-OK",
                    "FLG-CURR-BAL-BLANK", 204, 205, "+00001234.56", "", "ABCDEFGHIJKL", FLG_ISVALID),
            new Flag(ScreenField.ACRCYCR, "WS-EDIT-CURR-CYC-CREDIT", "FLG-CURR-CYC-CREDIT-ISVALID", "FLG-CURR-CYC-CREDIT-NOT-OK",
                    "FLG-CURR-CYC-CREDIT-BLANK", 208, 209, "+00000000.00", "", "ABCDEFGHIJKL", FLG_ISVALID),
            new Flag(ScreenField.ACRCYDB, "WS-EDIT-CURR-CYC-DEBIT", "FLG-CURR-CYC-DEBIT-ISVALID", "FLG-CURR-CYC-DEBIT-NOT-OK",
                    "FLG-CURR-CYC-DEBIT-BLANK", 212, 213, "+00000087.05", "", "ABCDEFGHIJKL", FLG_ISVALID),
            new Flag(ScreenField.DOBYEAR, "WS-EDIT-DT-OF-BIRTH-YEAR-FLG", "FLG-DT-OF-BIRTH-YEAR-ISVALID", "FLG-DT-OF-BIRTH-YEAR-NOT-OK",
                    "FLG-DT-OF-BIRTH-YEAR-BLANK", 219, 220, "19800229", "", "ZZZZ0229", FLG_ISVALID),
            new Flag(ScreenField.DOBMON, "WS-EDIT-DT-OF-BIRTH-MONTH", "FLG-DT-OF-BIRTH-MONTH-ISVALID", "FLG-DT-OF-BIRTH-MONTH-NOT-OK",
                    "FLG-DT-OF-BIRTH-MONTH-BLANK", 223, 224, "19800229", "", "1980ZZ29", FLG_ISVALID),
            new Flag(ScreenField.DOBDAY, "WS-EDIT-DT-OF-BIRTH-DAY", "FLG-DT-OF-BIRTH-DAY-ISVALID", "FLG-DT-OF-BIRTH-DAY-NOT-OK",
                    "FLG-DT-OF-BIRTH-DAY-BLANK", 227, 228, "19800229", "", "198002ZZ", FLG_ISVALID),
            new Flag(ScreenField.ACSTFCO, "WS-EDIT-FICO-SCORE-FLGS", "FLG-FICO-SCORE-ISVALID", "FLG-FICO-SCORE-NOT-OK",
                    "FLG-FICO-SCORE-BLANK", 231, 232, "750", "", "ZZZ", FLG_ISVALID),
            new Flag(ScreenField.OPNYEAR, "WS-EDIT-OPEN-YEAR-FLG", "FLG-OPEN-YEAR-ISVALID", "FLG-OPEN-YEAR-NOT-OK",
                    "FLG-OPEN-YEAR-BLANK", 237, 238, "20200115", "", "ZZZZ0115", FLG_ISVALID),
            new Flag(ScreenField.OPNMON, "WS-EDIT-OPEN-MONTH", "FLG-OPEN-MONTH-ISVALID", "FLG-OPEN-MONTH-NOT-OK",
                    "FLG-OPEN-MONTH-BLANK", 241, 242, "20200115", "", "2020ZZ15", FLG_ISVALID),
            new Flag(ScreenField.OPNDAY, "WS-EDIT-OPEN-DAY", "FLG-OPEN-DAY-ISVALID", "FLG-OPEN-DAY-NOT-OK",
                    "FLG-OPEN-DAY-BLANK", 245, 246, "20200115", "", "202001ZZ", FLG_ISVALID),
            new Flag(ScreenField.EXPYEAR, "WS-EDIT-EXPIRY-YEAR-FLG", "FLG-EXPIRY-YEAR-ISVALID", "FLG-EXPIRY-YEAR-NOT-OK",
                    "FLG-EXPIRY-YEAR-BLANK", 251, 252, "20250114", "", "ZZZZ0114", FLG_ISVALID),
            new Flag(ScreenField.EXPMON, "WS-EDIT-EXPIRY-MONTH", "FLG-EXPIRY-MONTH-ISVALID", "FLG-EXPIRY-MONTH-NOT-OK",
                    "FLG-EXPIRY-MONTH-BLANK", 255, 256, "20250114", "", "2025ZZ14", FLG_ISVALID),
            new Flag(ScreenField.EXPDAY, "WS-EDIT-EXPIRY-DAY", "FLG-EXPIRY-DAY-ISVALID", "FLG-EXPIRY-DAY-NOT-OK",
                    "FLG-EXPIRY-DAY-BLANK", 259, 260, "20250114", "", "202501ZZ", FLG_ISVALID),
            new Flag(ScreenField.RISYEAR, "WS-EDIT-REISSUE-YEAR-FLG", "FLG-REISSUE-YEAR-ISVALID", "FLG-REISSUE-YEAR-NOT-OK",
                    "FLG-REISSUE-YEAR-BLANK", 265, 266, "20220630", "", "ZZZZ0630", FLG_ISVALID),
            new Flag(ScreenField.RISMON, "WS-EDIT-REISSUE-MONTH", "FLG-REISSUE-MONTH-ISVALID", "FLG-REISSUE-MONTH-NOT-OK",
                    "FLG-REISSUE-MONTH-BLANK", 269, 270, "20220630", "", "2022ZZ30", FLG_ISVALID),
            new Flag(ScreenField.RISDAY, "WS-EDIT-REISSUE-DAY", "FLG-REISSUE-DAY-ISVALID", "FLG-REISSUE-DAY-NOT-OK",
                    "FLG-REISSUE-DAY-BLANK", 273, 274, "20220630", "", "202206ZZ", FLG_ISVALID),
            new Flag(ScreenField.ACSFNAM, "WS-EDIT-FIRST-NAME-FLGS", "FLG-FIRST-NAME-ISVALID", "FLG-FIRST-NAME-NOT-OK",
                    "FLG-FIRST-NAME-BLANK", 278, 279, "ANNE", "", "ANN3", FLG_ISVALID),
            new Flag(ScreenField.ACSMNAM, "WS-EDIT-MIDDLE-NAME-FLGS", "FLG-MIDDLE-NAME-ISVALID", "FLG-MIDDLE-NAME-NOT-OK",
                    "FLG-MIDDLE-NAME-BLANK", 282, 283, "Q", null, "Q3", FLG_ISVALID),
            new Flag(ScreenField.ACSLNAM, "WS-EDIT-LAST-NAME-FLGS", "FLG-LAST-NAME-ISVALID", "FLG-LAST-NAME-NOT-OK",
                    "FLG-LAST-NAME-BLANK", 286, 287, "ARCHER", "", "ARCH3R", FLG_ISVALID),
            new Flag(ScreenField.ACSADL1, "WS-EDIT-ADDRESS-LINE-1-FLGS", "FLG-ADDRESS-LINE-1-ISVALID", "FLG-ADDRESS-LINE-1-NOT-OK",
                    "FLG-ADDRESS-LINE-1-BLANK", 291, 292, "1 MAIN STREET", "", null, FLG_ISVALID),
            new Flag(ScreenField.ACSADL2, "WS-EDIT-ADDRESS-LINE-2-FLGS", "FLG-ADDRESS-LINE-2-ISVALID", "FLG-ADDRESS-LINE-2-NOT-OK",
                    "FLG-ADDRESS-LINE-2-BLANK", 295, 296, null, null, null, INITIALIZED_FLAG),
            new Flag(ScreenField.ACSCITY, "WS-EDIT-CITY-FLGS", "FLG-CITY-ISVALID", "FLG-CITY-NOT-OK",
                    "FLG-CITY-BLANK", 299, 300, "SEATTLE", "", "SEATTL3", FLG_ISVALID),
            new Flag(ScreenField.ACSSTTE, "WS-EDIT-STATE-FLGS", "FLG-STATE-ISVALID", "FLG-STATE-NOT-OK",
                    "FLG-STATE-BLANK", 303, 304, "WA", "", "W1", FLG_ISVALID),
            new Flag(ScreenField.ACSZIPC, "WS-EDIT-ZIPCODE-FLGS", "FLG-ZIPCODE-ISVALID", "FLG-ZIPCODE-NOT-OK",
                    "FLG-ZIPCODE-BLANK", 307, 308, "98101", "", "ZZZZZ", FLG_ISVALID),
            new Flag(ScreenField.ACSCTRY, "WS-EDIT-COUNTRY-FLGS", "FLG-COUNTRY-ISVALID", "FLG-COUNTRY-NOT-OK",
                    "FLG-COUNTRY-BLANK", 311, 312, "USA", "", "US1", FLG_ISVALID),
            new Flag(ScreenField.ACSPH1A, "WS-EDIT-PHONE-NUM-1A-FLG", "FLG-PHONE-NUM-1A-ISVALID", "FLG-PHONE-NUM-1A-NOT-OK",
                    "FLG-PHONE-NUM-1A-BLANK", 318, 319, "(206)555-1212", "(   )555-1212", "(999)555-1212", FLG_ISVALID),
            new Flag(ScreenField.ACSPH1B, "WS-EDIT-PHONE-NUM-1B", "FLG-PHONE-NUM-1B-ISVALID", "FLG-PHONE-NUM-1B-NOT-OK",
                    "FLG-PHONE-NUM-1B-BLANK", 322, 323, "(206)555-1212", "(206)   -1212", "(206)ZZZ-1212", FLG_ISVALID),
            new Flag(ScreenField.ACSPH1C, "WS-EDIT-PHONE-NUM-1C", "FLG-PHONE-NUM-1C-ISVALID", "FLG-PHONE-NUM-1C-NOT-OK",
                    "FLG-PHONE-NUM-1C-BLANK", 326, 327, "(206)555-1212", "(206)555-    ", "(206)555-ZZZZ", FLG_ISVALID),
            new Flag(ScreenField.ACSPH2A, "WS-EDIT-PHONE-NUM-2A-FLG", "FLG-PHONE-NUM-2A-ISVALID", "FLG-PHONE-NUM-2A-NOT-OK",
                    "FLG-PHONE-NUM-2A-BLANK", 333, 334, "(425)555-1313", "(   )555-1313", "(999)555-1313", FLG_ISVALID),
            new Flag(ScreenField.ACSPH2B, "WS-EDIT-PHONE-NUM-2B", "FLG-PHONE-NUM-2B-ISVALID", "FLG-PHONE-NUM-2B-NOT-OK",
                    "FLG-PHONE-NUM-2B-BLANK", 337, 338, "(425)555-1313", "(425)   -1313", "(425)ZZZ-1313", FLG_ISVALID),
            new Flag(ScreenField.ACSPH2C, "WS-EDIT-PHONE-NUM-2C", "FLG-PHONE-NUM-2C-ISVALID", "FLG-PHONE-NUM-2C-NOT-OK",
                    "FLG-PHONE-NUM-2C-BLANK", 341, 342, "(425)555-1313", "(425)555-    ", "(425)555-ZZZZ", FLG_ISVALID),
            new Flag(ScreenField.ACSEFTC, "WS-EFT-ACCOUNT-ID-FLGS", "FLG-EFT-ACCOUNT-ID-ISVALID", "FLG-EFT-ACCOUNT-ID-NOT-OK",
                    "FLG-EFT-ACCOUNT-ID-BLANK", 345, 346, "1234567890", "", "ZZZZZZZZZZ", FLG_ISVALID),
            new Flag(ScreenField.ACSPFLG, "WS-EDIT-PRI-CARDHOLDER", "FLG-PRI-CARDHOLDER-ISVALID", "FLG-PRI-CARDHOLDER-NOT-OK",
                    "FLG-PRI-CARDHOLDER-BLANK", 349, 350, "Y", "", "Q", "Y"));

    private record Generic(String cobolDataName, String isvalid, String notOk, String blank,
                           int declLine, Function<AccountUpdateController.Conversation, String> read,
                           ScreenField drivenBy, ScreenField republishedAs, String validInput,
                           String blankInput, String badInput, String isvalidImage) {
        @Override
        public String toString() {
            return cobolDataName + " (via " + drivenBy.label() + ", :" + declLine + ")";
        }
    }

    private static final List<Generic> GENERIC_FLAGS = List.of(
            new Generic("WS-FLG-SIGNED-NUMBER-EDIT", "FLG-SIGNED-NUMBER-ISVALID",
                    "FLG-SIGNED-NUMBER-NOT-OK", "FLG-SIGNED-NUMBER-BLANK", 56,
                    task -> task.wsFlgSignedNumberEdit, ScreenField.ACRCYDB, null,
                    "+00000087.05", "", "ABCDEFGHIJKL", FLG_ISVALID),
            new Generic("WS-EDIT-ALPHA-ONLY-FLAGS", "FLG-ALPHA-ISVALID", "FLG-ALPHA-NOT-OK",
                    "FLG-ALPHA-BLANK", 64, task -> task.wsEditAlphaOnlyFlags,
                    ScreenField.ACSCTRY, null, "USA", "", "US1", FLG_ISVALID),
            new Generic("WS-EDIT-ALPHANUM-ONLY-FLAGS", "FLG-ALPHNANUM-ISVALID",
                    "FLG-ALPHNANUM-NOT-OK", "FLG-ALPHNANUM-BLANK", 68,
                    task -> task.wsEditAlphanumOnlyFlags, ScreenField.ACSEFTC, null,
                    "1234567890", "", "ZZZZZZZZZZ", FLG_ISVALID),
            new Generic("WS-EDIT-MANDATORY-FLAGS", "FLG-MANDATORY-ISVALID", "FLG-MANDATORY-NOT-OK",
                    "FLG-MANDATORY-BLANK", 72, task -> task.wsEditMandatoryFlags,
                    ScreenField.ACSADL1, null, "1 MAIN STREET", "", null, FLG_ISVALID),
            new Generic("WS-EDIT-YES-NO", "FLG-YES-NO-ISVALID", "FLG-YES-NO-NOT-OK",
                    "FLG-YES-NO-BLANK", 76, task -> task.wsEditYesNo, ScreenField.ACSPFLG, null,
                    "Y", "", "Q", "Y"),
            new Generic("WS-EDIT-US-PHONEA-FLG", "FLG-EDIT-US-PHONEA-ISVALID",
                    "FLG-EDIT-US-PHONEA-NOT-OK", "FLG-EDIT-US-PHONEA-BLANK", 104,
                    task -> task.wsEditUsPhoneaFlg, ScreenField.ACSPH2A, null,
                    "(425)555-1313", "(   )555-1313", "(999)555-1313", FLG_ISVALID),
            new Generic("WS-EDIT-EDIT-US-PHONEB", "FLG-EDIT-US-PHONEB-ISVALID",
                    "FLG-EDIT-US-PHONEB-NOT-OK", "FLG-EDIT-US-PHONEB-BLANK", 108,
                    task -> task.wsEditEditUsPhoneb, ScreenField.ACSPH2B, null,
                    "(425)555-1313", "(425)   -1313", "(425)ZZZ-1313", FLG_ISVALID),
            new Generic("WS-EDIT-EDIT-PHONEC", "FLG-EDIT-US-PHONEC-ISVALID",
                    "FLG-EDIT-US-PHONEC-NOT-OK", "FLG-EDIT-US-PHONEC-BLANK", 112,
                    task -> task.wsEditEditPhonec, ScreenField.ACSPH2C, null,
                    "(425)555-1313", "(425)555-    ", "(425)555-ZZZZ", FLG_ISVALID),
            new Generic("WS-EDIT-US-SSN-PART1-FLGS", "FLG-EDIT-US-SSN-PART1-ISVALID",
                    "FLG-EDIT-US-SSN-PART1-NOT-OK", "FLG-EDIT-US-SSN-PART1-BLANK", 135,
                    task -> task.wsEditUsSsnPart1Flgs, ScreenField.ACTSSN1, ScreenField.ACTSSN1,
                    VALID_SSN, "   456789", "ZZZ456789", FLG_ISVALID),
            new Generic("WS-EDIT-US-SSN-PART2-FLGS", "FLG-EDIT-US-SSN-PART2-ISVALID",
                    "FLG-EDIT-US-SSN-PART2-NOT-OK", "FLG-EDIT-US-SSN-PART2-BLANK", 139,
                    task -> task.wsEditUsSsnPart2Flgs, ScreenField.ACTSSN2, ScreenField.ACTSSN2,
                    VALID_SSN, "123  6789", "123ZZ6789", FLG_ISVALID),
            new Generic("WS-EDIT-US-SSN-PART3-FLGS", "FLG-EDIT-US-SSN-PART3-ISVALID",
                    "FLG-EDIT-US-SSN-PART3-NOT-OK", "FLG-EDIT-US-SSN-PART3-BLANK", 143,
                    task -> task.wsEditUsSsnPart3Flgs, ScreenField.ACTSSN3, ScreenField.ACTSSN3,
                    VALID_SSN, "12345    ", "12345ZZZZ", FLG_ISVALID));

    private static Stream<Generic> genericFlags() {
        return GENERIC_FLAGS.stream();
    }

    private static Stream<Generic> genericFlagsWithABlankArm() {
        return GENERIC_FLAGS.stream().filter(generic -> generic.blankInput() != null);
    }

    private static Stream<Generic> genericFlagsWithANotOkArm() {
        return GENERIC_FLAGS.stream().filter(generic -> generic.badInput() != null);
    }

    private static Stream<Flag> editedFlags() {
        return FLAGS.stream().filter(Flag::isEdited);
    }

    private static Stream<Flag> flagsWithABlankArm() {
        return FLAGS.stream().filter(flag -> flag.blankInput() != null);
    }

    private static Stream<Flag> flagsWithANotOkArm() {
        return FLAGS.stream().filter(flag -> flag.badInput() != null);
    }

    private static Stream<Flag> unreachableArms() {
        return FLAGS.stream().filter(flag -> !flag.isEdited()
                || flag.blankInput() == null || flag.badInput() == null);
    }

    private AccountUpdateController.Conversation warm() {
        AccountUpdateRequest received = AccountUpdateRequest.initial()
                .withValue(AccountUpdateRequest.ScreenField.ACCTSID,
                        controller.codec().movePicX(ACCT, AccountUpdateRequest.ACCTSID_LENGTH))
                .withNavigationContext(NavigationContext.empty()
                        .withFromTranid("CAUP").withFromProgram("COACTUPC").withPgmReenter()
                        .withAcctId(11L).withCustId(CUST_ID));
        AccountUpdateController.Conversation task =
                new AccountUpdateController.Conversation(StandardCharsets.US_ASCII);
        controller.initializeStorage(received, task,
                AccountUpdateRequest.CommArea.RECORD_LENGTH + NavigationContext.COMMAREA_LENGTH,
                CicsAid.DFHENTER);
        task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
        task.ccWorkArea.setCcAcctId(ACCT);
        return task;
    }

    private void stage(AccountUpdateController.Conversation task, ScreenField field, String value) {
        switch (field) {
            case ACSTTUS -> task.acupNewAcct.activeStatus = value;
            case ACRDLIM -> task.acupNewCreditLimitX = signed(value);
            case ACSHLIM -> task.acupNewCashCreditLimitX = signed(value);
            case ACURBAL -> task.acupNewCurrBalX = signed(value);
            case ACRCYCR -> task.acupNewCurrCycCreditX = signed(value);
            case ACRCYDB -> task.acupNewCurrCycDebitX = signed(value);
            case OPNYEAR, OPNMON, OPNDAY -> task.acupNewAcct.openDate = value;
            case EXPYEAR, EXPMON, EXPDAY -> task.acupNewAcct.expiraionDate = value;
            case RISYEAR, RISMON, RISDAY -> task.acupNewAcct.reissueDate = value;
            case DOBYEAR, DOBMON, DOBDAY -> task.acupNewCust.dobYyyyMmDd = value;
            case ACSTFCO -> task.acupNewCust.ficoScoreX = value;
            case ACSFNAM -> task.acupNewCust.firstName = value;
            case ACSMNAM -> task.acupNewCust.middleName = value;
            case ACSLNAM -> task.acupNewCust.lastName = value;
            case ACSADL1 -> task.acupNewCust.addrLine1 = value;
            case ACSADL2 -> task.acupNewCust.addrLine2 = value;
            case ACSCITY -> task.acupNewCust.addrLine3 = value;
            case ACSSTTE -> task.acupNewCust.addrStateCd = value;
            case ACSZIPC -> task.acupNewCust.addrZip = value;
            case ACSCTRY -> task.acupNewCust.addrCountryCd = value;
            case ACSPH1A, ACSPH1B, ACSPH1C -> task.acupNewCust.phoneNum1 = value;
            case ACSPH2A, ACSPH2B, ACSPH2C -> task.acupNewCust.phoneNum2 = value;
            case ACSEFTC -> task.acupNewCust.eftAccountId = value;
            case ACSPFLG -> task.acupNewCust.priHolderInd = value;
            case ACTSSN1, ACTSSN2, ACTSSN3 -> task.acupNewCust.ssnX = value;
            default -> throw new IllegalArgumentException(
                    field.label() + " carries no WS-NON-KEY-FLAGS entry, so it cannot be staged here");
        }
    }

    private static String signed(String value) {
        return value + " ".repeat(Math.max(0, SIGNED_STAGING_WIDTH - value.length()));
    }

    private void stageValidScreen(AccountUpdateController.Conversation task) {
        for (Flag flag : FLAGS) {
            if (flag.isEdited()) {
                stage(task, flag.field(), flag.validInput());
            }
        }
        stage(task, ScreenField.ACTSSN1, VALID_SSN);
        task.acupNewCust.govtIssuedId = "GOVT123";
    }

    private AccountUpdateController.Conversation editWith(ScreenField field, String replaced) {
        AccountUpdateController.Conversation task = warm();
        stageValidScreen(task);
        if (field != null) {
            stage(task, field, replaced);
        }
        controller.editMapInputs1200(task);
        return task;
    }

    private static void assertOnlyStateHolding(AccountUpdateController.Conversation task, Flag flag,
                                               String expected) {
        assertThat(task.flag(flag.field()))
                .as("%s must hold %s", flag, nameOfState(flag, expected))
                .isEqualTo(expected);

        assertThat(task.flagIsvalid(flag.field()))
                .as("%s: %s", flag, flag.isvalid()).isEqualTo(FLG_ISVALID.equals(expected));
        assertThat(task.flagNotOk(flag.field()))
                .as("%s: %s", flag, flag.notOk()).isEqualTo(FLG_NOT_OK.equals(expected));
        assertThat(task.flagBlank(flag.field()))
                .as("%s: %s", flag, flag.blank()).isEqualTo(FLG_BLANK.equals(expected));
    }

    private static String nameOfState(Flag flag, String stateCharacter) {
        if (FLG_NOT_OK.equals(stateCharacter)) {
            return flag.notOk();
        }
        if (FLG_BLANK.equals(stateCharacter)) {
            return flag.blank();
        }
        return flag.isvalid();
    }

    private byte colourAfterAttrs(AccountUpdateController.Conversation task, ScreenField field) {
        task.wsEditAcctFlag = AccountUpdateController.FLG_FILTER_ISVALID;
        controller.screenInit3100(task);
        controller.setupScreenAttrs3300(task);
        return task.cactupao.attributes(field).getColour();
    }

    @Nested
    @DisplayName("The census itself - it cannot shrink, and it cannot lie about what it covers")
    class TheCensusItself {
        @Test
        @DisplayName("47 flags, 141 distinct condition names, one row per flag")
        void theTableCoversEveryDeclaredFlagAndCondition() {
            assertThat(FLAGS).as("one row per elementary flag of WS-NON-KEY-FLAGS")
                    .hasSize(ELEMENTARY_FLAG_COUNT);

            assertThat(GENERIC_FLAGS).as("one row per generic staging flag, app/cbl/COACTUPC.cbl:56-146")
                    .hasSize(GENERIC_FLAG_COUNT);

            Set<ScreenField> fields = new LinkedHashSet<>();
            Set<String> dataNames = new LinkedHashSet<>();
            Set<String> conditions = new LinkedHashSet<>();
            for (Generic generic : GENERIC_FLAGS) {
                assertThat(dataNames.add(generic.cobolDataName()))
                        .as("%s is claimed by two rows", generic.cobolDataName()).isTrue();
                conditions.add(generic.isvalid());
                conditions.add(generic.notOk());
                conditions.add(generic.blank());
            }
            for (Flag flag : FLAGS) {
                assertThat(fields.add(flag.field()))
                        .as("%s is claimed by two rows", flag.field().label()).isTrue();
                assertThat(dataNames.add(flag.cobolDataName()))
                        .as("%s is claimed by two rows", flag.cobolDataName()).isTrue();
                conditions.add(flag.isvalid());
                conditions.add(flag.notOk());
                conditions.add(flag.blank());
            }
            assertThat(conditions)
                    .as("every 88-level name is distinct - a duplicated name would mean one condition "
                            + "counted twice and another not at all")
                    .hasSize(CONDITION_COUNT);
        }

        @Test
        @DisplayName("the names follow the source's own three-name shape, and cite where they live")
        void theNamesAndCitationsAreWellFormed() {
            for (Flag flag : FLAGS) {
                assertThat(flag.isvalid()).as("%s", flag).startsWith("FLG-").endsWith("-ISVALID");
                assertThat(flag.notOk()).as("%s", flag).startsWith("FLG-").endsWith("-NOT-OK");
                assertThat(flag.blank()).as("%s", flag).startsWith("FLG-").endsWith("-BLANK");

                String stem = flag.isvalid().substring("FLG-".length(),
                        flag.isvalid().length() - "-ISVALID".length());
                assertThat(flag.notOk()).as("%s: the three names share one stem", flag)
                        .isEqualTo("FLG-" + stem + "-NOT-OK");
                assertThat(flag.blank()).as("%s: the three names share one stem", flag)
                        .isEqualTo("FLG-" + stem + "-BLANK");

                assertThat(flag.declLine())
                        .as("%s: the flag is declared inside WS-NON-KEY-FLAGS, "
                                + "app/cbl/COACTUPC.cbl:191-355", flag)
                        .isBetween(191, 355);
                assertThat(flag.firstCondLine())
                        .as("%s: its conditions follow its declaration", flag)
                        .isEqualTo(flag.declLine() + 1);
            }
        }

        @Test
        @DisplayName("the rows are in the source's declaration order")
        void theRowsAreInDeclarationOrder() {
            for (int i = 1; i < FLAGS.size(); i++) {
                assertThat(FLAGS.get(i).declLine())
                        .as("%s is declared after %s in app/cbl/COACTUPC.cbl",
                                FLAGS.get(i), FLAGS.get(i - 1))
                        .isGreaterThan(FLAGS.get(i - 1).declLine());
            }
        }

        @Test
        @DisplayName("a row's reachability claims and its inputs cannot contradict each other")
        void theTableIsInternallyConsistent() {
            for (Flag flag : FLAGS) {
                if (flag.isEdited()) {
                    assertThat(flag.isvalidImage())
                            .as("%s: an edited field's valid state is LOW-VALUES unless it declares "
                                    + "VALUES, in which case it holds the accepted character", flag)
                            .isIn(FLG_ISVALID, flag.validInput());
                } else {
                    assertThat(flag.blankInput())
                            .as("%s is never edited, so it can have no blank input", flag).isNull();
                    assertThat(flag.badInput())
                            .as("%s is never edited, so it can have no rejected input", flag).isNull();
                    assertThat(flag.isvalidImage())
                            .as("%s is never edited, so its flag keeps INITIALIZED_FLAG", flag)
                            .isEqualTo(INITIALIZED_FLAG);
                }
            }

            assertThat(unreachableArms().map(Flag::cobolDataName).toList())
                    .as("only three arms are unreachable through the edit path, and each is a property "
                            + "of the COBOL - see this class's documentation")
                    .containsExactly("WS-EDIT-MIDDLE-NAME-FLGS", "WS-EDIT-ADDRESS-LINE-1-FLGS",
                            "WS-EDIT-ADDRESS-LINE-2-FLGS");
        }

        @Test
        @DisplayName("the valid screen really is valid - INPUT-OK survives it")
        void editsWithNoErrorOnAValidScreen() {
            AccountUpdateController.Conversation task = editWith(null, null);
            assertThat(task.inputError())
                    .as("every case below changes one field of this screen, so the screen itself must "
                            + "carry no error - otherwise a case would be asserting against a "
                            + "conversation that was already broken. msg=[" + task.wsReturnMsg + "]")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Driven through 1200-EDIT-MAP-INPUTS - the real edit path")
    class DrivenThroughTheEditPath {
        @ParameterizedTest(name = "{0} reaches its ISVALID state")
        @MethodSource("com.vsergeychik.carddemo.account."
                + "AccountUpdateConditionCensusTest#editedFlags")
        @DisplayName("every FLG-<field>-ISVALID holds on a screen that field passes")
        void theIsvalidConditionHolds(Flag flag) {
            AccountUpdateController.Conversation task = editWith(flag.field(), flag.validInput());
            assertOnlyStateHolding(task, flag, flag.isvalidImage());
            assertThat(colourAfterAttrs(task, flag.field()))
                    .as("%s: CSSETATY's outer test at app/cpy/CSSETATY.cpy:18-19 fails for a valid "
                            + "field, so it is left completely untouched", flag)
                    .isNotEqualTo(BmsAttributes.DFHRED);
        }

        @ParameterizedTest(name = "{0} reaches its NOT-OK state")
        @MethodSource("com.vsergeychik.carddemo.account."
                + "AccountUpdateConditionCensusTest#flagsWithANotOkArm")
        @DisplayName("every reachable FLG-<field>-NOT-OK holds on a value its edit rejects")
        void theNotOkConditionHolds(Flag flag) {
            AccountUpdateController.Conversation task = editWith(flag.field(), flag.badInput());
            assertOnlyStateHolding(task, flag, FLG_NOT_OK);
            assertThat(task.inputError())
                    .as("%s: a rejected field raises INPUT-ERROR", flag).isTrue();
            assertThat(colourAfterAttrs(task, flag.field()))
                    .as("%s: a rejected field reddens", flag).isEqualTo(BmsAttributes.DFHRED);
        }

        @ParameterizedTest(name = "{0} reaches its BLANK state")
        @MethodSource("com.vsergeychik.carddemo.account."
                + "AccountUpdateConditionCensusTest#flagsWithABlankArm")
        @DisplayName("every reachable FLG-<field>-BLANK holds when that field is left empty")
        void theBlankConditionHolds(Flag flag) {
            AccountUpdateController.Conversation task = editWith(flag.field(), flag.blankInput());
            assertOnlyStateHolding(task, flag, FLG_BLANK);
            assertThat(task.inputError())
                    .as("%s: a missing mandatory field raises INPUT-ERROR", flag).isTrue();
            assertThat(colourAfterAttrs(task, flag.field()))
                    .as("%s: a blank field reddens, and satisfies CSSETATY's inner test too", flag)
                    .isEqualTo(BmsAttributes.DFHRED);
        }
    }

    @Nested
    @DisplayName("Preserved-dead arms - modelled and asserted, never excused")
    class PreservedDeadArms {
        @ParameterizedTest(name = "{0}: every condition over it is a real predicate")
        @MethodSource("com.vsergeychik.carddemo.account."
                + "AccountUpdateConditionCensusTest#unreachableArms")
        @DisplayName("the storage predicate answers correctly for all three states")
        void theStoragePredicateAnswersForEveryState(Flag flag) {
            for (String state : List.of(FLG_ISVALID, FLG_NOT_OK, FLG_BLANK)) {
                AccountUpdateController.Conversation task = warm();
                task.setFlag(flag.field(), state);
                assertOnlyStateHolding(task, flag, state);
            }
        }

        @Test
        @DisplayName("address line 2 keeps INITIALIZED_FLAG: 1200 never edits it")
        void addressLineTwoIsNeverEdited() {
            AccountUpdateController.Conversation task = warm();
            stageValidScreen(task);
            stage(task, ScreenField.ACSADL2, "");
            controller.editMapInputs1200(task);

            assertThat(task.flag(ScreenField.ACSADL2))
                    .as("blank or not, WS-EDIT-ADDRESS-LINE-2-FLGS is untouched: there is no "
                            + "MOVE 'Address Line 2' and no edit for it in 1200-EDIT-MAP-INPUTS")
                    .isEqualTo(INITIALIZED_FLAG);
            assertThat(task.flagNotOk(ScreenField.ACSADL2)).isFalse();
            assertThat(task.flagBlank(ScreenField.ACSADL2)).isFalse();
            assertThat(task.flagIsvalid(ScreenField.ACSADL2))
                    .as("nor is it valid: INITIALIZED_FLAG is a space and matches none of the three "
                            + "88-levels, which is exactly the fourth state FieldValidationState.OK "
                            + "documents CSSETATY as unable to distinguish from a pass")
                    .isFalse();
        }

        @Test
        @DisplayName("a blank middle name is VALID, which is why its BLANK arm is unreachable")
        void aBlankMiddleNameIsValid() {
            AccountUpdateController.Conversation task = editWith(ScreenField.ACSMNAM, "");
            assertThat(task.flagIsvalid(ScreenField.ACSMNAM))
                    .as("1235-EDIT-ALPHA-OPT sets FLG-ALPHA-ISVALID for a blank optional field")
                    .isTrue();
            assertThat(task.flagBlank(ScreenField.ACSMNAM))
                    .as("so FLG-MIDDLE-NAME-BLANK can never be this field's verdict").isFalse();
        }

        @Test
        @DisplayName("a blank address line 1 is BLANK, never NOT-OK")
        void aBlankAddressLineOneIsBlankNotRejected() {
            AccountUpdateController.Conversation blank = editWith(ScreenField.ACSADL1, "");
            assertThat(blank.flagBlank(ScreenField.ACSADL1)).isTrue();

            AccountUpdateController.Conversation full =
                    editWith(ScreenField.ACSADL1, "X".repeat(50));
            assertThat(full.flagIsvalid(ScreenField.ACSADL1))
                    .as("1215-EDIT-MANDATORY tests presence and nothing else, so a supplied value of "
                            + "any content passes and FLG-ADDRESS-LINE-1-NOT-OK is never a verdict")
                    .isTrue();
            assertThat(full.flagNotOk(ScreenField.ACSADL1)).isFalse();
        }
    }

    @Nested
    @DisplayName("The generic edits' own staging flags - the flags 1200 actually sets")
    class TheGenericStagingFlags {
        @ParameterizedTest(name = "{0} reaches its ISVALID state")
        @MethodSource("com.vsergeychik.carddemo.account."
                + "AccountUpdateConditionCensusTest#genericFlags")
        @DisplayName("every generic FLG-*-ISVALID holds when its last caller's field passes")
        void theIsvalidConditionHolds(Generic generic) {
            AccountUpdateController.Conversation task =
                    editWith(generic.drivenBy(), generic.validInput());
            assertGenericState(task, generic, generic.isvalidImage());
        }

        @ParameterizedTest(name = "{0} reaches its NOT-OK state")
        @MethodSource("com.vsergeychik.carddemo.account."
                + "AccountUpdateConditionCensusTest#genericFlagsWithANotOkArm")
        @DisplayName("every reachable generic FLG-*-NOT-OK holds on a value the edit rejects")
        void theNotOkConditionHolds(Generic generic) {
            AccountUpdateController.Conversation task =
                    editWith(generic.drivenBy(), generic.badInput());
            assertGenericState(task, generic, FLG_NOT_OK);
            assertThat(task.inputError()).as("%s: a rejected value raises INPUT-ERROR", generic)
                    .isTrue();
        }

        @ParameterizedTest(name = "{0} reaches its BLANK state")
        @MethodSource("com.vsergeychik.carddemo.account."
                + "AccountUpdateConditionCensusTest#genericFlagsWithABlankArm")
        @DisplayName("every reachable generic FLG-*-BLANK holds when the field is left empty")
        void theBlankConditionHolds(Generic generic) {
            AccountUpdateController.Conversation task =
                    editWith(generic.drivenBy(), generic.blankInput());
            assertGenericState(task, generic, FLG_BLANK);
            assertThat(task.inputError()).as("%s: a missing value raises INPUT-ERROR", generic)
                    .isTrue();
        }

        @Test
        @DisplayName("FLG-MANDATORY-NOT-OK is set, then always superseded before the paragraph exits")
        void mandatoryNotOkIsAnOpeningStateAndNeverAVerdict() {
            assertThat(editWith(ScreenField.ACSADL1, "").wsEditMandatoryFlags)
                    .as("a missing mandatory field ends BLANK").isEqualTo(FLG_BLANK);
            assertThat(editWith(ScreenField.ACSADL1, "1 MAIN STREET").wsEditMandatoryFlags)
                    .as("a supplied one ends ISVALID").isEqualTo(FLG_ISVALID);
        }

        @Test
        @DisplayName("the three SSN flags are republished into the flag map, so CSSETATY can redden them")
        void theSsnFlagsAreRepublishedIntoTheFlagMap() {
            for (Generic generic : GENERIC_FLAGS) {
                if (generic.republishedAs() == null) {
                    continue;
                }
                AccountUpdateController.Conversation task =
                        editWith(generic.drivenBy(), generic.badInput());
                assertThat(task.flag(generic.republishedAs()))
                        .as("%s must be copied into WS-NON-KEY-FLAGS under %s - without the copy the "
                                + "flag is written and never read, and a rejected SSN part stays at its "
                                + "default colour", generic, generic.republishedAs().label())
                        .isEqualTo(generic.read().apply(task));
                assertThat(task.flagNotOk(generic.republishedAs()))
                        .as("%s: the republished flag answers the same predicate", generic).isTrue();
                assertThat(colourAfterAttrs(task, generic.republishedAs()))
                        .as("%s: and 3300-SETUP-SCREEN-ATTRS reddens the field it names", generic)
                        .isEqualTo(BmsAttributes.DFHRED);
            }
        }

        private void assertGenericState(AccountUpdateController.Conversation task, Generic generic,
                                        String expected) {
            String actual = generic.read().apply(task);
            assertThat(actual).as("%s must hold %s", generic,
                            FLG_NOT_OK.equals(expected) ? generic.notOk()
                                    : FLG_BLANK.equals(expected) ? generic.blank()
                                            : generic.isvalid())
                    .isEqualTo(expected);
            assertThat(FLG_NOT_OK.equals(actual))
                    .as("%s: %s", generic, generic.notOk())
                    .isEqualTo(FLG_NOT_OK.equals(expected));
            assertThat(FLG_BLANK.equals(actual))
                    .as("%s: %s", generic, generic.blank())
                    .isEqualTo(FLG_BLANK.equals(expected));
        }
    }

    @Nested
    @DisplayName("The group conditions over the three-character flag groups, and the four dead literals")
    class TheGroupAndDeadConditions {
        private record GroupCondition(String name, int declLine, String value, List<ScreenField> parts,
                                      String drivingInput, ScreenField partStaged) {
            @Override
            public String toString() {
                return name + " (:" + declLine + ", VALUE " + value.replace(FLG_ISVALID, "@") + ")";
            }
        }

        private List<GroupCondition> groupConditions() {
            String allRejected = FLG_NOT_OK.repeat(3);
            String allValid = FLG_ISVALID.repeat(3);
            return List.of(
                    new GroupCondition("WS-EDIT-DT-OF-BIRTH-INVALID", 217, allRejected,
                            List.of(ScreenField.DOBYEAR, ScreenField.DOBMON, ScreenField.DOBDAY),
                            "ZZZZZZZZ", ScreenField.DOBYEAR),
                    new GroupCondition("WS-EDIT-DT-OF-BIRTH-ISVALID", 218, allValid,
                            List.of(ScreenField.DOBYEAR, ScreenField.DOBMON, ScreenField.DOBDAY),
                            "19800229", ScreenField.DOBYEAR),
                    new GroupCondition("WS-EDIT-OPEN-DATE-IS-INVALID", 236, allRejected,
                            List.of(ScreenField.OPNYEAR, ScreenField.OPNMON, ScreenField.OPNDAY),
                            "ZZZZZZZZ", ScreenField.OPNYEAR),
                    new GroupCondition("WS-EDIT-EXPIRY-IS-INVALID", 250, allRejected,
                            List.of(ScreenField.EXPYEAR, ScreenField.EXPMON, ScreenField.EXPDAY),
                            "ZZZZZZZZ", ScreenField.EXPYEAR),
                    new GroupCondition("WS-EDIT-REISSUE-DATE-INVALID", 264, allRejected,
                            List.of(ScreenField.RISYEAR, ScreenField.RISMON, ScreenField.RISDAY),
                            "ZZZZZZZZ", ScreenField.RISYEAR),
                    new GroupCondition("WS-EDIT-PHONE-NUM-1-IS-INVALID", 316, allRejected,
                            List.of(ScreenField.ACSPH1A, ScreenField.ACSPH1B, ScreenField.ACSPH1C),
                            "(999)ZZZ-ZZZZ", ScreenField.ACSPH1A),
                    new GroupCondition("WS-EDIT-PHONE-NUM-2-IS-INVALID", 331, allRejected,
                            List.of(ScreenField.ACSPH2A, ScreenField.ACSPH2B, ScreenField.ACSPH2C),
                            "(999)ZZZ-ZZZZ", ScreenField.ACSPH2A),
                    new GroupCondition("WS-EDIT-US-SSN-IS-INVALID", 133, allRejected,
                            List.of(ScreenField.ACTSSN1, ScreenField.ACTSSN2, ScreenField.ACTSSN3),
                            "ZZZZZZZZZ", ScreenField.ACTSSN1),
                    new GroupCondition("WS-EDIT-US-SSN-IS-VALID", 134, allValid,
                            List.of(ScreenField.ACTSSN1, ScreenField.ACTSSN2, ScreenField.ACTSSN3),
                            VALID_SSN, ScreenField.ACTSSN1));
        }

        private String groupImage(AccountUpdateController.Conversation task, GroupCondition group) {
            StringBuilder image = new StringBuilder(3);
            for (ScreenField part : group.parts()) {
                image.append(task.flag(part));
            }
            return image.toString();
        }

        @Test
        @DisplayName("each group condition holds when its whole group reaches its value, and not otherwise")
        void everyGroupConditionHoldsAndFails() {
            for (GroupCondition group : groupConditions()) {
                AccountUpdateController.Conversation holding =
                        editWith(group.partStaged(), group.drivingInput());
                assertThat(groupImage(holding, group))
                        .as("%s must hold when every part of its group reaches that state", group)
                        .isEqualTo(group.value());

                AccountUpdateController.Conversation notHolding = editWith(null, null);
                assertThat(groupImage(notHolding, group))
                        .as("%s must not hold on a screen its group passed cleanly - unless it is the "
                                + "ISVALID condition, which is what a clean screen means", group)
                        .satisfies(image -> {
                            if (group.value().equals(FLG_ISVALID.repeat(3))) {
                                assertThat(image).isEqualTo(group.value());
                            } else {
                                assertThat(image).isNotEqualTo(group.value());
                            }
                        });
            }
        }

        @Test
        @DisplayName("one rejected part is not enough: the group conditions are over all three bytes")
        void oneRejectedPartDoesNotSatisfyTheGroup() {
            AccountUpdateController.Conversation task =
                    editWith(ScreenField.DOBMON, "1980ZZ29");
            assertThat(task.flagNotOk(ScreenField.DOBMON))
                    .as("the month alone was rejected").isTrue();
            assertThat(groupImage(task, groupConditions().get(0)))
                    .as("WS-EDIT-DT-OF-BIRTH-INVALID VALUE '000' needs all three, so it does not hold")
                    .isNotEqualTo(FLG_NOT_OK.repeat(3));
        }

        @Test
        @DisplayName("the four dead WS-RETURN-MSG literals are transcribed character for character")
        void theFourDeadReturnMessageLiteralsAreTranscribed() {
            List<String> literals = List.of(
                    AccountUpdateController.MSG_PROMPT_FOR_LASTNAME,
                    AccountUpdateController.MSG_ACCT_STATUS_MUST_BE_YES_NO,
                    AccountUpdateController.MSG_THIS_MONTH_NOT_VALID,
                    AccountUpdateController.MSG_THIS_YEAR_NOT_VALID);

            assertThat(AccountUpdateController.MSG_PROMPT_FOR_LASTNAME)
                    .as("WS-PROMPT-FOR-LASTNAME, app/cbl/COACTUPC.cbl:485-486")
                    .isEqualTo("Last name not provided");
            assertThat(AccountUpdateController.MSG_ACCT_STATUS_MUST_BE_YES_NO)
                    .as("ACCT-STATUS-MUST-BE-YES-NO, :503-504")
                    .isEqualTo("Account Active Status must be Y or N");
            assertThat(AccountUpdateController.MSG_THIS_MONTH_NOT_VALID)
                    .as("THIS-MONTH-NOT-VALID, :509-510")
                    .isEqualTo("Card expiry month must be between 1 and 12");
            assertThat(AccountUpdateController.MSG_THIS_YEAR_NOT_VALID)
                    .as("THIS-YEAR-NOT-VALID, :511-512").isEqualTo("Invalid card expiry year");

            assertThat(literals).as("four distinct texts, so no condition shadows another")
                    .doesNotHaveDuplicates();
            for (String literal : literals) {
                assertThat(literal)
                        .as("each fits WS-RETURN-MSG PIC X(75) and is not the OFF state")
                        .isNotBlank()
                        .hasSizeLessThanOrEqualTo(AccountUpdateController.WS_RETURN_MSG_OFF.length())
                        .isNotEqualTo(AccountUpdateController.WS_RETURN_MSG_OFF.strip());
            }
        }

        @Test
        @DisplayName("none of the four is ever actually set - that is what makes them dead")
        void noneOfTheFourIsEverSet() {
            for (Flag flag : FLAGS) {
                if (flag.badInput() == null) {
                    continue;
                }
                String message = editWith(flag.field(), flag.badInput()).wsReturnMsg;
                assertThat(message.strip())
                        .as("%s's rejection message must not be one of the four dead literals", flag)
                        .isNotIn(AccountUpdateController.MSG_PROMPT_FOR_LASTNAME,
                                AccountUpdateController.MSG_ACCT_STATUS_MUST_BE_YES_NO,
                                AccountUpdateController.MSG_THIS_MONTH_NOT_VALID,
                                AccountUpdateController.MSG_THIS_YEAR_NOT_VALID);
            }
        }
    }
}
