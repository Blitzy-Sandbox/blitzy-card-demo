/*
 * ******************************************************************
 * Program     : BillPaymentRequestTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the bill-payment payload against its frozen
 *               field contract: the 10 screen fields COBIL00 declares,
 *               the balance width that diverges from the account-view
 *               map, and the four confirmation states the boundary
 *               deliberately does not adjudicate.
 * Source      : app/cpy-bms/COBIL00.CPY  (10 input fields)
 *               app/cbl/COBIL00C.cbl     (173-191 EVALUATE CONFIRMI)
 *               frozen at commit 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.dto.BillPaymentRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link BillPaymentRequest} against {@code app/cpy-bms/COBIL00.CPY}.
 *
 * <p>The interesting assertion on this payload concerns the confirmation field. The legacy program
 * adjudicates four outcomes for it at {@code app/cbl/COBIL00C.cbl:173-191} - affirmative, negative, absent
 * and invalid - and it does so in the program, not at the screen boundary. This record therefore constrains
 * the field to its declared width and nothing more, and the test asserts that all four representative
 * inputs pass the boundary rather than asserting that the boundary rejects three of them.
 */
@DisplayName("BillPaymentRequest: 10 screen fields, a divergent balance width, four unadjudicated states")
final class BillPaymentRequestTest {

    /** The bill-payment symbolic map, read from the frozen tree. */
    private static final BmsSymbolicMap COBIL00 = BmsSymbolicMap.of("COBIL00");

    /** The record component for each COBIL00 input field, in copybook declaration order. Read as pairs. */
    private static final List<String> FIELD_TO_COMPONENT = List.of(
            "TRNNAMEI", "transactionName",
            "TITLE01I", "title01",
            "CURDATEI", "currentDate",
            "PGMNAMEI", "programName",
            "TITLE02I", "title02",
            "CURTIMEI", "currentTime",
            "ACTIDINI", "accountId",
            "CURBALI", "currentBalance",
            "CONFIRMI", "confirmation",
            "ERRMSGI", "errorMessage");

    private static List<String> fieldComponentPairs() {
        return RecordFieldContract.pairsOf(FIELD_TO_COMPONENT);
    }

    private static BillPaymentRequest withConfirmation(final String confirmation) {
        return new BillPaymentRequest("CB00", "CardDemo Bill Payment", "08/01/26", "COBIL00C",
                "Bill Payment", "14:22:31", "00000000001", "     1234.56  ", confirmation, "");
    }

    private Set<ConstraintViolation<BillPaymentRequest>> violationsOf(final BillPaymentRequest request) {
        return ValidationSupport.violationsOf(request, "request");
    }

    @Nested
    @DisplayName("1. The 10 fields the copybook declares, matched positionally")
    final class FieldContract {

        @Test
        @DisplayName("the record declares one component per copybook input field")
        void componentCountMatchesTheCopybook() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .hasSize(COBIL00.inputFieldCount())
                    .hasSize(10);
        }

