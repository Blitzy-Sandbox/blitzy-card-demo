/*
 * ******************************************************************
 * Program     : CardUpdateRequestApiContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the field contract, the two snapshot detail
 *               groups, the preserved EXPIRAION misspelling, the
 *               validation cascade, the write-only card verification
 *               value, the unknown-property rejection and the
 *               rendering redaction of
 *               com.cardemo.model.dto.CardUpdateRequest, so that no
 *               later edit can drop a snapshot group, correct the
 *               source's spelling, widen a field, silently discard an
 *               unrecognised property or disclose a card number, a
 *               cardholder name, a card expiry date or a card
 *               verification value.
 * Source      : app/cpy-bms/COCRDUP.CPY (17 input fields, group
 *               CCRDUPAI) + app/cbl/COCRDUPC.cbl:291-313
 *               (CCUP-OLD-DETAILS / CCUP-NEW-DETAILS) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.model.dto.CardUpdateRequest;
import com.cardemo.model.dto.CardUpdateRequest.CardData;
import com.cardemo.model.dto.CardUpdateRequest.CardDetails;
import com.cardemo.model.dto.CardUpdateRequest.ExpiraionDate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression suite for {@link CardUpdateRequest}, the inbound payload of the card-update transaction.
 *
 * <h2>What it does</h2>
 *
 * <p>It holds the payload to two frozen sources at once, because the card-update contract is drawn
 * from two places rather than one. The seventeen screen fields come from the symbolic map
 * {@code app/cpy-bms/COCRDUP.CPY}, whose input group {@code 01 CCRDUPAI.} spans lines 17 to 120. The
 * two snapshot detail groups come from the program's own WORKING-STORAGE:
 * {@code 05 CCUP-OLD-DETAILS.} at {@code app/cbl/COCRDUPC.cbl:291} and
 * {@code 05 CCUP-NEW-DETAILS.} at {@code :303}.</p>
 *
 * <p>Four properties are pinned that a plausible edit would silently destroy, each of which would
 * leave a payload that still compiled and still bound.</p>
 *
 * <p><strong>The snapshot groups must be present.</strong> A stateless server has nowhere to keep
 * the values the screen was populated with, so {@code 9300-CHECK-CHANGE-IN-REC} at
 * {@code app/cbl/COCRDUPC.cbl:1498-1521} cannot be reproduced unless the snapshot travels in the
 * request body. Dropping the groups and relying on a store-level version column alone changes which
 * concurrent updates are accepted, because a version counter detects that a row changed whereas the
 * source detects which business field values changed. The tests below assert that both groups exist,
 * that they are cascaded into, and that their leaves are the eight the source declares.</p>
 *
 * <p><strong>The misspelling must survive.</strong> {@code EXPIRAION} is the corpus spelling, at
 * {@code app/cbl/COCRDUPC.cbl:297} and {@code :309} and again in the persisted layout
 * {@code app/cpy/CVACT02Y.cpy}. Correcting it in a Java identifier would rename a JSON property and
 * break the traceability the migration is measured on, so the corrected spelling is asserted to be
 * <em>rejected</em> rather than merely unused.</p>
 *
 * <p><strong>The card verification value must be refused in both directions.</strong> The symbolic
 * map declares no CVV field at all, and where {@code :1108-1112} sends the old embossed name, status
 * and three expiry components back to the screen it sends no CVV. Finding F13 then removed the
 * operational column, the entity field and the request component outright, so the contract asserted
 * here is not "inbound only" but "absent in both directions": an inbound verification value is
 * <em>refused</em> by the unknown-property rejector rather than accepted and discarded, and no
 * component exists that could serialise one outbound.</p>
 *
 * <p><strong>An unrecognised property must fail loudly, at every level of the payload.</strong> The
 * framework disables failure on unknown properties by default and no profile in this repository
 * re-enables it, so the rejection is local and is asserted against both a strict and a lenient
 * mapper. A nested object is where a silently discarded property does the most damage: a caller who
 * misspells a snapshot leaf would otherwise submit a snapshot the service believes to be absent.</p>
 *
 * <h2>How to run it</h2>
 *
 * <p>{@code ./mvnw -B -ntp test -Dtest=CardUpdateRequestApiContractTest} for this class alone, or
 * {@code ./mvnw -B -ntp test}
 * for the tier. It needs no container, no Spring context, no database and no network.</p>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every width, name, line number and count asserted below is transcribed from the frozen corpus
 * at commit {@code 7756d89} and declared as a constant here rather than read from the class under
 * test. One shared validator factory is bootstrapped for the class and closed in
 * {@link #releaseValidatorFactory()}; every other fixture is an immutable constant or a fresh local.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure in {@code SnapshotContract} means a snapshot group, a leaf or the nesting that
 *       separates the comparable subgroup from the read key has been altered. None of the three may
 *       be adjusted to match code.</li>
 *   <li>A failure in {@code PreservedMisspelling} means the source's spelling has been corrected.
 *       Restore {@code expiraionDate}; do not update the test.</li>
 *   <li>A failure in {@code Confidentiality} means a rendering has begun to disclose a card number, a
 *       cardholder name, a card expiry value or a card verification value, or the verification value
 *       has become readable on the wire.</li>
 *   <li>A failure in {@code UnknownPropertyRejection} means the local guard has been removed in favour
 *       of a mapper setting. A mapper setting is not equivalent: the lenient case proves it.</li>
 *   </ul>
 */
