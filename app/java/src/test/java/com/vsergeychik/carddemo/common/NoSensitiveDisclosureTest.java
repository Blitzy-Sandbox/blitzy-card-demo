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
 *
 * <h2>Why a single cross-cutting test</h2>
 * The individual type tests each assert their own masked rendering, which is where the detail belongs.
 * This test asserts the property those tests share, over the types together, using one set of
 * recognisable sentinel values. It exists for the failure mode a per-type test cannot catch: a type
 * gains a field, or a new type is added, and nobody remembers the policy. A sentinel that turns up in
 * any rendering fails here by name.
 *
 * <h2>It also proves the types that needed NO change</h2>
 * Seven of the reviewed types - the four menu DTOs, the report request, the sign-on response and the user
 * list request - carry no cardholder data of their own. Their only exposure was the
 * {@link NavigationContext} embedded in every one of them, which is a component of all seventeen online
 * DTOs because {@code COCOM01Y} is copied by all seventeen online programs. Masking that one record
 * closed all seven at once. That is a conclusion worth proving rather than asserting, so each of them is
 * populated here with a context carrying a sentinel card number and name, and each must withhold them.
 *
 * <h2>What is deliberately still legible</h2>
 * Transaction identifiers, program and mapset names, status codes, dates, amounts and operator user ids
 * render as stored. They carry no personal data once the identifiers are masked, and they are what a
 * parity failure is diagnosed from - which is the entire purpose of this migration.
 */
@DisplayName("No diagnostic rendering discloses cardholder data")
class NoSensitiveDisclosureTest {

    /** The code page of the ASCII fixtures, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A sentinel primary account number: recognisable, and not a value any fixture holds. */
    private static final String SENTINEL_PAN = "4111222233334444";

    /** The same sentinel as a number, for the fields that hold {@code PIC 9(16)}. */
    private static final long SENTINEL_PAN_NUMERIC = 4111222233334444L;

    /** A sentinel account identifier, {@code PIC 9(11)}. */
    private static final long SENTINEL_ACCT_ID = 98765432109L;

    /** A sentinel customer identifier, {@code PIC 9(09)}. */
    private static final int SENTINEL_CUST_ID = 987654321;

    /** A sentinel surname. */
    private static final String SENTINEL_LNAME = "QUATERMASS";

    /** A sentinel given name. */
    private static final String SENTINEL_FNAME = "PERCIVAL";

    /** A sentinel social security number. */
    private static final String SENTINEL_SSN = "078051120";

    /** A sentinel government-issued identifier. */
    private static final String SENTINEL_GOVT_ID = "GOVTID9988776655";

    /** A sentinel electronic funds transfer account. */
    private static final String SENTINEL_EFT = "EFT7654321";

    /** A sentinel card verification value. */
    private static final int SENTINEL_CVV = 937;

    /** A sentinel embossed name. */
    private static final String SENTINEL_EMBOSSED = "PERCIVAL QUATERMASS";

    /**
     * A sentinel eight-character password image; obviously fake, and never a credential or an
     * environment secret.
     */
    private static final String SENTINEL_PASSWORD = "PWQUATER";

    /** Every sentinel that must never appear in any rendering. */
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

    /**
     * Asserts that a rendering discloses none of the sentinels.
     *
     * @param what      the type being rendered, for the failure message
     * @param rendering the rendering under test
     */
    private static void disclosesNothing(String what, String rendering) {
        assertThat(rendering).as("%s must produce a rendering", what).isNotNull();
        FORBIDDEN.forEach((label, value) -> assertThat(rendering)
                .as("%s discloses the %s '%s' - CWE-532", what, label, value)
                .doesNotContain(value));
    }

    /** A navigation context carrying every sentinel it can hold. */
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
            // The masking is confined to toString. equals and hashCode still see the whole area, which
            // is what the parity harness compares records with.
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

        /** The twelve menu option lines, none of which carries personal data. */
        private static final String[] OPTIONS = new String[12];

        static {
            java.util.Arrays.fill(OPTIONS, "01. Account View                    ");
        }

        /** The resolved attention identifier byte the menu DTOs carry. */
        private static final byte ENTER_AID = CicsAid.DFHENTER;

