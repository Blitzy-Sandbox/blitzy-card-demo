package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vsergeychik.carddemo.account.AccountUpdateService;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.account.dto.AccountViewRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.admin.dto.AdminMenuRequest;
import com.vsergeychik.carddemo.admin.dto.AdminMenuResponse;
import com.vsergeychik.carddemo.admin.dto.MainMenuRequest;
import com.vsergeychik.carddemo.admin.dto.MainMenuResponse;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord;
import com.vsergeychik.carddemo.user.dto.UserListRequest;
import com.vsergeychik.carddemo.user.dto.SignOnResponse;
import com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A cross-cutting guard: no diagnostic rendering in this module may disclose cardholder data.
 */
@DisplayName("No diagnostic rendering discloses cardholder data")
class NoSensitiveDisclosureTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final String SENTINEL_PAN = "4111222233334444";

    private static final long SENTINEL_PAN_NUMERIC = 4111222233334444L;

    private static final long SENTINEL_ACCT_ID = 98765432109L;

    private static final int SENTINEL_CUST_ID = 987654321;

    private static final String SENTINEL_LNAME = "QUATERMASS";

    private static final String SENTINEL_FNAME = "PERCIVAL";

    private static final String SENTINEL_SSN = "078051120";

    private static final String SENTINEL_GOVT_ID = "GOVTID9988776655";

    private static final String SENTINEL_EFT = "EFT7654321";

    private static final int SENTINEL_CVV = 937;

    private static final String SENTINEL_EMBOSSED = "PERCIVAL QUATERMASS";

    private static final String SENTINEL_PASSWORD = "PWQUATER";

    private static final Map<String, String> FORBIDDEN = forbidden();

    private static Map<String, String> forbidden() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("primary account number", SENTINEL_PAN);
        values.put("account identifier", Long.toString(SENTINEL_ACCT_ID));
        values.put("customer identifier", Integer.toString(SENTINEL_CUST_ID));
        values.put("family name", SENTINEL_LNAME);
        values.put("given name", SENTINEL_FNAME);
        values.put("social security number", SENTINEL_SSN);
        values.put("government-issued identifier", SENTINEL_GOVT_ID);
        values.put("electronic funds transfer account", SENTINEL_EFT);
        return Map.copyOf(values);
    }

    private static void disclosesNothing(String what, String rendering) {
        assertThat(rendering).as("%s must produce a rendering", what).isNotNull();
        FORBIDDEN.forEach((label, value) -> assertThat(rendering)
                .as("%s discloses the %s '%s' - CWE-532", what, label, value)
                .doesNotContain(value));
    }

    private static NavigationContext populatedContext() {
        return NavigationContext.empty()
                .withFromTranid("CC00")
                .withFromProgram("COSGN00C")
                .withToTranid("CM00")
                .withToProgram("COMEN01C")
                .withUserId("ADMIN001")
                .withUserTypeAdmin()
                .withCustId(SENTINEL_CUST_ID)
                .withCustFname(SENTINEL_FNAME)
                .withCustLname(SENTINEL_LNAME)
                .withAcctId(SENTINEL_ACCT_ID)
                .withAcctStatus("Y")
                .withCardNum(SENTINEL_PAN_NUMERIC)
                .withLastMap("COMEN1A")
                .withLastMapset("COMEN01");
    }

    @Nested
    @DisplayName("NavigationContext - the record every online DTO carries")
    class TheSharedCarrier {
        @Test
        @DisplayName("it withholds the carried customer, account and card")
        void itWithholdsTheCarriedCustomerAccountAndCard() {
            disclosesNothing("NavigationContext", populatedContext().toString());
        }

        @Test
        @DisplayName("it masks rather than omits, so a rendering still correlates and reports width")
        void itMasksRatherThanOmits() {
            String rendering = populatedContext().toString();

            assertThat(rendering)
                    .contains("cardNum=************4444")
                    .contains("acctId=*******2109")
                    .contains("custId=*****4321")
                    .contains("custFname=[text len=8]")
                    .contains("custLname=[text len=10]");
        }

        @Test
        @DisplayName("the navigation state itself stays legible - it is what a flow failure is read from")
        void theNavigationStateStaysLegible() {
            assertThat(populatedContext().toString())
                    .contains("fromTranid=CC00")
                    .contains("fromProgram=COSGN00C")
                    .contains("toProgram=COMEN01C")
                    .contains("userId=ADMIN001")
                    .contains("userType=A")
                    .contains("acctStatus=Y")
                    .contains("lastMap=COMEN1A");
        }

        @Test
        @DisplayName("value semantics are untouched: equals still compares every component")
        void valueSemanticsAreUntouched() {
            assertThat(populatedContext()).isEqualTo(populatedContext());
            assertThat(populatedContext())
                    .isNotEqualTo(populatedContext().withCardNum(4111222233334445L));
            assertThat(populatedContext().cardNum())
                    .as("the accessor still returns the real value")
                    .isEqualTo(SENTINEL_PAN_NUMERIC);
        }
    }

    @Nested
    @DisplayName("The DTOs whose only exposure was the carried NavigationContext")
    class PassThroughDtos {
        private static final String[] OPTIONS = new String[12];

        static {
            java.util.Arrays.fill(OPTIONS, "01. Account View                    ");
        }

        private static final byte ENTER_AID = CicsAid.DFHENTER;

        static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> passThroughDtos() {
            NavigationContext context = populatedContext();
            return java.util.stream.Stream.of(
                    org.junit.jupiter.params.provider.Arguments.of("AdminMenuRequest",
                            (Supplier<Object>) () -> new AdminMenuRequest("CA00", "t1", "d", "COADM01C",
                                    "t2", "t", OPTIONS[0], OPTIONS[1], OPTIONS[2], OPTIONS[3],
                                    OPTIONS[4], OPTIONS[5], OPTIONS[6], OPTIONS[7], OPTIONS[8],
                                    OPTIONS[9], OPTIONS[10], OPTIONS[11], "01", "", context,
                                    ENTER_AID)),
                    org.junit.jupiter.params.provider.Arguments.of("MainMenuRequest",
                            (Supplier<Object>) () -> new MainMenuRequest("CM00", "t1", "d", "COMEN01C",
                                    "t2", "t", OPTIONS[0], OPTIONS[1], OPTIONS[2], OPTIONS[3],
                                    OPTIONS[4], OPTIONS[5], OPTIONS[6], OPTIONS[7], OPTIONS[8],
                                    OPTIONS[9], OPTIONS[10], OPTIONS[11], "01", "", context,
                                    ENTER_AID)),
                    org.junit.jupiter.params.provider.Arguments.of("AdminMenuResponse",
                            (Supplier<Object>) () -> new AdminMenuResponse("CA00", "t1", "d",
                                    "COADM01C", "t2", "t", OPTIONS[0], OPTIONS[1], OPTIONS[2],
                                    OPTIONS[3], OPTIONS[4], OPTIONS[5], OPTIONS[6], OPTIONS[7],
                                    OPTIONS[8], OPTIONS[9], OPTIONS[10], OPTIONS[11], "01", "",
                                    context, "COUSR00C", "COUSR00", "COUSR0A", ENTER_AID, false)),
                    org.junit.jupiter.params.provider.Arguments.of("MainMenuResponse",
                            (Supplier<Object>) () -> new MainMenuResponse("CM00", "t1", "d", "COMEN01C",
                                    "t2", "t", OPTIONS[0], OPTIONS[1], OPTIONS[2], OPTIONS[3],
                                    OPTIONS[4], OPTIONS[5], OPTIONS[6], OPTIONS[7], OPTIONS[8],
                                    OPTIONS[9], OPTIONS[10], OPTIONS[11], "01", "", context,
                                    "COACTVWC", "COACTVW", "CACTVWA", ENTER_AID, false)));
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("the embedded context is rendered through its masked form")
        @MethodSource("passThroughDtos")
        void theEmbeddedContextIsMasked(String name, Supplier<Object> dto) {
            disclosesNothing(name, dto.get().toString());
        }

        @Test
        @DisplayName("the report request withholds the carried context")
        void theReportRequestWithholdsTheCarriedContext() {
            disclosesNothing("ReportRequestRequest",
                    new ReportRequestRequest("CR00", "t1", "d", "CORPT00C", "t2", "t", "N", "N", "Y",
                            "01", "01", "2022", "31", "12", "2022", "Y", "", populatedContext(),
                            "ENTER")
                            .toString());
        }

        @Test
        @DisplayName("the sign-on response withholds the carried context and the PASSWDO span")
        void theSignOnResponseWithholdsTheCarriedContext() {
            String rendering = new SignOnResponse("CC00", "t1", "d", "COSGN00C", "t2", "t", "CICS",
                    "CICS", "ADMIN001", SENTINEL_PASSWORD, "", "A", "COADM01C", "COADM01", "COADM1A",
                    "", populatedContext())
                    .toString();

            disclosesNothing("SignOnResponse", rendering);
            assertThat(rendering)
                    .as("the PASSWDO span reaches a 3270 as dark field data; a log line is not a 3270")
                    .doesNotContain(SENTINEL_PASSWORD)
                    .contains(SensitiveDiagnostics.REDACTED);
            assertThat(rendering)
                    .as("the operator id and the resolved role are the point of this response")
                    .contains("ADMIN001")
                    .contains("COADM01C");
        }

        @Test
        @DisplayName("the user list request withholds the ten listed users' names")
        void theUserListRequestWithholdsTheListedNames() {
            List<UserListRequest.UserListRow> rows = new java.util.ArrayList<>();
            for (int row = 1; row <= 10; row++) {
                rows.add(new UserListRequest.UserListRow("", String.format("USER%04d", row),
                        SENTINEL_FNAME, SENTINEL_LNAME, "U"));
            }

            String rendering = new UserListRequest("CU00", "t1", "d", "COUSR00C", "t2", "t", "1",
                    "USER0001", rows, "", "USER0001", "USER0010", 1, "Y", "S", "USER0003",
                    populatedContext(), "ENTER").toString();

            disclosesNothing("UserListRequest", rendering);
            assertThat(rendering)
                    .as("all ten rows are masked, not just the first")
                    .doesNotContain(SENTINEL_FNAME, SENTINEL_LNAME)
                    .contains("fname=[text len=8]")
                    .contains("lname=[text len=10]");
            assertThat(rendering)
                    .as("the operator ids stay legible - they are what a pagination failure is read from")
                    .contains("USER0001")
                    .contains("USER0010");
        }

        @Test
        @DisplayName("a single UserListRow withholds its own name")
        void aSingleRowWithholdsItsName() {
            disclosesNothing("UserListRequest.UserListRow",
                    new UserListRequest.UserListRow("", "USER0001", SENTINEL_FNAME, SENTINEL_LNAME, "U")
                            .toString());
        }

        @Test
        @DisplayName("the menu option lines themselves stay legible - they carry no personal data")
        void theMenuOptionLinesStayLegible() {
            String rendering = new MainMenuRequest("CM00", "t1", "d", "COMEN01C", "t2", "t",
                    OPTIONS[0], OPTIONS[1], OPTIONS[2], OPTIONS[3], OPTIONS[4], OPTIONS[5], OPTIONS[6],
                    OPTIONS[7], OPTIONS[8], OPTIONS[9], OPTIONS[10], OPTIONS[11], "01", "",
                    populatedContext(), ENTER_AID).toString();

            assertThat(rendering).contains("01. Account View").contains("COMEN01C");
        }
    }

    @Nested
    @DisplayName("The copybook record types")
    class RecordTypes {
        @Test
        @DisplayName("CardRecord withholds the PAN, the account key, the CVV and the embossed name")
        void cardRecordWithholdsEverythingSensitive() {
            CardRecord record = new CardRecord(SENTINEL_PAN, SENTINEL_ACCT_ID, SENTINEL_CVV,
                    SENTINEL_EMBOSSED, "2025-12-31", "Y");

            String rendering = record.toString();

            disclosesNothing("CardRecord", rendering);
            assertThat(rendering)
                    .as("a three-digit CVV has no safely-revealable part, so it is withheld outright")
                    .doesNotContain(Integer.toString(SENTINEL_CVV))
                    .contains("CARD-CVV-CD=[redacted]");
            assertThat(rendering)
                    .as("the expiry and status stay legible for card-validation parity")
                    .contains("2025-12-31")
                    .contains("CARD-ACTIVE-STATUS='Y'");
        }

        @Test
        @DisplayName("CardXrefRecord withholds all three of its fields")
        void cardXrefRecordWithholdsAllThree() {
            disclosesNothing("CardXrefRecord",
                    new CardXrefRecord(SENTINEL_PAN, SENTINEL_CUST_ID, SENTINEL_ACCT_ID).toString());
        }

        @Test
        @DisplayName("CustomerRecord withholds the whole identity dossier")
        void customerRecordWithholdsTheIdentityDossier() {
            CustomerRecord record = new CustomerRecord();
            record.setCustId(SENTINEL_CUST_ID);
            record.setCustFirstName(SENTINEL_FNAME);
            record.setCustLastName(SENTINEL_LNAME);
            record.setCustSsn(Integer.parseInt(SENTINEL_SSN));
            record.setCustGovtIssuedId(SENTINEL_GOVT_ID);
            record.setCustEftAccountId(SENTINEL_EFT);
            record.setCustPhoneNum1("(555)555-0100");
            record.setCustAddrLine1("1 QUATERMASS LANE");

            String rendering = record.toString();

            disclosesNothing("CustomerRecord", rendering);
            assertThat(rendering)
                    .contains("CUST-SSN=[redacted]")
                    .contains("CUST-GOVT-ISSUED-ID=[redacted]")
                    .contains("CUST-EFT-ACCOUNT-ID=[redacted]")
                    .doesNotContain("(555)555-0100");
            assertThat(record.getCustGovtIssuedId())
                    .as("the accessor still returns the real value, padded to its declared width")
                    .startsWith(SENTINEL_GOVT_ID);
        }

        @Test
        @DisplayName("Stm03CustomerRecord withholds the same fields as its CVCUS01Y twin")
        void stm03CustomerRecordMatchesItsTwin() {
            Stm03CustomerRecord record = new Stm03CustomerRecord(
                    Integer.toString(SENTINEL_CUST_ID), SENTINEL_FNAME, "Q", SENTINEL_LNAME,
                    "1 QUATERMASS LANE", "", "", "IL", "USA", "62701", "(555)555-0100", "",
                    SENTINEL_SSN, SENTINEL_GOVT_ID, "1980-01-15", SENTINEL_EFT, "Y", "750");

            String rendering = record.toString();

            disclosesNothing("Stm03CustomerRecord", rendering);
            assertThat(rendering)
                    .contains("CUST-SSN=[redacted]")
                    .contains("CUST-GOVT-ISSUED-ID=[redacted]")
                    .contains("CUST-EFT-ACCOUNT-ID=[redacted]");
            assertThat(record.custSsn())
                    .as("the component still holds the real value")
                    .isEqualTo(SENTINEL_SSN);
        }

        @Test
        @DisplayName("the two customer twins withhold exactly the same fields")
        void theTwoCustomerTwinsAgree() {
            CustomerRecord cvcus = new CustomerRecord();
            cvcus.setCustSsn(Integer.parseInt(SENTINEL_SSN));
            cvcus.setCustGovtIssuedId(SENTINEL_GOVT_ID);
            cvcus.setCustEftAccountId(SENTINEL_EFT);
            Stm03CustomerRecord custrec = Stm03CustomerRecord.blank(ASCII);

            for (String withheld : new String[] {
                "CUST-SSN=[redacted]", "CUST-GOVT-ISSUED-ID=[redacted]",
                "CUST-EFT-ACCOUNT-ID=[redacted]"}) {
                assertThat(cvcus.toString()).as("CVCUS01Y withholds %s", withheld).contains(withheld);
                assertThat(custrec.toString()).as("CUSTREC withholds %s", withheld).contains(withheld);
            }
        }
    }

    @Nested
    @DisplayName("CARD-UPDATE-RECORD - the request and response twins")
    class CardUpdateRecords {
        private CardUpdateRequest.CardUpdateRecord request() {
            return new CardUpdateRequest.CardUpdateRecord(SENTINEL_PAN, SENTINEL_ACCT_ID, 456,
                    SENTINEL_LNAME, "2029-12-31", "Y");
        }

        private CardUpdateRequest.CardUpdateRecord response() {
            CardUpdateResponse payload = new CardUpdateResponse();
            payload.setCommArea(CardUpdateRequest.CommArea.initialised()
                    .withCardUpdateRecord(request()));
            return payload.getCommArea().cardUpdateRecord();
        }

        @Test
        @DisplayName("the record withholds the card number, account, CVV and embossed name")
        void bothTwinsWithholdThePaymentData() {
            disclosesNothing("CardUpdateRequest.CardUpdateRecord", request().toString());
            disclosesNothing("the response's embedded CardUpdateRecord", response().toString());
            assertThat(response()).isInstanceOf(CardUpdateRequest.CardUpdateRecord.class);
            assertThat(CardUpdateResponse.class.getDeclaredClasses())
                    .as("no twin of it is declared on the response")
                    .noneMatch(nested -> "CardUpdateRecord".equals(nested.getSimpleName()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.common.NoSensitiveDisclosureTest$CardUpdateRecords"
                + "#bothTwins")
        @DisplayName("the rendering states presence and width without stating content")
        void theRenderingStatesPresenceNotContent(String name, String rendering) {
            assertThat(rendering).as("%s names its type", name).contains("CardUpdateRecord[");
            assertThat(rendering).as("%s reveals the last four PAN digits", name).contains("4444");
            assertThat(rendering).as("%s masks the leading PAN digits", name)
                    .doesNotContain(SENTINEL_PAN);
            assertThat(rendering).as("%s withholds the CVV outright", name)
                    .contains("cardUpdateCvvCd=" + SensitiveDiagnostics.REDACTED);
            assertThat(rendering).as("%s reports the embossed name by width only", name)
                    .contains("cardUpdateEmbossedName=[text len=");
            assertThat(rendering).as("%s keeps the expiry date legible for diagnosis", name)
                    .contains("2029-12-31");
            assertThat(rendering).as("%s keeps the status code legible for diagnosis", name)
                    .contains("cardUpdateActiveStatus='Y'");
        }

        static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> bothTwins() {
            return java.util.stream.Stream.of(
                    org.junit.jupiter.params.provider.Arguments.of("request twin",
                            new CardUpdateRequest.CardUpdateRecord(SENTINEL_PAN, SENTINEL_ACCT_ID, 456,
                                    SENTINEL_LNAME, "2029-12-31", "Y").toString()),
                    org.junit.jupiter.params.provider.Arguments.of("the response's embedded record",
                            CardUpdateRequest.CommArea.initialised().withCardUpdateRecord(
                                    new CardUpdateRequest.CardUpdateRecord(SENTINEL_PAN,
                                            SENTINEL_ACCT_ID, 456, SENTINEL_LNAME, "2029-12-31", "Y"))
                                    .cardUpdateRecord().toString()));
        }

        @Test
        @DisplayName("masking the rendering leaves the stored values and the 150-byte image untouched")
        void theStoredValuesAndImageAreUntouched() {
            CardUpdateRequest.CardUpdateRecord record = response();
            assertThat(record.cardUpdateNum()).isEqualTo(SENTINEL_PAN);
            assertThat(record.cardUpdateAcctId()).isEqualTo(SENTINEL_ACCT_ID);
            assertThat(record.cardUpdateCvvCd()).isEqualTo(456);
            assertThat(record.encode(ASCII))
                    .as("CARD-UPDATE-RECORD occupies 150 bytes including its trailing FILLER")
                    .hasSize(CardUpdateRequest.CardUpdateRecord.RECORD_LENGTH);
            assertThat(request().cardUpdateNum()).isEqualTo(SENTINEL_PAN);
            assertThat(request().encode(ASCII))
                    .hasSize(CardUpdateRequest.CardUpdateRecord.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Structural guard - no record's rendering discloses a sensitive component")
    class StructuralGuard {
        private static final Set<String> SENSITIVE_COMPONENTS = Set.of(
                "cardnum", "cardid", "cardupdatenum", "cvvcd", "cvv",
                "custssn", "ssn", "custgovtissuedid", "govtissuedid",
                "custeftaccountid", "eftaccountid", "passwd", "pwd", "password",
                "fname", "lname", "mname", "custfname", "custlname", "custmname",
                "embossedname", "cardupdateembossedname", "crdname",
                "secusrfname", "secusrlname", "secusrpwd");

        private static final Set<String> KNOWN_OUT_OF_SCOPE = Set.of(
                "com.vsergeychik.carddemo.card.dto.CardListRequest$CardKey",
                "com.vsergeychik.carddemo.card.dto.CardListRequest$ScreenRow",
                "com.vsergeychik.carddemo.user.dto.UserUpdateResponse",
                "com.vsergeychik.carddemo.account.dto.AccountUpdateRequest$CustSnapshot");

        private static final Set<String> NOT_SWEPT = Set.of(
                "com.vsergeychik.carddemo.card.dto.CardUpdateRequest$CardDetails",
                "com.vsergeychik.carddemo.user.dto.UserDeleteResponse");

        private static final Map<String, String> CLASSIFIED_RENDERINGS = Map.of(
                "com.vsergeychik.carddemo.account.dto.AccountUpdateResponse", "ACTSSN1",
                "com.vsergeychik.carddemo.transaction.dto.TransactionListResponse", "TRNID01O",
                "com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse", "ACTIDIN",
                "com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest", "ACTIDIN",
                "com.vsergeychik.carddemo.card.dto.CardListResponse", "CRDNUM1",
                "com.vsergeychik.carddemo.card.dto.CardSelectRequest", "CARDSID",
                "com.vsergeychik.carddemo.card.dto.CardUpdateRequest", "CARDSID");

        private static final String CLASSIFIER_METHOD = "disclosureOf";

        private static final Set<String> UNLOADABLE_EXEMPT = Set.of();

        private static final String PROBE = "ZQX7";

        private static final Set<String> RENDERINGS_NOT_PROBED = Set.of(
                "com.vsergeychik.carddemo.transaction.model.TranRecord",
                "com.vsergeychik.carddemo.account.model.DisclosureGroupRecord",
                "com.vsergeychik.carddemo.account.AccountDateValidator$EditDateState");

        private record ProbeCase(Object instance, List<String> forbidden) { }

        private static final Map<String, Supplier<ProbeCase>> PROBE_FACTORIES = Map.of(
                "com.vsergeychik.carddemo.user.model.SecUserRecord",
                () -> new ProbeCase(
                        new com.vsergeychik.carddemo.user.model.SecUserRecord(
                                atWidth(PROBE, 8), atWidth(PROBE + "FNAME", 20),
                                atWidth(PROBE + "LNAME", 20), atWidth(PROBE + "PW", 8), "A",
                                atWidth("", 23)),
                        List.of(PROBE + "FNAME", PROBE + "LNAME", PROBE + "PW")),

                "com.vsergeychik.carddemo.transaction.model.TranCatBalRecord",
                () -> new ProbeCase(
                        com.vsergeychik.carddemo.transaction.model.TranCatBalRecord
                                .newInstance(StandardCharsets.US_ASCII)
                                .trancatAcctId(12345678901L)
                                .tranCatBal(new java.math.BigDecimal("-987654321.99")),
                        List.of("12345678901", "987654321.99", "98765432199", "9876543219R")),

                "com.vsergeychik.carddemo.account.model.AccountRecord",
                () -> {
                    com.vsergeychik.carddemo.account.model.AccountRecord account =
                            com.vsergeychik.carddemo.account.model.AccountRecord.decode(
                                    " ".repeat(300), StandardCharsets.US_ASCII);
                    account.setAcctId(12345678901L);
                    return new ProbeCase(account, List.of("12345678901"));
                },

                "com.vsergeychik.carddemo.statement.model.TrnxRecord",
                () -> {
                    com.vsergeychik.carddemo.statement.model.TrnxRecord trnx =
                            com.vsergeychik.carddemo.statement.model.TrnxRecord.decode(
                                    " ".repeat(350).getBytes(StandardCharsets.US_ASCII),
                                    StandardCharsets.US_ASCII);
                    trnx.writeTrnxCardNum(atWidth(PROBE + "CARDNUM", 16));
                    return new ProbeCase(trnx, List.of(PROBE + "CARDNUM"));
                },

                "com.vsergeychik.carddemo.transaction.model.DalyTranRecord",
                () -> {
                    com.vsergeychik.carddemo.transaction.model.DalyTranRecord daly =
                            new com.vsergeychik.carddemo.transaction.model.DalyTranRecord(
                                    StandardCharsets.US_ASCII);
                    daly.moveDalytranCardNum(atWidth(PROBE + "CARDNUM", 16));
                    return new ProbeCase(daly, List.of(PROBE + "CARDNUM"));
                },

                "com.vsergeychik.carddemo.user.dto.SignOnResponse",
                () -> new ProbeCase(
                        new com.vsergeychik.carddemo.user.dto.SignOnResponse(
                                atWidth(PROBE, 4), atWidth("", 40), atWidth("", 8), atWidth("", 8),
                                atWidth("", 40), atWidth("", 9), atWidth("", 8), atWidth("", 8),
                                atWidth(PROBE + "ID", 8), atWidth(PROBE + "PW", 8), atWidth("", 78),
                                "A", atWidth("", 8), atWidth("", 7), atWidth("", 7),
                                atWidth("", 80), populatedContext()),
                        List.of(PROBE + "PW")));

        private static String atWidth(String value, int width) {
            return value.length() >= width
                    ? value.substring(0, width)
                    : value + " ".repeat(width - value.length());
        }

        private static boolean isSensitive(String name) {
            return SENSITIVE_COMPONENTS.contains(name.toLowerCase(java.util.Locale.ROOT));
        }

        private static List<Class<?>> allRecords() throws Exception {
            List<Class<?>> found = new java.util.ArrayList<>();
            for (Class<?> type : allCompiledTypes()) {
                if (type.isRecord()) {
                    found.add(type);
                }
            }
            assertThat(found).as("the module must contain record types to check").isNotEmpty();
            return found;
        }

        private static List<Class<?>> allSpanAddressedTypes() throws Exception {
            List<Class<?>> found = new java.util.ArrayList<>();
            for (Class<?> type : allCompiledTypes()) {
                if (type.isRecord() || type.isInterface() || type.isEnum()) {
                    continue;
                }
                boolean holdsAnArea = java.util.Arrays.stream(type.getDeclaredFields())
                        .anyMatch(field -> field.getType() == FixedWidthRecord.class);
                if (holdsAnArea && declaresItsOwnRendering(type)) {
                    found.add(type);
                }
            }
            assertThat(found)
                    .as("the module must contain span-addressed types for this guard to be live")
                    .isNotEmpty();
            return found;
        }

        private static boolean declaresItsOwnRendering(Class<?> type) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if ("toString".equals(method.getName()) && method.getParameterCount() == 0
                        && !method.isSynthetic()) {
                    return true;
                }
            }
            return false;
        }

        private static List<Class<?>> allCompiledTypes() throws Exception {
            java.nio.file.Path classes = java.nio.file.Path.of(NavigationContext.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            assertThat(java.nio.file.Files.isDirectory(classes))
                    .as("the compiled main output must be a directory to walk: %s", classes).isTrue();
            List<Class<?>> found = new java.util.ArrayList<>();
            Map<String, String> unloadable = new java.util.TreeMap<>();
            try (java.util.stream.Stream<java.nio.file.Path> walk =
                         java.nio.file.Files.walk(classes)) {
                for (java.nio.file.Path f : walk.filter(x -> x.toString().endsWith(".class")).toList()) {
                    String binary = classes.relativize(f).toString()
                            .replace(java.io.File.separatorChar, '.').replaceAll("\\.class$", "");
                    loadOrRecord(binary, found, unloadable);
                }
            }
            assertEveryClassLoaded(unloadable);
            assertThat(found).as("the compiled output must contain classes to walk").isNotEmpty();
            return found;
        }

        private static void loadOrRecord(String binary, List<Class<?>> loaded,
                                         Map<String, String> unloadable) {
            try {
                loaded.add(Class.forName(binary, false, NavigationContext.class.getClassLoader()));
            } catch (Throwable failure) {
                unloadable.put(binary, com.vsergeychik.carddemo.parity.ParityCase.Redaction
                        .describeThrowable(failure));
            }
        }

        private static void assertEveryClassLoaded(Map<String, String> unloadable) {
            Map<String, String> unexplained = new java.util.TreeMap<>(unloadable);
            unexplained.keySet().removeAll(UNLOADABLE_EXEMPT);
            assertThat(unexplained)
                    .as("every class compiled into this module must be loadable for this sweep to be "
                            + "complete. A class that cannot be loaded is not inspected, and a class "
                            + "that is not inspected is not vouched for - so each one here must either "
                            + "be made loadable or be admitted to UNLOADABLE_EXEMPT with the reason "
                            + "and a per-type disclosure test standing in for it.")
                    .isEmpty();
            assertThat(UNLOADABLE_EXEMPT)
                    .as("an exemption that no longer applies has to go, or it starts excusing a "
                            + "different class than the one it was written for")
                    .allSatisfy(exempt -> assertThat(unloadable)
                            .as("%s is exempted from loading but loaded fine", exempt)
                            .containsKey(exempt));
        }

        private static Object probe(Class<?> type) {
            Supplier<ProbeCase> registered = PROBE_FACTORIES.get(type.getName());
            if (registered != null) {
                return registered.get().instance();
            }
            java.lang.reflect.RecordComponent[] components = type.getRecordComponents();
            Class<?>[] parameters = java.util.Arrays.stream(components)
                    .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
            Object[] arguments = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                Class<?> t = components[i].getType();
                if (t == String.class) {
                    arguments[i] = isSensitive(components[i].getName()) ? PROBE : "A";
                } else if (t == long.class) {
                    arguments[i] = 1L;
                } else if (t == int.class) {
                    arguments[i] = 1;
                } else if (t == boolean.class) {
                    arguments[i] = false;
                } else if (t == char.class) {
                    arguments[i] = 'A';
                } else if (t == byte.class) {
                    arguments[i] = (byte) 1;
                } else if (t == java.math.BigDecimal.class) {
                    arguments[i] = java.math.BigDecimal.ZERO;
                } else if (t == java.util.Optional.class) {
                    arguments[i] = java.util.Optional.empty();
                } else if (t == List.class) {
                    arguments[i] = List.of();
                } else if (t == Map.class) {
                    arguments[i] = Map.of();
                } else {
                    arguments[i] = null;
                }
            }
            try {
                java.lang.reflect.Constructor<?> canonical = type.getDeclaredConstructor(parameters);
                canonical.setAccessible(true);
                return canonical.newInstance(arguments);
            } catch (Throwable rejected) {
                return null;
            }
        }

        private static Map<Boolean, List<String>> sweep() throws Exception {
            List<String> disclosing = new java.util.ArrayList<>();
            List<String> clean = new java.util.ArrayList<>();
            for (Class<?> type : allRecords()) {
                boolean bearsSensitive = java.util.Arrays.stream(type.getRecordComponents())
                        .anyMatch(c -> c.getType() == String.class && isSensitive(c.getName()));
                if (!bearsSensitive) {
                    continue;
                }
                Object instance = probe(type);
                if (instance == null) {
                    assertThat(NOT_SWEPT)
                            .as("%s could not be populated with a generic sentinel, so this sweep "
                                    + "cannot vouch for it. Either relax its constructor or assert its "
                                    + "rendering in a per-type test and record it here.", type.getName())
                            .contains(type.getName());
                    continue;
                }
                (disclosesAnyForbiddenValue(type, instance) ? disclosing : clean).add(type.getName());
            }
            return Map.of(true, disclosing, false, clean);
        }

        private static boolean disclosesAnyForbiddenValue(Class<?> type, Object instance) {
            Supplier<ProbeCase> registered = PROBE_FACTORIES.get(type.getName());
            List<String> forbidden = registered == null
                    ? List.of(PROBE)
                    : registered.get().forbidden();
            String rendered = String.valueOf(instance);
            for (String value : forbidden) {
                if (rendered.contains(value)) {
                    return true;
                }
            }
            return false;
        }

        @Test
        @DisplayName("no record beyond the documented out-of-scope set discloses a sensitive component")
        void nothingBeyondTheDocumentedSetDiscloses() throws Exception {
            assertThat(sweep().get(true))
                    .as("these renderings publish a sensitive component verbatim - CWE-532")
                    .isSubsetOf(KNOWN_OUT_OF_SCOPE);
        }

        @Test
        @DisplayName("every span-addressed non-record type is registered or documented")
        void everySpanAddressedTypeIsAccountedFor() throws Exception {
            List<String> unaccounted = new java.util.ArrayList<>();
            for (Class<?> type : allSpanAddressedTypes()) {
                if (!PROBE_FACTORIES.containsKey(type.getName())
                        && !RENDERINGS_NOT_PROBED.contains(type.getName())) {
                    unaccounted.add(type.getName());
                }
            }
            assertThat(unaccounted)
                    .as("each of these renders a fixed-width record area and no disclosure decision "
                            + "has been recorded for it. Register a probe in PROBE_FACTORIES if the "
                            + "rendering touches personal data, or add it to RENDERINGS_NOT_PROBED with "
                            + "the reason it does not.")
                    .isEmpty();
        }

        @Test
        @DisplayName("every registered probe is exercised, and each withholds what it declared")
        void everyRegisteredProbeWithholdsWhatItDeclared() {
            assertThat(PROBE_FACTORIES).hasSizeGreaterThanOrEqualTo(2)
                    .containsKeys("com.vsergeychik.carddemo.user.model.SecUserRecord",
                            "com.vsergeychik.carddemo.transaction.model.TranCatBalRecord");
            PROBE_FACTORIES.forEach((name, factory) -> {
                ProbeCase probeCase = factory.get();
                assertThat(probeCase.forbidden())
                        .as("%s's probe must declare at least one value to withhold", name)
                        .isNotEmpty();
                String rendered = String.valueOf(probeCase.instance());
                assertThat(rendered).as("%s must render something", name).isNotEmpty();
                for (String forbidden : probeCase.forbidden()) {
                    assertThat(rendered)
                            .as("%s's rendering discloses '%s' - CWE-532", name, forbidden)
                            .doesNotContain(forbidden);
                }
                assertThat(rendered.lines()).as("%s must render on one line", name).hasSize(1);
            });
        }

        private static List<Class<?>> allClassifiedRenderings() throws Exception {
            List<Class<?>> found = new java.util.ArrayList<>();
            for (Class<?> type : allCompiledTypes()) {
                boolean classifies = java.util.Arrays.stream(type.getDeclaredMethods())
                        .anyMatch(method -> CLASSIFIER_METHOD.equals(method.getName())
                                && method.getParameterCount() == 1
                                && method.getReturnType()
                                == SensitiveDiagnostics.Disclosure.class);
                if (classifies) {
                    found.add(type);
                }
            }
            return found;
        }

        @SuppressWarnings("unchecked")
        private static SensitiveDiagnostics.Disclosure classify(Class<?> type, String fieldName)
                throws Exception {
            java.lang.reflect.Method classifier = java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> CLASSIFIER_METHOD.equals(method.getName())
                            && method.getParameterCount() == 1)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(type.getName() + " declares no "
                            + CLASSIFIER_METHOD));
            classifier.setAccessible(true);
            Class<?> parameter = classifier.getParameterTypes()[0];
            Object argument = null;
            if (fieldName != null) {
                argument = parameter == String.class
                        ? fieldName
                        : Enum.valueOf((Class<Enum>) parameter.asSubclass(Enum.class), fieldName);
            }
            return (SensitiveDiagnostics.Disclosure) classifier.invoke(null, argument);
        }

        @Test
        @DisplayName("every screen DTO that classifies its own rendering is discovered and registered")
        void everyClassifiedRenderingIsRegistered() throws Exception {
            List<String> discovered = allClassifiedRenderings().stream()
                    .map(Class::getName).sorted().toList();

            assertThat(discovered)
                    .as("the third discovery arm must be live - a module with no classifying DTO would "
                            + "make this whole guard vacuous")
                    .isNotEmpty();
            assertThat(discovered)
                    .as("a screen DTO that classifies its rendering must say what it withholds. The two "
                            + "this checkpoint added - AccountUpdateResponse and TransactionListResponse "
                            + "- were rendering a social-security number and a page of transaction keys "
                            + "with neither of the other two discovery arms able to see them.")
                    .containsExactlyInAnyOrderElementsOf(
                            new java.util.TreeSet<>(CLASSIFIED_RENDERINGS.keySet()));
        }

        @Test
        @DisplayName("each one withholds the item it is registered against, and defaults to withholding")
        void eachClassifiedRenderingWithholdsWhatItRegistered() throws Exception {
            for (Class<?> type : allClassifiedRenderings()) {
                String mustWithhold = CLASSIFIED_RENDERINGS.get(type.getName());

                assertThat(classify(type, mustWithhold))
                        .as("%s must not render %s as stored", type.getName(), mustWithhold)
                        .isNotEqualTo(SensitiveDiagnostics.Disclosure.PLAIN);
                assertThat(classify(type, null))
                        .as("%s must withhold a field nobody classified rather than publish it - a "
                                + "disclosure decision has to fail closed", type.getName())
                        .isEqualTo(SensitiveDiagnostics.Disclosure.REDACTED_VALUE);
            }
        }

        @Test
        @DisplayName("the two response types this checkpoint fixed withhold their values in the rendering")
        void theTwoFixedResponsesWithholdInTheRendering() {
            String rendered = com.vsergeychik.carddemo.account.dto.AccountUpdateResponse.initial()
                    .withValue(com.vsergeychik.carddemo.account.dto.AccountUpdateResponse
                            .ScreenField.ACTSSN1, PROBE.substring(0, 3))
                    .withValue(com.vsergeychik.carddemo.account.dto.AccountUpdateResponse
                            .ScreenField.ACSGOVT, PROBE.repeat(5))
                    .toString();
            assertThat(rendered)
                    .as("AccountUpdateResponse: the social-security part and the government identifier "
                            + "must not survive into the rendering")
                    .doesNotContain(PROBE.repeat(5))
                    .contains("ACTSSN1='" + SensitiveDiagnostics.REDACTED + "'");

            com.vsergeychik.carddemo.transaction.dto.TransactionListResponse list =
                    new com.vsergeychik.carddemo.transaction.dto.TransactionListResponse();
            list.setTrnid01O("00000000000ZQX7");
            assertThat(list.toString())
                    .as("TransactionListResponse: a transaction key is a TRANSACT key and the record it "
                            + "opens carries TRAN-CARD-NUM, so it is masked to its trailing characters")
                    .doesNotContain("00000000000ZQX7")
                    .contains("TRNID01O='" + SensitiveDiagnostics
                            .maskIdentifier(list.getTrnid01O()) + "'");
        }

        @Test
        @DisplayName("a class that cannot be loaded is recorded with a sanitised reason, not dropped")
        void aClassThatCannotBeLoadedIsRecorded() {
            List<Class<?>> loaded = new java.util.ArrayList<>();
            Map<String, String> unloadable = new java.util.TreeMap<>();

            loadOrRecord("com.vsergeychik.carddemo.NoSuchTypeExistsHere", loaded, unloadable);

            assertThat(loaded).as("nothing was loaded, so nothing may be claimed as inspected")
                    .isEmpty();
            assertThat(unloadable)
                    .as("the failure is written down against the class it happened to")
                    .containsOnlyKeys("com.vsergeychik.carddemo.NoSuchTypeExistsHere");
            assertThat(unloadable.values().iterator().next())
                    .as("and the reason names the throwable's type, so a reader can act on it")
                    .contains(ClassNotFoundException.class.getName());
        }

        @Test
        @DisplayName("an unloadable class fails the sweep rather than shrinking it")
        void anUnloadableClassFailsTheSweep() {
            assertThatThrownBy(() -> assertEveryClassLoaded(Map.of(
                    "com.vsergeychik.carddemo.account.model.AccountRecord", "linkage error")))
                    .as("a class the sweep could not inspect must break the build, naming the class")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("com.vsergeychik.carddemo.account.model.AccountRecord")
                    .hasMessageContaining("UNLOADABLE_EXEMPT");

            assertThatCode(() -> assertEveryClassLoaded(Map.of()))
                    .as("and the real state of this module - nothing unloadable - passes")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the guard is live - it sweeps real types and the fixed ones come back clean")
        void theGuardIsLive() throws Exception {
            assertThat(sweep().get(false))
                    .as("the sweep must reach the masked types and find them clean")
                    .contains("com.vsergeychik.carddemo.common.NavigationContext",
                            "com.vsergeychik.carddemo.card.model.CardRecord",
                            "com.vsergeychik.carddemo.card.dto.CardUpdateRequest$CardUpdateRecord",
                            "com.vsergeychik.carddemo.user.model.SecUserRecord")
                    .hasSizeGreaterThan(5);
        }
    }

    @Nested
    @DisplayName("the account screen DTOs, the update service records, and the transaction diagnostics")
    class BoundaryRenderings {
        @Test
        @DisplayName("the account view request discloses none of the sentinels")
        void accountViewRequestDisclosesNothing() {
            AccountViewRequest request = new AccountViewRequest();
            request.setAcctsid(Long.toString(SENTINEL_ACCT_ID));
            request.setAcstssn(SENTINEL_SSN);
            request.setAcsgovt(SENTINEL_GOVT_ID);
            request.setAcseftc(SENTINEL_EFT);
            request.setAcsfnam(SENTINEL_FNAME);
            request.setAcslnam(SENTINEL_LNAME);

            disclosesNothing("AccountViewRequest", request.toString());
        }

        @Test
        @DisplayName("the account update request and both of its snapshots disclose none of the "
                + "sentinels")
        void accountUpdateRequestDisclosesNothing() {
            AccountUpdateRequest request = AccountUpdateRequest.builder()
                    .acctsid(Long.toString(SENTINEL_ACCT_ID))
                    .actssn1(SENTINEL_SSN.substring(0, 3))
                    .actssn2(SENTINEL_SSN.substring(3, 5))
                    .actssn3(SENTINEL_SSN.substring(5))
                    .acsgovt(SENTINEL_GOVT_ID)
                    .acseftc(SENTINEL_EFT)
                    .acsfnam(SENTINEL_FNAME)
                    .acslnam(SENTINEL_LNAME)
                    .build();

            disclosesNothing("AccountUpdateRequest", request.toString());

            AccountUpdateRequest.CustSnapshot cust = new AccountUpdateRequest.CustSnapshot(
                    Integer.toString(SENTINEL_CUST_ID), SENTINEL_FNAME, "Q", SENTINEL_LNAME,
                    "1 MAIN ST", "APT 2", "SPRINGFIELD", "IL", "USA", "62704-0001",
                    "(217)555-1234", "(217)555-9876", SENTINEL_SSN, SENTINEL_GOVT_ID, "19800704",
                    SENTINEL_EFT, "Y", "750");
            AccountUpdateRequest.AcctSnapshot acct = new AccountUpdateRequest.AcctSnapshot(
                    Long.toString(SENTINEL_ACCT_ID), "Y", "1000.00", "5000.00", "500.00",
                    "20220101", "20270101", "20240101", "10.00", "20.00", "GROUP01");

            disclosesNothing("AccountUpdateRequest.CustSnapshot", cust.toString());
            disclosesNothing("AccountUpdateRequest.AcctSnapshot", acct.toString());
            disclosesNothing("AccountUpdateRequest.Details",
                    new AccountUpdateRequest.Details(DetailGroup.OLD, acct, cust).toString());
        }

        @Test
        @DisplayName("the update service's account and customer subgroups disclose none of the "
                + "sentinels")
        void accountUpdateServiceRecordsDiscloseNothing() {
            AccountUpdateService.CustomerData customer = new AccountUpdateService.CustomerData(
                    SENTINEL_CUST_ID, SENTINEL_FNAME, "Q", SENTINEL_LNAME,
                    "1 MAIN ST", "APT 2", "SPRINGFIELD", "IL", "USA", "62704-0001",
                    "(217)555-1234", "(217)555-9876", Integer.parseInt(SENTINEL_SSN),
                    SENTINEL_GOVT_ID, "1980", "07", "04", SENTINEL_EFT, "Y", 750);
            AccountUpdateService.AccountData account = new AccountUpdateService.AccountData(
                    SENTINEL_ACCT_ID, "Y", new BigDecimal("1000.00"), new BigDecimal("5000.00"),
                    new BigDecimal("500.00"), "2022", "01", "01", "2027", "01", "01",
                    "2024", "01", "01", new BigDecimal("10.00"), new BigDecimal("20.00"), "GROUP01");

            disclosesNothing("AccountUpdateService.CustomerData", customer.toString());
            disclosesNothing("AccountUpdateService.AccountData", account.toString());
            disclosesNothing("AccountUpdateService.AccountUpdateDetails",
                    new AccountUpdateService.AccountUpdateDetails(
                            AccountUpdateService.DetailGroup.OLD, account, customer).toString());
        }

        @Test
        @DisplayName("the daily transaction record discloses none of the sentinels, and withholds the "
                + "amount")
        void dalyTranRecordDisclosesNothing() {
            String image = (SENTINEL_PAN + "0000000000683580").substring(0, 0)
                    + padded("0000000000683580", 16)
                    + padded("01", 2) + padded("0001", 4) + padded("POS TERM", 10)
                    + padded("A DESCRIPTION", 100) + padded("0000005047G", 11)
                    + padded("800000000", 9) + padded("MERCHANT", 50) + padded("CITY", 50)
                    + padded("72112", 10) + padded(SENTINEL_PAN, 16)
                    + padded("2022-06-10 19:27:53.000000", 26)
                    + padded("2022-06-10 19:27:53.000000", 26) + padded("", 20);

            DalyTranRecord record = DalyTranRecord.decode(image, StandardCharsets.US_ASCII);

            disclosesNothing("DalyTranRecord", record.toString());
            assertThat(record.toString()).doesNotContain("0000005047G").doesNotContain("504.77");
            assertThat(record.displayImage()).contains(SENTINEL_PAN);
        }

        @Test
        @DisplayName("the bill payment response discloses none of the sentinels and withholds the "
                + "balance")
        void billPaymentResponseDisclosesNothing() {
            BillPaymentResponse response = new BillPaymentResponse();
            response.setActIdIn(Long.toString(SENTINEL_ACCT_ID));
            response.setCurBal("1234.56");
            response.setNavigationContext(populatedContext());

            disclosesNothing("BillPaymentResponse", response.toString());
            assertThat(response.toString()).doesNotContain("1234.56");
        }

        @Test
        @DisplayName("every one of these renderings stays on one line, whatever it was given")
        void everyRenderingStaysOnOneLine() {
            AccountViewRequest request = new AccountViewRequest();
            request.setTrnname("CAVW\r\nINJECTED");
            AccountUpdateRequest update = AccountUpdateRequest.builder()
                    .trnname("CAUP\r\nINJECTED").build();
            BillPaymentResponse bill = new BillPaymentResponse();
            bill.setErrMsg("BROKEN\r\nINJECTED");

            for (String rendering : java.util.List.of(request.toString(), update.toString(),
                    bill.toString())) {
                assertThat(rendering.lines()).hasSize(1);
                assertThat(rendering).doesNotContain("\r");
            }
        }

        private static String padded(String value, int width) {
            return value.length() >= width ? value.substring(0, width)
                    : value + " ".repeat(width - value.length());
        }
    }
}
