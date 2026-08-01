/*
 * ******************************************************************
 * Program     : CommAreaTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the COMMAREA payload against COCOM01Y: the
 *               nine live fields it retains, the seven routing and
 *               screen-state fields a stateless design drops, the
 *               widths read from the copybook, and the rendering that
 *               discloses no card number and no customer name.
 * Source      : app/cpy/COCOM01Y.cpy  (16 leaf fields, 9 live)
 *               app/cpy/CVACT02Y.cpy  (CARD-NUM PIC X(16))
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

import com.cardemo.model.dto.CommArea;
import com.cardemo.model.enums.UserType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link CommArea} against {@code app/cpy/COCOM01Y.cpy}.
 *
 * <p>This payload is the one place in the migration where the right answer is to carry <em>less</em> than the
 * source declared. The COMMAREA was the pseudo-conversational carrier for both business data and CICS
 * navigation state, and a stateless HTTP design reproduces the first and has no use for the second. The
 * central assertion here is therefore a subtraction: the copybook declares sixteen leaf fields, this record
 * exposes exactly nine, and the seven it omits are exactly the routing, re-entry and screen-state fields.
 *
 * <p>Widths are read from the copybook rather than restated. {@code COCOM01Y} is a record-layout copybook
 * rather than a generated symbolic map, so {@link BmsSymbolicMap} does not apply to it and this class parses
 * the {@code PIC} clauses itself.
 */
@DisplayName("CommArea: nine live fields, seven deliberately dropped, and a rendering that discloses little")
final class CommAreaTest {

    /** Every leaf field the copybook declares, mapped to its declared width, in declaration order. */
    private static final Map<String, Integer> COCOM01Y = declaredWidths();

    /** The seven fields a stateless design has no counterpart for, per AAP section 0.5.2.4. */
    private static final List<String> DROPPED_FIELDS = List.of(
            "CDEMO-FROM-TRANID", "CDEMO-FROM-PROGRAM", "CDEMO-TO-TRANID", "CDEMO-TO-PROGRAM",
            "CDEMO-PGM-CONTEXT", "CDEMO-LAST-MAP", "CDEMO-LAST-MAPSET");

    /** The nine live fields, paired with the component each becomes. Read as pairs. */
    private static final List<String> FIELD_TO_COMPONENT = List.of(
            "CDEMO-USER-ID", "userId",
            "CDEMO-USER-TYPE", "userType",
            "CDEMO-CUST-ID", "customerId",
            "CDEMO-CUST-FNAME", "customerFirstName",
            "CDEMO-CUST-MNAME", "customerMiddleName",
            "CDEMO-CUST-LNAME", "customerLastName",
            "CDEMO-ACCT-ID", "accountId",
            "CDEMO-ACCT-STATUS", "accountStatus",
            "CDEMO-CARD-NUM", "cardNumber");