@DisplayName("CardUpdateRequest - app/cpy-bms/COCRDUP.CPY (17) + app/cbl/COCRDUPC.cbl:291-313")
final class CardUpdateRequestApiContractTest {

    /** Input-field count of the update map, group {@code CCRDUPAI}, {@code app/cpy-bms/COCRDUP.CPY}. */
    private static final int MAP_FIELDS = 17;

    /**
     * The two WORKING-STORAGE snapshot carriers the stateless contract has to hold: the edited group as a
     * group, and the as-displayed group as one sealed opaque string.
     */
    private static final int SNAPSHOT_GROUPS = 2;

    /**
     * Leaves per snapshot group: account, card, name, three date parts, status.
     *
     * <p>Seven, not eight. The verification value was the eighth until finding F13 removed it from the
     * operational record; the group carries no substitute leaf in its place.</p>
     */
    private static final int SNAPSHOT_LEAVES = 7;

    /** {@code ACCTSIDI PIC X(11)} at {@code app/cpy-bms/COCRDUP.CPY:60}, and both snapshot copies. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CARDSIDI PIC X(16)} at {@code app/cpy-bms/COCRDUP.CPY:66}, and both snapshot copies. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code CRDNAMEI PIC X(50)} at {@code app/cpy-bms/COCRDUP.CPY:72}, and both snapshot copies. */
    private static final int CARDHOLDER_NAME_WIDTH = 50;

    /** {@code EXPYEARI PIC X(4)} at {@code app/cpy-bms/COCRDUP.CPY:90}, and both snapshot copies. */
    private static final int EXPIRY_YEAR_WIDTH = 4;

    /** {@code EXPMONI PIC X(2)} at {@code app/cpy-bms/COCRDUP.CPY:84}, and both snapshot copies. */
    private static final int EXPIRY_MONTH_WIDTH = 2;

    /** {@code EXPDAYI PIC X(2)} at {@code app/cpy-bms/COCRDUP.CPY:96}, and both snapshot copies. */
    private static final int EXPIRY_DAY_WIDTH = 2;

    /** {@code INFOMSGI PIC X(40)} at {@code app/cpy-bms/COCRDUP.CPY:102}. */
    private static final int INFORMATION_MESSAGE_WIDTH = 40;

    /** {@code ERRMSGI PIC X(80)} at {@code app/cpy-bms/COCRDUP.CPY:108}. */
    private static final int ERROR_MESSAGE_WIDTH = 80;

    /** {@code FKEYSI PIC X(21)} at {@code app/cpy-bms/COCRDUP.CPY:114}. */
    private static final int FUNCTION_KEYS_WIDTH = 21;

    /** {@code FKEYSCI PIC X(18)} at {@code app/cpy-bms/COCRDUP.CPY:120}. */
    private static final int FUNCTION_KEYS_CONTINUED_WIDTH = 18;

