/*
 * ******************************************************************
 * Program     : CardUpdateRequestTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the card-update payload against its frozen
 *               field contract: the 17 screen fields COCRDUP declares,
 *               the fields that differ from the card-detail map, the
 *               two WORKING-STORAGE snapshot groups a stateless server
 *               must carry, the three-state input model, and the
 *               toString overrides that withhold every cardholder
 *               value.
 * Source      : app/cpy-bms/COCRDUP.CPY (17 input fields, group CCRDUPAI)
 *               + app/cbl/COCRDUPC.cbl @ 7756d89
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
import com.cardemo.model.dto.CardUpdateRequest.CardData;
import com.cardemo.model.dto.CardUpdateRequest.CardDetails;
import com.cardemo.model.dto.CardUpdateRequest.ExpiraionDate;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link CardUpdateRequest} against the frozen corpus that defines it.
 *
 * <h2>What this test does</h2>
 *
 * <p>The card-update map and the card-detail map look alike and are not. A payload built from the
 * wrong one of the two would compile, populate and silently truncate, so the differences are
 * asserted here rather than assumed. Every width, count and literal below is <em>read from the
 * frozen tree at run time</em> rather than restated as a Java constant, so that a drift between the
 * corpus and the payload fails this suite instead of passing it. The evidence base is:</p>
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COCRDUP.CPY} - the field contract. Input group {@code 01 CCRDUPAI.} at
 *       line 17 spans lines 17 to 120 and declares <strong>exactly 17</strong> input fields; the
 *       output redefinition {@code 01 CCRDUPAO REDEFINES CCRDUPAI.} begins at line 121. Each field
 *       is generated as a quintuple - a {@code COMP PIC S9(4)} length field, an attribute byte, a
 *       redefined attribute alias, four reserved bytes, then the data field - preceded by a
 *       twelve-byte terminal I/O area {@code FILLER PIC X(12)} at line 18. Only the trailing data
 *       field, the one whose name ends in {@code I}, is a payload field. Load-bearing locators:
 *       {@code :54} ({@code CURTIMEI PIC X(8)}), {@code :60} ({@code ACCTSIDI PIC X(11)}),
 *       {@code :96} ({@code EXPDAYI PIC X(2)}).</li>
 *   <li>{@code app/cpy-bms/COCRDSL.CPY} - the detail projection, which holds <strong>zero</strong>
 *       {@code EXPDAY} tokens. Only {@code COACTUP.CPY} and {@code COCRDUP.CPY} mention that field
 *       anywhere in the corpus.</li>
 *   <li>{@code app/cpy-bms/COSGN00.CPY:54} - the sole map on which {@code CURTIMEI} is
 *       {@code PIC X(9)} rather than {@code PIC X(8)}, which is why no shared header type exists.</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} - the driving program, 1560 lines. Snapshot groups at
 *       {@code :291-301} and {@code :303-313}; character-first edit variables at {@code :103-112};
 *       the six three-state edit flags at {@code :53-80}; its own message block at {@code :156-214};
 *       the did-anything-change test at {@code :679-683}; the unconditional edit sequence at
 *       {@code :696-715}; snapshot capture at {@code :1345-1367}; the concurrency test at
 *       {@code :1498-1520}.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} - the reference implementation of the same pattern.
 *       {@code ACUP-OLD-DETAILS} at {@code :669} and {@code ACUP-NEW-DETAILS} at {@code :757}; the
 *       shared message block at {@code :505-528}; the <em>gated</em> cross-field edit at
 *       {@code :1665-1675}.</li>
 *   <li>{@code app/cpy/CVACT02Y.cpy:L5-L11} - the persisted layout, 150 bytes, key 16, whose expiry
 *       is a single {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code L9}.</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L202} - {@code KEYLEN 16 / AVGLRECL 150} - and {@code :L283}
 *       {@code AXRKP 16}, whose alternate index {@code :L285} marks {@code NONUNIQKEY}.</li>
 *   <li>{@code app/cpy/CSSETATY.cpy} - the procedural {@code COPY ... REPLACING} template that
 *       models OK, NOT-OK and BLANK as three states.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:L43-L44} - the session-state fields that must not appear here,
 *       both {@code PIC X(7)}.</li>
 *   <li>{@code app/data/ASCII/carddata.txt} - 7550 bytes, 50 rows, width 150, of which 8 rows carry
 *       a leading-zero card verification value.</li>
 * </ul>
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Ddependency-check.skip=true test} runs this class;
 * {@code ./mvnw -B -ntp clean verify} runs it inside the full gate.
 * <strong>Surefire 3.5.4 binds this tier</strong>: the plugin includes {@code **}{@code /*Test.java}
 * and {@code **}{@code /*Tests.java} and excludes {@code **}{@code /integration/**} and
 * {@code **}{@code /e2e/**}, so a class moved out of {@code src/test/java/com/cardemo/unit} is
 * collected by neither Surefire nor Failsafe and silently never runs - a green build, both plugins
 * reporting success, and JaCoCo recording the class uncovered, with no error and no warning. Do not
 * move or rename this file. Where no local toolchain is available the pinned image reproduces the
 * build exactly: {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.
 * This is a pure-JVM tier: no container, no Spring context, no database and no cloud endpoint is
 * started here, and none may be.</p>
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <p>There is no external configuration and no mutable static state. Time is never read from the
 * host: every temporal value comes from {@link FixedClockProvider}, whose
 * {@link FixedClockProvider#canonicalClock()} is fixed at
 * {@link FixedClockProvider#CANONICAL_INSTANT} and pinned to
 * {@link FixedClockProvider#CANONICAL_ZONE}, so no assertion here can drift with the wall clock,
 * the host time zone or the default locale. Fixture bytes come from {@link FixtureLoader} by
 * classpath resource name only - never copied, never edited, never written. Every case fold passes
 * {@link Locale#ROOT}. Mockito is not used: this type has no collaborator to stub, so there is no
 * strictness setting to configure and a mock would only assert that the test framework works.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The build fails on something trivial-looking.</em> Compilation runs
 *       {@code -Xlint:all -Werror} with {@code failOnWarning}, and that reaches <em>test</em>
 *       compilation, so a raw type or a deprecation is an error rather than a warning. An unused
 *       import is the exception: {@code javac} 25.0.3 publishes no lint key for one, so it stays a
 *       review matter and will not be caught here. Reproduce with {@code ./mvnw -q test-compile}.</li>
 *   <li><em>A fixture stream is null at run time.</em> The daily-transaction fixture is
 *       {@code dailytran.txt}, spelled in full. The mainframe DD name and dataset are
 *       {@code DALYTRAN}, so {@code dalytran.txt} is the natural guess and is wrong; it compiles
 *       cleanly and fails only when the resource is opened.</li>
 *   <li><em>A line-number assertion against {@code app/cbl/COACTUPC.cbl} fails by a wide margin.</em>
 *       That member is one of five CRLF files in the corpus and all 4236 of its lines carry a
 *       carriage return. Splitting on {@code \n} without discarding the {@code \r} shifts nothing,
 *       but reading it with a tool that treats {@code \r} as content makes every cited line number
 *       drift. {@link Files#readAllLines} terminates on {@code \r}, {@code \n} and {@code \r\n}
 *       alike, which is why it is used here; {@link #literalAt} additionally truncates to column 72
 *       so the columns 73 to 80 sequence numbers can never leak into a comparison.</li>
 *   <li><em>An expiry component is missing.</em> Reusing {@code CardDto} as the update payload loses
 *       {@code EXPDAYI} silently, because the detail map declares no counterpart. The two types are
 *       deliberately not unified.</li>
 *   <li><em>A card verification value or an account identifier loses a leading zero.</em> A numeric
 *       Java type was used where the corpus is character-first. {@code app/data/ASCII/carddata.txt}
 *       carries 8 leading-zero verification values in 50 rows and zero-pads every account
 *       identifier to 11 characters.</li>
 *   <li><em>A redaction assertion passes but proves nothing.</em> A probe string that the instance
 *       under test never carried passes vacuously. Every probe in
 *       {@link CardUpdateRequestTest.Redaction} is therefore derived from the instance itself, and
 *       {@link Redaction#everyProbeIsNonVacuous()} exists to prove that it is.</li>
 * </ul>
 *
 * <h2>What this class deliberately does not assert</h2>
 *
 * <p>Nothing here claims anything about the physical schema: {@code V1__create_schema.sql} and
 * {@code V2__create_indexes.sql} are outside this tier's evidence base, so no column, type or index is
 * asserted and none is invented. What <em>is</em> asserted is the copybook and catalogue geometry the payload
 * has to survive, and the two snapshot groups {@code CCUP-OLD-DETAILS} and {@code CCUP-NEW-DETAILS}
 * ({@code app/cbl/COCRDUPC.cbl:291} and {@code :303}) that a stateless server has to receive from the client
 * because it keeps nothing between the display and the submit.</p>
 *
 * @see CardUpdateRequest
 */
@DisplayName("CardUpdateRequest: 17 screen fields, two snapshot groups, three input states, total redaction")
final class CardUpdateRequestTest {

    /** The card-update symbolic map, read from the frozen tree. */
    private static final BmsSymbolicMap COCRDUP = BmsSymbolicMap.of("COCRDUP");

    /** The card-detail symbolic map, which this one deliberately differs from. */
    private static final BmsSymbolicMap COCRDSL = BmsSymbolicMap.of("COCRDSL");

    /** The sign-on map, the sole member on which {@code CURTIMEI} is nine characters wide. */
    private static final BmsSymbolicMap COSGN00 = BmsSymbolicMap.of("COSGN00");

    /** The persisted card record layout, 150 bytes with a 16-byte key. */
    private static final RecordLayoutCopybook CVACT02Y = RecordLayoutCopybook.of("CVACT02Y");

    /** The driving program, source of the snapshot groups and of every message literal. */
    private static final Path COCRDUPC = Path.of("app", "cbl", "COCRDUPC.cbl");

    /** The account-update program, the reference implementation of the same pattern. CRLF. */
    private static final Path COACTUPC = Path.of("app", "cbl", "COACTUPC.cbl");

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