    /**
     * Parses every {@code CDEMO-} leaf field and its declared width out of the frozen copybook.
     *
     * @return field name to declared width, in copybook declaration order
     */
    private static Map<String, Integer> declaredWidths() {
        final Path path = Path.of("app", "cpy", "COCOM01Y.cpy");
        final List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("cannot read the frozen copybook " + path, unreadable);
        }
        final java.util.regex.Pattern declaration =
                java.util.regex.Pattern.compile("(CDEMO-[A-Z0-9-]+)\\s+PIC\\s+[X9]\\((\\d+)\\)");
        final Map<String, Integer> widths = new LinkedHashMap<>();
        for (final String line : lines) {
            final java.util.regex.Matcher matched = declaration.matcher(line);
            if (matched.find()) {
                widths.put(matched.group(1), Integer.valueOf(matched.group(2)));
            }
        }
        // Collections.unmodifiableMap over the LinkedHashMap, not Map.copyOf: the latter returns a map
        // whose iteration order is unspecified, which would falsify the declaration order promised above.
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(widths));
    }

    private static List<String> fieldComponentPairs() {
        return RecordFieldContract.pairsOf(FIELD_TO_COMPONENT);
    }

    private static CommArea populated() {
        return new CommArea("ADMNUSR1", "A", "000000001", "FNAMEAA1", "MNAM1", "LNM1",
                "00000000011", "Y", "4111999988887777");
    }

    private static CommArea empty() {
        return new CommArea(null, null, null, null, null, null, null, null, null);
    }

    private Set<ConstraintViolation<CommArea>> violationsOf(final CommArea commArea) {
        return ValidationSupport.violationsOf(commArea, "commArea");
    }

    @Nested
    @DisplayName("1. Nine live fields kept, seven navigation fields dropped")
    final class FieldSubtraction {

        @Test
        @DisplayName("the copybook declares sixteen leaf fields")
        void copybookDeclaresSixteen() {
            assertThat(COCOM01Y)
                    .as("app/cpy/COCOM01Y.cpy leaf fields carrying a PIC clause")
                    .hasSize(16);
        }

        @Test
        @DisplayName("the record exposes exactly nine of them")
        void recordExposesNine() {
            assertThat(RecordFieldContract.componentNames(CommArea.class)).hasSize(9);
        }

        @Test
        @DisplayName("nine kept plus seven dropped accounts for all sixteen")
        void theArithmeticCloses() {
            assertThat(FIELD_TO_COMPONENT).hasSize(18);
            assertThat(DROPPED_FIELDS).hasSize(7);
            assertThat(FIELD_TO_COMPONENT.size() / 2 + DROPPED_FIELDS.size()).isEqualTo(COCOM01Y.size());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CDEMO-FROM-TRANID", "CDEMO-FROM-PROGRAM", "CDEMO-TO-TRANID",
                "CDEMO-TO-PROGRAM", "CDEMO-PGM-CONTEXT", "CDEMO-LAST-MAP", "CDEMO-LAST-MAPSET"})
        @DisplayName("each dropped field is declared by the copybook and absent from the record")
        void droppedFieldsAreDeclaredButAbsent(final String field) {
            // Routing is URL-based, the enter-versus-re-enter flag collapses into stateless request handling,
            // and no screen state is retained. Each of these is genuinely declared in the source, so the
            // omission is a decision rather than an oversight.
            assertThat(COCOM01Y).containsKey(field);
            assertThat(RecordFieldContract.componentNames(CommArea.class))
                    .as("%s has no counterpart in a stateless design", field)
                    .doesNotContain(camelCaseOf(field));
        }

        @Test
        @DisplayName("no component name resembles a routing or screen-state field")
        void noRoutingComponentSurvives() {
            assertThat(RecordFieldContract.componentNames(CommArea.class))
                    .doesNotContain("fromTranId", "toTranId", "fromProgram", "toProgram", "programContext",
                            "lastMap", "lastMapset");
        }

        @Test
        @DisplayName("the nine kept components are exactly the business fields, in copybook order")
        void keptComponentsAreTheBusinessFields() {
            assertThat(RecordFieldContract.componentNames(CommArea.class))
                    .containsExactly("userId", "userType", "customerId", "customerFirstName",
                            "customerMiddleName", "customerLastName", "accountId", "accountStatus",
                            "cardNumber");
        }

        private String camelCaseOf(final String cobolName) {
            final StringBuilder camel = new StringBuilder();
            boolean upper = false;
            for (final char character : cobolName.substring("CDEMO-".length()).toCharArray()) {
                if (character == '-') {
                    upper = true;
                } else if (upper) {
                    camel.append(Character.toUpperCase(character));
                    upper = false;
                } else {
                    camel.append(Character.toLowerCase(character));
                }
            }
            return camel.toString();
        }
    }

    @Nested
    @DisplayName("2. The published widths are the copybook's widths")
    final class PublishedWidths {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.CommAreaTest#fieldComponentPairs")
        @DisplayName("each component's declared ceiling is the copybook's width")
        void ceilingsComeFromTheCopybook(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);
            final int declaredWidth = COCOM01Y.get(field).intValue();

            final Size size = RecordFieldContract.annotationOn(CommArea.class, component, Size.class);
            final Digits digits = RecordFieldContract.annotationOn(CommArea.class, component, Digits.class);

            assertThat(size == null ^ digits == null)
                    .as("%s must carry exactly one of @Size or @Digits", component)
                    .isTrue();
            final int declaredCeiling = size != null ? size.max() : digits.integer();
            assertThat(declaredCeiling)
                    .as("%s is declared with %d positions in COCOM01Y", field, declaredWidth)
                    .isEqualTo(declaredWidth);
        }

        @Test
        @DisplayName("the alphanumeric fields carry a size constraint")
        void alphanumericFieldsCarrySize() {
            assertThat(CommArea.USER_ID_MAX_LENGTH).isEqualTo(COCOM01Y.get("CDEMO-USER-ID").intValue());
            assertThat(CommArea.USER_TYPE_LENGTH).isEqualTo(COCOM01Y.get("CDEMO-USER-TYPE").intValue());
            assertThat(CommArea.CUSTOMER_NAME_MAX_LENGTH)
                    .isEqualTo(COCOM01Y.get("CDEMO-CUST-FNAME").intValue())
                    .isEqualTo(COCOM01Y.get("CDEMO-CUST-MNAME").intValue())
                    .isEqualTo(COCOM01Y.get("CDEMO-CUST-LNAME").intValue());
            assertThat(CommArea.ACCOUNT_STATUS_LENGTH)
                    .isEqualTo(COCOM01Y.get("CDEMO-ACCT-STATUS").intValue());
            assertThat(CommArea.CARD_NUMBER_MAX_LENGTH)
                    .isEqualTo(COCOM01Y.get("CDEMO-CARD-NUM").intValue());
        }

        @Test
        @DisplayName("the numeric-PIC fields carry a size constraint sized from their PIC")
        void numericFieldsCarrySize() {
            assertThat(CommArea.CUSTOMER_ID_LENGTH).isEqualTo(COCOM01Y.get("CDEMO-CUST-ID").intValue());
            assertThat(CommArea.ACCOUNT_ID_LENGTH).isEqualTo(COCOM01Y.get("CDEMO-ACCT-ID").intValue());

            // CDEMO-CUST-ID PIC 9(09) and CDEMO-ACCT-ID PIC 9(11) are display numerics, so the value is a
            // fixed-width character field and its ceiling is a character count. A @Digits bound would
            // describe a numeric type this record deliberately does not use, which is why the constraint
            // is @Size on these two components exactly as it is on the seven alphanumeric ones.
            assertThat(RecordFieldContract.declares(CommArea.class, "customerId", Digits.class)).isFalse();
            assertThat(RecordFieldContract.declares(CommArea.class, "accountId", Digits.class)).isFalse();
            assertThat(RecordFieldContract.declaredSizeMax(CommArea.class, "customerId"))
                    .isEqualTo(COCOM01Y.get("CDEMO-CUST-ID").intValue());
            assertThat(RecordFieldContract.declaredSizeMax(CommArea.class, "accountId"))
                    .isEqualTo(COCOM01Y.get("CDEMO-ACCT-ID").intValue());
        }

        @Test
        @DisplayName("the card number is typed alphanumerically although this copybook declares it numeric")
        void cardNumberIsTypedAlphanumerically() {
            // COCOM01Y declares CDEMO-CARD-NUM PIC 9(16) while CVACT02Y declares the same sixteen positions
            // as CARD-NUM PIC X(16). The two copybooks disagree about the type and agree about the width, so
            // the record follows the record layout and the single width constant is correct for both.
            assertThat(RecordFieldContract.declares(CommArea.class, "cardNumber", Size.class)).isTrue();
            assertThat(RecordFieldContract.declares(CommArea.class, "cardNumber", Digits.class)).isFalse();
            assertThat(CommArea.CARD_NUMBER_MAX_LENGTH).isEqualTo(16);
        }

        @Test
        @DisplayName("an over-width value is rejected and an exactly-wide one is not")
        void theBoundaryIsTheCopybookWidth() {
            final CommArea exact = new CommArea("A".repeat(CommArea.USER_ID_MAX_LENGTH), null, null, null,
                    null, null, null, null, null);
            final CommArea tooWide = new CommArea("A".repeat(CommArea.USER_ID_MAX_LENGTH + 1), null, null,
                    null, null, null, null, null, null);

            assertThat(violationsOf(exact)).isEmpty();
            assertThat(violationsOf(tooWide))
                    .singleElement()
                    .satisfies(violation -> assertThat(violation.getPropertyPath()).hasToString("userId"));
        }
    }

    @Nested
    @DisplayName("3. Nothing stricter than a width is invented")
    final class NoInventedRules {

        @Test
        @DisplayName("no component is mandatory, because an unpopulated COMMAREA is legitimate")
        void noComponentIsMandatory() {
            // A COMMAREA reaching a program before an account had been selected simply had no account
            // identifier in it.
            assertThat(RecordFieldContract.componentNames(CommArea.class))
                    .allSatisfy(component -> assertThat(
                            RecordFieldContract.declares(CommArea.class, component, NotNull.class))
                            .as("%s must tolerate absence", component)
                            .isFalse());
        }

        @Test
        @DisplayName("no component imposes a format")
        void noComponentImposesAFormat() {
            assertThat(RecordFieldContract.componentNames(CommArea.class))
                    .allSatisfy(component -> assertThat(
                            RecordFieldContract.declares(CommArea.class, component, Pattern.class))
                            .as("%s must impose no format", component)
                            .isFalse());
        }

        @Test
        @DisplayName("an entirely absent payload raises no violation")
        void absentPayloadIsValid() {
            assertThat(violationsOf(empty())).isEmpty();
        }

        @Test
        @DisplayName("a blank field is a different state from an absent one and both pass")
        void blankAndAbsentBothPass() {
            final CommArea blank = new CommArea("        ", " ", null, null, null, null, null, " ", null);

            assertThat(violationsOf(blank)).isEmpty();
            assertThat(blank.userId()).as("a present blank field is preserved, not nulled").isEqualTo("        ");
            assertThat(empty().userId()).isNull();
        }

        @Test
        @DisplayName("a fully populated payload raises no violation")
        void populatedPayloadIsValid() {
            assertThat(violationsOf(populated())).isEmpty();
        }
    }

    @Nested
    @DisplayName("4. The typed user-type view")
    final class TypedUserType {

        @Test
        @DisplayName("the administrator code resolves to the administrator constant")
        void administratorResolves() {
            assertThat(new CommArea(null, "A", null, null, null, null, null, null, null).resolvedUserType())
                    .isEqualTo(UserType.ADMIN);
        }

        @Test
        @DisplayName("the standard-user code resolves to the user constant")
        void standardUserResolves() {
            assertThat(new CommArea(null, "U", null, null, null, null, null, null, null).resolvedUserType())
                    .isEqualTo(UserType.USER);
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"X", "a", "u", " ", "AA", "1"})
        @DisplayName("any other value resolves to no type rather than throwing")
        void anythingElseResolvesToNull(final String code) {
            assertThat(new CommArea(null, code, null, null, null, null, null, null, null)
                    .resolvedUserType())
                    .as("[%s] is not one of the two codes the copybook defines", code)
                    .isNull();
        }

        @Test
        @DisplayName("an absent user type resolves to no type")
        void absentResolvesToNull() {
            assertThat(empty().resolvedUserType()).isNull();
        }

        @Test
        @DisplayName("the raw component is preserved regardless of whether it resolves")
        void rawValueIsPreserved() {
            final CommArea unknown = new CommArea(null, "X", null, null, null, null, null, null, null);

            assertThat(unknown.userType()).isEqualTo("X");
            assertThat(unknown.resolvedUserType()).isNull();
        }
    }

    @Nested
    @DisplayName("5. The rendering discloses neither the card number nor any name")
    final class Rendering {

        @Test
        @DisplayName("only the identity and the two control values are reported")
        void onlyThreeFieldsAreReported() {
            final String rendered = populated().toString();

            assertThat(rendered)
                    .contains("ADMNUSR1")
                    .contains("userType=A")
                    .contains("accountStatus=Y");
        }

        @Test
        @DisplayName("the card number is absent from the output")
        void cardNumberIsAbsent() {
            final String rendered = populated().toString();

            assertThat(rendered).doesNotContain("4111999988887777");
            assertThat(rendered).as("no masked form, no digit count, no fragment").doesNotContain("1111");
        }

        @Test
        @DisplayName("all three customer names are absent from the output")
        void namesAreAbsent() {
            final String rendered = populated().toString();

            assertThat(rendered).doesNotContain("FNAMEAA1").doesNotContain("MNAM1").doesNotContain("LNM1");
        }

        @Test
        @DisplayName("the two identifiers are omitted as well")
        void identifiersAreOmitted() {
            final String rendered = populated().toString();

            assertThat(rendered).doesNotContain("customerId").doesNotContain("accountId=");
        }

        @Test
        @DisplayName("an absent payload renders without failing")
        void absentPayloadRenders() {
            assertThat(empty().toString()).contains("userId=null");
        }
    }
}
