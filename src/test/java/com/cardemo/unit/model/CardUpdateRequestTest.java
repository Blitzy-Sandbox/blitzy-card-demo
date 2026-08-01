/*
 * ******************************************************************
 * Program     : CardUpdateRequestTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the card-update payload against its frozen
 *               field contract: the 17 screen fields COCRDUP declares,
 *               the three fields that differ from the card-detail map,
 *               and the toString override that withholds both the card
 *               number and the cardholder name.
 * Source      : app/cpy-bms/COCRDUP.CPY  (17 input fields)
 *               app/cpy-bms/COCRDSL.CPY  (the detail map it diverges from)
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

import com.cardemo.model.dto.CardUpdateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies {@link CardUpdateRequest} against {@code app/cpy-bms/COCRDUP.CPY}.
 *
 * <p>The card-update map and the card-detail map look alike and are not. Three differences are asserted
 * here because a payload built from the wrong one of the two would compile, populate and silently truncate:
 * the update map declares an expiry-day field the detail map has no counterpart for, and it splits the
 * function-key legend across two narrower fields where the detail map declares one wide one.
 */
@DisplayName("CardUpdateRequest: 17 screen fields, three divergences from the detail map, a double redaction")
final class CardUpdateRequestTest {

    /** The card-update symbolic map, read from the frozen tree. */
    private static final BmsSymbolicMap COCRDUP = BmsSymbolicMap.of("COCRDUP");

    /** The card-detail symbolic map, which this one deliberately differs from. */
    private static final BmsSymbolicMap COCRDSL = BmsSymbolicMap.of("COCRDSL");

    /** The record component for each COCRDUP input field, in copybook declaration order. Read as pairs. */
    private static final List<String> FIELD_TO_COMPONENT = List.of(
            "TRNNAMEI", "transactionName",
            "TITLE01I", "title01",
            "CURDATEI", "currentDate",
            "PGMNAMEI", "programName",
            "TITLE02I", "title02",
            "CURTIMEI", "currentTime",
            "ACCTSIDI", "accountId",
            "CARDSIDI", "cardNumber",
            "CRDNAMEI", "cardholderName",
            "CRDSTCDI", "cardStatusCode",
            "EXPMONI", "expiryMonth",
            "EXPYEARI", "expiryYear",
            "EXPDAYI", "expiryDay",
            "INFOMSGI", "informationMessage",
            "ERRMSGI", "errorMessage",
            "FKEYSI", "functionKeys",
            "FKEYSCI", "functionKeysContinued");

    private static List<String> fieldComponentPairs() {
        return RecordFieldContract.pairsOf(FIELD_TO_COMPONENT);
    }

    /**
     * Builds a payload whose every component carries a distinguishable, in-width value.
     *
     * @return a fully populated payload
     */
    private static CardUpdateRequest populated() {
        return new CardUpdateRequest("CCUP", "CardDemo Update Card", "08/01/26", "COCRDUPC",
                "Update Card", "14:22:31", "00000000001", "4111999988887777", "FNAMEAA1 LNM1", "Y",
                "12", "2027", "31", "Card updated", "", "F3=Exit F5=Save", "F12=Cancel", null, null);
    }

