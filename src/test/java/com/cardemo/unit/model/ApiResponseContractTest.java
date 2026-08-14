/*
 * ****************************************************************************
 * Program     : ApiResponseContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies the API-native response types: no full primary account
 *               number and no protected account field can be serialised, no CICS
 *               routing or 3270 attribute appears, and the two list envelopes
 *               carry the page metadata and the screen status messages the
 *               source produced.
 * Source      : app/cpy/CVACT02Y.cpy:L5 (CARD-NUM PIC X(16)),
 *                 :L7 (CARD-CVV-CD PIC 9(03))
 *               + app/cpy/CVCUS01Y.cpy (CUST-SSN, CUST-DOB-YYYY-MM-DD,
 *                 CUST-GOVT-ISSUED-ID, CUST-EFT-ACCOUNT-ID, CUST-PHONE-NUM-1/2)
 *               + app/cbl/COCRDLIC.cbl:L112, :L117 (WS-INFO-MSG, WS-ERROR-MSG),
 *                 :L177-L178 (7 rows), :L1197-L1205 (saved card-number keys)
 *               + app/cbl/COTRN00C.cbl:L290 (10 rows)
 *               @ 7756d89
 * ****************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.cardemo.model.dto.AccountUpdateResponse;
import com.cardemo.model.dto.AccountViewResponse;
import com.cardemo.model.dto.ApiMasking;
import com.cardemo.model.dto.BillPaymentResponse;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.CardListResponse;
import com.cardemo.model.dto.CardResponse;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.dto.ReportSubmissionResponse;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.dto.TransactionListResponse;
import com.cardemo.model.dto.TransactionResponse;
import com.cardemo.model.dto.UserSecurityDto;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contract tests for the API-native response types.
 *
 * <p>These types exist because the legacy projections are faithful transcriptions of 3270 symbolic maps: they
 * carry the primary account number in full and, for the account, nine high-risk personal identifiers, because
 * the screens displayed them. A field contract must keep them; a public HTTP body may not. The assertions
 * below are therefore structural rather than incidental - they read the record components and the serialised
 * JSON, so a component added back in future fails the build instead of quietly widening a response.</p>
 */
@DisplayName("API response contract: nothing protected, nothing 3270, nothing invented")
class ApiResponseContractTest {

    /** A recognisable primary account number, used so its presence in JSON is unmistakable. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Its masked rendering: twelve asterisks then the last four. */
    private static final String MASKED_CARD_NUMBER = "************1111";

    /** The serialiser, configured as the application's default is. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every response type introduced for the REST boundary. */
    private static final List<Class<?>> RESPONSE_TYPES = List.of(
            CardResponse.class,
            CardListResponse.class,
            CardListResponse.CardListRowResponse.class,
            TransactionResponse.class,
            TransactionListResponse.class,
            TransactionListResponse.TransactionListRowResponse.class,
            AccountViewResponse.class,
            AccountUpdateResponse.class,
            BillPaymentResponse.class,
            ReportSubmissionResponse.class);

    /**
     * Component names that must not appear on any response type, in lower case for comparison.
     *
     * <p>The first group is authentication and cardholder data. The second is CICS and 3270 mechanism: a
     * next-program, a mapset, a map, a cursor, a colour or a field attribute describes a terminal.</p>
     */
    private static final List<String> FORBIDDEN_COMPONENT_FRAGMENTS = List.of(
            "cvv",
            "ssn",
            "socialsecurity",
            "dateofbirth",
            "governmentissued",
            "eftaccount",
            "phonenumber",
            "password",
            "nextprogram",
            "nextmapset",
            "nextmap",
            "lastmap",
            "mapset",
            "cursorfield",
            "colour",
            "color",
            "highlight",
            "attribute",
            "fromtranid",
            "totranid",
            "pgmcontext",
            "functionkey");

    /** Renders every record component name of a type in lower case. */
    private static List<String> componentNames(final Class<?> type) {
        final RecordComponent[] components = type.getRecordComponents();
        assertThat(components).as("%s must be a record", type.getSimpleName()).isNotNull();
        return Arrays.stream(components)
                .map(component -> component.getName().toLowerCase(Locale.ROOT))
                .toList();
    }

    @Nested
    @DisplayName("Structural exclusions")
    class StructuralExclusions {