    /**
     * The COMMAREA members that carry pseudo-conversational session state, from
     * {@code app/cpy/COCOM01Y.cpy}. None may appear as a component of this payload, because
     * navigation is URL-based and no server-side session state exists.
     */
    private static final List<String> SESSION_STATE_TOKENS = List.of(
            "fromtranid", "totranid", "fromprogram", "toprogram",
            "pgmcontext", "lastmap", "lastmapset");

    /**
     * Supplies the screen-field-to-component pairs as arguments, one case per pair.
     *
     * @return one pair per declared field, in copybook order.
     */
    private static List<String> fieldComponentPairs() {
        return RecordFieldContract.pairsOf(FIELD_TO_COMPONENT);
    }

    /**
     * Returns the single-quoted COBOL literal declared on one line of a frozen member.
     *
     * <p>The line is truncated to column 72 before anything else, so the columns 73 to 80 sequence
     * numbers that several members carry can never reach a comparison, and so that the carriage
     * return of a CRLF member is discarded with them. The trailing statement period is removed and
     * the surrounding quotes stripped, leaving the literal exactly as the program declares it -
     * including any misspelling, which is the point of reading it rather than restating it.</p>
     *
     * @param member     the frozen member to read
     * @param oneBasedLine the line number as the corpus numbers it, counting from one
     * @return the literal text with its quotes and trailing period removed
     * @throws UncheckedIOException if the member cannot be read, wrapping the cause rather than
     *                              discarding it, because an unreadable corpus is a setup fault
     *                              that must not be reported as a contract failure
     */
    private static String literalAt(final Path member, final int oneBasedLine) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(member, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(
                    "the frozen member " + member + " must be readable from the repository root", unreadable);
        }
        final String line = lines.get(oneBasedLine - 1);
        String text = line.substring(0, Math.min(72, line.length())).strip();
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            text = text.substring(1, text.length() - 1);
        }
        return text;
    }

    /**
     * Builds a payload whose every component carries a distinguishable, in-width value.
     *
     * <p>The two header time components are derived from {@link FixedClockProvider} rather than
     * written as literals, so that no value here can drift with the host clock, and the card number
     * is a documentation-safe test value rather than anything resembling a live account.</p>
     *
     * @return a fully populated payload, both snapshot groups absent
     */
    private static CardUpdateRequest populated() {
        final Clock clock = FixedClockProvider.canonicalClock();
        final String timestamp = FixedClockProvider.onlineTimestamp(clock);
        return new CardUpdateRequest("CCUP", "CardDemo Update Card", timestamp.substring(2, 10), "COCRDUPC",
                "Update Card", timestamp.substring(11, 19), "00000000001", "4111999988887777",
                "FNAMEAA1 LNM1", "Y", "12", "2027", "31", "Card updated", "", "F3=Exit F5=Save",
                "F12=Cancel", null, null);
    }

    /**
     * Builds a payload whose every component is absent, for the not-supplied cases.
     *
     * @return that payload.
     */
    private static CardUpdateRequest empty() {
        return new CardUpdateRequest(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a payload carrying only the named screen component, every other component absent.
     *
     * @param component the record component to populate
     * @param value     the value to place in it
     * @return a payload with exactly one populated screen component
     */
    private static CardUpdateRequest onlyScreenComponent(final String component, final String value) {
        final List<String> names = RecordFieldContract.componentNames(CardUpdateRequest.class);
        final List<String> values = new ArrayList<>();
        for (int index = 0; index < COCRDUP.inputFieldCount(); index++) {
            values.add(names.get(index).equals(component) ? value : null);
        }
        return new CardUpdateRequest(values.get(0), values.get(1), values.get(2), values.get(3),
                values.get(4), values.get(5), values.get(6), values.get(7), values.get(8), values.get(9),
                values.get(10), values.get(11), values.get(12), values.get(13), values.get(14),
                values.get(15), values.get(16), null, null);
    }

    /**
     * A stand-in sealed snapshot. These tests are about the wire shape, so an opaque literal is exactly as
     * representative as a genuinely sealed value and needs no key; the sealing itself is
     * {@code SnapshotTokenServiceTest}'s subject.
     */
    private static final String SEALED_SNAPSHOT = "c2VhbGVkLXNuYXBzaG90LXN0YW5kLWlu";

    /**
     * Builds a snapshot group from the leaf widths the driving program declares.
     *
     * @param name   the embossed name leaf
     * @param year   the expiry year leaf
     * @param month  the expiry month leaf
     * @param day    the expiry day leaf
     * @param status the active-status leaf
     * @return a snapshot group whose account identifier and card number are the fixture's own
     */
    private static CardDetails snapshot(final String name, final String year, final String month,
            final String day, final String status) {
        return new CardDetails("00000000050", "0500024453765740",
                new CardData(name, new ExpiraionDate(year, month, day), status));
    }

    /**
     * Validates one payload under the property path the controller would bind it at.
     *
     * @param request the payload to validate.
     * @return its violations, empty when the payload is valid.
     */
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
                    .containsExactly("snapshot", "newDetails");
        }

        @Test
        @DisplayName("the copybook itself declares exactly 17 input fields in group CCRDUPAI")
        void theCopybookDeclaresSeventeenFields() {
            // Asserted against the member rather than against a constant, so that a miscount in the
            // parser is a failure here rather than a silently agreed-upon wrong number.
            assertThat(COCRDUP.member()).isEqualTo("COCRDUP");
            assertThat(COCRDUP.inputFieldCount()).isEqualTo(17);
            assertThat(COCRDUP.inputGroupLine())
                    .as("01 CCRDUPAI. is declared at app/cpy-bms/COCRDUP.CPY:17")
                    .isEqualTo(17);
            assertThat(COCRDUP.outputRedefinitionLine())
                    .as("01 CCRDUPAO REDEFINES CCRDUPAI. begins at app/cpy-bms/COCRDUP.CPY:121")
                    .isEqualTo(121);
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

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.CardUpdateRequestTest#fieldComponentPairs")
        @DisplayName("every component is textual, so leading zeros and trailing padding survive")
        void everyComponentIsTextual(final String pair) throws NoSuchFieldException {
            final String component = pair.substring(pair.indexOf(':') + 1);

            // Character-first is the corpus's own choice, not a Java convenience: COCRDUPC.cbl:103-112
            // declares CARD-ACCT-ID-X PIC X(11), CARD-CVV-CD-X PIC X(03) and CARD-CARD-NUM-X PIC X(16)
            // and reaches their numeric values only through REDEFINES overlays.
            assertThat(CardUpdateRequest.class.getDeclaredField(component).getType())
                    .as("%s must be textual", component)
                    .isEqualTo(String.class);
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

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.CardUpdateRequestTest#fieldComponentPairs")
        @DisplayName("a value one character over the copybook width is rejected, on every field")
        void oneOverTheWidthIsRejected(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);
            final int width = COCRDUP.widthOf(field);

            // "A" is used rather than a digit because it is in-domain for every one of the 17 fields,
            // and it is inside the A-R range that a global overpunch decoder would corrupt - which is
            // exactly why no such decoder may be applied to text.
            final Set<ConstraintViolation<CardUpdateRequest>> violations =
                    violationsOf(onlyScreenComponent(component, "A".repeat(width + 1)));

            assertThat(violations)
                    .as("%s is PIC X(%d), so %d characters must not bind", field, width, width + 1)
                    .singleElement()
                    .satisfies(violation -> {
                        assertThat(violation.getPropertyPath()).hasToString(component);
                        assertThat(violation.getMessage()).contains("at most " + width);
                    });
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.CardUpdateRequestTest#fieldComponentPairs")
        @DisplayName("a value at exactly the copybook width binds, on every field")
        void exactlyTheWidthIsAccepted(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);
            final int width = COCRDUP.widthOf(field);

            assertThat(violationsOf(onlyScreenComponent(component, "A".repeat(width))))
                    .as("%s is PIC X(%d), so %d characters must bind", field, width, width)
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.CardUpdateRequestTest#fieldComponentPairs")
        @DisplayName("a value one character under the copybook width binds, on every field")
        void oneUnderTheWidthIsAccepted(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);
            final int width = COCRDUP.widthOf(field);

            // Width 1 fields degenerate to the empty string here, which is the correct one-under case
            // and is itself a state the source distinguishes - see the three-state group below.
            assertThat(violationsOf(onlyScreenComponent(component, "A".repeat(width - 1))))
                    .as("%s is PIC X(%d), so %d characters must bind", field, width, width - 1)
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.CardUpdateRequestTest#fieldComponentPairs")
        @DisplayName("trailing space padding is preserved verbatim, never trimmed on ingest")
        void trailingPaddingIsPreserved(final String pair) throws Exception {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);
            final int width = COCRDUP.widthOf(field);
            // A COBOL alphanumeric field is space-filled to its PIC width, so the padding is data.
            // app/data/ASCII/carddata.txt carries a 59-character trailing run for exactly this reason.
            final String padded = "A" + " ".repeat(width - 1);

            final CardUpdateRequest request = onlyScreenComponent(component, padded);
            final Object stored = CardUpdateRequest.class.getDeclaredMethod(component).invoke(request);

            assertThat(stored).isEqualTo(padded);
            assertThat((String) stored)
                    .as("%s must not be trimmed on ingest", component)
                    .hasSize(width);
            assertThat(violationsOf(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("2. The divergences from the card-detail map")
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
        @DisplayName("the detail map holds no EXPDAY token of any kind, not merely no input field")
        void theDetailMapHoldsNoExpiryDayTokenAtAll() throws IOException {
            // Stronger than asking the parser: the raw member is searched, so a differently spelled or
            // differently levelled declaration could not hide from this assertion.
            final String detail = Files.readString(
                    Path.of("app", "cpy-bms", "COCRDSL.CPY"), StandardCharsets.UTF_8);
            final String update = Files.readString(
                    Path.of("app", "cpy-bms", "COCRDUP.CPY"), StandardCharsets.UTF_8);

            assertThat(detail).doesNotContain("EXPDAY");
            assertThat(update).contains("EXPDAYI  PIC X(2).");
        }

        @Test
        @DisplayName("this payload is not the detail projection reused: the field sets differ")
        void thisIsNotTheDetailProjectionReused() {
            // CardDto derives from COCRDSL.CPY (15 fields) plus COCRDLI.CPY (45). Conflating the two
            // would drop the expiry day and the legend continuation and would resize the legend.
            assertThat(COCRDSL.inputFieldCount()).isEqualTo(15);
            assertThat(COCRDUP.inputFieldCount()).isEqualTo(17);
            assertThat(BmsSymbolicMap.of("COCRDLI").inputFieldCount()).isEqualTo(45);

            // The update map is a strict superset by NAME, which is precisely what makes the mistake
            // easy: every detail field is present here, so a reused type would bind cleanly and lose
            // only the two fields the detail map never had.
            assertThat(COCRDUP.fieldNames())
                    .as("every detail field also exists on the update map")
                    .containsAll(COCRDSL.fieldNames());
            final List<String> onlyOnUpdateMap = new ArrayList<>(COCRDUP.fieldNames());
            onlyOnUpdateMap.removeAll(COCRDSL.fieldNames());
            assertThat(onlyOnUpdateMap)
                    .as("and exactly these two are absent from the detail map")
                    .containsExactly("EXPDAYI", "FKEYSCI");

            // Superset by name is still not interchangeable, because one shared name is a different
            // width - so the divergence cannot be reduced to "two extra fields".
            assertThat(COCRDUP.widthOf("FKEYSI")).isNotEqualTo(COCRDSL.widthOf("FKEYSI"));
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
    @DisplayName("3. The six header fields are declared inline, because they are not uniform")
    final class HeaderFieldsAreDeclaredInline {

        @Test
        @DisplayName("CURTIMEI is eight characters here and nine on the sign-on map alone")
        void currentTimeWidthIsNotUniform() {
            assertThat(COCRDUP.widthOf("CURTIMEI")).isEqualTo(8);
            assertThat(COCRDSL.widthOf("CURTIMEI")).isEqualTo(8);
            assertThat(COSGN00.widthOf("CURTIMEI"))
                    .as("app/cpy-bms/COSGN00.CPY:54 is the only nine-character declaration")
                    .isEqualTo(9);
            assertThat(RecordFieldContract.declaredSizeMax(CardUpdateRequest.class, "currentTime"))
                    .as("this payload sizes to its own map, not to the widest map")
                    .isEqualTo(COCRDUP.widthOf("CURTIMEI"))
                    .isNotEqualTo(COSGN00.widthOf("CURTIMEI"));
        }

        @Test
        @DisplayName("the other five header fields are uniform, which is why only CURTIMEI proves the point")
        void theOtherFiveHeaderFieldsAreUniform() {
            // Recorded so the conclusion is not overstated: five of the six genuinely do agree. It takes
            // only the sixth to make a shared header type factually wrong for at least one map.
            assertThat(COCRDUP.widthOf("TRNNAMEI")).isEqualTo(COSGN00.widthOf("TRNNAMEI")).isEqualTo(4);
            assertThat(COCRDUP.widthOf("TITLE01I")).isEqualTo(COSGN00.widthOf("TITLE01I")).isEqualTo(40);
            assertThat(COCRDUP.widthOf("CURDATEI")).isEqualTo(COSGN00.widthOf("CURDATEI")).isEqualTo(8);
            assertThat(COCRDUP.widthOf("PGMNAMEI")).isEqualTo(COSGN00.widthOf("PGMNAMEI")).isEqualTo(8);
            assertThat(COCRDUP.widthOf("TITLE02I")).isEqualTo(COSGN00.widthOf("TITLE02I")).isEqualTo(40);
        }

        @Test
        @DisplayName("no shared header supertype exists to inherit a wrong width from")
        void noSharedHeaderSupertypeExists() {
            assertThat(CardUpdateRequest.class.isRecord()).isTrue();
            assertThat(CardUpdateRequest.class.getSuperclass())
                    .as("a record's only supertype is java.lang.Record")
                    .isEqualTo(Record.class);
            assertThat(CardUpdateRequest.class.getInterfaces())
                    .as("no header interface or mixin, and therefore no native serialization either")
                    .isEmpty();
        }

        @Test
        @DisplayName("the six header components are declared on this type itself")
        void theSixHeaderComponentsAreDeclaredHere() {
            assertThat(RecordFieldContract.componentNames(CardUpdateRequest.class))
                    .startsWith("transactionName", "title01", "currentDate", "programName", "title02",
                            "currentTime");
        }
    }

    @Nested
    @DisplayName("4. The two snapshot groups a stateless server has to carry")
    final class SnapshotGroups {

        @Test
        @DisplayName("both groups are declared, mirroring CCUP-OLD-DETAILS and CCUP-NEW-DETAILS")
        void bothGroupsArePresent() throws IOException {
            // Read from the program rather than asserted from memory: the payload carries these groups
            // only because the source declares them, so the source is the assertion's subject.
            final String program = Files.readString(COCRDUPC, StandardCharsets.UTF_8);
            assertThat(program).contains("05 CCUP-OLD-DETAILS.", "05 CCUP-NEW-DETAILS.");

            assertThat(RecordFieldContract.componentNames(CardUpdateRequest.class))
                    .endsWith("snapshot", "newDetails");
        }

        @Test
        @DisplayName("one Java type serves both groups, and the as-displayed one travels sealed")
        void oneTypeServesBothGroups() throws NoSuchFieldException {
            // The two COBOL declarations are identical, so one type serves both roles. Only the edited group
            // is carried as a group on the wire: the as-displayed group is sealed by
            // com.cardemo.security.SnapshotTokenService and travels as one opaque string, because a group the
            // caller can rewrite makes 9300-CHECK-CHANGE-IN-REC unconditionally true. The type is still the
            // one the service seals and opens, which is why it keeps serving both roles server-side.
            assertThat(CardUpdateRequest.class.getDeclaredField("newDetails").getType())
                    .isEqualTo(CardDetails.class);
            assertThat(CardUpdateRequest.class.getDeclaredField("snapshot").getType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the group's leaves and nesting reproduce the source hierarchy level for level")
        void theGroupHierarchyMatchesTheSource() {
            // 10 CCUP-xxx-ACCTID / -CARDID sit directly under the group; the embossed name, the expiry
            // components and the status sit one level deeper inside 10 CCUP-xxx-CARDDATA. That nesting is
            // load-bearing: :679-683 compares the CARDDATA group as a whole, so what is inside it
            // participates in the did-anything-change test and what is outside it does not.
            assertThat(RecordFieldContract.componentNames(CardDetails.class))
                    .containsExactly("accountId", "cardNumber", "cardData");
            assertThat(RecordFieldContract.componentNames(CardData.class))
                    .containsExactly("cardholderName", "expiraionDate", "cardStatusCode");
            assertThat(RecordFieldContract.componentNames(ExpiraionDate.class))
                    .as("app/cbl/COCRDUPC.cbl:298-300 orders the components year, month, day")
                    .containsExactly("expiryYear", "expiryMonth", "expiryDay");
        }

        @Test
        @DisplayName("the expiry components are ordered as the group declares, not as the screen declares")
        void groupOrderDiffersFromScreenOrder() {
            // The screen declares month at :84, year at :90, day at :96; the snapshot group declares
            // year at :298, month at :299, day at :300. Two different orders for the same three values.
            assertThat(RecordFieldContract.componentNames(ExpiraionDate.class))
                    .containsExactly("expiryYear", "expiryMonth", "expiryDay");
            assertThat(COCRDUP.fieldNames().indexOf("EXPMONI"))
                    .as("the map declares the month before the year")
                    .isLessThan(COCRDUP.fieldNames().indexOf("EXPYEARI"));
            assertThat(COCRDUP.fieldNames().indexOf("EXPYEARI"))
                    .isLessThan(COCRDUP.fieldNames().indexOf("EXPDAYI"));
        }

        @Test
        @DisplayName("each leaf is bounded at the width the program declares for it")
        void everyLeafWidthComesFromTheProgram() {
            assertThat(RecordFieldContract.declaredSizeMax(CardDetails.class, "accountId")).isEqualTo(11);
            assertThat(RecordFieldContract.declaredSizeMax(CardDetails.class, "cardNumber")).isEqualTo(16);
            assertThat(RecordFieldContract.declaredSizeMax(CardData.class, "cardholderName")).isEqualTo(50);
            assertThat(RecordFieldContract.declaredSizeMax(CardData.class, "cardStatusCode")).isEqualTo(1);
            assertThat(RecordFieldContract.declaredSizeMax(ExpiraionDate.class, "expiryYear")).isEqualTo(4);
            assertThat(RecordFieldContract.declaredSizeMax(ExpiraionDate.class, "expiryMonth")).isEqualTo(2);
            assertThat(RecordFieldContract.declaredSizeMax(ExpiraionDate.class, "expiryDay")).isEqualTo(2);
        }

        @Test
        @DisplayName("the expiry leaves match the screen widths they are populated from")
        void theExpiryLeavesMatchTheScreenWidths() {
            assertThat(RecordFieldContract.declaredSizeMax(ExpiraionDate.class, "expiryYear"))
                    .isEqualTo(COCRDUP.widthOf("EXPYEARI"));
            assertThat(RecordFieldContract.declaredSizeMax(ExpiraionDate.class, "expiryMonth"))
                    .isEqualTo(COCRDUP.widthOf("EXPMONI"));
            assertThat(RecordFieldContract.declaredSizeMax(ExpiraionDate.class, "expiryDay"))
                    .isEqualTo(COCRDUP.widthOf("EXPDAYI"));
        }

        @Test
        @DisplayName("the cascade reaches three levels deep, so a leaf constraint is not inert decoration")
        void theValidCascadeReachesTheDeepestLeaf() {
            final CardUpdateRequest overWide = new CardUpdateRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, snapshot("N".repeat(51), "YYYYY", "MM", "DD", "Y"));

            assertThat(violationsOf(overWide))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .as("a cascade omitted is a cascade that never fires")
                    .contains("newDetails.cardData.cardholderName",
                            "newDetails.cardData.expiraionDate.expiryYear");
        }

        @Test
        @DisplayName("an in-width snapshot on either member raises no violation")
        void anInWidthSnapshotIsValid() {
            final CardDetails valid = snapshot("ANIYA VON", "2023", "03", "09", "Y");

            assertThat(violationsOf(new CardUpdateRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, SEALED_SNAPSHOT, valid)))
                    .isEmpty();
        }

        @Test
        @DisplayName("neither snapshot member is mandatory, because the source INITIALIZEs the group")
        void neitherSnapshotIsMandatory() {
            // app/cbl/COCRDUPC.cbl:1345 and :586 both begin by executing INITIALIZE on the group, so a
            // wholly unpopulated group is a state the source produces rather than a request it refuses.
            assertThat(RecordFieldContract.declares(CardUpdateRequest.class, "snapshot", NotNull.class))
                    .isFalse();
            assertThat(RecordFieldContract.declares(CardUpdateRequest.class, "newDetails", NotNull.class))
                    .isFalse();
            assertThat(violationsOf(empty())).isEmpty();
        }

        @Test
        @DisplayName("the source misspelling EXPIRAION is preserved in the component name")
        void theSourceMisspellingIsPreserved() throws IOException {
            // Present in the program at :297 and :309, in its update record at :319, and in the persisted
            // layout at app/cpy/CVACT02Y.cpy:L9. A caller who "corrects" it sends a group that binds to
            // nothing, so the misspelling is contract and must never be tidied.
            assertThat(Files.readString(COCRDUPC, StandardCharsets.UTF_8))
                    .contains("CCUP-OLD-EXPIRAION-DATE", "CCUP-NEW-EXPIRAION-DATE");
            assertThat(CVACT02Y.declares("CARD-EXPIRAION-DATE")).isTrue();
            assertThat(CVACT02Y.declares("CARD-EXPIRATION-DATE"))
                    .as("the correctly spelled name appears nowhere")
                    .isFalse();
            assertThat(RecordFieldContract.componentNames(CardData.class)).contains("expiraionDate");
            assertThat(ExpiraionDate.class.getSimpleName()).isEqualTo("ExpiraionDate");
        }
    }

    @Nested
    @DisplayName("5. Why a store-level version column cannot replace the snapshot")
    final class ConcurrencyContract {

        @Test
        @DisplayName("the payload carries the values the concurrency test compares, not a version counter")
        void thePayloadCarriesValuesRatherThanAVersion() {
            // A version column detects THAT a row changed. 9300-CHECK-CHANGE-IN-REC at
            // app/cbl/COCRDUPC.cbl:1503-1508 detects WHICH field values differ from what the user was
            // shown, so a concurrent write that restored a field to its original value passes the source's
            // test and fails a version test. The two guarantees are different and neither replaces the
            // other; this payload's job is to carry the values the first one needs.
            assertThat(RecordFieldContract.componentNames(CardUpdateRequest.class))
                    .contains("snapshot", "newDetails");
            assertThat(RecordFieldContract.componentNames(CardUpdateRequest.class))
                    .as("no version counter is carried on the wire: the store owns that layer")
                    .noneSatisfy(component -> assertThat(component.toLowerCase(Locale.ROOT))
                            .containsAnyOf("version", "etag", "revision", "timestampversion"));
        }

        @Test
        @DisplayName("every field the concurrency test compares is reachable from a snapshot group")
        void everyComparedFieldIsCarried() {
            // The six comparisons at :1503-1508 are the CVV, the embossed name, the three EXPIRAION
            // components and the active status. Five of the six are carried; the sixth is the CVV, which
            // is deliberately not declared anywhere - see the security group.
            assertThat(RecordFieldContract.componentNames(CardData.class))
                    .contains("cardholderName", "cardStatusCode", "expiraionDate");
            assertThat(RecordFieldContract.componentNames(ExpiraionDate.class))
                    .containsExactly("expiryYear", "expiryMonth", "expiryDay");
        }

        @Test
        @DisplayName("the read keys sit outside the comparable subgroup, exactly as in the source")
        void theReadKeysSitOutsideTheComparableSubgroup() {
            // :292-293 place the account identifier and the card number directly under the group, while
            // :295-301 nest the comparable values inside CARDDATA. The did-anything-change test at
            // :679-683 compares CARDDATA only, so the keys must not be inside it.
            assertThat(RecordFieldContract.componentNames(CardDetails.class))
                    .contains("accountId", "cardNumber");
            assertThat(RecordFieldContract.componentNames(CardData.class))
                    .as("the keys are not part of the comparable subgroup")
                    .doesNotContain("accountId", "cardNumber");
        }

        @Test
        @DisplayName("the source compares the expiry component-wise, so the components stay separate")
        void theExpiryStaysDecomposed() throws IOException {
            // The live field is one X(10) in dash-separated form, so :1505-1507 address its parts by
            // reference modification at offsets 1, 6 and 9 while the snapshot holds three discrete fields.
            // Comparing the two as whole strings would report a change on every single request.
            final String program = Files.readString(COCRDUPC, StandardCharsets.UTF_8);
            assertThat(program).contains(
                    "CARD-EXPIRAION-DATE(1:4)", "CARD-EXPIRAION-DATE(6:2)", "CARD-EXPIRAION-DATE(9:2)");

            assertThat(CVACT02Y.widthOf("CARD-EXPIRAION-DATE"))
                    .as("the persisted field is a single ten-character value")
                    .isEqualTo(10);
            assertThat(RecordFieldContract.declaredSizeMax(ExpiraionDate.class, "expiryYear")
                    + RecordFieldContract.declaredSizeMax(ExpiraionDate.class, "expiryMonth")
                    + RecordFieldContract.declaredSizeMax(ExpiraionDate.class, "expiryDay"))
                    .as("the three components sum to 8; the missing 2 are the dash separators")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("no component is a calendar or date-time type, in the payload or any nested group")
        void noComponentIsATemporalType() {
            // A calendar type would normalise away the separator geometry the comparison depends on, and
            // would reject the blank and marked states the source distinguishes.
            assertEveryLeafIsTextual(CardUpdateRequest.class);
            assertEveryLeafIsTextual(CardDetails.class);
            assertEveryLeafIsTextual(CardData.class);
            assertEveryLeafIsTextual(ExpiraionDate.class);
        }

        @Test
        @DisplayName("the payload performs no comparison itself: it declares no comparison method")
        void thePayloadDoesNotCompare() {
            // Separation of concerns: the payload transports, the service compares. The only methods this
            // type declares beyond the record's own accessors are the redacted rendering and the
            // unknown-property guard.
            assertThat(ReflectionCensus.declaredMethodNames(CardUpdateRequest.class))
                    .doesNotContain("hasChanged", "isChanged", "compare", "compareTo", "detectChanges",
                            "changedFields", "normalise", "normalize", "trim", "pad");
        }
    }

    @Nested
    @DisplayName("6. The observable message literals, read from the frozen corpus")
    final class LiteralMessageContract {

        @Test
        @DisplayName("the two expiry literals are byte-for-byte what the driving program declares")
        void theExpiryLiteralsAreExact() {
            // The parity comparison is byte-for-byte on message text, so a paraphrase is a defect. Both
            // are declared twice in the corpus, once by each update program, and the two agree.
            assertThat(literalAt(COCRDUPC, 198))
                    .isEqualTo("Card expiry month must be between 1 and 12")
                    .isEqualTo(literalAt(COACTUPC, 510));
            assertThat(literalAt(COCRDUPC, 200))
                    .isEqualTo("Invalid card expiry year")
                    .isEqualTo(literalAt(COACTUPC, 512));
        }

        @Test
        @DisplayName("the concurrent-change literal spells \"some one\" as two words, and stays that way")
        void theConcurrentChangeLiteralKeepsItsMisspelling() {
            final String literal = literalAt(COCRDUPC, 208);

            assertThat(literal)
                    .isEqualTo("Record changed by some one else. Please review")
                    .isEqualTo(literalAt(COACTUPC, 522));
            assertThat(literal)
                    .as("two words, not one: never corrected")
                    .contains("some one")
                    .doesNotContain("someone");
        }

        @Test
        @DisplayName("the coding-to-be-done literal keeps all four of its dots")
        void theCodingToBeDoneLiteralKeepsFourDots() {
            final String literal = literalAt(COCRDUPC, 214);

            assertThat(literal)
                    .isEqualTo("Looks Good.... so far")
                    .isEqualTo(literalAt(COACTUPC, 528));
            assertThat(literal.substring(literal.indexOf('.'), literal.indexOf(' ', literal.indexOf('.'))))
                    .as("four dots, not three and not one")
                    .isEqualTo("....");
        }

        @Test
        @DisplayName("the remaining card-update literals are exact too")
        void theRemainingLiteralsAreExact() {
            assertThat(literalAt(COCRDUPC, 182)).isEqualTo("Card name not provided");
            assertThat(literalAt(COCRDUPC, 184))
                    .isEqualTo("Card name can only contain alphabets and spaces");
            assertThat(literalAt(COCRDUPC, 188))
                    .isEqualTo("No change detected with respect to values fetched.");
            assertThat(literalAt(COCRDUPC, 196)).isEqualTo("Card Active Status must be Y or N");
            assertThat(literalAt(COCRDUPC, 202)).isEqualTo("Did not find this account in cards database");
            assertThat(literalAt(COCRDUPC, 204)).isEqualTo("Did not find cards for this search condition");
            assertThat(literalAt(COCRDUPC, 210)).isEqualTo("Update of record failed");
            assertThat(literalAt(COCRDUPC, 212)).isEqualTo("Error reading Card Data File");
        }

        @Test
        @DisplayName("the card program declares one lock message where the account program declares two")
        void theLockMessageCountDiffersBetweenPrograms() {
            // Recorded so that the two message blocks are not assumed interchangeable: this program writes
            // one dataset and needs one message; the account program writes two and needs two.
            assertThat(literalAt(COCRDUPC, 206)).isEqualTo("Could not lock record for update");
            assertThat(literalAt(COACTUPC, 518)).isEqualTo("Could not lock account record for update");
            assertThat(literalAt(COACTUPC, 520)).isEqualTo("Could not lock customer record for update");
        }

        @Test
        @DisplayName("blank and invalid carry two distinct literals, which is the three-state model's proof")
        void blankAndInvalidCarryDistinctLiterals() {
            // The account program states it for the credit limit at :506 and :508; this program states it
            // for the card name at :182 and :184. Two states, two messages - so collapsing null and blank
            // into one notion would make one of the two messages unreachable.
            assertThat(literalAt(COACTUPC, 506)).isEqualTo("Credit Limit must be supplied");
            assertThat(literalAt(COACTUPC, 508)).isEqualTo("Credit Limit is not valid");
            assertThat(literalAt(COACTUPC, 506)).isNotEqualTo(literalAt(COACTUPC, 508));
            assertThat(literalAt(COCRDUPC, 182)).isNotEqualTo(literalAt(COCRDUPC, 184));
        }

        @Test
        @DisplayName("no message literal is restated in this payload's own constraint messages")
        void thePayloadDoesNotRestateLegacyMessages() {
            // The legacy literals belong to the service that emits them. A width violation here is a
            // binding failure and must not impersonate one of them.
            for (final String component : RecordFieldContract.componentNames(CardUpdateRequest.class)
                    .subList(0, COCRDUP.inputFieldCount())) {
                final String message = RecordFieldContract
                        .annotationOn(CardUpdateRequest.class, component, Size.class)
                        .message();
                assertThat(message)
                        .as("%s carries a binding message, not a legacy screen message", component)
                        .doesNotContain("Card expiry month must be between 1 and 12")
                        .doesNotContain("Invalid card expiry year")
                        .doesNotContain("some one")
                        .doesNotContain("Looks Good");
            }
        }
    }

    /**
     * Asserts that every component of a record is either textual or another record from this payload's
     * own nested hierarchy, so that no calendar, date-time or approximate binary numeric type can
     * enter the payload unnoticed.
     *
     * @param recordType the record type to inspect
     */
    private static void assertEveryLeafIsTextual(final Class<?> recordType) {
        final List<Class<?>> permittedGroups =
                List.of(CardDetails.class, CardData.class, ExpiraionDate.class);
        for (final String component : RecordFieldContract.componentNames(recordType)) {
            final Class<?> type;
            try {
                type = recordType.getDeclaredField(component).getType();
            } catch (final NoSuchFieldException absent) {
                throw new AssertionError(
                        "a record component always has a backing field: " + component, absent);
            }
            assertThat(type == String.class || permittedGroups.contains(type))
                    .as("%s.%s is %s; only text and the declared groups are permitted",
                            recordType.getSimpleName(), component, type.getName())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("7. Absent, blank and marked are three distinct states, never one \"empty\" notion")
    final class ThreeStateInputModel {

        @Test
        @DisplayName("the driving program declares six flags with three states each, plus a fourth sentinel")
        void theSourceModelsThreeStatesPerField() throws IOException {
            // Read from the program, because this is the fact the whole group rests on. app/cpy/CSSETATY.cpy
            // is the procedural COPY ... REPLACING template that renders them; being procedural it gets no
            // class of its own, and it fires its markers only on CDEMO-PGM-REENTER.
            final String program = Files.readString(COCRDUPC, StandardCharsets.UTF_8);

            assertThat(program).contains(
                    "FLG-CARDNAME-NOT-OK", "FLG-CARDNAME-ISVALID", "FLG-CARDNAME-BLANK",
                    "FLG-CARDSTATUS-NOT-OK", "FLG-CARDSTATUS-ISVALID", "FLG-CARDSTATUS-BLANK",
                    "FLG-CARDEXPMON-NOT-OK", "FLG-CARDEXPMON-ISVALID", "FLG-CARDEXPMON-BLANK",
                    "FLG-CARDEXPYEAR-NOT-OK", "FLG-CARDEXPYEAR-ISVALID", "FLG-CARDEXPYEAR-BLANK");
            assertThat(program)
                    .as("a fourth sentinel: LOW-VALUES is distinct from SPACES and from ZEROS")
                    .contains("INPUT-PENDING", "LOW-VALUES");

            final String template = Files.readString(
                    Path.of("app", "cpy", "CSSETATY.cpy"), StandardCharsets.UTF_8);
            assertThat(template).contains("(TESTVAR1)", "(SCRNVAR2)", "(MAPNAME3)", "CDEMO-PGM-REENTER");
        }

        @Test
        @DisplayName("the edit paragraphs test low-values, spaces and zeros as three separate sentinels")
        void theSourceTestsThreeSentinelsInOnePredicate() throws IOException {
            final String program = Files.readString(COCRDUPC, StandardCharsets.UTF_8);

            // 1230-EDIT-NAME at :811-813 and 1250-EDIT-EXPIRY-MON at :883-885 both spell it out.
            assertThat(program).contains("CCUP-NEW-CRDNAME   EQUAL LOW-VALUES");
            assertThat(program).contains("CCUP-NEW-EXPMON   EQUAL LOW-VALUES");
            assertThat(program).contains("EQUAL SPACES");
            assertThat(program).contains("EQUAL ZEROS");
        }

        @Test
        @DisplayName("absent, empty, all-space and marked remain four distinguishable values")
        void thePayloadKeepsTheStatesDistinct() {
            final CardUpdateRequest absent = onlyScreenComponent("expiryMonth", null);
            final CardUpdateRequest emptyValue = onlyScreenComponent("expiryMonth", "");
            final CardUpdateRequest spaces = onlyScreenComponent("expiryMonth", "  ");
            final CardUpdateRequest marked = onlyScreenComponent("expiryMonth", "*");

            assertThat(absent.expiryMonth()).isNull();
            assertThat(emptyValue.expiryMonth()).isEmpty();
            assertThat(spaces.expiryMonth()).isEqualTo("  ");
            assertThat(marked.expiryMonth()).isEqualTo("*");

            assertThat(List.of(emptyValue, spaces, marked))
                    .as("no two of the populated states collapse into each other")
                    .doesNotHaveDuplicates()
                    .doesNotContain(absent);
        }

        @Test
        @DisplayName("null is never coerced to empty and empty is never coerced to null")
        void noIngestCoercionHappens() {
            assertThat(onlyScreenComponent("cardholderName", null).cardholderName()).isNull();
            assertThat(onlyScreenComponent("cardholderName", "").cardholderName())
                    .isNotNull()
                    .isEmpty();
            assertThat(onlyScreenComponent("cardholderName", " ").cardholderName()).isEqualTo(" ");
        }

        @Test
        @DisplayName("all four states bind without a violation, so the service decides, not the binder")
        void allFourStatesBind() {
            // The legacy map tolerates a blank field and reports it with its own message rather than
            // refusing the transmission, so a presence constraint here would pre-empt that message.
            assertThat(violationsOf(onlyScreenComponent("expiryMonth", null))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryMonth", ""))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryMonth", "  "))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryMonth", "*"))).isEmpty();
        }

        @Test
        @DisplayName("no component carries a presence, non-blank or non-empty constraint")
        void noPresenceConstraintIsDeclaredAnywhere() {
            for (final String component : RecordFieldContract.componentNames(CardUpdateRequest.class)) {
                assertThat(RecordFieldContract.declares(CardUpdateRequest.class, component, NotNull.class))
                        .as("%s must tolerate absence", component).isFalse();
                assertThat(RecordFieldContract.declares(CardUpdateRequest.class, component, NotBlank.class))
                        .as("%s must tolerate blank, which is a state distinct from absent", component)
                        .isFalse();
                assertThat(RecordFieldContract.declares(CardUpdateRequest.class, component, NotEmpty.class))
                        .as("%s must tolerate empty", component).isFalse();
            }
        }

        @Test
        @DisplayName("the same discipline holds on every nested snapshot leaf")
        void noPresenceConstraintOnAnyNestedLeaf() {
            for (final Class<?> group : List.of(CardDetails.class, CardData.class, ExpiraionDate.class)) {
                for (final String component : RecordFieldContract.componentNames(group)) {
                    assertThat(RecordFieldContract.declares(group, component, NotNull.class))
                            .as("%s.%s", group.getSimpleName(), component).isFalse();
                    assertThat(RecordFieldContract.declares(group, component, NotBlank.class))
                            .as("%s.%s", group.getSimpleName(), component).isFalse();
                    assertThat(RecordFieldContract.declares(group, component, NotEmpty.class))
                            .as("%s.%s", group.getSimpleName(), component).isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("8. No unconditional cross-field constraint, because the source's is gated")
    final class NoCrossFieldConstraint {

        @Test
        @DisplayName("the record declares no class-level constraint at all")
        void noClassLevelConstraintIsDeclared() {
            // A class-level assertion fires unconditionally. The analogous cross-field edit in the account
            // program is gated on both single-field edits already having passed, so an unconditional one
            // would emit a cross-field error on input whose single-field validation already failed -
            // producing a different message set from the source.
            assertThat(ReflectionCensus.declaredAnnotationTypeNames(CardUpdateRequest.class))
                    .doesNotContain("AssertTrue", "AssertFalse", "ScriptAssert");
            assertThat(CardUpdateRequest.class.getAnnotations()).isEmpty();
        }

        @Test
        @DisplayName("no nested group declares a class-level constraint either")
        void noNestedGroupDeclaresOne() {
            for (final Class<?> group : List.of(CardDetails.class, CardData.class, ExpiraionDate.class)) {
                assertThat(group.getAnnotations())
                        .as("%s must declare no class-level constraint", group.getSimpleName())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the source's cross-field edit is gated on both single-field flags being valid")
        void theSourcesCrossFieldEditIsGated() throws IOException {
            final String accountProgram = Files.readString(COACTUPC, StandardCharsets.UTF_8);

            assertThat(accountProgram)
                    .as("app/cbl/COACTUPC.cbl:1665-1668 guards the cross-field edit")
                    .contains("1280-EDIT-US-STATE-ZIP-CD");
            assertThat(literalAt(COACTUPC, 1665)).isEqualTo("IF  FLG-STATE-ISVALID");
            assertThat(literalAt(COACTUPC, 1666)).isEqualTo("AND FLG-ZIPCODE-ISVALID");
            assertThat(literalAt(COACTUPC, 1667)).isEqualTo("PERFORM 1280-EDIT-US-STATE-ZIP-CD");
        }

        @Test
        @DisplayName("the card program's own edit sequence is unconditional and has no cross-field edit")
        void theCardProgramHasNoCrossFieldEditAtAll() {
            // :698-707 performs four edits unconditionally, then :710-714 sets the outcome flag. There is
            // no cross-field edit here to model, gated or otherwise.
            assertThat(literalAt(COCRDUPC, 698)).isEqualTo("PERFORM 1230-EDIT-NAME");
            assertThat(literalAt(COCRDUPC, 701)).isEqualTo("PERFORM 1240-EDIT-CARDSTATUS");
            assertThat(literalAt(COCRDUPC, 704)).isEqualTo("PERFORM 1250-EDIT-EXPIRY-MON");
            assertThat(literalAt(COCRDUPC, 707)).isEqualTo("PERFORM 1260-EDIT-EXPIRY-YEAR");
            assertThat(literalAt(COCRDUPC, 710)).isEqualTo("IF INPUT-ERROR");
        }

        @Test
        @DisplayName("an out-of-range expiry month binds, because the source judges it and reports its own message")
        void anOutOfRangeExpiryMonthStillBinds() {
            // The boundary the source states is 1 to 12, at app/cbl/COCRDUPC.cbl:895 with the check at
            // :898. Rejecting "00" or "13" at binding time would replace 'Card expiry month must be
            // between 1 and 12' with a framework error.
            assertThat(violationsOf(onlyScreenComponent("expiryMonth", "00"))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryMonth", "13"))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryMonth", "01"))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryMonth", "12"))).isEmpty();
        }

        @Test
        @DisplayName("an out-of-domain status code binds, because no enum stands between caller and service")
        void anOutOfDomainStatusCodeStillBinds() {
            // 'Card Active Status must be Y or N' is the service's message; an enum binding would turn a
            // "Z" into a deserialization failure and lose it.
            assertThat(violationsOf(onlyScreenComponent("cardStatusCode", "Z"))).isEmpty();
            assertThat(onlyScreenComponent("cardStatusCode", "Z").cardStatusCode()).isEqualTo("Z");
        }

        @Test
        @DisplayName("the expiry day carries no validation, because the source declares no edit for it")
        void theExpiryDayCarriesNoValidationBeyondItsWidth() {
            // The complete edit inventory is 1210-EDIT-ACCOUNT, 1220-EDIT-CARD, 1230-EDIT-NAME,
            // 1240-EDIT-CARDSTATUS, 1250-EDIT-EXPIRY-MON and 1260-EDIT-EXPIRY-YEAR. There is no
            // EDIT-EXPIRY-DAY paragraph, so the day is transported and never judged.
            assertThat(violationsOf(onlyScreenComponent("expiryDay", "00"))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryDay", "99"))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryDay", "1"))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryDay", "AA"))).isEmpty();
            assertThat(violationsOf(onlyScreenComponent("expiryDay", "123")))
                    .as("only the declared width bounds it")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("9. Every cardholder value is withheld from every rendering")
    final class Redaction {

        @Test
        @DisplayName("only the account identifier and the program name are rendered")
        void onlyTwoFieldsAreRendered() {
            final CardUpdateRequest request = populated();

            assertThat(request)
                    .hasToString("CardUpdateRequest[accountId=" + request.accountId()
                            + ", programName=" + request.programName() + "]");
        }

        @Test
        @DisplayName("the card number is omitted entirely, probed with the value the instance carries")
        void cardNumberIsOmitted() {
            final CardUpdateRequest request = populated();
            final String pan = request.cardNumber();

            // Probed from the instance, never from a literal. A hardcoded probe that the instance does not
            // carry would pass vacuously and would keep passing if the rendering began leaking the value.
            assertThat(pan).as("the probe must be a real value, or this assertion proves nothing")
                    .isNotNull().hasSize(COCRDUP.widthOf("CARDSIDI"));
            assertThat(request.toString()).doesNotContain(pan);
            assertThat(request.toString())
                    .as("not truncated to a last four either, because a truncation still discloses")
                    .doesNotContain(pan.substring(pan.length() - 4));
        }

        @Test
        @DisplayName("the cardholder name is omitted entirely, probed the same way")
        void cardholderNameIsOmitted() {
            final CardUpdateRequest request = populated();
            final String name = request.cardholderName();

            assertThat(name).isNotNull().isNotBlank();
            assertThat(request.toString()).doesNotContain(name);
            for (final String word : name.split(" ")) {
                assertThat(request.toString())
                        .as("no fragment of the cardholder name may survive")
                        .doesNotContain(word);
            }
        }

        @Test
        @DisplayName("every probe used by this group is a value the instance really carries")
        void everyProbeIsNonVacuous() {
            // The guard against this group's own failure mode: a probe that is absent from the
            // instance makes doesNotContain succeed for the wrong reason. Asserting the probes are present
            // in the instance's own state makes each omission assertion meaningful.
            final CardUpdateRequest request = populated();

            assertThat(request.cardNumber()).isNotNull().isNotEmpty();
            assertThat(request.cardholderName()).isNotNull().isNotEmpty();
            assertThat(request.accountId()).isNotNull().isNotEmpty();
            assertThat(request.programName()).isNotNull().isNotEmpty();
            assertThat(request.cardNumber()).isNotEqualTo(request.accountId());
        }

        @Test
        @DisplayName("no placeholder is emitted in place of either omitted value")
        void noPlaceholderIsEmitted() {
            assertThat(populated().toString()).doesNotContain("*").doesNotContain("...");
        }

        @Test
        @DisplayName("the two rendered values are emitted verbatim, so absence stays visible")
        void renderedValuesAreVerbatim() {
            assertThat(empty())
                    .hasToString("CardUpdateRequest[accountId=null, programName=null]");
        }

        @Test
        @DisplayName("no other populated component reaches the rendering")
        void noOtherComponentIsRendered() {
            final CardUpdateRequest request = populated();
            final String rendered = request.toString();

            assertThat(rendered)
                    .doesNotContain(request.informationMessage())
                    .doesNotContain(request.functionKeys())
                    .doesNotContain(request.functionKeysContinued())
                    .doesNotContain(request.expiryYear())
                    .doesNotContain(request.title01());
        }

        @Test
        @DisplayName("the snapshot renderings withhold the card number and the embossed name too")
        void theSnapshotRenderingsRedactAsWell() {
            final CardDetails details = snapshot("ANIYA VON", "2023", "03", "09", "Y");

            assertThat(details.toString())
                    .as("the snapshot carries its own copy of the card number")
                    .doesNotContain(details.cardNumber())
                    .contains(details.accountId());
            assertThat(details.cardData().toString())
                    .doesNotContain(details.cardData().cardholderName())
                    .contains(details.cardData().cardStatusCode());
            assertThat(details.cardData().expiraionDate().toString())
                    .as("an expiry date is cardholder data: the rendering reports presence, not values")
                    .doesNotContain("2023")
                    .doesNotContain("03")
                    .doesNotContain("09");
        }

        @Test
        @DisplayName("a populated snapshot never leaks through the enclosing rendering")
        void aPopulatedSnapshotNeverLeaksThroughTheRequest() {
            final CardDetails details = snapshot("ANIYA VON", "2023", "03", "09", "Y");
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, SEALED_SNAPSHOT,
                    details);

            assertThat(request.toString())
                    .doesNotContain(details.cardNumber())
                    .doesNotContain(details.cardData().cardholderName())
                    .doesNotContain(SEALED_SNAPSHOT)
                    .doesNotContain("snapshot")
                    .doesNotContain("newDetails");
        }

        @Test
        @DisplayName("no verification-value component exists anywhere in the payload tree")
        void noVerificationValueComponentExists() {
            // Stronger than never emitting it: a component that is not declared cannot be accepted,
            // stored, compared or rendered. app/cpy-bms/COCRDUP.CPY declares no such screen field, so no
            // symbolic-map contract is lost by its absence.
            for (final Class<?> type : List.of(CardUpdateRequest.class, CardDetails.class, CardData.class,
                    ExpiraionDate.class)) {
                assertThat(RecordFieldContract.componentNames(type))
                        .as("%s must declare no verification value", type.getSimpleName())
                        .noneSatisfy(component -> assertThat(component.toLowerCase(Locale.ROOT))
                                .containsAnyOf("cvv", "cvc", "cid", "securitycode", "verification"));
            }
        }

        @Test
        @DisplayName("no credential, secret or personal-identifier component exists")
        void noCredentialOrPersonalIdentifierComponentExists() {
            for (final Class<?> type : List.of(CardUpdateRequest.class, CardDetails.class, CardData.class,
                    ExpiraionDate.class)) {
                assertThat(RecordFieldContract.componentNames(type))
                        .as("%s must carry no credential and no personal identifier", type.getSimpleName())
                        .noneSatisfy(component -> assertThat(component.toLowerCase(Locale.ROOT))
                                .containsAnyOf("password", "passwd", "secret", "token", "credential",
                                        "signingkey", "apikey", "ssn", "socialsecurity", "governmentid",
                                        "dateofbirth", "dob", "phone", "pin"));
            }
        }

        @Test
        @DisplayName("value semantics are the compiler's, and disclose nothing because no value is rendered")
        void valueSemanticsAreTheCompilersAndDiscloseNothing() {
            final CardUpdateRequest first = populated();
            final CardUpdateRequest second = populated();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first.toString()).isEqualTo(second.toString());

            // Equality considers every component, which is correct for a value carrier: two payloads that
            // differ in the card number are different payloads. It discloses nothing, because neither
            // rendering emits that value.
            final CardUpdateRequest differentPan = new CardUpdateRequest("CCUP", null, null, null, null,
                    null, "00000000001", "4111999988887778", null, null, null, null, null, null, null,
                    null, null, null, null);
            final CardUpdateRequest samePanDifferentField = new CardUpdateRequest("CCUP", null, null, null,
                    null, null, "00000000001", "4111999988887777", null, null, null, null, null, null,
                    null, null, null, null, null);

            assertThat(differentPan).isNotEqualTo(samePanDifferentField);
            assertThat(differentPan.toString()).isEqualTo(samePanDifferentField.toString());
        }

        @Test
        @DisplayName("the payload cannot take part in Java native serialization")
        void thePayloadIsNotSerializable() {
            for (final Class<?> type : List.of(CardUpdateRequest.class, CardDetails.class, CardData.class,
                    ExpiraionDate.class)) {
                assertThat(type.getInterfaces())
                        .as("%s implements nothing, so no readObject path exists", type.getSimpleName())
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("10. The frozen fixture the payload has to survive")
    final class FixtureBackedContract {

        /**
         * Loads the frozen card fixture by classpath resource name.
         *
         * @return the fixture data; the file is never copied, edited or written.
         */
        private FixtureLoader.FixtureData cardFixture() {
            // Loaded by classpath resource name only: never copied, never edited, never written.
            return FixtureLoader.load(FixtureLoader.Fixture.CARD);
        }

        @Test
        @DisplayName("the fixture geometry is exactly 50 rows of 150 bytes")
        void theFixtureGeometryIsFixedWidth() {
            final FixtureLoader.FixtureData card = cardFixture();

            assertThat(card.resourceName()).isEqualTo("carddata.txt");
            assertThat(card.recordCount()).isEqualTo(50);
            assertThat(card.recordWidth()).isEqualTo(150);
            assertThat(card.byteCount()).isEqualTo(7_550);
            assertThat(card.impliedByteCount())
                    .as("7550 == 50 x (150 + 1), the trailing byte of each row being its terminator")
                    .isEqualTo(card.byteCount());
        }

        @Test
        @DisplayName("every row is exactly the record width, so trailing padding is data and not noise")
        void everyRowIsExactlyTheRecordWidth() {
            final FixtureLoader.FixtureData card = cardFixture();

            assertThat(card.records()).allSatisfy(record -> assertThat(record).hasSize(150));

            // The widest trailing run is the FILLER X(59) of app/cpy/CVACT02Y.cpy:L11. A whitespace cleanup
            // over this fixture would destroy the fixed-width geometry every offset here depends on.
            int widestTrailingRun = 0;
            for (final String record : card.records()) {
                widestTrailingRun = Math.max(widestTrailingRun, record.length() - record.stripTrailing().length());
            }
            assertThat(widestTrailingRun).isEqualTo(CVACT02Y.recordLength() - 91).isEqualTo(59);
        }

        @Test
        @DisplayName("eight of the fifty verification values begin with a zero, which text preserves")
        void leadingZeroVerificationValuesSurvive() {
            final FixtureLoader.FixtureData card = cardFixture();
            final List<String> leadingZero = new ArrayList<>();
            for (int row = 0; row < card.recordCount(); row++) {
                final String value = card.field(row, 28, CVACT02Y.widthOf("CARD-CVV-CD"));
                if (value.startsWith("0")) {
                    leadingZero.add(value);
                }
            }

            // A numeric Java type would render 003 as 3 and destroy the field. The payload declares no
            // verification value at all, which is stronger still - but the fixture fact is what proves the
            // corpus is character-first, so it is asserted here rather than assumed.
            assertThat(leadingZero).hasSize(8);
            assertThat(leadingZero).allSatisfy(value -> assertThat(value).hasSize(3).startsWith("0"));
            assertThat(leadingZero).containsExactlyInAnyOrder(
                    "003", "021", "028", "031", "033", "045", "067", "075");
        }

        @Test
        @DisplayName("account identifiers are zero-padded to eleven characters and fit the component")
        void accountIdentifiersAreZeroPaddedAndFit() {
            final FixtureLoader.FixtureData card = cardFixture();
            final int width = COCRDUP.widthOf("ACCTSIDI");

            assertThat(card.field(0, 17, CVACT02Y.widthOf("CARD-ACCT-ID")))
                    .as("row 1 holds account 50 stored as eleven characters")
                    .isEqualTo("00000000050");

            // Every row is checked against the declared width - that is the contract, and checking it
            // directly is exhaustive. The binding path is then exercised once, with the longest value
            // present, rather than fifty times with values that are equal in width by construction: a
            // redundant validator call proves nothing new and only costs wall-clock time.
            String longest = "";
            for (int row = 0; row < card.recordCount(); row++) {
                final String accountId = card.field(row, 17, CVACT02Y.widthOf("CARD-ACCT-ID"));
                assertThat(accountId)
                        .as("account identifier at row %d must be zero-padded to the full width", row)
                        .hasSize(width)
                        .containsOnlyDigits();
                if (accountId.length() > longest.length()) {
                    longest = accountId;
                }
            }
            assertThat(violationsOf(onlyScreenComponent("accountId", longest))).isEmpty();
        }

        @Test
        @DisplayName("card numbers are sixteen characters and fit the component")
        void cardNumbersFitTheComponent() {
            final FixtureLoader.FixtureData card = cardFixture();
            final int width = COCRDUP.widthOf("CARDSIDI");

            String longest = "";
            for (int row = 0; row < card.recordCount(); row++) {
                final String cardNumber = card.field(row, 1, CVACT02Y.widthOf("CARD-NUM"));
                // The value is deliberately never echoed into an assertion description; the row index
                // identifies a failure instead.
                assertThat(cardNumber)
                        .as("card number at row %d must be exactly the declared width", row)
                        .hasSize(width);
                if (cardNumber.length() > longest.length()) {
                    longest = cardNumber;
                }
            }
            assertThat(violationsOf(onlyScreenComponent("cardNumber", longest))).isEmpty();
        }

        @Test
        @DisplayName("the fixture carries no sign overpunch, so no decoder may be run across it")
        void theFixtureCarriesNoSignOverpunch() {
            final FixtureLoader.FixtureData card = cardFixture();
            final String whole = String.join("", card.records());

            // Not one signed field exists in this member, so a global overpunch substitution has nothing
            // legitimate to decode and everything to corrupt.
            assertThat(whole).doesNotContain("{").doesNotContain("}");

            // Meanwhile the embossed names are full of the very letters such a decoder claims: A-I map to
            // +1..+9 and J-R to -1..-9. Decoding is position-aware from the PIC clauses only.
            boolean sawOverpunchLetterInsideAName = false;
            for (int row = 0; row < card.recordCount(); row++) {
                final String embossed = card.field(row, 31, CVACT02Y.widthOf("CARD-EMBOSSED-NAME"));
                for (final char letter : embossed.toCharArray()) {
                    if (letter >= 'A' && letter <= 'R') {
                        sawOverpunchLetterInsideAName = true;
                        break;
                    }
                }
            }
            assertThat(sawOverpunchLetterInsideAName)
                    .as("A-R occur legitimately inside text, which is why a global decoder is unsafe")
                    .isTrue();
        }

        @Test
        @DisplayName("a real fixture row round-trips into a snapshot group within every declared width")
        void aRealFixtureRowRoundTripsIntoASnapshot() {
            final FixtureLoader.FixtureData card = cardFixture();
            final String expiry = card.field(0, 81, CVACT02Y.widthOf("CARD-EXPIRAION-DATE"));

            // The persisted expiry is dash-separated, so the components sit at offsets 1, 6 and 9 - the
            // same offsets :1505-1507 addresses. Decomposing here rather than parsing keeps the
            // representation the comparison depends on.
            assertThat(expiry).hasSize(10).isEqualTo("2023-03-09");
            final CardDetails details = new CardDetails(
                    card.field(0, 17, CVACT02Y.widthOf("CARD-ACCT-ID")),
                    card.field(0, 1, CVACT02Y.widthOf("CARD-NUM")),
                    new CardData(card.field(0, 31, CVACT02Y.widthOf("CARD-EMBOSSED-NAME")),
                            new ExpiraionDate(expiry.substring(0, 4), expiry.substring(5, 7),
                                    expiry.substring(8, 10)),
                            card.field(0, 91, CVACT02Y.widthOf("CARD-ACTIVE-STATUS"))));

            assertThat(violationsOf(new CardUpdateRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, details))).isEmpty();
            assertThat(details.cardData().cardholderName())
                    .as("the fifty-character embossed name keeps its space padding")
                    .hasSize(50)
                    .startsWith("Aniya Von");
            assertThat(details.cardData().cardStatusCode()).isEqualTo("Y");
        }

        @Test
        @DisplayName("the daily-transaction fixture is spelled in full, which is the name trap")
        void theDailyTransactionFixtureIsSpelledInFull() {
            // The mainframe DD name and dataset are DALYTRAN, so dalytran.txt is the natural guess and is
            // wrong: it compiles cleanly and fails only when the resource is opened.
            assertThat(FixtureLoader.Fixture.DAILY_TRANSACTION.resourceName()).isEqualTo("dailytran.txt");
            assertThat(FixtureLoader.Fixture.CARD.resourceName()).isEqualTo("carddata.txt");
        }
    }

    @Nested
    @DisplayName("11. The persisted layout and catalogue geometry the payload maps onto")
    final class EntityAndCatalogueGeometry {

        @Test
        @DisplayName("the record layout is 150 bytes with a sixteen-character key")
        void theRecordLayoutIs150BytesKeyed16() {
            assertThat(CVACT02Y.member()).isEqualTo("CVACT02Y");
            assertThat(CVACT02Y.recordLength()).isEqualTo(150);
            assertThat(CVACT02Y.widthOf("CARD-NUM"))
                    .as("the key is the first sixteen bytes, matching KEYLEN 16")
                    .isEqualTo(16);
        }

        @Test
        @DisplayName("every layout field has the width the copybook declares")
        void everyLayoutFieldWidthIsExact() {
            assertThat(CVACT02Y.widthOf("CARD-NUM")).isEqualTo(16);
            assertThat(CVACT02Y.widthOf("CARD-ACCT-ID")).isEqualTo(11);
            assertThat(CVACT02Y.widthOf("CARD-CVV-CD")).isEqualTo(3);
            assertThat(CVACT02Y.widthOf("CARD-EMBOSSED-NAME")).isEqualTo(50);
            assertThat(CVACT02Y.widthOf("CARD-EXPIRAION-DATE")).isEqualTo(10);
            assertThat(CVACT02Y.widthOf("CARD-ACTIVE-STATUS")).isEqualTo(1);
        }

        @Test
        @DisplayName("the screen widths agree with the layout widths they are read from and written to")
        void screenAndLayoutWidthsAgree() {
            assertThat(COCRDUP.widthOf("CARDSIDI")).isEqualTo(CVACT02Y.widthOf("CARD-NUM"));
            assertThat(COCRDUP.widthOf("ACCTSIDI")).isEqualTo(CVACT02Y.widthOf("CARD-ACCT-ID"));
            assertThat(COCRDUP.widthOf("CRDNAMEI")).isEqualTo(CVACT02Y.widthOf("CARD-EMBOSSED-NAME"));
            assertThat(COCRDUP.widthOf("CRDSTCDI")).isEqualTo(CVACT02Y.widthOf("CARD-ACTIVE-STATUS"));
        }

        @Test
        @DisplayName("the catalogue corroborates the key length and the record length")
        void theCatalogueCorroboratesTheGeometry() throws IOException {
            final List<String> catalogue = Files.readAllLines(
                    Path.of("app", "catlg", "LISTCAT.txt"), StandardCharsets.UTF_8);

            assertThat(catalogue.get(201))
                    .as("app/catlg/LISTCAT.txt:L202 for the CARDDATA cluster")
                    .contains("KEYLEN----------------16")
                    .contains("AVGLRECL-------------150");
            assertThat(catalogue.get(282))
                    .as("app/catlg/LISTCAT.txt:L283, the alternate key position")
                    .contains("AXRKP-----------------16");
        }

        @Test
        @DisplayName("the alternate key is non-unique, so nothing here may imply one card per account")
        void theAlternateKeyIsNonUnique() throws IOException {
            final List<String> catalogue = Files.readAllLines(
                    Path.of("app", "catlg", "LISTCAT.txt"), StandardCharsets.UTF_8);

            // The decisive token. Careful: the preceding line carries the word UNIQUE, but that is the
            // dataset-name-sharing attribute, not alternate-key uniqueness - reading it alone inverts the
            // conclusion.
            assertThat(catalogue.get(284))
                    .as("app/catlg/LISTCAT.txt:L285 marks the alternate index NONUNIQKEY")
                    .contains("NONUNIQKEY");

            // AXRKP 16 is zero-based, so the alternate key begins at one-based byte 17 - immediately after
            // the sixteen-byte primary key - and therefore resolves to CARD-ACCT-ID at bytes 17 to 27.
            assertThat(CVACT02Y.widthOf("CARD-NUM") + 1).isEqualTo(17);

            // The fixture happens to be one-to-one. That is a property of this fixture only and must never
            // be turned into a constraint.
            final FixtureLoader.FixtureData card = FixtureLoader.load(FixtureLoader.Fixture.CARD);
            final List<String> accountIds = new ArrayList<>();
            for (int row = 0; row < card.recordCount(); row++) {
                accountIds.add(card.field(row, 17, CVACT02Y.widthOf("CARD-ACCT-ID")));
            }
            assertThat(accountIds).doesNotHaveDuplicates().hasSize(50);
            assertThat(RecordFieldContract.declares(CardUpdateRequest.class, "accountId", Size.class))
                    .as("the account identifier is width-bounded only; no uniqueness is expressed here")
                    .isTrue();
        }

        @Test
        @DisplayName("the layout keeps one ten-character expiry while the screen keeps three components")
        void theLayoutKeepsOneExpiryFieldAndTheScreenKeepsThree() {
            assertThat(CVACT02Y.widthOf("CARD-EXPIRAION-DATE")).isEqualTo(10);
            assertThat(COCRDUP.widthOf("EXPMONI") + COCRDUP.widthOf("EXPYEARI")
                    + COCRDUP.widthOf("EXPDAYI"))
                    .as("2 + 4 + 2 = 8; the two remaining bytes are the dash separators")
                    .isEqualTo(8);
            assertThat(RecordFieldContract.componentNames(CardUpdateRequest.class))
                    .as("the payload keeps the components apart, never reassembling them")
                    .contains("expiryMonth", "expiryYear", "expiryDay");
        }
    }

    @Nested
    @DisplayName("12. No server-side session state survives into the payload")
    final class NoSessionState {

        @Test
        @DisplayName("the COMMAREA declares the session-state fields this payload must not carry")
        void theCommAreaDeclaresThemAtSevenCharacters() throws IOException {
            final List<String> commArea = Files.readAllLines(
                    Path.of("app", "cpy", "COCOM01Y.cpy"), StandardCharsets.UTF_8);

            // Both are PIC X(7), not X(8) - a width worth pinning because the neighbouring identity fields
            // are eight characters and the difference is easy to lose.
            assertThat(commArea.get(42)).contains("CDEMO-LAST-MAP").contains("PIC X(7)");
            assertThat(commArea.get(43)).contains("CDEMO-LAST-MAPSET").contains("PIC X(7)");
            assertThat(String.join("\n", commArea))
                    .contains("CDEMO-FROM-TRANID", "CDEMO-TO-TRANID", "CDEMO-FROM-PROGRAM",
                            "CDEMO-TO-PROGRAM", "CDEMO-PGM-CONTEXT");
        }

        @Test
        @DisplayName("no routing, program-context or last-map component appears in the payload tree")
        void noSessionStateComponentAppears() {
            // Navigation is URL-based and the enter-versus-re-enter flag collapses into stateless request
            // handling, so none of these has a counterpart here.
            for (final Class<?> type : List.of(CardUpdateRequest.class, CardDetails.class, CardData.class,
                    ExpiraionDate.class)) {
                for (final String component : RecordFieldContract.componentNames(type)) {
                    final String normalised = component.toLowerCase(Locale.ROOT).replace("_", "");
                    assertThat(SESSION_STATE_TOKENS)
                            .as("%s.%s must not be a session-state field", type.getSimpleName(), component)
                            .noneSatisfy(token -> assertThat(normalised).contains(token));
                }
            }
        }

        @Test
        @DisplayName("no pagination component appears either, that state having moved to the request line")
        void noPaginationComponentAppears() {
            assertThat(RecordFieldContract.componentNames(CardUpdateRequest.class))
                    .noneSatisfy(component -> assertThat(component.toLowerCase(Locale.ROOT))
                            .containsAnyOf("pagenumber", "nextpage", "previouspage", "pageindicator"));
        }

        @Test
        @DisplayName("the payload declares no attribute byte, cursor or screen-rendering component")
        void noTerminalPresentationComponentAppears() {
            // The symbolic map is consumed as a field contract only. The quintuple's length field, attribute
            // byte, attribute alias and reserved bytes are presentation and are deliberately not modelled:
            // only the trailing data field of each group is a payload field.
            final List<String> components = RecordFieldContract.componentNames(CardUpdateRequest.class);

            assertThat(components).hasSize(COCRDUP.inputFieldCount() + 2);
            assertThat(components)
                    .noneSatisfy(component -> assertThat(component.toLowerCase(Locale.ROOT))
                            .containsAnyOf("attribute", "cursor", "colour", "color", "highlight",
                                    "protect", "length", "reserved", "aid"));
        }
    }

    /**
     * The diagnostic rendering may not be turned into a forged log record.
     *
     * <p>Every component {@code toString()} emits is declared {@code String} and arrives from a JSON request
     * body, so a caller controls its bytes: concatenated straight in, a CR or LF forges as many further log
     * lines as the caller likes, in the exact shape a reader trusts. {@code @Size} and {@code @Pattern} run
     * <em>after</em> Jackson has constructed the record, and a validation failure is precisely the occasion
     * on which something renders the offending instance, so these tests build hostile values directly and
     * never validate them first.
     */
    @Nested
    @DisplayName("the diagnostic rendering cannot forge a log record")
    class HostileDiagnosticRendering {

        @ParameterizedTest(name = "a CR/LF payload in {0} cannot break the record")
        @ValueSource(strings = {"accountId", "programName"})
        @DisplayName("a control character in any rendered component is escaped, not emitted")
        void aControlCharacterInAnyRenderedComponentIsEscaped(final String component) {
            final String hostile = "AAA\r\n2026-08-04 INFO forged FORGED-RECORD";

            final String rendered = onlyScreenComponent(component, hostile).toString();

            assertThat(rendered)
                    .as("the raw terminators must be gone, or the rendering is one log record per attacker "
                            + "newline rather than one per event")
                    .doesNotContain("\r")
                    .doesNotContain("\n");
            assertThat(rendered.lines().count())
                    .as("and the whole rendering must remain exactly one line")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the escaped payload is still legible, so the evidence survives neutralisation")
        void theEscapedPayloadRemainsLegible() {
            final String rendered = onlyScreenComponent("accountId", "AAA\r\nFORGED-RECORD").toString();

            assertThat(rendered)
                    .as("a reader investigating a hostile request needs to see what arrived; escaping the "
                            + "terminator must not discard the value around it")
                    .contains("FORGED-RECORD")
                    .contains("\\u000D")
                    .contains("\\u000A");
        }

        @Test
        @DisplayName("an over-long component is bounded, so one field cannot flood the record")
        void anOverLongComponentIsBounded() {
            final String rendered = onlyScreenComponent("accountId", "q".repeat(400)).toString();

            assertThat(rendered)
                    .as("the length constraints have not run on an instance being rendered because it failed "
                            + "them, so the rendering bounds the value itself")
                    .contains("chars)")
                    .hasSizeLessThan(600);
        }

        @Test
        @DisplayName("a benign instance renders unchanged, so the guard is invisible in normal use")
        void aBenignInstanceRendersUnchanged() {
            assertThat(populated().toString())
                    .as("neutralisation must not alter what an ordinary log record says")
                    .doesNotContain("chars)")
                    .doesNotContain("\\u");
        }
    }

}