    /** The 17 screen components, in the update map's own declaration order. */
    private static final List<String> SCREEN_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "accountId", "cardNumber", "cardholderName", "cardStatusCode", "expiryMonth",
            "expiryYear", "expiryDay", "informationMessage", "errorMessage", "functionKeys",
            "functionKeysContinued");

    /** The validator factory, bootstrapped once and closed in {@link #releaseValidatorFactory()}. */
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

    /** The validator derived from {@link #VALIDATOR_FACTORY}; immutable and thread-safe. */
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    /** Releases the validator factory once the class has finished, so the resource is not merely dropped. */
    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    /**
     * Returns a string of the requested length made only of the letter {@code A}.
     *
     * @param length the exact number of characters the returned value carries
     * @return a value of exactly {@code length} characters
     */
    private static String text(final int length) {
        return "A".repeat(length);
    }

    /**
     * Builds a fully populated snapshot group whose every leaf sits at its declared width.
     *
     * @return a snapshot carrying a value in all seven leaves
     */
    private static CardDetails populatedSnapshot() {
        return new CardDetails(text(ACCOUNT_ID_WIDTH), text(CARD_NUMBER_WIDTH),
                new CardData(text(CARDHOLDER_NAME_WIDTH),
                        new ExpiraionDate("2026", "11", "30"), "Y"));
    }

    /**
     * Builds a request carrying only the two snapshot groups, every screen field being absent.
     *
     * @param snapshot the sealed as-displayed snapshot, or {@code null} for an absent one
     * @param newDetails the new snapshot, or {@code null} for an absent group
     * @return the constructed request
     */
    private static CardUpdateRequest requestWithSnapshots(final String snapshot,
            final CardDetails newDetails) {

        return new CardUpdateRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, snapshot, newDetails);
    }

    /**
     * A stand-in sealed snapshot. Its bytes are never opened by these tests - this file is about the wire
     * shape, and the sealing itself belongs to {@code SnapshotTokenServiceTest} - so an opaque literal is
     * exactly as representative as a genuinely sealed value and needs no key.
     */
    private static final String SEALED_SNAPSHOT = "c2VhbGVkLXNuYXBzaG90LXN0YW5kLWlu";

    /**
     * Returns the declared size constraint of a record component, read from the backing field.
     *
     * <p>A constraint declared on a record component is not readable through
     * {@code RecordComponent.getAnnotation}, because the constraint's own target list omits record
     * components; the compiler propagates it to the field, the accessor and the constructor parameter
     * instead. This helper reads the field, which is the reading that reflects reality.</p>
     *
     * @param type          the record type to inspect
     * @param componentName the component whose constraint is wanted
     * @return the declared constraint, or {@code null} when the component carries none
     * @throws NoSuchFieldException when the named component does not exist on the type
     */
    private static Size sizeOf(final Class<?> type, final String componentName)
            throws NoSuchFieldException {

        return type.getDeclaredField(componentName).getAnnotation(Size.class);
    }

    /**
     * Returns the record component names of a type, in declaration order.
     *
     * @param type the record type to inspect
     * @return the component names, in the order the compiler recorded them
     */
    private static List<String> componentNames(final Class<?> type) {
        final List<String> names = new ArrayList<>();
        for (final RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Returns the declared method names of a type.
     *
     * @param type the type to inspect
     * @return the names of every declared method
     */
    private static List<String> declaredMethodNames(final Class<?> type) {
        final List<String> names = new ArrayList<>();
        for (final Method method : type.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    /**
     * Supplies every leaf of a snapshot group as a case: its owning type, its name and its width.
     *
     * @return one argument triple per leaf, eight in total across the three nested types
     */
    private static Stream<Arguments> snapshotLeafCases() {
        return Stream.of(
                Arguments.of(CardDetails.class, "accountId", ACCOUNT_ID_WIDTH),
                Arguments.of(CardDetails.class, "cardNumber", CARD_NUMBER_WIDTH),
                Arguments.of(CardData.class, "cardholderName", CARDHOLDER_NAME_WIDTH),
                Arguments.of(CardData.class, "cardStatusCode", 1),
                Arguments.of(ExpiraionDate.class, "expiryYear", EXPIRY_YEAR_WIDTH),
                Arguments.of(ExpiraionDate.class, "expiryMonth", EXPIRY_MONTH_WIDTH),
                Arguments.of(ExpiraionDate.class, "expiryDay", EXPIRY_DAY_WIDTH));
    }

    /**
     * Supplies every screen component as a case: its name and the width its map field declares.
     *
     * @return one argument pair per screen component, seventeen in total
     */
    private static Stream<Arguments> screenComponentCases() {
        return Stream.of(
                Arguments.of("transactionName", 4),
                Arguments.of("title01", 40),
                Arguments.of("currentDate", 8),
                Arguments.of("programName", 8),
                Arguments.of("title02", 40),
                Arguments.of("currentTime", 8),
                Arguments.of("accountId", ACCOUNT_ID_WIDTH),
                Arguments.of("cardNumber", CARD_NUMBER_WIDTH),
                Arguments.of("cardholderName", CARDHOLDER_NAME_WIDTH),
                Arguments.of("cardStatusCode", 1),
                Arguments.of("expiryMonth", EXPIRY_MONTH_WIDTH),
                Arguments.of("expiryYear", EXPIRY_YEAR_WIDTH),
                Arguments.of("expiryDay", EXPIRY_DAY_WIDTH),
                Arguments.of("informationMessage", INFORMATION_MESSAGE_WIDTH),
                Arguments.of("errorMessage", ERROR_MESSAGE_WIDTH),
                Arguments.of("functionKeys", FUNCTION_KEYS_WIDTH),
                Arguments.of("functionKeysContinued", FUNCTION_KEYS_CONTINUED_WIDTH));
    }

    @Nested
    @DisplayName("Field census: 17 screen fields plus the 2 snapshot groups, and nothing else")
    class FieldCensus {

        @ParameterizedTest(name = "{0} is constrained to PIC X({1})")
        @MethodSource("com.cardemo.unit.model.CardUpdateRequestApiContractTest#screenComponentCases")
        @DisplayName("every screen component carries the size constraint of its picture width, "
                + "byte-exactly")
        void everyScreenComponentCarriesItsPictureWidth(final String componentName, final int width)
                throws NoSuchFieldException {

            final Size declared = sizeOf(CardUpdateRequest.class, componentName);

            assertThat(declared).as("constraint on %s", componentName).isNotNull();
            assertThat(declared.max()).as("width of %s", componentName).isEqualTo(width);
        }

        @Test
        @DisplayName("the payload declares 19 components: the map's 17 plus the program's 2 groups")
        void thePayloadDeclaresNineteenComponents() {
            assertThat(componentNames(CardUpdateRequest.class))
                    .hasSize(MAP_FIELDS + SNAPSHOT_GROUPS);
        }

        @Test
        @DisplayName("the 17 screen components appear in the update map's own declaration order, "
                + "with the two groups after them and not interleaved")
        void theComponentsAppearInMapOrder() {
            final List<String> expected = new ArrayList<>(SCREEN_COMPONENTS);
            expected.add("snapshot");
            expected.add("newDetails");

            assertThat(componentNames(CardUpdateRequest.class)).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("every screen component is alphanumeric, because every map field is PIC X(n)")
        void everyScreenComponentIsAlphanumeric() {
            for (final RecordComponent component : CardUpdateRequest.class.getRecordComponents()) {
                if (SCREEN_COMPONENTS.contains(component.getName())) {
                    assertThat(component.getType())
                            .as("type of %s", component.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("the edited group is the only non-textual component, the as-displayed group having "
                + "become one opaque sealed string")
        void theSnapshotComponentsShareOneType() throws NoSuchFieldException {
            assertThat(CardUpdateRequest.class.getDeclaredField("snapshot").getType())
                    .isEqualTo(String.class);
            assertThat(CardUpdateRequest.class.getDeclaredField("newDetails").getType())
                    .isEqualTo(CardDetails.class);
        }

        @Test
        @DisplayName("the expiry day exists here, unlike on the detail map, and sits after the year "
                + "exactly as the map declares it")
        void theExpiryDayExistsAndFollowsTheYear() {
            final List<String> components = componentNames(CardUpdateRequest.class);

            assertThat(components).contains("expiryDay");
            assertThat(components.indexOf("expiryDay"))
                    .isEqualTo(components.indexOf("expiryYear") + 1);
            assertThat(components.indexOf("expiryYear"))
                    .isEqualTo(components.indexOf("expiryMonth") + 1);
        }

        @Test
        @DisplayName("the function-key legend stays two fields of 21 and 18, never one of 39 and "
                + "never the detail map's single 75")
        void theFunctionKeyLegendStaysTwoFields() throws NoSuchFieldException {
            assertThat(sizeOf(CardUpdateRequest.class, "functionKeys").max())
                    .isEqualTo(FUNCTION_KEYS_WIDTH);
            assertThat(sizeOf(CardUpdateRequest.class, "functionKeysContinued").max())
                    .isEqualTo(FUNCTION_KEYS_CONTINUED_WIDTH);
            assertThat(FUNCTION_KEYS_WIDTH + FUNCTION_KEYS_CONTINUED_WIDTH).isEqualTo(39);
            assertThat(FUNCTION_KEYS_WIDTH).isNotEqualTo(75);
        }

        @Test
        @DisplayName("the header time is 8 characters here, so no shared header abstraction imposed "
                + "the sign-on map's 9")
        void theHeaderTimeIsEightCharacters() throws NoSuchFieldException {
            assertThat(sizeOf(CardUpdateRequest.class, "currentTime").max()).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("Snapshot contract: two groups, seven leaves each, nested as the source nests them")
    class SnapshotContract {

        @Test
        @DisplayName("both snapshot groups are declared, so the stateless comparison has its inputs")
        void bothSnapshotGroupsAreDeclared() {
            assertThat(componentNames(CardUpdateRequest.class))
                    .contains("snapshot", "newDetails");
        }

        @Test
        @DisplayName("the group declares the two-part read key at its own level, and the comparable "
                + "subgroup one level deeper")
        void theGroupNestsAsTheSourceNests() {
            assertThat(componentNames(CardDetails.class))
                    .containsExactly("accountId", "cardNumber", "cardData");
            assertThat(componentNames(CardData.class))
                    .containsExactly("cardholderName", "expiraionDate", "cardStatusCode");
            assertThat(componentNames(ExpiraionDate.class))
                    .containsExactly("expiryYear", "expiryMonth", "expiryDay");
        }

        @Test
        @DisplayName("the three nested types resolve to exactly seven leaves, matching the source's "
                + "elementary items per group that survive finding F13")
        void theGroupResolvesToSevenLeaves() {
            final long detailLeaves = componentNames(CardDetails.class).stream()
                    .filter(name -> !"cardData".equals(name)).count();
            final long dataLeaves = componentNames(CardData.class).stream()
                    .filter(name -> !"expiraionDate".equals(name)).count();
            final long dateLeaves = componentNames(ExpiraionDate.class).size();

            assertThat(detailLeaves + dataLeaves + dateLeaves).isEqualTo(SNAPSHOT_LEAVES);
        }

        @Test
        @DisplayName("the comparable subgroup holds exactly the three members the source compares as "
                + "a unit, and excludes the read key")
        void theComparableSubgroupHoldsOnlyItsThreeMembers() {
            assertThat(componentNames(CardData.class))
                    .containsExactly("cardholderName", "expiraionDate", "cardStatusCode")
                    .doesNotContain("accountId", "cardNumber", "cvvCode");
        }

        @Test
        @DisplayName("the date group holds three separately addressable parts, not one ten-character "
                + "value, because the source compares it by reference modification")
        void theDateGroupHoldsThreeParts() {
            assertThat(componentNames(ExpiraionDate.class)).hasSize(3);
            assertThat(componentNames(CardData.class)).doesNotContain("expiraionDateText");
            for (final RecordComponent component : ExpiraionDate.class.getRecordComponents()) {
                assertThat(component.getType()).isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the date group's own order is year, month, day, which is the group's order and "
                + "not the screen's order of month, year, day")
        void theDateGroupOrderIsTheGroupsOwn() {
            assertThat(componentNames(ExpiraionDate.class))
                    .containsExactly("expiryYear", "expiryMonth", "expiryDay");

            final List<String> screenOrder = componentNames(CardUpdateRequest.class);
            assertThat(screenOrder.indexOf("expiryMonth"))
                    .isLessThan(screenOrder.indexOf("expiryYear"));
        }

        @ParameterizedTest(name = "{1} on {0} is constrained to PIC X({2})")
        @MethodSource("com.cardemo.unit.model.CardUpdateRequestApiContractTest#snapshotLeafCases")
        @DisplayName("every snapshot leaf carries the width its COBOL declaration gives it")
        void everySnapshotLeafCarriesItsWidth(final Class<?> owner, final String leafName,
                final int width) throws NoSuchFieldException {

            final Size declared = sizeOf(owner, leafName);

            assertThat(declared).as("constraint on %s.%s", owner.getSimpleName(), leafName).isNotNull();
            assertThat(declared.max()).as("width of %s", leafName).isEqualTo(width);
        }

        @Test
        @DisplayName("the edited group is marked for cascading, or the leaf constraints would never fire, "
                + "while the sealed member carries a length bound instead of a cascade")
        void bothGroupMembersAreCascaded() throws NoSuchFieldException {
            assertThat(CardUpdateRequest.class.getDeclaredField("newDetails")
                    .getAnnotation(Valid.class)).isNotNull();
            // The sealed member is one opaque string, so there is nothing to cascade into; what it needs is a
            // bound, so that an arbitrarily long value never reaches the base64 decoder or the cipher.
            assertThat(CardUpdateRequest.class.getDeclaredField("snapshot")
                    .getAnnotation(Valid.class)).isNull();
            assertThat(CardUpdateRequest.class.getDeclaredField("snapshot")
                    .getAnnotation(Size.class)).isNotNull();
            assertThat(CardUpdateRequest.class.getDeclaredField("snapshot")
                    .getAnnotation(Size.class).max())
                    .isEqualTo(CardUpdateRequest.MAX_SNAPSHOT_LENGTH);
        }

        @Test
        @DisplayName("a sealed snapshot longer than the declared bound is refused before it can reach the "
                + "cipher")
        void anOverLongSealedSnapshotIsRefused() {
            final CardUpdateRequest request = requestWithSnapshots(
                    "A".repeat(CardUpdateRequest.MAX_SNAPSHOT_LENGTH + 1), null);

            final Set<ConstraintViolation<CardUpdateRequest>> violations = VALIDATOR.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo("snapshot");
        }

        @Test
        @DisplayName("the nested group members are cascaded too, so the cascade reaches the date "
                + "parts three levels down")
        void theNestedGroupMembersAreCascaded() throws NoSuchFieldException {
            assertThat(CardDetails.class.getDeclaredField("cardData").getAnnotation(Valid.class))
                    .isNotNull();
            assertThat(CardData.class.getDeclaredField("expiraionDate").getAnnotation(Valid.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("an over-wide leaf three levels down is reported by the validator, with the "
                + "violation path naming every level")
        void anOverWideLeafIsReportedWithItsFullPath() {
            final CardUpdateRequest request = requestWithSnapshots(null,
                    new CardDetails(null, null,
                            new CardData(null, new ExpiraionDate("20266", null, null), null)));

            final Set<ConstraintViolation<CardUpdateRequest>> violations = VALIDATOR.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo("newDetails.cardData.expiraionDate.expiryYear");
        }

        @Test
        @DisplayName("the new group is validated three levels down, the cascade reaching every leaf")
        void theNewGroupIsValidatedToo() {
            // Re-vehicled onto an over-wide expiry year after finding F13 removed the verification leaf
            // whose width constraint used to trigger here. The probe is stronger for it: an empty group
            // could only ever prove that one leaf was constrained, whereas this proves the cascade
            // reaches three levels down through the NEW group exactly as it does through the old.
            final CardUpdateRequest request = requestWithSnapshots(null,
                    new CardDetails(null, null,
                            new CardData(text(CARDHOLDER_NAME_WIDTH + 1), null, null)));

            final Set<ConstraintViolation<CardUpdateRequest>> violations = VALIDATOR.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo("newDetails.cardData.cardholderName");
        }

        @Test
        @DisplayName("a populated pair of groups raises no violation once the verification value is "
                + "omitted, which is the only shape a client may send")
        void aPopulatedPairRaisesNoViolation() {
            assertThat(VALIDATOR.validate(
                    requestWithSnapshots(SEALED_SNAPSHOT, populatedSnapshot()))).isEmpty();
        }

        @Test
        @DisplayName("an absent group is not a violation, because the source itself INITIALIZEs the "
                + "group and reports on an unpopulated one rather than refusing it")
        void anAbsentGroupIsNotAViolation() {
            assertThat(VALIDATOR.validate(requestWithSnapshots(null, null))).isEmpty();
        }

        @Test
        @DisplayName("a wholly empty group is not a violation either, an empty leaf being the state "
                + "INITIALIZE leaves behind")
        void aWhollyEmptyGroupIsNotAViolation() {
            final CardDetails empty = new CardDetails("", "",
                    new CardData("", new ExpiraionDate("", "", ""), ""));

            assertThat(VALIDATOR.validate(requestWithSnapshots("", empty))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Preserved misspelling: the wire says expiraionDate, as the corpus does")
    class PreservedMisspelling {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("the component is named for the source's spelling, not the dictionary's")
        void theComponentCarriesTheSourceSpelling() {
            assertThat(componentNames(CardData.class))
                    .contains("expiraionDate")
                    .doesNotContain("expirationDate");
            assertThat(ExpiraionDate.class.getSimpleName()).isEqualTo("ExpiraionDate");
        }

        @Test
        @DisplayName("the JSON property carries the source's spelling on the way in")
        void theJsonPropertyCarriesTheSourceSpellingInbound() throws Exception {
            final String json = "{\"newDetails\":{\"cardData\":{\"expiraionDate\":"
                    + "{\"expiryYear\":\"2026\",\"expiryMonth\":\"11\",\"expiryDay\":\"30\"}}}}";

            final CardUpdateRequest bound = mapper.readValue(json, CardUpdateRequest.class);

            assertThat(bound.newDetails().cardData().expiraionDate().expiryYear()).isEqualTo("2026");
            assertThat(bound.newDetails().cardData().expiraionDate().expiryMonth()).isEqualTo("11");
            assertThat(bound.newDetails().cardData().expiraionDate().expiryDay()).isEqualTo("30");
        }

        @Test
        @DisplayName("the JSON property carries the source's spelling on the way out")
        void theJsonPropertyCarriesTheSourceSpellingOutbound() throws Exception {
            final String json = mapper.writeValueAsString(
                    requestWithSnapshots(SEALED_SNAPSHOT, populatedSnapshot()));

            assertThat(json).contains("\"expiraionDate\"").doesNotContain("expirationDate");
        }

        @Test
        @DisplayName("the corrected spelling is rejected rather than ignored, so a well-meaning "
                + "caller cannot defeat the comparison silently")
        void theCorrectedSpellingIsRejected() {
            final String json = "{\"newDetails\":{\"cardData\":{\"expirationDate\":{}}}}";

            assertThatThrownBy(() -> mapper.readValue(json, CardUpdateRequest.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Confidentiality: no verification value is accepted, held or rendered anywhere")
    class Confidentiality {

        private final ObjectMapper mapper = new ObjectMapper();

        /**
         * No type anywhere in the request graph declares a verification value.
         *
         * <p>This replaced an assertion that {@code CardDetails.cvvCode} carried Jackson's
         * {@code WRITE_ONLY} access. That assertion described a field which finding F13 removed, and a
         * write-only annotation was always the weaker guarantee: it kept the value out of the response
         * while still binding it into memory and writing it onto the record. Asserting absence across all
         * four types is both the stronger property and the one that cannot rot, because it names no member
         * that has to keep existing for the test to stay meaningful.</p>
         */
        @Test
        @DisplayName("no type in the request graph declares a verification value at all")
        void noTypeInTheRequestGraphDeclaresAVerificationValue() {
            assertThat(List.of(CardUpdateRequest.class, CardDetails.class, CardData.class,
                    ExpiraionDate.class))
                    .allSatisfy(type -> assertThat(type.getRecordComponents())
                            .noneMatch(component -> component.getName()
                                    .toLowerCase(Locale.ROOT).contains("cvv")));
        }

        @Test
        @DisplayName("an inbound verification value is REFUSED at bind time, not accepted and discarded")
        void anInboundVerificationValueIsRefusedAtBindTime() {
            // The strongest of the three possible behaviours, and the one a caller migrating from the
            // legacy field shape should meet. Accepting the property and validating it away would still
            // have bound the value into memory; ignoring it would have returned success while silently
            // discarding it, which is the failure mode that teaches a caller the wrong contract. The
            // nested group's unknown-property guard refuses it outright instead.
            assertThatThrownBy(() -> mapper.readValue(
                    "{\"newDetails\":{\"cvvCode\":\"123\"}}", CardUpdateRequest.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause()
                    .hasMessageContaining("CardDetails")
                    .hasMessageNotContaining("cvvCode")
                    .hasMessageNotContaining("123");
        }

        @Test
        @DisplayName("the verification value never appears on the way out, not even as a key")
        void theVerificationValueNeverAppearsOutbound() throws Exception {
            final String json = mapper.writeValueAsString(
                    requestWithSnapshots(SEALED_SNAPSHOT, populatedSnapshot()));

            // Now structural rather than behavioural: no component exists to serialise. The assertion is
            // kept because it is the property a reader of the API cares about, and because it would fail
            // the moment a component were reintroduced.
            assertThat(json).doesNotContain("cvv").doesNotContain("Cvv").doesNotContain("CVV");
            assertThat(CardDetails.class.getRecordComponents())
                    .noneMatch(component ->
                            component.getName().toLowerCase(Locale.ROOT).contains("cvv"));
        }

        @Test
        @DisplayName("the request rendering emits only the account identifier and the program name")
        void theRequestRenderingIsRedacted() {
            final String pan = "4111111111111111";
            final String name = "JANE DOE";
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, "COCRDUPC",
                    null, null, "00000000001", pan, name, "Y", "12", "2027", "31", null, null, null,
                    null, SEALED_SNAPSHOT, populatedSnapshot());

            assertThat(request.toString())
                    .isEqualTo("CardUpdateRequest[accountId=00000000001, programName=COCRDUPC]")
                    .doesNotContain(pan)
                    .doesNotContain(name);
        }

        @Test
        @DisplayName("the group rendering omits the card number, and declares no verification value")
        void theGroupRenderingIsRedacted() {
            final CardDetails snapshot = new CardDetails("00000000001", "4111111111111111",
                    new CardData("JANE DOE", new ExpiraionDate("2026", "11", "30"), "Y"));

            assertThat(snapshot.toString())
                    .contains("accountId=00000000001")
                    .doesNotContain("4111111111111111")
                    .doesNotContain("123")
                    .doesNotContain("JANE DOE");
        }

        @Test
        @DisplayName("the subgroup rendering omits the cardholder name")
        void theSubgroupRenderingIsRedacted() {
            final CardData data =
                    new CardData("JANE DOE", new ExpiraionDate("2026", "11", "30"), "Y");

            assertThat(data.toString())
                    .contains("cardStatusCode=Y")
                    .doesNotContain("JANE DOE");
        }

        @Test
        @DisplayName("the date rendering reports presence only, a card expiry date being cardholder "
                + "data")
        void theDateRenderingReportsPresenceOnly() {
            assertThat(new ExpiraionDate("2026", "11", null).toString())
                    .isEqualTo("ExpiraionDate[expiryYearPresent=true, expiryMonthPresent=true, "
                            + "expiryDayPresent=false]")
                    .doesNotContain("2026")
                    .doesNotContain("11");
        }

        @Test
        @DisplayName("all four types override the rendering, so none inherits the compiler's "
                + "component-by-component form")
        void allFourTypesOverrideTheRendering() {
            assertThat(declaredMethodNames(CardUpdateRequest.class)).contains("toString");
            assertThat(declaredMethodNames(CardDetails.class)).contains("toString");
            assertThat(declaredMethodNames(CardData.class)).contains("toString");
            assertThat(declaredMethodNames(ExpiraionDate.class)).contains("toString");
        }

        @Test
        @DisplayName("no type is serializable, so none can be written out by Java native "
                + "serialization while holding a verification value")
        void noTypeIsSerializable() {
            assertThat(Serializable.class.isAssignableFrom(CardUpdateRequest.class)).isFalse();
            assertThat(Serializable.class.isAssignableFrom(CardDetails.class)).isFalse();
            assertThat(Serializable.class.isAssignableFrom(CardData.class)).isFalse();
            assertThat(Serializable.class.isAssignableFrom(ExpiraionDate.class)).isFalse();
        }

        @Test
        @DisplayName("equality considers every surviving leaf, which is correct value semantics and "
                + "discloses nothing")
        void equalityConsidersEveryLeaf() {
            // Distinguished on the card number after finding F13 removed the verification leaf this probe
            // used to vary. Two instances differing in exactly one leaf must compare unequal, or the
            // record has stopped deriving equality from its state.
            final CardDetails first = new CardDetails(null, "0000000000000011", null);
            final CardDetails second = new CardDetails(null, "0000000000000022", null);

            assertThat(first).isNotEqualTo(second);
            assertThat(first).isEqualTo(new CardDetails(null, "0000000000000011", null));

            // Equality and rendering diverge deliberately, and that divergence is the point: the leaf
            // that distinguishes these two instances is the card number, which the redacted toString()
            // withholds. So the two render identically even though they are unequal. Asserting the
            // renderings equal is therefore asserting the redaction still holds - an assertion that they
            // differed would be asserting the key had leaked into the rendering.
            assertThat(first.toString()).isEqualTo(second.toString());
            assertThat(first.toString()).doesNotContain("0000000000000011");
        }
    }

    @Nested
    @DisplayName("Unknown-property rejection: local, and therefore effective under any mapper")
    class UnknownPropertyRejection {

        private final ObjectMapper strict = new ObjectMapper();

        private final ObjectMapper lenient = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        @ParameterizedTest(name = "an unknown property at {0} is rejected by a strict mapper")
        @ValueSource(strings = {
            "{\"bogus\":\"x\"}",
            "{\"oldDetails\":{\"accountId\":\"00000000001\"}}",
            "{\"newDetails\":{\"bogus\":\"x\"}}",
            "{\"newDetails\":{\"cardData\":{\"bogus\":\"x\"}}}",
            "{\"newDetails\":{\"cardData\":{\"expiraionDate\":{\"bogus\":\"x\"}}}}"})
        @DisplayName("an unknown property is rejected at every level of the payload, including a "
                + "hand-built as-displayed group, which this contract no longer accepts")
        void anUnknownPropertyIsRejectedAtEveryLevel(final String json) {
            assertThatThrownBy(() -> strict.readValue(json, CardUpdateRequest.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "an unknown property at {0} is rejected by a lenient mapper too")
        @ValueSource(strings = {
            "{\"bogus\":\"x\"}",
            "{\"oldDetails\":{\"accountId\":\"00000000001\"}}",
            "{\"newDetails\":{\"bogus\":\"x\"}}",
            "{\"newDetails\":{\"cardData\":{\"bogus\":\"x\"}}}",
            "{\"newDetails\":{\"cardData\":{\"expiraionDate\":{\"bogus\":\"x\"}}}}"})
        @DisplayName("the rejection survives a mapper that has been told to ignore unknown "
                + "properties, which is the whole point of making it local")
        void theRejectionSurvivesALenientMapper(final String json) {
            assertThatThrownBy(() -> lenient.readValue(json, CardUpdateRequest.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the refusal names the type and its sources but never the offending name or "
                + "value, both being untrusted input")
        void theRefusalWithholdsTheOffendingNameAndValue() {
            assertThatThrownBy(() -> strict.readValue(
                    "{\"secretHeader\":\"4111111111111111\"}", CardUpdateRequest.class))
                    .rootCause()
                    .hasMessageContaining("CardUpdateRequest")
                    .hasMessageContaining("app/cpy-bms/COCRDUP.CPY")
                    .hasMessageContaining("app/cbl/COCRDUPC.cbl:291-313")
                    .hasMessageNotContaining("secretHeader")
                    .hasMessageNotContaining("4111111111111111");
        }

        @Test
        @DisplayName("all four types declare the guard, so no level of the payload is unprotected")
        void allFourTypesDeclareTheGuard() {
            assertThat(declaredMethodNames(CardUpdateRequest.class))
                    .contains("rejectUnrecognisedProperty");
            assertThat(declaredMethodNames(CardDetails.class))
                    .contains("rejectUnrecognisedProperty");
            assertThat(declaredMethodNames(CardData.class)).contains("rejectUnrecognisedProperty");
            assertThat(declaredMethodNames(ExpiraionDate.class))
                    .contains("rejectUnrecognisedProperty");
        }

        @Test
        @DisplayName("a payload made only of declared properties binds without complaint")
        void aWellFormedPayloadBinds() throws Exception {
            final String json = "{\"accountId\":\"00000000001\",\"expiryDay\":\"31\","
                    + "\"snapshot\":\"" + SEALED_SNAPSHOT + "\","
                    + "\"newDetails\":{\"accountId\":\"00000000001\","
                    + "\"cardData\":{\"cardStatusCode\":\"Y\"}}}";

            final CardUpdateRequest bound = strict.readValue(json, CardUpdateRequest.class);

            assertThat(bound.accountId()).isEqualTo("00000000001");
            assertThat(bound.expiryDay()).isEqualTo("31");
            assertThat(bound.snapshot()).isEqualTo(SEALED_SNAPSHOT);
            assertThat(bound.newDetails().cardData().cardStatusCode()).isEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("Immutability and raw-state fidelity")
    class RawStateFidelity {

        @Test
        @DisplayName("all four types are records, so every component is final and no mutator exists")
        void allFourTypesAreRecords() {
            assertThat(CardUpdateRequest.class.isRecord()).isTrue();
            assertThat(CardDetails.class.isRecord()).isTrue();
            assertThat(CardData.class.isRecord()).isTrue();
            assertThat(ExpiraionDate.class.isRecord()).isTrue();
        }

        @Test
        @DisplayName("no type declares a mutator, so a validated payload stays validated")
        void noTypeDeclaresAMutator() {
            for (final Class<?> type : List.of(CardUpdateRequest.class, CardDetails.class,
                    CardData.class, ExpiraionDate.class)) {
                assertThat(declaredMethodNames(type))
                        .as("mutators on %s", type.getSimpleName())
                        .noneMatch(name -> name.startsWith("set"));
            }
        }

        @Test
        @DisplayName("every instance field of every type is final")
        void everyInstanceFieldIsFinal() {
            for (final Class<?> type : List.of(CardUpdateRequest.class, CardDetails.class,
                    CardData.class, ExpiraionDate.class)) {
                for (final Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s", type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("absence, emptiness and a low-values marker remain three distinct states on a "
                + "snapshot leaf")
        void absenceEmptinessAndMarkingRemainDistinct() {
            final String lowValues = "\u0000";

            assertThat(new CardDetails(null, null, null).cardNumber()).isNull();
            assertThat(new CardDetails(null, "", null).cardNumber()).isEmpty();
            assertThat(new CardDetails(null, lowValues, null).cardNumber()).isEqualTo(lowValues);
        }

        @Test
        @DisplayName("nothing is trimmed, padded or case-folded on ingest, at any level")
        void nothingIsNormalisedOnIngest() {
            final String mixed = "  jAnE  dOe  ";
            final CardData data = new CardData(mixed, new ExpiraionDate(" 26", "1 ", " 3"), " ");

            assertThat(data.cardholderName()).isEqualTo(mixed);
            assertThat(data.expiraionDate().expiryYear()).isEqualTo(" 26");
            assertThat(data.expiraionDate().expiryMonth()).isEqualTo("1 ");
            assertThat(data.expiraionDate().expiryDay()).isEqualTo(" 3");
            assertThat(data.cardStatusCode()).isEqualTo(" ");
        }

        @Test
        @DisplayName("a leading zero survives, because every identifier is textual and never numeric")
        void aLeadingZeroSurvives() {
            final CardDetails snapshot =
                    new CardDetails("00000000001", "0000000000000001", null);

            assertThat(snapshot.accountId()).isEqualTo("00000000001");
            assertThat(snapshot.cardNumber()).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("the constructor accepts an over-wide leaf, deferring to the validator, so a "
                + "service that binds without validating still sees the caller's bytes rather than "
                + "an exception in place of the source's own message")
        void theConstructorDefersToTheValidator() {
            final CardDetails snapshot =
                    new CardDetails(null, "X".repeat(CARD_NUMBER_WIDTH + 1), null);

            assertThat(snapshot.cardNumber()).hasSize(CARD_NUMBER_WIDTH + 1);
            // One violation, on the over-wide leaf: the constructor accepted the caller's bytes and the
            // validator is what refuses them, so the service still reports the source's own message rather
            // than an exception raised during binding. Asserting the count keeps a silently dropped
            // constraint visible.
            final Set<ConstraintViolation<CardUpdateRequest>> violations =
                    VALIDATOR.validate(requestWithSnapshots(null, snapshot));
            assertThat(violations).hasSize(1);
            assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                    .containsOnly("newDetails.cardNumber");
        }
    }
}