        @Test
        @DisplayName("No response type declares a component naming authentication, cardholder or 3270 state")
        void noResponseTypeDeclaresForbiddenComponents() {
            for (final Class<?> type : RESPONSE_TYPES) {
                final List<String> names = componentNames(type);
                for (final String forbidden : FORBIDDEN_COMPONENT_FRAGMENTS) {
                    assertThat(names)
                            .as("%s must not declare a component containing '%s'", type.getSimpleName(),
                                    forbidden)
                            .noneMatch(name -> name.contains(forbidden));
                }
            }
        }

        @Test
        @DisplayName("The only cursor-named components are the two sealed page cursors, never a screen field")
        void theOnlyCursorsArePageCursors() {
            for (final Class<?> type : RESPONSE_TYPES) {
                assertThat(componentNames(type))
                        .as("%s may name a keyset page cursor and nothing else cursor-like",
                                type.getSimpleName())
                        .filteredOn(name -> name.contains("cursor"))
                        .allSatisfy(name -> assertThat(name).isIn("firstcursor", "lastcursor"));
            }
            assertThat(componentNames(CardListResponse.class))
                    .as("and the card list does declare them, or the filter above proves nothing")
                    .contains("firstcursor", "lastcursor");
        }

        @Test
        @DisplayName("No response type declares a bare cardNumber: a card reference is always masked")
        void noResponseTypeDeclaresABareCardNumber() {
            for (final Class<?> type : RESPONSE_TYPES) {
                assertThat(componentNames(type))
                        .as("%s must name a masked card reference or none at all", type.getSimpleName())
                        .noneMatch(name -> name.equals("cardnumber"));
            }
        }