        /**
         * The four menu DTOs, each built with a context carrying every sentinel. Their own fields are
         * menu option lines and screen furniture, so the carried context is the whole of what is under
         * test here - and it is what all seventeen online DTOs share.
         *
         * @return a named supplier per type
         */
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
            // CORPT00's own fields are three report-type flags and six date parts. The carried context is
            // its only exposure, so this proves the conclusion rather than assuming it.
            disclosesNothing("ReportRequestRequest",
                    new ReportRequestRequest("CR00", "t1", "d", "CORPT00C", "t2", "t", "N", "N", "Y",
                            "01", "01", "2022", "31", "12", "2022", "Y", "", populatedContext(),
                            "ENTER")
                            .toString());
        }

        @Test
        @DisplayName("the sign-on response withholds the carried context and the PASSWDO span")
        void theSignOnResponseWithholdsTheCarriedContext() {
            // The password is a component because COSGN0AO REDEFINES COSGN0AI makes PASSWDO the span the
            // receive filled and the send transmits, so it is on the wire by parity. It is still absent
            // from every rendering, which is the split this suite exists to hold.
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
            // This one was NOT a pass-through after all, and the discovery is the reason this test exists
            // rather than an inspection note: UserListRequest carries a List<UserListRow>, and each row
            // holds a given and a family name. Neither the record nor the row overrode toString, so the
            // generated rendering published a page of the user directory. The masked rendering lives on
            // UserListRow, and List.toString delegates to it, which closes the enclosing record too.
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
            // CVCUS01Y and CUSTREC model the same customer. If one disclosed a field the other withheld,
            // the protection would depend on which type happened to be logged.
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

        /** The record, carrying every sentinel its six components can hold. */
        private CardUpdateRequest.CardUpdateRecord request() {
            return new CardUpdateRequest.CardUpdateRecord(SENTINEL_PAN, SENTINEL_ACCT_ID, 456,
                    SENTINEL_LNAME, "2029-12-31", "Y");
        }

        /**
         * The same record reached through the response, whose commarea carries it.
         *
         * <p>There is no second type to reach: one area in the COBOL is one type in Java, so the
         * response embeds the request's {@code CardUpdateRecord} rather than declaring a twin of it.
         * That is asserted here as well as in {@code CardUpdateResponseTest}, because a re-introduced
         * duplicate would also be a second rendering nobody had masked.
         */
        private CardUpdateRequest.CardUpdateRecord response() {
            CardUpdateResponse payload = new CardUpdateResponse();
            payload.setCommArea(CardUpdateRequest.CommArea.initialised()
                    .withCardUpdateRecord(request()));
            return payload.getCommArea().cardUpdateRecord();
        }

        @Test
        @DisplayName("the record withholds the card number, account, CVV and embossed name")
        void bothTwinsWithholdThePaymentData() {
            // This record is the whole of a card credential: PAN, account, CVV and name. It declared no
            // toString, so the record-generated rendering published all six verbatim.
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
            // Masked, not omitted: a parity failure still has to be diagnosable. The last four digits
            // and the full stored width remain, which identifies the record without disclosing it.
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

        /**
         * Both twins' renderings, so the shared property is asserted once over both.
         *
         * @return the named renderings
         */
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
            // B6: the migrated program's behaviour must not change. Only toString is masked - every
            // accessor still answers verbatim and the record still occupies its declared 150 bytes.
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

        /**
         * Exact component names whose value must never appear in a rendering.
         *
         * <p>Matched by equality rather than by substring, deliberately. A substring match on
         * {@code "mname"} also selects {@code pgmName}, and one on {@code "cardnum"} also selects
         * {@code selectByCardNumber} - a composed SQL statement, not a cardholder's number. Both are
         * legitimately legible, and a guard that flagged them would be turned off rather than fixed.
         */
        private static final Set<String> SENSITIVE_COMPONENTS = Set.of(
                "cardnum", "cardid", "cardupdatenum", "cvvcd", "cvv",
                "custssn", "ssn", "custgovtissuedid", "govtissuedid",
                "custeftaccountid", "eftaccountid", "passwd", "pwd", "password",
                "fname", "lname", "mname", "custfname", "custlname", "custmname",
                "embossedname", "cardupdateembossedname", "crdname",
                // CSUSR01Y spells its items with a SEC-USR- prefix, and matching by equality means the
                // unprefixed names above did not select any of them. SecUserRecord was therefore swept
                // and reported clean while publishing both of a user's names verbatim - the sweep was
                // looking for components that record does not have. Adding the real names is what makes
                // it visible; the review found this by reading the file rather than by running the test,
                // which is exactly the reliance a structural guard is supposed to remove.
                "secusrfname", "secusrlname", "secusrpwd");

        /**
         * Renderings that still disclose, and are knowingly outside this checkpoint's scope.
         *
         * <p>The review examined all three files and recorded them as PASS - {@code CardListRequest.java}
         * as "BMS/cardinality and data-contract checks pass" and {@code UserUpdateResponse.java} as
         * "Password withheld and no data-contract issue found" - so F18 was not raised against them.
         * Masking them would be an unrequested change to a file the review cleared, which practice B6
         * cautions against, so they are recorded here in executable form instead of being silently
         * tolerated. They are carried in the resolution report as out-of-scope observations.
         *
         * <p><strong>{@code AccountUpdateRequest$CustSnapshot}</strong> is here for a different and
         * stronger reason, and it is worth stating in full because the entry will otherwise read as an
         * oversight. It is the {@code ACUP-xxx-CUST-DATA} half of {@code COACTUPC}'s 873-byte
         * {@code WS-THIS-PROGCOMMAREA} ({@code app/cbl/COACTUPC.cbl:652-849}), and the sweep flags it on
         * {@code govtIssuedId} and {@code eftAccountId}. Three things decide it:
         *
         * <ul>
         *   <li>The specification for that file states the requirement directly - the payload carries the
         *       SSN parts, the date of birth and the government-issued identifier, the COBOL handles all
         *       of them in clear text, and adding {@code @JsonIgnore}, masking, redaction, a
         *       serialisation filter or a hiding {@code toString} is named as an unrequested behaviour
         *       change. Its own verification step greps for exactly those additions and fails on them.</li>
         *   <li>{@link SensitiveDiagnostics} is not among that file's declared dependencies, so the
         *       module's single masking policy is not available to it, and hand-rolling a second masking
         *       scheme beside the shared one would be worse than either alternative.</li>
         *   <li>Its sibling {@code AccountViewRequest} took the same decision and documents it in its own
         *       source. The sweep does not reach that type only because it is a class rather than a record
         *       and so has no components to match on - not because it withholds anything.</li>
         * </ul>
         *
         * <p>So this is recorded, in executable form, as a deliberate and bounded exception rather than a
         * gap: the type is reachable only from a work area that a controller and its service exchange, it
         * is never a log target itself, and if the masking policy is ever extended to the account-update
         * screen it should be extended to both of that screen's DTOs together, in a change scoped to say
         * so.
         *
         * <p>Asserted as a subset rather than an equality: closing one of these later must not fail this
         * test, while a NEW disclosure anywhere must.
         */
        private static final Set<String> KNOWN_OUT_OF_SCOPE = Set.of(
                "com.vsergeychik.carddemo.card.dto.CardListRequest$CardKey",
                "com.vsergeychik.carddemo.card.dto.CardListRequest$ScreenRow",
                "com.vsergeychik.carddemo.user.dto.UserUpdateResponse",
                "com.vsergeychik.carddemo.account.dto.AccountUpdateRequest$CustSnapshot");

        /**
         * Types whose canonical constructor rejects a generic sentinel, so they cannot be swept.
         *
         * <p>Each validates its component widths, which is correct behaviour - and each already has a
         * hand-written masked rendering asserted by the per-type cases above. Recorded so the swept set
         * cannot quietly shrink and hide a regression behind an unrelated constructor change.
         */
        private static final Set<String> NOT_SWEPT = Set.of(
                "com.vsergeychik.carddemo.card.dto.CardUpdateRequest$CardDetails",
                "com.vsergeychik.carddemo.user.dto.UserDeleteResponse");
        // A width-validating constructor is no longer a reason on its own to land here: registering a
        // probe in PROBE_FACTORIES builds the type at its declared widths and gets it genuinely swept,
        // which is what SecUserRecord needed. These two remain because the review examined both files and
        // recorded them as PASS, so probing them would extend this checkpoint's scope rather than close a
        // finding; they are carried in the resolution report as out-of-scope observations.

        /**
         * The screen DTOs that classify their own diagnostic rendering, and the item each must withhold.
         *
         * <p>The third discovery arm. {@code allRecords()} walks {@code type.isRecord()} and
         * {@code allSpanAddressedTypes()} walks classes holding a {@link FixedWidthRecord}; a screen DTO is
         * neither - it is a plain {@code final class} holding a map or a field per BMS item - so both
         * missed every one of these, and {@code AccountUpdateResponse} and {@code TransactionListResponse}
         * were rendering a social-security number and a page of transaction keys with nothing watching.
         *
         * <p>Selection is structural: a class declaring a {@code disclosureOf} method is, by this module's
         * own convention, a type that classifies its own rendering. Every one discovered must appear here
         * with a name it is required to withhold, so a new screen DTO cannot be added - or an existing
         * one's classification quietly emptied - without somebody choosing what it withholds.
         *
         * <p>The value is a field or item name the type must NOT render as stored. For an enum-keyed
         * classifier it is the {@code ScreenField} constant's name; for a string-keyed one it is the
         * symbolic-map item name.
         */
        private static final Map<String, String> CLASSIFIED_RENDERINGS = Map.of(
                "com.vsergeychik.carddemo.account.dto.AccountUpdateResponse", "ACTSSN1",
                "com.vsergeychik.carddemo.transaction.dto.TransactionListResponse", "TRNID01O",
                "com.vsergeychik.carddemo.transaction.dto.TransactionViewResponse", "ACTIDIN",
                "com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest", "ACTIDIN",
                "com.vsergeychik.carddemo.card.dto.CardListResponse", "CRDNUM1",
                "com.vsergeychik.carddemo.card.dto.CardSelectRequest", "CARDSID",
                "com.vsergeychik.carddemo.card.dto.CardUpdateRequest", "CARDSID");

        /** The method name that marks a type as classifying its own diagnostic rendering. */
        private static final String CLASSIFIER_METHOD = "disclosureOf";

        /**
         * Compiled classes exempted from the loadability requirement, each with its reason.
         *
         * <p><strong>Empty, and that is the finding.</strong> Every class compiled into this module
         * loads, so nothing needs excusing - and the sweep now says so out loud instead of discovering
         * it silently one class at a time. An entry here is admissible only when a class genuinely
         * cannot be loaded in this JVM <em>and</em> a per-type disclosure test asserts its rendering
         * instead, and the reason belongs in a comment beside it. {@link #assertEveryClassLoaded(Map)}
         * also rejects an exemption that has stopped applying, so a stale entry cannot sit here
         * excusing a class it was never written for.
         */
        private static final Set<String> UNLOADABLE_EXEMPT = Set.of();

        /** A short sentinel that fits any {@code PIC X} field and appears in no fixture. */
        private static final String PROBE = "ZQX7";

        /**
         * Span-addressed types this sweep deliberately does not probe, each with the reason.
         *
         * <p>Recorded in executable form rather than left out of the discovery, so the set cannot quietly
         * grow: adding a copybook model means either registering a probe for it or writing down here why
         * it needs none. Every entry below was read before being listed.
         *
         * <ul>
         *   <li>{@code TranRecord} - its rendering decodes {@code TRAN-AMT} in order to show the image
         *       and the value together, so it cannot be rendered over the blank area a generic probe
         *       would supply, and building a valid 350-byte {@code TRANSACT} row here would duplicate the
         *       fixture its own test already holds. Its PAN masking is asserted directly by
         *       {@code TranRecordTest.toStringIsCompleteAndUnmasked}, which pins
         *       {@code TRAN-CARD-NUM='************7065'} and the absence of the full number.</li>
         *   <li>{@code DisclosureGroupRecord} - {@code CVTRA02Y} holds an account group, a sub-group, a
         *       type code and {@code DIS-INT-RATE}. There is no identifier and no personal data in it: an
         *       interest rate is a term of a product, not a fact about a person, and it is what an
         *       interest parity failure is diagnosed from.</li>
         *   <li>{@code AccountDateValidator$EditDateState} - a work area for the {@code CSUTLDPY} date
         *       edits rather than a stored record. It holds the date currently being validated and the
         *       edit flags, which carry no identifier and are the entire subject of the diagnostic.</li>
         * </ul>
         */
        private static final Set<String> RENDERINGS_NOT_PROBED = Set.of(
                "com.vsergeychik.carddemo.transaction.model.TranRecord",
                "com.vsergeychik.carddemo.account.model.DisclosureGroupRecord",
                "com.vsergeychik.carddemo.account.AccountDateValidator$EditDateState");

        /**
         * One type populated with sentinel data, together with everything its rendering must not show.
         *
         * @param instance  the populated instance
         * @param forbidden the values that must not appear in {@code instance.toString()}
         */
        private record ProbeCase(Object instance, List<String> forbidden) { }

        /**
         * Hand-built probes for the types the generic sentinel cannot populate.
         *
         * <p>Two kinds of type need one, and excusing either into {@link #NOT_SWEPT} would have left the
         * guard vouching for nothing:
         * <ul>
         *   <li>a record whose canonical constructor <strong>validates component widths</strong>. The
         *       generic probe supplies a four-character sentinel, every field is rejected, and the type
         *       falls out of the sweep - which is what happened to {@code SecUserRecord} while it was
         *       publishing both of a user's names. Padding the sentinel to each field's declared width
         *       makes the type constructible and the sweep real;</li>
         *   <li>a <strong>non-record</strong> type that addresses its fields by span. It has no
         *       components at all, so the sentinel has nowhere to go by name; the factory writes it into
         *       the spans that carry personal data and declares what must not come back out. A numeric
         *       span cannot hold {@value #PROBE}, so for those the forbidden value is a distinctive
         *       digit string instead.</li>
         * </ul>
         */
        private static final Map<String, Supplier<ProbeCase>> PROBE_FACTORIES = Map.of(
                "com.vsergeychik.carddemo.user.model.SecUserRecord",
                () -> new ProbeCase(
                        // CSUSR01Y: 8, 20, 20, 8, 1, 23. The probe goes in the id, both names and the
                        // password - every field the policy has an opinion about.
                        new com.vsergeychik.carddemo.user.model.SecUserRecord(
                                atWidth(PROBE, 8), atWidth(PROBE + "FNAME", 20),
                                atWidth(PROBE + "LNAME", 20), atWidth(PROBE + "PW", 8), "A",
                                atWidth("", 23)),
                        // The id itself is legible by design - it is the VSAM key COSGN00C matches on -
                        // so the bare probe is not forbidden; the names and the password are.
                        List.of(PROBE + "FNAME", PROBE + "LNAME", PROBE + "PW")),

                "com.vsergeychik.carddemo.transaction.model.TranCatBalRecord",
                () -> new ProbeCase(
                        com.vsergeychik.carddemo.transaction.model.TranCatBalRecord
                                .newInstance(StandardCharsets.US_ASCII)
                                .trancatAcctId(12345678901L)
                                .tranCatBal(new java.math.BigDecimal("-987654321.99")),
                        // TRANCAT-ACCT-ID is PIC 9(11) and TRAN-CAT-BAL is PIC S9(09)V99, so neither can
                        // hold letters. The full account identifier and every rendering of the balance -
                        // as digits, and as the zoned image whose last byte carries the sign - are what
                        // must not appear.
                        List.of("12345678901", "987654321.99", "98765432199", "9876543219R")),

                "com.vsergeychik.carddemo.account.model.AccountRecord",
                () -> {
                    // CVACT01Y is 300 bytes and this rendering reads every field raw, so a blank area is
                    // renderable - which is the point: the probe is about the key, not the amounts.
                    com.vsergeychik.carddemo.account.model.AccountRecord account =
                            com.vsergeychik.carddemo.account.model.AccountRecord.decode(
                                    " ".repeat(300), StandardCharsets.US_ASCII);
                    account.setAcctId(12345678901L);
                    return new ProbeCase(account, List.of("12345678901"));
                },

                "com.vsergeychik.carddemo.statement.model.TrnxRecord",
                () -> {
                    // COSTM01's TRNX-CARD-NUM is PIC X(16), so the sentinel goes in as text. The
                    // statement files carry a PAN and are written to two output datasets, so this is one
                    // of the renderings most likely to reach a log.
                    com.vsergeychik.carddemo.statement.model.TrnxRecord trnx =
                            com.vsergeychik.carddemo.statement.model.TrnxRecord.decode(
                                    " ".repeat(350).getBytes(StandardCharsets.US_ASCII),
                                    StandardCharsets.US_ASCII);
                    trnx.writeTrnxCardNum(atWidth(PROBE + "CARDNUM", 16));
                    return new ProbeCase(trnx, List.of(PROBE + "CARDNUM"));
                },

                "com.vsergeychik.carddemo.transaction.model.DalyTranRecord",
                () -> {
                    // CVTRA06Y's DALYTRAN-CARD-NUM is PIC X(16). Probed rather than excused into
                    // RENDERINGS_NOT_PROBED, even though its rendering decodes DALYTRAN-AMT the way
                    // TranRecord's does: the constructor INITIALIZEs the three numeric spans to zeros, so
                    // an allocated area is renderable without a fixture and the guard gets to exercise
                    // this rendering for real. That matters here - CBTRN01C:168 displays the whole daily
                    // record and CBTRN02C:447 copies it onto the rejects file, so a card number moves
                    // through this type on both the posted and the rejected path.
                    com.vsergeychik.carddemo.transaction.model.DalyTranRecord daly =
                            new com.vsergeychik.carddemo.transaction.model.DalyTranRecord(
                                    StandardCharsets.US_ASCII);
                    daly.moveDalytranCardNum(atWidth(PROBE + "CARDNUM", 16));
                    return new ProbeCase(daly, List.of(PROBE + "CARDNUM"));
                },

                "com.vsergeychik.carddemo.user.dto.SignOnResponse",
                () -> new ProbeCase(
                        // COSGN00.CPY output widths in declaration order - 4, 40, 8, 8, 40, 9, 8, 8, 8,
                        // 8, 78 - then role at one, the navigation triple, the eighty-byte plain text and
                        // the communication area. The generic probe cannot build this type: role is
                        // PIC X(01), narrower than the sentinel, and the context component is not a
                        // String. The password is the field the policy has an opinion about, and it is
                        // here because COSGN0AO REDEFINES COSGN0AI puts the received image in the span
                        // the SEND transmits.
                        new com.vsergeychik.carddemo.user.dto.SignOnResponse(
                                atWidth(PROBE, 4), atWidth("", 40), atWidth("", 8), atWidth("", 8),
                                atWidth("", 40), atWidth("", 9), atWidth("", 8), atWidth("", 8),
                                atWidth(PROBE + "ID", 8), atWidth(PROBE + "PW", 8), atWidth("", 78),
                                "A", atWidth("", 8), atWidth("", 7), atWidth("", 7),
                                atWidth("", 80), populatedContext()),
                        // USERIDO is legible by design - it is the identifier a sign-on parity failure is
                        // diagnosed from, and the program echoes it to the terminal - so the bare probe
                        // is not forbidden there. The PASSWDO span is.
                        List.of(PROBE + "PW")));

        /**
         * Pads or truncates a value to a declared {@code PIC X} width, on the right as COBOL does.
         *
         * @param value the value
         * @param width the receiving field's declared width
         * @return the value at exactly {@code width} characters
         */
        private static String atWidth(String value, int width) {
            return value.length() >= width
                    ? value.substring(0, width)
                    : value + " ".repeat(width - value.length());
        }

        /**
         * Whether a component name is one whose value must never be rendered.
         *
         * @param name the record component name
         * @return {@code true} when the component holds data that must be withheld
         */
        private static boolean isSensitive(String name) {
            return SENSITIVE_COMPONENTS.contains(name.toLowerCase(java.util.Locale.ROOT));
        }

        /**
         * Every record type compiled into this module, nested types included.
         *
         * <p>Enumerated from the compiled output rather than from a hand-written list, because a
         * hand-written list is exactly what failed here: the per-type cases above cover the types
         * somebody remembered, and two nested {@code CardUpdateRecord} types added later were invisible
         * to all of them. Uses only {@code java.nio.file} and reflection - no scanning library is
         * introduced, since the dependency set is closed.
         *
         * @return every record class in the module
         * @throws Exception if the compiled output cannot be walked
         */
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

        /**
         * Every non-record class in this module that renders a span-addressed record area.
         *
         * <p>The other half of the gap the review found. {@link #allRecords()} walks
         * {@code type.isRecord()}, so a copybook model written as a plain {@code final class} was
         * invisible to it however sensitive its contents - and {@code TranCatBalRecord}, which held an
         * account identifier beside a balance, is exactly that. Component-name matching cannot reach one
         * either: such a type holds a single {@link FixedWidthRecord} area and addresses its fields by
         * span, so there are no component names to match on and no name to add to
         * {@link #SENSITIVE_COMPONENTS}.
         *
         * <p>Selection is therefore <em>structural</em>: a non-record class that declares its own
         * {@code toString()} and holds a {@link FixedWidthRecord} field is a hand-written rendering over
         * raw copybook bytes, which is precisely the shape that needs a disclosure decision. Every type
         * selected must then be either registered in {@link #PROBE_FACTORIES}, so its rendering is
         * actually exercised, or listed in {@link #RENDERINGS_NOT_PROBED} with a reason - so a new
         * copybook model cannot be added without somebody choosing one or the other.
         *
         * @return every non-record class that renders a fixed-width record area
         * @throws Exception if the compiled output cannot be walked
         */
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

        /**
         * Whether a type declares a {@code toString()} of its own rather than inheriting one.
         *
         * @param type the type to inspect
         * @return {@code true} when the type overrides {@code toString()}
         */
        private static boolean declaresItsOwnRendering(Class<?> type) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if ("toString".equals(method.getName()) && method.getParameterCount() == 0
                        && !method.isSynthetic()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Every class compiled into this module's main output, nested types included.
         *
         * <p>Enumerated from the compiled output rather than from a hand-written list, because a
         * hand-written list is exactly what failed here: the per-type cases above cover the types
         * somebody remembered, and two nested {@code CardUpdateRecord} types added later were invisible
         * to all of them. Uses only {@code java.nio.file} and reflection - no scanning library is
         * introduced, since the dependency set is closed.
         *
         * @return every loadable class in the module
         * @throws Exception if the compiled output cannot be walked
         */
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

        /**
         * Loads one compiled class, or records why it could not be loaded.
         *
         * <p>Split out of {@link #allCompiledTypes()} so the recording and the enforcement can each be
         * exercised on their own - see {@code aClassThatCannotBeLoadedIsRecorded} and
         * {@code anUnloadableClassFailsTheSweep}. A guard that skips what it cannot inspect has no way
         * to prove it inspected anything, so its recording path has to be testable.
         *
         * @param binary     the binary class name derived from the file path
         * @param loaded     accumulates successfully loaded classes
         * @param unloadable accumulates binary name to sanitised reason for the ones that failed
         */
        private static void loadOrRecord(String binary, List<Class<?>> loaded,
                                         Map<String, String> unloadable) {
            try {
                loaded.add(Class.forName(binary, false, NavigationContext.class.getClassLoader()));
            } catch (Throwable failure) {
                // Recorded, never dropped. Class.forName can fail with an Error as readily as an
                // Exception - NoClassDefFoundError for an absent supertype, UnsupportedClassVersionError
                // for output from a newer release - so Throwable is the right width to catch. What was
                // wrong before was not the width of the catch but that the body was empty.
                //
                // The reason goes through the same redaction policy the parity harness uses, because a
                // linkage error names the type it could not resolve and this text lands in a build log.
                unloadable.put(binary, com.vsergeychik.carddemo.parity.ParityCase.Redaction
                        .describeThrowable(failure));
            }
        }

        /**
         * Requires every compiled class to have been loadable, bar the documented exemptions.
         *
         * <p>This sweep's whole claim is completeness - it exists because a hand-written list of types
         * missed two. A class silently dropped because it would not load is a hole in exactly that
         * claim, and it is the worst kind: it widens on its own. A type that gains an unresolvable
         * supertype disappears from the sweep and the sweep still passes, so a disclosure added to it
         * afterwards is never seen. Failing closed converts that into a build failure naming the class.
         *
         * @param unloadable binary name to sanitised reason, empty when every class loaded
         */
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

        /**
         * Builds an instance with {@link #PROBE} in every sensitive component, or {@code null} if the
         * canonical constructor rejects the generic arguments.
         *
         * @param type the record type
         * @return the populated instance, or {@code null} when it cannot be constructed
         */
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
                    // A raw EBCDIC byte, which is how every carrier of a CICS EIBAID holds it - see
                    // CicsAid and SignOnService.SignOnInput. Without this branch the argument stayed null,
                    // newInstance rejected a null for a primitive, and the type fell through to NOT_SWEPT
                    // unvouched-for even though it carries a password component. Widening the gap in the
                    // prober is the fix; excusing the type would have been an exemption.
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

        /**
         * Partitions every sensitive-bearing record into those that disclose and those that do not.
         *
         * @return disclosing binary names under key {@code true}, clean ones under {@code false}
         * @throws Exception if the compiled output cannot be walked
         */
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

        /**
         * Whether a rendering shows any value that type's probe declared must not appear.
         *
         * <p>For a generically probed type that is the bare sentinel. For a registered one it is the
         * factory's own list, which matters for {@code SecUserRecord}: its {@code SEC-USR-ID} is legible
         * by design - it is the VSAM key {@code COSGN00C} matches on and the only field a sign-on parity
         * failure can be diagnosed from - so the sentinel appearing there is correct, while the same
         * sentinel appearing in a name is not.
         *
         * @param type     the type under inspection
         * @param instance the populated instance
         * @return {@code true} when the rendering shows something it must withhold
         */
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
            // The property that matters, asserted by BEHAVIOUR. Asking whether a record "declares its
            // own toString" would be vacuous: a record's generated toString is an implicitly declared
            // member, so getDeclaredMethods() reports one for EVERY record, masked or not.
            assertThat(sweep().get(true))
                    .as("these renderings publish a sensitive component verbatim - CWE-532")
                    .isSubsetOf(KNOWN_OUT_OF_SCOPE);
        }

        @Test
        @DisplayName("every span-addressed non-record type is registered or documented")
        void everySpanAddressedTypeIsAccountedFor() throws Exception {
            // The structural half of the fix. A non-record copybook model has no components to match on,
            // so the only way it can be guarded is by being discovered from the compiled output and then
            // deliberately either probed or excused. TranCatBalRecord was neither, which is why it
            // published an account identifier and a balance with nothing failing.
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
            // Asserted per type and by name, because a registry whose entries were never run would look
            // exactly like a passing guard.
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
                // And a rendering must stay on one line whatever it holds, or a stored control character
                // appends a log entry of the writer's choosing after it - CWE-117.
                assertThat(rendered.lines()).as("%s must render on one line", name).hasSize(1);
            });
        }

        /**
         * Every class in this module that classifies its own diagnostic rendering.
         *
         * <p>Structural, for the reason given on {@link #CLASSIFIED_RENDERINGS}: neither of the other two
         * discovery arms can see a screen DTO, and a hand-written list is what let two of them render
         * cardholder data unwatched. A declared {@code disclosureOf} method is this module's own marker
         * for the shape, so the discovery follows the marker rather than a remembered list.
         *
         * @return every type declaring a single-argument {@value #CLASSIFIER_METHOD}
         * @throws Exception if the compiled output cannot be walked
         */
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

        /**
         * Invokes a type's own {@value #CLASSIFIER_METHOD} for one field or item name.
         *
         * @param type      the classifying type
         * @param fieldName the {@code ScreenField} constant name or symbolic-map item name, or
         *                  {@code null} to exercise the safe default
         * @return the classification the type returned
         * @throws Exception if the method cannot be invoked
         */
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
            // The classification asserted above decides what SHOULD be withheld; this asserts the
            // rendering actually applies it, which is the part a build log sees.
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

            // A name no class file backs, which is the same failure shape as a class whose supertype
            // has gone: Class.forName throws and the type never reaches the sweep.
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
            // The enforcement, exercised directly. Before this checkpoint the equivalent of this map
            // was discarded, so this assertion had nothing to fail on and the sweep quietly covered
            // fewer types than it reported.
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
            // A guard that selected nothing would pass forever. Name the types it must vouch for,
            // including the two nested records this sweep is what found.
            assertThat(sweep().get(false))
                    .as("the sweep must reach the masked types and find them clean")
                    .contains("com.vsergeychik.carddemo.common.NavigationContext",
                            "com.vsergeychik.carddemo.card.model.CardRecord",
                            "com.vsergeychik.carddemo.card.dto.CardUpdateRequest$CardUpdateRecord",
                            // Named explicitly because it is the type the sweep silently skipped: its
                            // components are spelled SEC-USR- and matched none of the unprefixed names,
                            // and its width-validating constructor then rejected the generic sentinel,
                            // so it was neither flagged nor vouched for while publishing both names.
                            "com.vsergeychik.carddemo.user.model.SecUserRecord")
                    .hasSizeGreaterThan(5);
        }
    }

    // =============================================================================================
    // The B2 renderings brought under the policy - the security findings of this checkpoint.
    //
    // Each of these published personal or financial data through a Java-only toString(). None of them
    // has a COBOL counterpart, so withholding costs no observable behaviour; publishing cost a log line
    // holding a named person's identity (CWE-532) and a field able to forge a second line (CWE-117).
    // =============================================================================================

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

            // And the nested snapshots on their own, because the generated record renderings printed
            // every component and the group recursed into both of them.
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
            // The amount is the field this record exists to carry, and it is withheld in both forms.
            assertThat(record.toString()).doesNotContain("0000005047G").doesNotContain("504.77");
            // While the byte-exact path - the parity surface - still carries everything.
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
            // CWE-117, checked once for the whole group: a fixed-width field holds whatever was moved
            // into it, so a CR or LF must not survive into the rendering.
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

        /**
         * Right-pads a value to a declared width, as a stored record span holds it.
         *
         * @param value the value
         * @param width the declared width
         * @return exactly {@code width} characters
         */
        private static String padded(String value, int width) {
            return value.length() >= width ? value.substring(0, width)
                    : value + " ".repeat(width - value.length());
        }
    }
}