        @Test
        @DisplayName("every component matches the copybook field at the same index")
        void componentsCorrespondPositionally() {
            final List<String> components = RecordFieldContract.componentNames(BillPaymentRequest.class);
            final List<String> copybookOrder = COBIL00.fieldNames();

            assertThat(FIELD_TO_COMPONENT).hasSize(2 * components.size());
            for (int index = 0; index < components.size(); index++) {
                assertThat(copybookOrder.get(index))
                        .as("copybook field at index %d", index)
                        .isEqualTo(FIELD_TO_COMPONENT.get(2 * index));
                assertThat(components.get(index))
                        .as("component at index %d", index)
                        .isEqualTo(FIELD_TO_COMPONENT.get(2 * index + 1));
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.BillPaymentRequestTest#fieldComponentPairs")
        @DisplayName("each declared width is the copybook's width, not a restated literal")
        void declaredWidthComesFromTheCopybook(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);

            assertThat(RecordFieldContract.declaredSizeMax(BillPaymentRequest.class, component))
                    .as("%s is PIC X(%d) on COBIL00", field, COBIL00.widthOf(field))
                    .isEqualTo(COBIL00.widthOf(field));
        }

        @Test
        @DisplayName("a fully populated in-width payload raises no violation")
        void populatedPayloadIsValid() {
            assertThat(violationsOf(withConfirmation("Y"))).isEmpty();
        }

        @Test
        @DisplayName("an over-width component raises a violation naming it")
        void overWidthComponentIsRejected() {
            final BillPaymentRequest oversized = new BillPaymentRequest(null, null, null, null, null, null,
                    "000000000012", null, null, null);

            assertThat(violationsOf(oversized))
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath()).hasToString("accountId"));
        }

        @Test
        @DisplayName("an all-absent payload raises no violation")
        void allAbsentPayloadIsValid() {
            assertThat(violationsOf(new BillPaymentRequest(null, null, null, null, null, null, null, null,
                    null, null))).isEmpty();
        }

        @Test
        @DisplayName("no component is mandatory and none imposes a format")
        void nothingStricterIsInvented() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .allSatisfy(component -> {
                        assertThat(RecordFieldContract.declares(BillPaymentRequest.class, component,
                                NotNull.class)).as("%s must tolerate absence", component).isFalse();
                        assertThat(RecordFieldContract.declares(BillPaymentRequest.class, component,
                                Pattern.class)).as("%s must impose no format", component).isFalse();
                    });
        }
    }

    @Nested
    @DisplayName("2. The balance width diverges from the account-view map")
    final class DivergentBalanceWidth {

        @Test
        @DisplayName("this map names the balance differently and declares it narrower")
        void balanceIsNarrowerHere() {
            final BmsSymbolicMap coactvw = BmsSymbolicMap.of("COACTVW");

            assertThat(COBIL00.widthOf("CURBALI")).isEqualTo(14);
            assertThat(coactvw.widthOf("ACURBALI")).isEqualTo(15);
            assertThat(COBIL00.declares("ACURBALI")).isFalse();
            assertThat(coactvw.declares("CURBALI")).isFalse();
        }

        @Test
        @DisplayName("so the account payload's monetary width does not apply here")
        void accountMonetaryWidthDoesNotApply() {
            // The rationale documented on AccountDto.MONEY_DISPLAY_LENGTH: a different name at a different
            // width, which is why neither that constant nor its conversion may be shared with this map.
            assertThat(RecordFieldContract.declaredSizeMax(BillPaymentRequest.class, "currentBalance"))
                    .isEqualTo(COBIL00.widthOf("CURBALI"))
                    .isNotEqualTo(AccountDto.MONEY_DISPLAY_LENGTH);
        }

        @Test
        @DisplayName("a balance filling this map's field is accepted, and one byte more is not")
        void theBoundaryIsTheCopybookWidth() {
            final int width = COBIL00.widthOf("CURBALI");
            final BillPaymentRequest exact = new BillPaymentRequest(null, null, null, null, null, null,
                    null, "9".repeat(width), null, null);
            final BillPaymentRequest tooWide = new BillPaymentRequest(null, null, null, null, null, null,
                    null, "9".repeat(width + 1), null, null);

            assertThat(violationsOf(exact)).isEmpty();
            assertThat(violationsOf(tooWide)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("3. The four confirmation states are the program's business, not the boundary's")
    final class ConfirmationStates {

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"Y", "y", "N", "n", " ", "Q", "1"})
        @DisplayName("every one-character confirmation passes the boundary")
        void everySingleCharacterPasses(final String confirmation) {
            // app/cbl/COBIL00C.cbl:173-191 adjudicates affirmative, negative, absent and invalid in the
            // program. The boundary constrains the width only, so an invalid value has to reach the service
            // in order to be answered with the source's own message.
            assertThat(violationsOf(withConfirmation(confirmation)))
                    .as("[%s] must not be rejected at the boundary", confirmation)
                    .isEmpty();
        }

        @Test
        @DisplayName("a low-values confirmation passes, which the source treats as absent")
        void lowValuesConfirmationPasses() {
            // WHEN SPACES / WHEN LOW-VALUES share one branch at app/cbl/COBIL00C.cbl:182-184. The character
            // is supplied here rather than through a parameter source because a NUL cannot be written into
            // the Surefire XML report as part of a display name.
            assertThat(violationsOf(withConfirmation("\u0000"))).isEmpty();
        }

        @Test
        @DisplayName("an absent confirmation passes, which is the first-entry state")
        void absentConfirmationPasses() {
            assertThat(violationsOf(withConfirmation(null))).isEmpty();
        }

        @Test
        @DisplayName("a two-character confirmation is rejected, because the screen field holds one")
        void twoCharactersAreRejected() {
            assertThat(COBIL00.widthOf("CONFIRMI")).isEqualTo(1);
            assertThat(violationsOf(withConfirmation("YY")))
                    .singleElement()
                    .satisfies(violation ->
                            assertThat(violation.getPropertyPath()).hasToString("confirmation"));
        }

        @Test
        @DisplayName("the lower-case forms are distinct values the boundary preserves verbatim")
        void caseIsPreserved() {
            // The program treats 'Y' and 'y' alike, but it does so itself. Normalising here would hide from
            // the service which of the two the terminal actually sent.
            assertThat(withConfirmation("y").confirmation()).isEqualTo("y");
            assertThat(withConfirmation("Y").confirmation()).isEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("4. The redacting override, and the components it withholds")
    final class RedactedRendering {

        @Test
        @DisplayName("it carries no card number and no customer name component")
        void itCarriesNothingSensitive() {
            // The other three request payloads on this screen family override toString to suppress a card
            // number or a name. This one declares neither kind of component, so the generated rendering
            // discloses nothing of that class.
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .doesNotContain("cardNumber", "cardholderName", "customerFirstName",
                            "customerMiddleName", "customerLastName", "customerSsn", "phoneNumber1",
                            "phoneNumber2", "governmentIssuedId", "eftAccountId");
        }

        @Test
        @DisplayName("its components are screen headers, an account identifier and control values")
        void itsComponentsAreBenign() {
            assertThat(RecordFieldContract.componentNames(BillPaymentRequest.class))
                    .containsExactly("transactionName", "title01", "currentDate", "programName", "title02",
                            "currentTime", "accountId", "currentBalance", "confirmation", "errorMessage");
        }

        @Test
        @DisplayName("the rendering is overridden and withholds the account identifier and the balance")
        void renderingWithholdsTheAccountIdentifierAndBalance() {
            // A record's generated rendering emits every component. Two of the ten are the account
            // identifier at app/cpy-bms/COBIL00.CPY:60 and the balance at :66 - the balance is financial
            // data and the identifier selects a customer's account - so the rendering is overridden rather
            // than inherited. There is no downstream mask to fall back on: no logback-spring.xml exists
            // under src/main/resources.
            final String rendered = withConfirmation("Y").toString();

            assertThat(rendered)
                    .as("the rendering names each modelled member and then describes its shape, so the "
                            + "labels are present but no submitted byte is")
                    .startsWith("BillPaymentRequest[")
                    .endsWith("]")
                    .contains("accountId=", "currentBalance=", "errorMessage=")
                    .doesNotContain("00000000001");
            assertThat(rendered)
                    .as("the transaction name and the program name are caller supplied too, so they "
                            + "are withheld with the rest of the presentation header rather than "
                            + "echoed; a line break in either would otherwise forge a log entry")
                    .doesNotContain("transactionName=", "programName=", "CB00", "COBIL00C")
                    .contains("header=<6 presentation members omitted>");
        }

        @Test
        @DisplayName("toString is declared on the type, so the generated one cannot be inherited")
        void toStringIsDeclaredOnTheType() {
            assertThat(ReflectionCensus.declaredMethodNames(BillPaymentRequest.class))
                    .as("an inherited rendering would emit all ten components")
                    .contains("toString");
        }

        @Test
        @DisplayName("the rendering is null safe and keeps absent distinct from empty")
        void theRenderingIsNullSafeAndKeepsAbsentDistinctFromEmpty() {
            // A record bound from a partially populated screen carries nulls, and the rendering has to
            // survive them: a NullPointerException raised while building a log line would lose the very
            // event being reported. Absent and empty are kept distinct because they mean different
            // things on a 3270 field - never transmitted versus transmitted blank - and neither
            // discloses a submitted byte.
            final BillPaymentRequest unpopulated = new BillPaymentRequest(
                    null, null, null, null, null, null, null, null, null, null);

            assertThat(unpopulated.toString())
                    .as("every member renders as absent, and the presentation header is still withheld")
                    .isEqualTo("BillPaymentRequest[accountId=absent, currentBalance=absent, "
                            + "confirmation=absent, errorMessage=absent, "
                            + "header=<6 presentation members omitted>]");

            assertThat(withConfirmation("Y").toString())
                    .as("a transmitted blank is reported as empty rather than as absent, so the two "
                            + "screen states stay distinguishable in a log")
                    .contains("errorMessage=empty")
                    .doesNotContain("errorMessage=absent");
        }

        @Test
        @DisplayName("a confirmation wider than one byte is described by its width, not its content")
        void aWiderConfirmationIsDescribedByItsWidth() {
            // The single byte case is rendered as a code point, which is injective and therefore fully
            // diagnostic. Anything wider cannot be rendered that way without emitting content, so it
            // falls back to the width. CONFIRMI is PIC X(01) at app/cpy-bms/COBIL00.CPY, so a wider
            // value is already invalid - but the rendering must not leak it while reporting that fault.
            assertThat(withConfirmation("YY").toString())
                    .as("the width is disclosed, the bytes are not")
                    .contains("confirmation=2 chars")
                    .doesNotContain("YY");
        }
    }
}