        @Test
        @DisplayName("The account response withholds all nine protected components as display fields")
        void accountResponseWithholdsProtectedComponents() {
            final List<String> names = componentNames(AccountViewResponse.class);

            assertThat(AccountViewResponse.WITHHELD_COMPONENTS).hasSize(9);
            for (final String withheld : AccountViewResponse.WITHHELD_COMPONENTS) {
                assertThat(names)
                        .as("AccountViewResponse must not declare %s", withheld)
                        .doesNotContain(withheld.toLowerCase(Locale.ROOT));
                assertThat(componentNames(AccountDtoComponents.LEGACY))
                        .as("%s is expected to exist on the legacy projection, or this test is vacuous",
                                withheld)
                        .contains(withheld.toLowerCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("The account response carries the as-displayed snapshot sealed, and never as a group")
        void accountResponseCarriesTheAsDisplayedSnapshotSealed() {
            // Transformation Rule 7 puts the ACUP-OLD-DETAILS group in the body of the matching PUT, because
            // a stateless server holds no COMMAREA between the two turns. app/cbl/COACTUPC.cbl:4109-4193
            // compares all twenty-nine of its values, the nine protected ones included - which is exactly
            // why the group may not travel readably. It travels as one opaque sealed member instead, for two
            // independent reasons: publishing the group would put the nine on the wire on a plain read, and a
            // group the caller could rewrite would let the caller assert that the record had not changed,
            // making the comparison the guard exists for unconditionally true.
            assertThat(componentNames(AccountViewResponse.class))
                    .contains("snapshot")
                    .doesNotContain("olddetails");

            final java.lang.reflect.RecordComponent sealed =
                    java.util.Arrays.stream(AccountViewResponse.class.getRecordComponents())
                            .filter(component -> "snapshot".equals(component.getName()))
                            .findFirst()
                            .orElseThrow();
            assertThat(sealed.getType())
                    .as("the component is an opaque token, not a structured group")
                    .isEqualTo(String.class);
            // And the shape is symmetrical: the request binds the same opaque member, so the value the read
            // issued is echoed back verbatim rather than reassembled. AccountUpdateRequest is a class rather
            // than a record - it declares fifty-four screen fields - so its wire members are read from the
            // constructor Jackson binds through.
            final java.lang.reflect.Constructor<?> wireConstructor =
                    java.util.Arrays.stream(
                                    com.cardemo.model.dto.AccountUpdateRequest.class.getConstructors())
                            .filter(candidate -> candidate.isAnnotationPresent(
                                    com.fasterxml.jackson.annotation.JsonCreator.class))
                            .findFirst()
                            .orElseThrow();
            final List<String> boundMembers = Arrays.stream(wireConstructor.getParameters())
                    .map(parameter -> parameter.getAnnotation(
                            com.fasterxml.jackson.annotation.JsonProperty.class))
                    .filter(java.util.Objects::nonNull)
                    .map(property -> property.value().toLowerCase(Locale.ROOT))
                    .toList();
            assertThat(boundMembers)
                    .as("the PUT binds the sealed member and declares no readable group")
                    .contains("snapshot")
                    .doesNotContain("olddetails");
        }

        /** Holder so the legacy projection is named once and the vacuity check above stays readable. */
        private static final class AccountDtoComponents {
            /** The legacy thirty-seven-field projection. */
            private static final Class<?> LEGACY = com.cardemo.model.dto.AccountDto.class;

            private AccountDtoComponents() {
            }
        }
    }

    @Nested
    @DisplayName("Serialisation")
    class Serialisation {

        @Test
        @DisplayName("A card read response emits the masked number, the as-displayed group and no full number")
        void cardReadResponseEmitsNothingSensitive() throws Exception {
            final CardDto detail = CardDto.detail("CCDL", "T1", "01/15/26", "COCRDSLC", "T2", "10:30:00",
                    "00000000011", CARD_NUMBER, "ANNA LEE", "Y", "12", "2099", null, null, null);
            // The group the matching PUT echoes back, per transformation Rule 7. The expiry DAY is in it
            // and is on no read map, which is why the group travels rather than flat fields.
            final String sealedSnapshot = "c2VhbGVkLWNhcmQtc25hcHNob3Q";

            final String json = MAPPER.writeValueAsString(
                    CardResponse.readOf(detail, sealedSnapshot, "sealed-reference"));

            // The sealed snapshot discloses nothing: it is one opaque string, so the card number that
            // maskedCardNumber exists to withhold cannot reappear through it, and neither can the embossed
            // name or the expiry day the comparison consumes.
            assertThat(json).doesNotContain(CARD_NUMBER);
            assertThat(json).contains(MASKED_CARD_NUMBER);
            assertThat(json).contains("\"snapshot\":\"" + sealedSnapshot + "\"");
            // The expiry DAY is the value the detail map never declared and the comparison at :1507 needs.
            // It is inside the sealed value and therefore absent as a readable member - which is the whole
            // point: a client echoes the sealed value rather than reassembling the group.
            assertThat(json).doesNotContain("expiryDay").doesNotContain("cardData");
            assertThat(json).doesNotContain("TRNNAME").doesNotContain("COCRDSLC");
        }

        @Test
        @DisplayName("A card write response carries no as-displayed group, because a client re-reads to edit again")
        void cardWriteResponseIssuesNoToken() {
            final CardDto detail = CardDto.detail("CCUP", "T1", "01/15/26", "COCRDUPC", "T2", "10:30:00",
                    "00000000011", CARD_NUMBER, "ANNA LEE", "Y", "12", "2099", null, null, null);

            assertThat(CardResponse.writeOf(detail).snapshot()).isNull();
            assertThat(CardResponse.writeOf(detail).maskedCardNumber()).isEqualTo(MASKED_CARD_NUMBER);
        }

        @Test
        @DisplayName("A card list row emits the masked number only, and the envelope carries both messages")
        void cardListEnvelopeCarriesContextAndNoPan() throws Exception {
            final CardDto.CardListRow row =
                    new CardDto.CardListRow(1, null, null, "00000000011", CARD_NUMBER, "Y");
            final PageResponse<CardDto.CardListRow> page = new PageResponse<>(
                    List.of(row), 1, CardDto.CARD_LIST_PAGE_SIZE, true, CARD_NUMBER, CARD_NUMBER);

            final CardListResponse envelope = CardListResponse.of(page, "sealed-first", "sealed-last",
                    "You are already at the top of the page...", "Search key is not valid", false,
                    (accountId, cardNumber) -> "sealed-reference");
            final String json = MAPPER.writeValueAsString(envelope);

            assertThat(json)
                    .as("neither the rows, the cursors nor the opaque row references may carry a "
                            + "primary account number")
                    .doesNotContain(CARD_NUMBER);
            assertThat(envelope.rows()).singleElement()
                    .extracting(CardListResponse.CardListRowResponse::cardKey)
                    .as("the reference is what makes the masked row navigable at all: detail and "
                            + "update both require the sixteen digits this row does not disclose, so "
                            + "without it a client can see the list and go nowhere from it")
                    .isEqualTo("sealed-reference");
            assertThat(envelope.rows()).singleElement()
                    .extracting(CardListResponse.CardListRowResponse::maskedCardNumber)
                    .isEqualTo(MASKED_CARD_NUMBER);
            assertThat(envelope.firstCursor()).isEqualTo("sealed-first");
            assertThat(envelope.lastCursor()).isEqualTo("sealed-last");
            assertThat(envelope.informationMessage()).isEqualTo("You are already at the top of the page...");
            assertThat(envelope.errorMessage()).isEqualTo("Search key is not valid");
            assertThat(envelope.pageSize()).isEqualTo(7);
            assertThat(envelope.nextPageAvailable()).isTrue();
            assertThat(envelope.rowSelectionAvailable()).isFalse();
        }

        @Test
        @DisplayName("The card list envelope reports no total count, because the source never counts")
        void cardListEnvelopeReportsNoTotals() {
            assertThat(componentNames(CardListResponse.class))
                    .noneMatch(name -> name.contains("total"));
        }

        @Test
        @DisplayName("A transaction response emits the masked number and no screen header")
        void transactionResponseEmitsNothingSensitive() throws Exception {
            final TransactionDto detail = new TransactionDto("CT01", "T1", "01/15/26", "COTRN01C", "T2",
                    "10:30:00", null, "0000000000000001", CARD_NUMBER, "01", "0001", "POS TERM",
                    "PURCHASE", "+00000100.00", "2026-01-15", "2026-01-15", "000000001", "STORE", "CITY",
                    "10001", null, null, null, new BigDecimal("100.00"));

            final String json = MAPPER.writeValueAsString(TransactionResponse.of(detail));

            assertThat(json).doesNotContain(CARD_NUMBER).contains(MASKED_CARD_NUMBER);
            assertThat(json).doesNotContain("COTRN01C");
        }

        @Test
        @DisplayName("A transaction list envelope carries the page metadata and the source status message")
        void transactionListEnvelopeCarriesContext() {
            final TransactionDto.TransactionListRow row = new TransactionDto.TransactionListRow(
                    null, "0000000000000001", "01/15/26", "PURCHASE", "+00000100.00");
            final PageResponse<TransactionDto.TransactionListRow> page = new PageResponse<>(
                    List.of(row), 1, TransactionDto.PAGE_SIZE, false, "0000000000000001",
                    "0000000000000001");

            final TransactionListResponse envelope = TransactionListResponse.of(page, "sealed-first",
                    "sealed-last", "You are already at the bottom of the page...");

            assertThat(envelope.pageSize()).isEqualTo(10);
            assertThat(envelope.nextPageAvailable()).isFalse();
            assertThat(envelope.statusMessage())
                    .isEqualTo("You are already at the bottom of the page...");
            assertThat(envelope.rows()).singleElement()
                    .extracting(TransactionListResponse.TransactionListRowResponse::transactionId)
                    .isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("Envelope row lists are immutable once built")
        void envelopeRowListsAreImmutable() {
            final CardListResponse cards = new CardListResponse(List.of(), 1, 7, false, null, null, null,
                    null, true);
            final TransactionListResponse transactions =
                    new TransactionListResponse(List.of(), 1, 10, false, null, null, null);

            assertThatThrownBy(() -> cards.rows().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> transactions.rows().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("A null row list becomes an empty one rather than a null JSON member")
        void nullRowListBecomesEmpty() {
            assertThat(new CardListResponse(null, 1, 7, false, null, null, null, null, true).rows()).isEmpty();
            assertThat(new TransactionListResponse(null, 1, 10, false, null, null, null).rows()).isEmpty();
        }
    }

    /**
     * The six recurring header fields of the seventeen symbolic maps, and the {@code XCTL} operand, are
     * transcribed on the field-contract types and excluded from every JSON body.
     *
     * <p>Two operations could otherwise publish them - the delete-user confirmation carries all six on its
     * component, and both
     * menu option records carry the program name. Both are closed by {@code @JsonIgnoreProperties}, which
     * keeps the component, its width check and its copybook citation while removing it from the body. These
     * tests assert the body, because the body is the contract.
     */
    @Nested
    @DisplayName("Screen chrome and routing state never reach a JSON body")
    class ChromeExclusion {

        /** The six fields {@code app/cpy-bms/*.CPY} repeats on every map, by JSON member name. */
        private static final List<String> CHROME_MEMBERS = List.of("transactionName", "title01", "title02",
                "currentDate", "currentTime", "programName");

        @Test
        @DisplayName("The delete-user confirmation publishes exactly its five business members")
        void deleteUserPublishesFiveMembers() throws Exception {
            final UserSecurityDto.UserDeleteScreen screen = new UserSecurityDto.UserDeleteScreen(
                    "CU03", "      AWS Mainframe Modernization       ", "08/05/26", "COUSR03C",
                    "              CardDemo                  ", "04:38:05",
                    "USER0001", "LAWRENCE", "THOMAS", "U", "User USER0001 has been deleted ...");

            final String json = MAPPER.writeValueAsString(screen);

            assertThat(CHROME_MEMBERS).allSatisfy(member -> assertThat(json)
                    .as("chrome member %s must not reach the body", member)
                    .doesNotContain("\"" + member + "\""));
            assertThat(json).doesNotContain("COUSR03C").doesNotContain("CardDemo");
            assertThat(MAPPER.readTree(json).fieldNames()).toIterable()
                    .as("api-contracts.md documents a five-member response for this operation")
                    .containsExactlyInAnyOrder("userIdInput", "firstName", "lastName", "userType",
                            "errorMessage");
        }

        @Test
        @DisplayName("Neither menu publishes the XCTL target, and both keep their option-table data")
        void neitherMenuPublishesTheXctlTarget() throws Exception {
            final String main = MAPPER.writeValueAsString(MenuResponse.mainMenu());
            final String admin = MAPPER.writeValueAsString(MenuResponse.adminMenu());

            assertThat(main).doesNotContain("programName").doesNotContain("COACTVWC");
            assertThat(admin).doesNotContain("programName").doesNotContain("COUSR00C");
            assertThat(main).contains("optionNumber").contains("optionName").contains("userTypeCode");
            assertThat(admin).contains("optionNumber").contains("optionName");
        }

        @Test
        @DisplayName("The excluded components remain on the records, because the copybooks declare them")
        void theExcludedComponentsRemainOnTheRecords() {
            assertThat(MenuResponse.MAIN_MENU_OPTIONS.get(0).programName()).isEqualTo("COACTVWC");
            assertThat(Arrays.stream(UserSecurityDto.UserDeleteScreen.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .as("all eleven fields of app/cpy-bms/COUSR03.CPY stay transcribed")
                    .contains("transactionName", "title01", "programName", "userIdInput", "errorMessage")
                    .hasSize(11);
        }
    }

    @Nested
    @DisplayName("The masking rule")
    class Masking {

        @Test
        @DisplayName("A sixteen-digit number keeps its last four and its length")
        void sixteenDigitsKeepTheLastFour() {
            assertThat(ApiMasking.maskCardNumber(CARD_NUMBER))
                    .isEqualTo(MASKED_CARD_NUMBER)
                    .hasSize(CARD_NUMBER.length());
        }

        @Test
        @DisplayName("A value of four characters or fewer is masked in full")
        void shortValuesAreMaskedInFull() {
            assertThat(ApiMasking.maskCardNumber("1234")).isEqualTo("****");
            assertThat(ApiMasking.maskCardNumber("1")).isEqualTo("*");
            assertThat(ApiMasking.maskCardNumber("12345")).isEqualTo("*2345");
        }

        @Test
        @DisplayName("Null stays null and blank stays blank, so absence is never turned into a card")
        void absenceIsPreserved() {
            assertThat(ApiMasking.maskCardNumber(null)).isNull();
            assertThat(ApiMasking.maskCardNumber("")).isEmpty();
            assertThat(ApiMasking.maskCardNumber("      ")).isEqualTo("      ");
        }

        @Test
        @DisplayName("Trailing field padding survives, because the maps declare fixed widths")
        void trailingPaddingIsPreserved() {
            assertThat(ApiMasking.maskCardNumber("41111111111111  "))
                    .isEqualTo("**********1111  ")
                    .hasSize(16);
        }

        @Test
        @DisplayName("The rule holder cannot be instantiated")
        void theRuleHolderIsNotInstantiable() throws Exception {
            final var constructor = ApiMasking.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatThrownBy(constructor::newInstance).hasRootCauseInstanceOf(AssertionError.class);
        }
    }
}