    private static CardUpdateRequest empty() {
        return new CardUpdateRequest(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private Set<ConstraintViolation<CardUpdateRequest>> violationsOf(final CardUpdateRequest request) {
        return ValidationSupport.violationsOf(request, "request");
    }

    @Nested
    @DisplayName("1. The 17 fields the copybook declares, matched positionally")
    final class FieldContract {

        @Test
        @DisplayName("the record declares one component per copybook input field, then the two snapshots")
        void componentCountMatchesTheCopybook() {
            // The leading components are the screen fields, one per COCRDUP input field. The two that
            // follow are CCUP-OLD-DETAILS and CCUP-NEW-DETAILS, declared in WORKING-STORAGE at
            // app/cbl/COCRDUPC.cbl:291 and :303 rather than on the map: 9300-CHECK-CHANGE-IN-REC compares
            // business field values that a stateless server cannot otherwise retain across the submit, so
            // the payload has to carry them even though the copybook does not declare them.
            final List<String> components = RecordFieldContract.componentNames(CardUpdateRequest.class);
            assertThat(components).hasSize(COCRDUP.inputFieldCount() + 2).hasSize(19);
            assertThat(components.subList(0, COCRDUP.inputFieldCount()))
                    .as("the screen fields come first, in copybook order")
                    .hasSize(17);
            assertThat(components.subList(COCRDUP.inputFieldCount(), components.size()))
                    .containsExactly("oldDetails", "newDetails");
        }

        @Test
        @DisplayName("every component matches the copybook field at the same index")
        void componentsCorrespondPositionally() {
            final List<String> components = RecordFieldContract.componentNames(CardUpdateRequest.class)
                    .subList(0, COCRDUP.inputFieldCount());
            final List<String> copybookOrder = COCRDUP.fieldNames();

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
        @MethodSource("com.cardemo.unit.model.CardUpdateRequestTest#fieldComponentPairs")
        @DisplayName("each declared width is the copybook's width, not a restated literal")
        void declaredWidthComesFromTheCopybook(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);

            assertThat(RecordFieldContract.declaredSizeMax(CardUpdateRequest.class, component))
                    .as("%s is PIC X(%d) on COCRDUP", field, COCRDUP.widthOf(field))
                    .isEqualTo(COCRDUP.widthOf(field));
        }

        @Test
        @DisplayName("a fully populated in-width payload raises no violation")
        void populatedPayloadIsValid() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("an all-absent payload raises no violation")
        void allAbsentPayloadIsValid() {
            assertThat(violationsOf(empty())).isEmpty();
        }

        @Test
        @DisplayName("no component is mandatory")
        void noComponentIsMandatory() {
            assertThat(RecordFieldContract.componentNames(CardUpdateRequest.class))
                    .allSatisfy(component -> assertThat(RecordFieldContract.declares(
                            CardUpdateRequest.class, component, NotNull.class))
                            .as("%s must tolerate absence", component)
                            .isFalse());
        }

        @Test
        @DisplayName("an over-width cardholder name is rejected at the declared boundary")
        void overWidthCardholderNameIsRejected() {
            final int width = COCRDUP.widthOf("CRDNAMEI");
            final CardUpdateRequest tooWide = new CardUpdateRequest(null, null, null, null, null, null,
                    null, null, "A".repeat(width + 1), null, null, null, null, null, null, null, null,
                    null, null);

            assertThat(violationsOf(tooWide))
                    .singleElement()
                    .satisfies(violation -> {
                        assertThat(violation.getPropertyPath()).hasToString("cardholderName");
                        assertThat(violation.getMessage()).contains("at most 50 characters");
                    });
        }
    }

    @Nested
    @DisplayName("2. The three divergences from the card-detail map")
    final class DivergencesFromDetailMap {

        @Test
        @DisplayName("the update map declares an expiry day the detail map does not")
        void expiryDayExistsOnlyHere() {
            assertThat(COCRDUP.declares("EXPDAYI")).isTrue();
            assertThat(COCRDUP.widthOf("EXPDAYI")).isEqualTo(2);
            assertThat(COCRDSL.declares("EXPDAYI"))
                    .as("app/cpy-bms/COCRDSL.CPY declares no counterpart")
                    .isFalse();
            assertThat(RecordFieldContract.componentNames(CardUpdateRequest.class))
                    .contains("expiryDay");
        }

        @Test
        @DisplayName("the function-key legend is split in two here and single on the detail map")
        void functionKeyLegendIsSplit() {
            assertThat(COCRDUP.declares("FKEYSI")).isTrue();
            assertThat(COCRDUP.declares("FKEYSCI")).isTrue();
            assertThat(COCRDSL.declares("FKEYSI")).isTrue();
            assertThat(COCRDSL.declares("FKEYSCI"))
                    .as("the detail map has no continuation field")
                    .isFalse();
        }

        @Test
        @DisplayName("the shared field name FKEYSI is a different width on each map")
        void functionKeyWidthDiverges() {
            // Reusing the detail map's 75 here would let a 75-character legend through a 21-character field.
            assertThat(COCRDSL.widthOf("FKEYSI")).isEqualTo(75);
            assertThat(COCRDUP.widthOf("FKEYSI")).isEqualTo(21);
            assertThat(RecordFieldContract.declaredSizeMax(CardUpdateRequest.class, "functionKeys"))
                    .as("the payload must size to its own map")
                    .isEqualTo(21)
                    .isNotEqualTo(COCRDSL.widthOf("FKEYSI"));
        }

        @Test
        @DisplayName("the two legend fields together are still narrower than the detail map's one")
        void theTwoLegendFieldsAreNarrowerCombined() {
            final int combined = COCRDUP.widthOf("FKEYSI") + COCRDUP.widthOf("FKEYSCI");

            assertThat(combined).isEqualTo(39);
            assertThat(combined).isLessThan(COCRDSL.widthOf("FKEYSI"));
        }

        @Test
        @DisplayName("the message fields happen to agree, so only the legend and the day differ")
        void messageFieldsAgree() {
            // Stated explicitly so that a future reader does not assume every shared name diverges. On the
            // two card maps these two do agree; on the card list map they do not.
            assertThat(COCRDUP.widthOf("INFOMSGI")).isEqualTo(COCRDSL.widthOf("INFOMSGI")).isEqualTo(40);
            assertThat(COCRDUP.widthOf("ERRMSGI")).isEqualTo(COCRDSL.widthOf("ERRMSGI")).isEqualTo(80);
        }
    }

    @Nested
    @DisplayName("3. The diagnostic rendering withholds two values, not one")
    final class DoubleRedaction {

        @Test
        @DisplayName("only the account identifier and the program name are rendered")
        void onlyTwoFieldsAreRendered() {
            assertThat(populated().toString())
                    .isEqualTo("CardUpdateRequest[accountId=00000000001, programName=COCRDUPC]");
        }

        @Test
        @DisplayName("the card number is omitted entirely")
        void cardNumberIsOmitted() {
            final String rendered = populated().toString();

            assertThat(rendered).doesNotContain("4111111111111111");
            assertThat(rendered).as("not truncated to a last four").doesNotContain("1111");
        }

        @Test
        @DisplayName("the cardholder name is omitted entirely too")
        void cardholderNameIsOmitted() {
            final String rendered = populated().toString();

            // The probes must be the values the fixture actually carries, or the assertion passes
            // vacuously: populated() builds the cardholder name from "FNAMEAA1 LNM1".
            assertThat(rendered).doesNotContain("FNAMEAA1").doesNotContain("LNM1");
        }

        @Test
        @DisplayName("no placeholder is emitted in place of either omitted value")
        void noPlaceholderIsEmitted() {
            // Omitted entirely rather than rendered as a placeholder, so the output carries no token derived
            // from either value in any form.
            assertThat(populated().toString()).doesNotContain("*").doesNotContain("...");
        }

        @Test
        @DisplayName("the two rendered values are emitted verbatim, so absence stays visible")
        void renderedValuesAreVerbatim() {
            assertThat(empty().toString())
                    .isEqualTo("CardUpdateRequest[accountId=null, programName=null]");
        }

        @Test
        @DisplayName("no other populated component reaches the rendering")
        void noOtherComponentIsRendered() {
            assertThat(populated().toString())
                    .doesNotContain("Card updated")
                    .doesNotContain("F3=Exit")
                    .doesNotContain("F12=Cancel")
                    .doesNotContain("2027");
        }
    }
}
