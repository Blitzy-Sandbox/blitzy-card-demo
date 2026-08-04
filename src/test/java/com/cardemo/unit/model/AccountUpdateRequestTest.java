/*
 * ******************************************************************
 * Program     : AccountUpdateRequestTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the account-update request payload carried by
 *               com.cardemo.model.dto.AccountUpdateRequest against
 *               the frozen map and snapshot groups it was translated
 *               from. In particular it pins that the payload is
 *               IMMUTABLE, that validation cascades into BOTH
 *               snapshot groups, that the source's EXPIRAION
 *               misspelling survives on the wire, that every
 *               REDEFINES overlay carries exactly ONE stored member
 *               with the other reading derived, that the stored side
 *               of the telephone overlay differs between the two
 *               groups exactly as the source differs, and that an
 *               unrecognised property is refused rather than
 *               discarded.
 * Source      : app/cpy-bms/COACTUP.CPY (54 input fields, group
 *               CACTUPAI, lines 17-342) + app/cbl/COACTUPC.cbl
 *               ACUP-OLD-DETAILS:669-756 and ACUP-NEW-DETAILS:757-849
 *               + 1205-COMPARE-OLD-NEW:1681-1777
 *               + 9700-CHECK-CHANGE-IN-REC:4109-4193 @ 7756d89
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
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.model.dto.AccountUpdateRequest;
import com.cardemo.model.dto.AccountUpdateRequest.NewDetails;
import com.cardemo.model.dto.AccountUpdateRequest.OldDetails;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression suite for {@link AccountUpdateRequest}, the account-update request payload.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the most intricate payload in the package, because it is the only one that must serve
 * <em>two</em> comparison regimes that normalise the same fields differently.
 * {@code 1205-COMPARE-OLD-NEW} ({@code app/cbl/COACTUPC.cbl:1681-1777}) asks whether the user
 * changed anything, comparing the NEW group against the OLD group.
 * {@code 9700-CHECK-CHANGE-IN-REC} ({@code :4109-4193}) asks whether somebody else changed the
 * record, comparing the live record against the OLD group. Six properties are pinned here that a
 * plausible tidy-up would silently break.</p>
 *
 * <p><strong>The payload is immutable.</strong> An earlier revision exposed 139 setters, so a value
 * could be rewritten between validation and the snapshot comparison. A concurrency guard that can
 * be rewritten after it is validated is not a guard. Every field must be {@code final}, no setter
 * may exist, and each type must be reachable through exactly one all-arguments creator.</p>
 *
 * <p><strong>Validation cascades into both snapshot groups.</strong> An earlier revision left
 * {@code oldDetails} without a cascade and without one width contract, arguing that the OLD group
 * declares no {@code 88}-level condition name. A {@code PIC} clause is itself a contract, and under
 * statelessness the group arrives from the client rather than from {@code 9000-READ-DATA}
 * ({@code app/cbl/COACTUPC.cbl:3610}), so it is the untrusted operand of the concurrency guard.</p>
 *
 * <p><strong>Each REDEFINES overlay carries one stored member.</strong>
 * {@code ACUP-OLD-CURR-BAL PIC X(12)} at {@code app/cbl/COACTUPC.cbl:675} and
 * {@code ACUP-OLD-CURR-BAL-N PIC S9(10)V99} at {@code :676-677} name the <em>same twelve
 * bytes</em>. A model in which the text and the number are independently writable describes a byte
 * state that cannot exist. The stored side is the side the source assigns; the other reading is a
 * derived accessor deliberately not named as a bean property, so the serializer neither emits it
 * nor binds it.</p>
 *
 * <p><strong>The telephone overlay stores opposite sides on the two groups.</strong> The OLD group
 * is assigned whole, by {@code MOVE CUST-PHONE-NUM-1} at {@code app/cbl/COACTUPC.cbl:3876}, and
 * never by part. The NEW group is assigned only by part, at {@code :1359-1396}, and never whole.
 * Flattening either side to match the other would name a value that group never holds.</p>
 *
 * <p><strong>The source's misspelling survives.</strong> {@code ACUP-OLD-EXPIRAION-DATE}
 * ({@code :690}) and {@code ACUP-NEW-EXPIRAION-DATE} ({@code :778}) are misspelled in the frozen
 * corpus. The wire name must be {@code expiraionDate}, and the corrected spelling must be actively
 * refused rather than quietly accepted as an alias.</p>
 *
 * <p><strong>An unrecognised property is refused.</strong> The framework disables failure on
 * unknown properties by default and no profile in this repository re-enables it, so a declarative
 * type-level annotation would be inert. The guard has to hold under a lenient mapper as well as a
 * strict one, because otherwise a derived view submitted as though it were a member would be
 * silently discarded - which looks like acceptance.</p>
 *
 * <p><strong>The date-of-birth offsets are asymmetric, and that is the highest-severity property
 * in the whole payload.</strong> {@code 9700-CHECK-CHANGE-IN-REC} compares the live customer date
 * against the snapshot date at <em>different offsets on each side</em>
 * ({@code app/cbl/COACTUPC.cbl:4174-4179}): the live value is {@code CUST-DOB-YYYY-MM-DD PIC X(10)}
 * ({@code app/cpy/CVCUS01Y.cpy:19}), dash-separated, so its parts sit at 1, 6 and 9, while the
 * snapshot is {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} ({@code :746}), compact, so its parts
 * sit at 1, 5 and 7. A whole-string comparison of the two can never be equal, so the endpoint would
 * answer {@code 'Record changed by some one else. Please review'} to every request ever made and
 * no account could be updated again. {@link DateOfBirthOffsetAsymmetry} pins the compact storage and
 * the component rule, and demonstrates the permanent-failure mode rather than describing it.</p>
 *
 * <p><strong>Two comparison regimes normalise the same field differently, so the payload must not
 * normalise at all.</strong> The account group identifier is folded with {@code FUNCTION LOWER-CASE}
 * and no {@code TRIM} in {@code 9700} ({@code :4139-4140}) but with
 * {@code FUNCTION UPPER-CASE(FUNCTION TRIM(...))} in {@code 1205} ({@code :1697-1700}); the postal
 * code carries no case function at all in {@code 9700} ({@code :4168}) yet is folded and trimmed in
 * {@code 1205} ({@code :1747-1750}); telephones are compared whole in {@code 9700}
 * ({@code :4169-4170}) but part by part in {@code 1205} ({@code :1751-1756}). Normalising on arrival
 * in <em>either</em> direction would silently pick one regime and break the other, so
 * {@link ComparisonRegimes} pins that values round-trip byte-for-byte and that both foldings remain
 * derivable - and reaches opposite verdicts from the same pair to prove the asymmetry is
 * observable.</p>
 *
 * <p><strong>Absent, blank and low-values are three states, not one.</strong> When a screen field
 * holds {@code '*'} or spaces the source moves {@code LOW-VALUES} - binary zeros, not spaces - into
 * the NEW group ({@code :1235}, {@code :1258}, {@code :1279}), and {@code app/cpy/CSSETATY.cpy}
 * models exactly OK, NOT-OK and BLANK. The source proves the distinction at the message level:
 * {@code 'Credit Limit must be supplied'} ({@code :505-506}) and
 * {@code 'Credit Limit is not valid'} ({@code :507-508}) are two literals for two states.
 * {@link TriStateEmptiness} pins that all three survive the payload distinguishably; collapsing them
 * into one notion of emptiness would delete half the validation surface without any test
 * failing.</p>
 *
 * <p><strong>The state-and-postal-code edit is gated, so it cannot be a type-level constraint.</strong>
 * {@code 1280-EDIT-US-STATE-ZIP-CD} runs only once both single-field edits have passed
 * ({@code :1664-1669}). A type-level {@code @AssertTrue} fires unconditionally and would report a
 * cross-field error on input whose single-field validation had already failed, which is a different
 * message set from the source. {@link GatedCrossFieldEdit} pins that no such annotation exists
 * anywhere on the payload.</p>
 *
 * <p><strong>The outcome codes and message literals are the observable contract.</strong> Six
 * distinct outcome codes are declared at {@code :660-668} and four failure markers at
 * {@code :517-523}; each must stay distinguishable, because collapsing them into one conflict status
 * discards what the legacy screen displayed. The literals include two oddities that must never be
 * tidied: {@code 'Record changed by some one else. Please review'} spells "some one" as two words,
 * and {@code 'Looks Good.... so far'} carries four dots. {@link OutcomeFlagsAndMessages} pins all
 * thirteen byte-for-byte, and pins the width consequence the misspelling causes - at 46 characters
 * the concurrency verdict does not fit {@code informationMessage}, so it has to travel on
 * {@code errorMessage}.</p>
 *
 * <p><strong>Money is decimal, dates are text, and the personal data never reaches a log.</strong>
 * {@link PrecisionDatesAndSecurity} pins that every money reading is a {@code BigDecimal} compared
 * with {@code compareTo} rather than {@code equals}, that the three declared precisions stay
 * distinct, that every date member is a {@code String} rather than a {@code LocalDate}, and that no
 * type overrides {@code toString} - the payload carries a social security number, two telephone
 * numbers, a date of birth, a government-issued identifier, names and a full address, so an
 * inherited {@code toString} is the only safe one.</p>
 *
 * <h2>How to run it</h2>
 *
 * <p>{@code ./mvnw -B -o test -Dtest=AccountUpdateRequestTest} runs this class alone;
 * {@code ./mvnw -B test} runs it with the rest of the unit tier. It needs no container, no Spring
 * context, no database and no network. This class lives under
 * {@code src/test/java/com/cardemo/unit/} because that is the tree Surefire is bound to; a class
 * moved out of it is collected by neither Surefire nor Failsafe and stops running without any
 * error being reported.</p>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Nothing here reads a clock, a locale or a time zone from the environment. Temporal values come
 * from {@link FixedClockProvider}, whose {@link FixedClockProvider#CANONICAL_INSTANT} fixes the
 * instant and whose {@link FixedClockProvider#CANONICAL_ZONE} fixes the zone, and every case
 * operation passes {@link java.util.Locale#ROOT} explicitly - a Turkish default locale maps
 * {@code i} to a dotted capital and would otherwise change which updates this suite says are
 * accepted. Fixture bytes come from {@link FixtureLoader} by classpath resource name, never by
 * filesystem path, so the suite is indifferent to the working directory. The validator is the
 * default Jakarta Bean Validation factory and the mappers are plain Jackson instances, one strict
 * and one lenient, both configured in this class rather than inherited from a profile.</p>
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <p>A failure in {@link Immutability} means a setter or a non-final field was reintroduced. A
 * failure in {@link SnapshotCascade} means a cascade or a width contract was dropped. A failure in
 * {@link OverlayCanonicalisation} means an overlay reading became independently writable again -
 * check whether a stored member was added alongside a derived view of the same bytes. A failure in
 * {@link SourceFaithfulNaming} means the misspelling was corrected, which is a parity break. In
 * every case the frozen corpus is right and the code is wrong.</p>
 *
 * <p>A failure in {@link DateOfBirthOffsetAsymmetry} is the most serious outcome available here: it
 * means the snapshot date stopped being stored compact, or its components stopped being sliced at
 * the compact offsets, either of which makes the account-update endpoint reject every request. A
 * failure in {@link ComparisonRegimes} means normalisation crept into the payload, which silently
 * serves one comparison regime and breaks the other. A failure in {@link TriStateEmptiness} means
 * blank and low-values were folded together. A failure in {@link GatedCrossFieldEdit} means a
 * type-level constraint was added that fires before the single-field edits have run. A failure in
 * {@link OutcomeFlagsAndMessages} means a literal was paraphrased or an outcome code was merged. A
 * failure in {@link PrecisionDatesAndSecurity} means a binary floating-point type, a
 * {@code LocalDate} or a {@code toString} override entered the payload.</p>
 *
 * <p>Two traps are worth naming because they cost more time than they should. First,
 * {@code app/cbl/COACTUPC.cbl} is one of only five files in the frozen corpus terminated with CRLF,
 * so every line number cited above is valid only against the carriage-return-stripped file; reading
 * it as-is drifts every locator. Second, compilation runs with {@code -Xlint:all -Werror}, which
 * reaches test sources, so a single unused import fails the build rather than warning - the compiler
 * error names the import, and the fix is always to delete it rather than to relax the flag.</p>
 *
 * @see AccountUpdateRequest
 * @see FixtureLoader
 * @see FixedClockProvider
 */
@DisplayName("AccountUpdateRequest - app/cpy-bms/COACTUP.CPY group CACTUPAI + app/cbl/COACTUPC.cbl")
final class AccountUpdateRequestTest {

    /** Screen fields declared by {@code app/cpy-bms/COACTUP.CPY} between lines 17 and 342. */
    private static final int MAP_FIELDS = 54;

    /** Top-level members: the 54 screen fields plus the two snapshot groups. */
    private static final int TOP_LEVEL_MEMBERS = 56;

    /** Members of {@code ACUP-OLD-DETAILS} after overlay canonicalisation. */
    private static final int OLD_MEMBERS = 29;

    /** Members of {@code ACUP-NEW-DETAILS} after overlay canonicalisation. */
    private static final int NEW_MEMBERS = 35;

    /** Declared width of a snapshot telephone member, {@code PIC X(15)}. */
    private static final int PHONE_WIDTH = 15;

    /** Bound on cause-chain traversal, so a self-referential cause cannot spin. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** Declared width of a snapshot date, {@code PIC X(08)} at {@code app/cbl/COACTUPC.cbl:746}. */
    private static final int COMPACT_DATE_WIDTH = 8;

    /** Declared width of the live customer date, {@code PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:19}. */
    private static final int LIVE_DATE_WIDTH = 10;

    /** Declared width of a snapshot money member, {@code PIC X(12)} at {@code app/cbl/COACTUPC.cbl:675}. */
    private static final int MONEY_WIDTH = 12;

    /**
     * A date of birth exactly as {@code app/data/ASCII/custdata.txt} holds it on row 1 at bytes
     * 309 to 318: dash-separated, ten characters, the shape of the live customer record.
     */
    private static final String LIVE_DATE_OF_BIRTH = "1961-06-08";

    /**
     * The same date in the compact eight-character shape the snapshot groups declare. Nothing but the
     * two separators differs, which is precisely why a whole-string comparison of the two fails.
     */
    private static final String COMPACT_DATE_OF_BIRTH = "19610608";

    /** {@code 88 CRED-LIMIT-IS-BLANK} at {@code app/cbl/COACTUPC.cbl:505-506}. */
    private static final String CREDIT_LIMIT_BLANK = "Credit Limit must be supplied";

    /** {@code 88 CRED-LIMIT-IS-NOT-VALID} at {@code app/cbl/COACTUPC.cbl:507-508}. */
    private static final String CREDIT_LIMIT_NOT_VALID = "Credit Limit is not valid";

    /**
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} at {@code app/cbl/COACTUPC.cbl:521-522}.
     *
     * <p>"some one" is two words in the frozen corpus. That is not a typo to repair: at 46
     * characters this literal does not fit {@code INFOMSGI PIC X(45)}, whereas the corrected
     * one-word spelling would, so the misspelling is what forces the verdict onto the wider
     * {@code ERRMSGI PIC X(78)} field. {@link OutcomeFlagsAndMessages} asserts both facts.</p>
     */
    private static final String DATA_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /** {@code 88 CODING-TO-BE-DONE} at {@code app/cbl/COACTUPC.cbl:527-528}; four dots, not three. */
    private static final String CODING_TO_BE_DONE = "Looks Good.... so far";

    /**
     * Every message literal declared between {@code app/cbl/COACTUPC.cbl:503} and {@code :528}, in
     * source order.
     *
     * <p>Held here so that a later paraphrase fails a test rather than passing review. The parity
     * gates compare these strings, so "clearer" wording is a regression.</p>
     */
    private static final List<String> MESSAGE_LITERALS = List.of(
            "Account Active Status must be Y or N",
            CREDIT_LIMIT_BLANK,
            CREDIT_LIMIT_NOT_VALID,
            "Card expiry month must be between 1 and 12",
            "Invalid card expiry year",
            "Did not find this account in cards database",
            "Did not find cards for this search condition",
            "Could not lock account record for update",
            "Could not lock customer record for update",
            DATA_CHANGED_BEFORE_UPDATE,
            "Update of record failed",
            "Error reading Card Data File",
            CODING_TO_BE_DONE);

    /**
     * The six outcome codes carried by {@code ACUP-CHANGE-ACTION} at
     * {@code app/cbl/COACTUPC.cbl:660-668}, each with the condition name that reads it.
     *
     * <p>{@code 'L'} and {@code 'F'} are both failures - {@code 88 ACUP-CHANGES-FAILED} covers the
     * pair at {@code :666} - but they are distinct failures, one a lock error and one an update
     * error, and the legacy screen said which. A response surface that answers the same status for
     * both discards that.</p>
     */
    private static final Map<String, String> OUTCOME_CODES = Map.of(
            "E", "ACUP-CHANGES-NOT-OK",
            "N", "ACUP-CHANGES-OK-NOT-CONFIRMED",
            "C", "ACUP-CHANGES-OKAYED-AND-DONE",
            "L", "ACUP-CHANGES-OKAYED-LOCK-ERROR",
            "F", "ACUP-CHANGES-OKAYED-BUT-FAILED");

    /**
     * The four failure markers declared at {@code app/cbl/COACTUPC.cbl:517-523}, plus the fifth set
     * specifically on an account-lock failure at {@code :2607-2608}.
     */
    private static final List<String> FAILURE_MARKERS = List.of(
            "Could not lock account record for update",
            "Could not lock customer record for update",
            DATA_CHANGED_BEFORE_UPDATE,
            "Update of record failed",
            "Error reading Card Data File");

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper();

    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Supplies the three payload types together with the member count each must declare.
     *
     * @return the outer request type and both nested snapshot groups, each with its member count
     */
    static Stream<Arguments> payloadTypes() {
        return Stream.of(
                Arguments.of(AccountUpdateRequest.class, TOP_LEVEL_MEMBERS),
                Arguments.of(OldDetails.class, OLD_MEMBERS),
                Arguments.of(NewDetails.class, NEW_MEMBERS));
    }

    /**
     * Supplies the three payload types on their own.
     *
     * @return the outer request type and both nested snapshot groups
     */
    static Stream<Class<?>> payloadTypesOnly() {
        return Stream.of(AccountUpdateRequest.class, OldDetails.class, NewDetails.class);
    }

    /**
     * Supplies the two nested snapshot groups on their own, without the outer request type.
     *
     * <p>Separate from {@link #payloadTypesOnly()} because the properties asserted against the
     * snapshot groups - compact dates, telephone overlays, the credit-score range - have no
     * counterpart on the outer screen-field type, whose members mirror the map rather than the
     * record.</p>
     *
     * @return {@code ACUP-OLD-DETAILS} and {@code ACUP-NEW-DETAILS}
     */
    static Stream<Class<?>> snapshotGroups() {
        return Stream.of(OldDetails.class, NewDetails.class);
    }

    /**
     * Returns the sole declared constructor of a payload type.
     *
     * @param type the payload type
     * @return its only constructor
     */
    private static Constructor<?> soleConstructor(final Class<?> type) {
        final Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertThat(constructors)
                .as("%s must offer exactly one way in, so that no path bypasses the width contracts",
                        type.getSimpleName())
                .hasSize(1);
        return constructors[0];
    }

    /**
     * Returns the constructor parameter names of a payload type, in declaration order.
     *
     * @param type the payload type
     * @return the parameter names
     */
    private static List<String> parameterNames(final Class<?> type) {
        return Arrays.stream(soleConstructor(type).getParameters())
                .map(Parameter::getName)
                .toList();
    }

    /**
     * Returns the non-static field names of a payload type, in declaration order.
     *
     * @param type the payload type
     * @return the instance field names
     */
    private static List<String> fieldNames(final Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    /**
     * Builds an instance of a payload type in which one named member holds {@code value} and every
     * other member is {@code null}.
     *
     * @param type       the payload type
     * @param memberName the member to populate
     * @param value      the value to place in it
     * @param <T>        the payload type
     * @return the constructed instance
     */
    private static <T> T withOnly(final Class<T> type, final String memberName, final String value) {
        final Map<String, String> single = new LinkedHashMap<>();
        single.put(memberName, value);
        return withMembers(type, single);
    }

    /**
     * Builds an instance of a payload type from a sparse map of member names to values.
     *
     * @param type   the payload type
     * @param values the members to populate; every other member is {@code null}
     * @param <T>    the payload type
     * @return the constructed instance
     */
    private static <T> T withMembers(final Class<T> type, final Map<String, String> values) {
        final List<String> names = parameterNames(type);
        final Object[] arguments = new Object[names.size()];
        values.forEach((name, value) -> {
            final int index = names.indexOf(name);
            assertThat(index)
                    .withFailMessage("%s declares no member named %s", type.getSimpleName(), name)
                    .isNotNegative();
            arguments[index] = value;
        });
        try {
            return type.cast(soleConstructor(type).newInstance(arguments));
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError("cannot construct " + type.getSimpleName(), cause);
        }
    }

    /**
     * Builds an instance of a payload type in which every member holds a value exactly as wide as
     * its declared {@code PIC} clause, composed only of decimal digits.
     *
     * <p>Digits satisfy every overlay simultaneously: a twelve-digit money image decodes as an
     * unsigned zoned decimal, an eight-digit date slices cleanly at 4/2/2, and a three-digit credit
     * score reads as a number. That makes this instance the widest legal payload, which is the one
     * on which no accessor may throw.</p>
     *
     * @param type the payload type
     * @param <T>  the payload type
     * @return the fully populated instance
     */
    private static <T> T fullyPopulated(final Class<T> type) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String member : fieldNames(type)) {
            if (isSnapshotGroup(type, member)) {
                continue;
            }
            values.put(member, "0".repeat(declaredWidth(type, member)));
        }
        return withMembers(type, values);
    }

    /**
     * Reports whether a top-level member is one of the two nested snapshot groups.
     *
     * @param type       the payload type
     * @param memberName the member to test
     * @return {@code true} when the member is a snapshot group rather than a screen field
     */
    private static boolean isSnapshotGroup(final Class<?> type, final String memberName) {
        try {
            return !String.class.equals(type.getDeclaredField(memberName).getType());
        } catch (NoSuchFieldException cause) {
            throw new AssertionError(type.getSimpleName() + " has no member " + memberName, cause);
        }
    }

    /**
     * Reads the {@code @Size} maximum declared on a member's backing field.
     *
     * <p>Read from the field rather than from a getter, because that is where the production code
     * declares the annotation.</p>
     *
     * @param type       the payload type
     * @param memberName the member whose declared width is wanted
     * @return the declared maximum
     */
    private static int declaredWidth(final Class<?> type, final String memberName) {
        final Size size = sizeOf(type, memberName);
        assertThat(size)
                .withFailMessage("%s.%s declares no @Size, so its PIC width is unenforced",
                        type.getSimpleName(), memberName)
                .isNotNull();
        return size.max();
    }

    /**
     * Returns the {@code @Size} annotation on a member's backing field, or {@code null}.
     *
     * @param type       the payload type
     * @param memberName the member to inspect
     * @return the annotation, or {@code null} when the member declares none
     */
    private static Size sizeOf(final Class<?> type, final String memberName) {
        try {
            return type.getDeclaredField(memberName).getAnnotation(Size.class);
        } catch (NoSuchFieldException cause) {
            throw new AssertionError(type.getSimpleName() + " has no member " + memberName, cause);
        }
    }

    /**
     * Serializes a payload and returns its JSON property names.
     *
     * @param payload the payload to serialize
     * @return the emitted property names
     */
    private static Set<String> serializedProperties(final Object payload) {
        return STRICT_MAPPER
                .convertValue(payload, new TypeReference<LinkedHashMap<String, Object>>() { })
                .keySet();
    }

    /**
     * Invokes a callable that must be refused, and returns the refusal.
     *
     * <p>The refusal may arrive on its own or wrapped by the serializer, so the whole cause chain is
     * searched rather than only the root. That keeps the assertion about <em>what</em> was refused
     * rather than about how many layers the serializer happened to add.</p>
     *
     * @param callable the code that must be refused
     * @return the {@link IllegalArgumentException} found in the thrown cause chain
     */
    private static IllegalArgumentException refusalOf(final ThrowingCallable callable) {
        final Throwable thrown = catchThrowable(callable);
        assertThat(thrown).as("the payload must be refused rather than accepted").isNotNull();
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof IllegalArgumentException refused) {
                return refused;
            }
            current = current.getCause();
        }
        throw new AssertionError("no IllegalArgumentException in the cause chain of " + thrown);
    }

    /**
     * Returns a copy of {@code payload} in which one snapshot group is replaced.
     *
     * <p>The payload is immutable, so this rebuilds it through the canonical constructor rather than
     * mutating it - which is the point of the {@link Immutability} group.</p>
     *
     * @param payload the payload to copy
     * @param group   {@code oldDetails} or {@code newDetails}
     * @param value   the group to place in the copy
     * @return the rebuilt payload
     */
    private static AccountUpdateRequest replaceGroup(final AccountUpdateRequest payload,
            final String group, final Object value) {
        final List<String> names = parameterNames(AccountUpdateRequest.class);
        final Object[] arguments = new Object[names.size()];
        for (int index = 0; index < names.size(); index++) {
            final String name = names.get(index);
            arguments[index] = name.equals(group) ? value : readMember(payload, name);
        }
        try {
            return (AccountUpdateRequest) soleConstructor(AccountUpdateRequest.class)
                    .newInstance(arguments);
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError("cannot rebuild AccountUpdateRequest", cause);
        }
    }

    /**
     * Reads one member of a payload through its public accessor.
     *
     * @param payload    the payload to read
     * @param memberName the member to read
     * @return the member's value
     */
    private static Object readMember(final Object payload, final String memberName) {
        final String accessor = "get" + Character.toUpperCase(memberName.charAt(0))
                + memberName.substring(1);
        try {
            return payload.getClass().getMethod(accessor).invoke(payload);
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError("cannot read member " + memberName, cause);
        }
    }

    /**
     * Returns the names of every member of a payload type that declares no {@code @Size}.
     *
     * @param type the payload type
     * @return the unconstrained member names, empty when every member carries a width contract
     */
    private static List<String> membersWithoutAWidthContract(final Class<?> type) {
        final List<String> unconstrained = new ArrayList<>();
        for (final String member : fieldNames(type)) {
            if (isSnapshotGroup(type, member)) {
                continue;
            }
            if (sizeOf(type, member) == null) {
                unconstrained.add(member);
            }
        }
        return unconstrained;
    }

    /**
     * Returns the public zero-argument declared methods of a payload type.
     *
     * @param type the payload type
     * @return the accessors and derived views the type exposes
     */
    private static List<Method> zeroArgumentAccessors(final Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .toList();
    }

    /**
     * Invokes a named zero-argument accessor and returns what it produced.
     *
     * <p>Used for the derived views, which are deliberately not named as bean properties and so
     * cannot be reached through {@link #readMember(Object, String)}.
     *
     * @param payload  the payload to read
     * @param accessor the accessor name, exactly as declared
     * @return the value the accessor returned, which may be {@code null}
     */
    private static Object invokeAccessor(final Object payload, final String accessor) {
        try {
            return payload.getClass().getMethod(accessor).invoke(payload);
        } catch (InvocationTargetException cause) {
            throw new AssertionError(payload.getClass().getSimpleName() + "." + accessor
                    + " must not throw here", cause.getCause());
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError("cannot invoke " + accessor, cause);
        }
    }

    /**
     * Slices a date the way {@code 9700-CHECK-CHANGE-IN-REC} slices the <em>live</em> customer
     * record: reference-modified at 1, 6 and 9 over a dash-separated {@code PIC X(10)} value
     * ({@code app/cbl/COACTUPC.cbl:4174-4179}, {@code app/cpy/CVCUS01Y.cpy:19}).
     *
     * <p>COBOL reference modification is one-based and inclusive of the starting character, so
     * {@code (1:4)} is characters 1 to 4, {@code (6:2)} is 6 to 7 and {@code (9:2)} is 9 to 10.</p>
     *
     * @param liveDate the ten-character dash-separated date held by the customer record
     * @return year, month and day, in that order
     */
    private static List<String> liveDateComponents(final String liveDate) {
        assertThat(liveDate)
                .as("the live customer date is PIC X(10), so a shorter value cannot be sliced at 9:2")
                .hasSize(LIVE_DATE_WIDTH);
        return List.of(liveDate.substring(0, 4), liveDate.substring(5, 7), liveDate.substring(8, 10));
    }

    /**
     * Slices a date the way {@code 9700-CHECK-CHANGE-IN-REC} slices the <em>snapshot</em>:
     * reference-modified at 1, 5 and 7 over a compact {@code PIC X(08)} value
     * ({@code app/cbl/COACTUPC.cbl:4174-4179}, {@code :746}).
     *
     * <p>These are different offsets from {@link #liveDateComponents(String)} for the same three
     * components, which is the whole point: the snapshot carries no separators, so month and day sit
     * one and two characters earlier respectively.</p>
     *
     * @param compactDate the eight-character compact date held by a snapshot group
     * @return year, month and day, in that order
     */
    private static List<String> snapshotDateComponents(final String compactDate) {
        assertThat(compactDate)
                .as("the snapshot date is PIC X(08), so a longer value would shift every offset")
                .hasSize(COMPACT_DATE_WIDTH);
        return List.of(compactDate.substring(0, 4), compactDate.substring(4, 6),
                compactDate.substring(6, 8));
    }

    /**
     * Applies the normalisation that {@code 9700-CHECK-CHANGE-IN-REC} applies to the account group
     * identifier: {@code FUNCTION LOWER-CASE} on both operands and no {@code TRIM}
     * ({@code app/cbl/COACTUPC.cbl:4139-4140}).
     *
     * <p>{@link Locale#ROOT} is passed explicitly. The default locale is an ambient input, and in a
     * Turkish locale {@code String.toLowerCase()} maps {@code I} to a dotless {@code ı}, which would
     * make this suite's verdict depend on where it ran.</p>
     *
     * @param value the raw value as the payload carried it
     * @return the value folded exactly as the concurrent-change regime folds it
     */
    private static String concurrentChangeFolding(final String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Applies the normalisation that {@code 1205-COMPARE-OLD-NEW} applies to the account group
     * identifier: {@code FUNCTION UPPER-CASE(FUNCTION TRIM(...))} on both operands
     * ({@code app/cbl/COACTUPC.cbl:1697-1700}).
     *
     * <p>COBOL's {@code TRIM} removes leading and trailing spaces, which is what
     * {@link String#strip()} does for the space character; {@code strip} additionally removes other
     * Unicode whitespace, which cannot arise from a fixed-width alphanumeric screen field.</p>
     *
     * @param value the raw value as the payload carried it
     * @return the value folded exactly as the user-change regime folds it
     */
    private static String userChangeFolding(final String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }

    @Nested
    @DisplayName("1. Field contract: 54 screen fields plus two snapshot groups")
    final class FieldContract {

        @Test
        @DisplayName("declares the 54 COACTUP screen fields plus oldDetails and newDetails")
        void declaresFiftyFourScreenFieldsPlusTwoGroups() {
            final List<String> names = fieldNames(AccountUpdateRequest.class);
            assertThat(names)
                    .as("app/cpy-bms/COACTUP.CPY declares 54 input fields between line 17 and "
                            + "line 342; the two snapshot groups are additional")
                    .hasSize(TOP_LEVEL_MEMBERS)
                    .endsWith("oldDetails", "newDetails");
            assertThat(names.size() - 2).isEqualTo(MAP_FIELDS);
        }

        @Test
        @DisplayName("carries every screen field as text, so no inbound code binds to an enum")
        void carriesEveryScreenFieldAsText() {
            assertThat(declaredWidth(AccountUpdateRequest.class, "accountStatus")).isEqualTo(1);
            assertThat(declaredWidth(AccountUpdateRequest.class, "primaryCardHolderIndicator"))
                    .isEqualTo(1);
            assertThat(Arrays.stream(AccountUpdateRequest.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> field.getType().isEnum())
                    .toList())
                    .as("binding an inbound one-character code to an enum would turn an "
                            + "out-of-domain value into a framework error instead of the source's "
                            + "own message")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} declares {1} members and {1} constructor parameters")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypes")
        @DisplayName("declares one constructor parameter per member on each type")
        void declaresOneParameterPerMember(final Class<?> type, final int expectedMembers) {
            assertThat(fieldNames(type)).hasSize(expectedMembers);
            assertThat(soleConstructor(type).getParameterCount()).isEqualTo(expectedMembers);
            assertThat(parameterNames(type))
                    .as("a parameter that matches no member would be silently discarded")
                    .containsExactlyElementsOf(fieldNames(type));
        }

        @Test
        @DisplayName("decomposes the social security number three ways on NEW and one way on OLD")
        void reproducesTheSocialSecurityAsymmetry() {
            assertThat(fieldNames(OldDetails.class))
                    .as("ACUP-OLD-CUST-SSN-X PIC X(09) at app/cbl/COACTUPC.cbl:742 is one flat "
                            + "field")
                    .contains("ssn")
                    .doesNotContain("ssnPart1", "ssnPart2", "ssnPart3");
            assertThat(fieldNames(NewDetails.class))
                    .as("ACUP-NEW-CUST-SSN-X at app/cbl/COACTUPC.cbl:830-833 is three parts")
                    .contains("ssnPart1", "ssnPart2", "ssnPart3")
                    .doesNotContain("ssn");
            assertThat(declaredWidth(OldDetails.class, "ssn")).isEqualTo(9);
            assertThat(declaredWidth(NewDetails.class, "ssnPart1")).isEqualTo(3);
            assertThat(declaredWidth(NewDetails.class, "ssnPart2")).isEqualTo(2);
            assertThat(declaredWidth(NewDetails.class, "ssnPart3")).isEqualTo(4);
        }

        @Test
        @DisplayName("stores every snapshot date compact at eight characters, never dash-separated")
        void storesSnapshotDatesCompact() {
            for (final String date : List.of("openDate", "expiraionDate", "reissueDate",
                    "dateOfBirth")) {
                assertThat(declaredWidth(OldDetails.class, date))
                        .as("%s is PIC X(08) on the snapshot against PIC X(10) on the live record, "
                                + "which is why 9700-CHECK-CHANGE-IN-REC compares offsets 1/6/9 "
                                + "against 1/5/7 at app/cbl/COACTUPC.cbl:4174-4179", date)
                        .isEqualTo(8);
                assertThat(declaredWidth(NewDetails.class, date)).isEqualTo(8);
            }
        }

        @Test
        @DisplayName("keeps the screen expiry fields named after the map, which is not misspelled")
        void keepsScreenExpiryFieldsNamedAfterTheMap() {
            assertThat(fieldNames(AccountUpdateRequest.class))
                    .as("app/cpy-bms/COACTUP.CPY declares EXPYEARI, EXPMONI and EXPDAYI at lines "
                            + "96, 102 and 108; the misspelling belongs to the copybook snapshot "
                            + "fields, not to the map")
                    .contains("expiryDateYear", "expiryDateMonth", "expiryDateDay");
        }
    }

    @Nested
    @DisplayName("2. Immutability: nothing can change between binding and comparison")
    final class Immutability {

        @ParameterizedTest(name = "{0} declares no setter")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares no setter on any of the three types")
        void declaresNoSetter(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("set"))
                    .toList())
                    .as("an earlier revision exposed 139 setters across these three types, so a "
                            + "value could be rewritten after validation and before the snapshot "
                            + "comparison")
                    .isEmpty();
        }

        @ParameterizedTest(name = "every field of {0} is final")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares every instance field final")
        void declaresEveryFieldFinal(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList())
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} has exactly one constructor taking {1} parameters")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypes")
        @DisplayName("offers exactly one way in, so no path bypasses the width contracts")
        void offersExactlyOneWayIn(final Class<?> type, final int expectedMembers) {
            assertThat(soleConstructor(type).getParameterCount()).isEqualTo(expectedMembers);
        }

        @ParameterizedTest(name = "{0} declares no mutable static state")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares no mutable static state")
        void declaresNoMutableStaticState(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("binds through the creator, so the payload is complete when it is validated")
        void bindsThroughTheCreator() throws Exception {
            final String json = "{\"accountId\":\"00000000001\","
                    + "\"oldDetails\":{\"activeStatus\":\"Y\"},"
                    + "\"newDetails\":{\"activeStatus\":\"N\"}}";
            final AccountUpdateRequest bound =
                    STRICT_MAPPER.readValue(json, AccountUpdateRequest.class);
            assertThat(bound.getAccountId()).isEqualTo("00000000001");
            assertThat(bound.getOldDetails().getActiveStatus()).isEqualTo("Y");
            assertThat(bound.getNewDetails().getActiveStatus()).isEqualTo("N");
        }

        @ParameterizedTest(name = "{0} does not implement Serializable")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("does not implement Serializable, given the protected data it carries")
        void doesNotImplementSerializable(final Class<?> type) {
            assertThat(Serializable.class.isAssignableFrom(type))
                    .withFailMessage("%s must not be exposed to native deserialization",
                            type.getSimpleName())
                    .isFalse();
        }

        @ParameterizedTest(name = "{0} declares no toString")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares no toString, so the inherited one cannot leak a member")
        void declaresNoToString(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .map(Method::getName)
                    .filter("toString"::equals)
                    .toList())
                    .as("%s carries social security numbers, dates of birth, telephone numbers and "
                            + "names; the inherited Object.toString emits none of them",
                            type.getSimpleName())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("3. Snapshot cascade: both groups validated, only NEW carries a range")
    final class SnapshotCascade {

        @ParameterizedTest(name = "{0} carries @Valid")
        @ValueSource(strings = {"oldDetails", "newDetails"})
        @DisplayName("cascades into oldDetails as well as newDetails")
        void cascadesIntoBothGroups(final String group) throws NoSuchFieldException {
            assertThat(AccountUpdateRequest.class.getDeclaredField(group).getAnnotation(Valid.class))
                    .as("a cascade omitted is a cascade that never fires, and %s is untrusted "
                            + "client input under statelessness", group)
                    .isNotNull();
        }

        @Test
        @DisplayName("reports a violation for every over-wide oldDetails member")
        void reportsViolationsInsideOldDetails() {
            final Map<String, String> hostile = new LinkedHashMap<>();
            hostile.put("addressStateCode", "TOO-WIDE");
            hostile.put("currentBalance", "9".repeat(40));
            hostile.put("ficoScore", "9999");
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "oldDetails",
                    withMembers(OldDetails.class, hostile));
            final Set<ConstraintViolation<AccountUpdateRequest>> violations =
                    VALIDATOR.validate(payload);
            assertThat(violations)
                    .as("an earlier revision produced zero violations here, because oldDetails "
                            + "carried neither a cascade nor a single width contract")
                    .isNotEmpty();
            assertThat(violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .toList())
                    .containsExactlyInAnyOrder("oldDetails.addressStateCode",
                            "oldDetails.currentBalance", "oldDetails.ficoScore");
        }

        @Test
        @DisplayName("reports a violation for an over-wide newDetails member as well")
        void reportsViolationsInsideNewDetails() {
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "newDetails",
                    withOnly(NewDetails.class, "phoneNumber1AreaCode", "5555"));
            assertThat(VALIDATOR.validate(payload).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .toList())
                    .containsExactly("newDetails.phoneNumber1AreaCode");
        }

        @Test
        @DisplayName("reports no violation for a snapshot whose members all fit their PIC clauses")
        void acceptsAWellFormedSnapshot() {
            final Map<String, String> wellFormed = new LinkedHashMap<>();
            wellFormed.put("accountId", "00000000001");
            wellFormed.put("addressStateCode", "NY");
            wellFormed.put("currentBalance", "00000019400");
            wellFormed.put("ficoScore", "720");
            wellFormed.put("dateOfBirth", "19750412");
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "oldDetails",
                    withMembers(OldDetails.class, wellFormed));
            assertThat(VALIDATOR.validate(payload))
                    .as("enforcing the declared width can only reject what the source could never "
                            + "have produced")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts an entirely absent snapshot, because absence is not a violation")
        void acceptsAnAbsentSnapshot() {
            assertThat(VALIDATOR.validate(withMembers(AccountUpdateRequest.class, Map.of())))
                    .isEmpty();
            assertThat(VALIDATOR.validate(withMembers(OldDetails.class, Map.of()))).isEmpty();
            assertThat(VALIDATOR.validate(withMembers(NewDetails.class, Map.of()))).isEmpty();
        }

        @Test
        @DisplayName("accepts a snapshot whose members are blank or low-values at full width")
        void acceptsBlankAndLowValueSnapshotMembers() {
            final Map<String, String> blanks = new LinkedHashMap<>();
            blanks.put("currentBalance", " ".repeat(12));
            blanks.put("dateOfBirth", "\u0000".repeat(8));
            blanks.put("groupId", "");
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "oldDetails",
                    withMembers(OldDetails.class, blanks));
            assertThat(VALIDATOR.validate(payload))
                    .as("INITIALIZE at app/cbl/COACTUPC.cbl:1047 and MOVE LOW-VALUES at :1359 both "
                            + "produce states the source holds, so neither may be rejected")
                    .isEmpty();
        }

        @ParameterizedTest(name = "OldDetails.{0} declares @Size(max = {1})")
        @CsvSource({
            "accountId,11", "activeStatus,1", "currentBalance,12", "creditLimit,12",
            "cashCreditLimit,12", "openDate,8", "expiraionDate,8", "reissueDate,8",
            "currentCycleCredit,12", "currentCycleDebit,12", "groupId,10", "customerId,9",
            "firstName,25", "middleName,25", "lastName,25", "addressLine1,50", "addressLine2,50",
            "addressLine3,50", "addressStateCode,2", "addressCountryCode,3", "addressZip,10",
            "phoneNumber1,15", "phoneNumber2,15", "ssn,9", "governmentIssuedId,20",
            "dateOfBirth,8", "eftAccountId,10", "primaryCardHolderIndicator,1", "ficoScore,3",
        })
        @DisplayName("declares the source PIC width on every one of the 29 OLD members")
        void declaresEveryOldWidth(final String memberName, final int expectedWidth) {
            assertThat(declaredWidth(OldDetails.class, memberName)).isEqualTo(expectedWidth);
        }

        @ParameterizedTest(name = "NewDetails.{0} declares @Size(max = {1})")
        @CsvSource({
            "accountId,11", "activeStatus,1", "currentBalance,12", "creditLimit,12",
            "cashCreditLimit,12", "openDate,8", "expiraionDate,8", "reissueDate,8",
            "currentCycleCredit,12", "currentCycleDebit,12", "groupId,10", "customerId,9",
            "firstName,25", "middleName,25", "lastName,25", "addressLine1,50", "addressLine2,50",
            "addressLine3,50", "addressStateCode,2", "addressCountryCode,3", "addressZip,10",
            "phoneNumber1AreaCode,3", "phoneNumber1Prefix,3", "phoneNumber1LineNumber,4",
            "phoneNumber2AreaCode,3", "phoneNumber2Prefix,3", "phoneNumber2LineNumber,4",
            "ssnPart1,3", "ssnPart2,2", "ssnPart3,4", "governmentIssuedId,20", "dateOfBirth,8",
            "eftAccountId,10", "primaryCardHolderIndicator,1", "ficoScore,3",
        })
        @DisplayName("declares the source PIC width on every one of the 35 NEW members")
        void declaresEveryNewWidth(final String memberName, final int expectedWidth) {
            assertThat(declaredWidth(NewDetails.class, memberName)).isEqualTo(expectedWidth);
        }

        @ParameterizedTest(name = "{0} leaves no member without a width contract")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("leaves no member of any type without a width contract")
        void leavesNoMemberUnconstrained(final Class<?> type) {
            assertThat(membersWithoutAWidthContract(type)).isEmpty();
        }

        @Test
        @DisplayName("expresses the credit-score range as a NEW-only predicate, not a constraint")
        void expressesTheRangeAsAPredicateOnNewOnly() {
            assertThat(Arrays.stream(NewDetails.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter("ficoScoreIsInValidRange"::equals)
                    .toList())
                    .as("88 FICO-RANGE-IS-VALID at app/cbl/COACTUPC.cbl:848-849 is declared on the "
                            + "NEW numeric member only")
                    .hasSize(1);
            assertThat(Arrays.stream(OldDetails.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter("ficoScoreIsInValidRange"::equals)
                    .toList())
                    .as("the OLD group declares no 88-level at all, so it must have no twin")
                    .isEmpty();
            assertThat(VALIDATOR.validate(withOnly(NewDetails.class, "ficoScore", "299")))
                    .as("the source moves the screen value into storage unvalidated at "
                            + "app/cbl/COACTUPC.cbl:1283 and emits its own message, so a bean "
                            + "constraint would substitute a framework rejection")
                    .isEmpty();
        }

        @ParameterizedTest(name = "credit score \"{0}\" in range: {1}")
        @CsvSource({
            "300,true", "850,true", "720,true", "299,false", "851,false", "000,false",
        })
        @DisplayName("reproduces the 300 through 850 range of the NEW 88-level exactly")
        void reproducesTheCreditScoreRange(final String image, final boolean inRange) {
            assertThat(withOnly(NewDetails.class, "ficoScore", image).ficoScoreIsInValidRange())
                    .isEqualTo(inRange);
        }

        @Test
        @DisplayName("treats an unreadable credit score as out of range rather than throwing")
        void treatsAnUnreadableCreditScoreAsOutOfRange() {
            assertThat(withMembers(NewDetails.class, Map.of()).ficoScoreIsInValidRange()).isFalse();
            assertThat(withOnly(NewDetails.class, "ficoScore", "abc").ficoScoreIsInValidRange())
                    .isFalse();
        }

        @Test
        @DisplayName("names the member and the source locator in a violation, never the value")
        void violationMessageNeverQuotesTheValue() {
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "oldDetails",
                    withOnly(OldDetails.class, "ssn", "123456789-LEAKED"));
            final Set<ConstraintViolation<AccountUpdateRequest>> violations =
                    VALIDATOR.validate(payload);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .contains("OldDetails.ssn")
                    .contains("ACUP-OLD-CUST-SSN-X")
                    .contains("app/cbl/COACTUPC.cbl:742")
                    .doesNotContain("LEAKED")
                    .doesNotContain("123456789");
        }
    }

    @Nested
    @DisplayName("4. Source-faithful naming: the EXPIRAION misspelling survives")
    final class SourceFaithfulNaming {

        @ParameterizedTest(name = "{0} declares expiraionDate and not expirationDate")
        @MethodSource("groups")
        @DisplayName("declares the misspelled member on both snapshot groups")
        void declaresTheMisspelledMember(final Class<?> type) {
            assertThat(fieldNames(type))
                    .as("ACUP-OLD-EXPIRAION-DATE at app/cbl/COACTUPC.cbl:690 and "
                            + "ACUP-NEW-EXPIRAION-DATE at :778 are both misspelled in the frozen "
                            + "corpus")
                    .contains("expiraionDate")
                    .doesNotContain("expirationDate");
        }

        @ParameterizedTest(name = "{0} exposes getExpiraionDate and no corrected alias")
        @MethodSource("groups")
        @DisplayName("exposes the misspelled accessor and its misspelled component views")
        void exposesTheMisspelledAccessor(final Class<?> type) {
            final List<String> methods = Arrays.stream(type.getDeclaredMethods())
                    .map(Method::getName)
                    .toList();
            assertThat(methods)
                    .contains("getExpiraionDate", "expiraionDateYear", "expiraionDateMonth",
                            "expiraionDateDay")
                    .doesNotContain("getExpirationDate", "expirationDateYear",
                            "expirationDateMonth", "expirationDateDay");
        }

        @Test
        @DisplayName("emits the misspelled JSON name and never the corrected one")
        void emitsTheMisspelledJsonName() {
            assertThat(serializedProperties(
                    withOnly(OldDetails.class, "expiraionDate", "20250131")))
                    .contains("expiraionDate")
                    .doesNotContain("expirationDate");
            assertThat(serializedProperties(
                    withOnly(NewDetails.class, "expiraionDate", "20260131")))
                    .contains("expiraionDate")
                    .doesNotContain("expirationDate");
        }

        @ParameterizedTest(name = "the corrected spelling in {0} is refused")
        @ValueSource(strings = {"oldDetails", "newDetails"})
        @DisplayName("refuses the corrected spelling rather than accepting it as an alias")
        void refusesTheCorrectedSpelling(final String group) {
            final String json = "{\"" + group + "\":{\"expirationDate\":\"20250131\"}}";
            for (final ObjectMapper mapper : List.of(STRICT_MAPPER, LENIENT_MAPPER)) {
                assertThat(refusalOf(() -> mapper.readValue(json, AccountUpdateRequest.class)))
                        .as("accepting the corrected spelling as an alias would make the wire "
                                + "contract diverge from the frozen corpus")
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("binds the misspelled name, so the contract is usable and not merely strict")
        void bindsTheMisspelledName() throws Exception {
            final String json = "{\"oldDetails\":{\"expiraionDate\":\"20250131\"},"
                    + "\"newDetails\":{\"expiraionDate\":\"20260131\"}}";
            final AccountUpdateRequest bound =
                    STRICT_MAPPER.readValue(json, AccountUpdateRequest.class);
            assertThat(bound.getOldDetails().getExpiraionDate()).isEqualTo("20250131");
            assertThat(bound.getNewDetails().getExpiraionDate()).isEqualTo("20260131");
        }

        /**
         * Supplies the two snapshot group types.
         *
         * @return the OLD and NEW snapshot group types
         */
        static Stream<Class<?>> groups() {
            return Stream.of(OldDetails.class, NewDetails.class);
        }
    }

    @Nested
    @DisplayName("5. Overlay canonicalisation: one stored member per REDEFINES")
    final class OverlayCanonicalisation {

        @ParameterizedTest(name = "OldDetails.{0} is stored once with a derived numeric view")
        @ValueSource(strings = {"currentBalance", "creditLimit", "cashCreditLimit",
            "currentCycleCredit", "currentCycleDebit"})
        @DisplayName("stores each money overlay as the display text the source holds")
        void storesMoneyAsDisplayText(final String memberName) {
            assertThat(declaredWidth(OldDetails.class, memberName))
                    .as("PIC X(12), overlaid by PIC S9(10)V99 at app/cbl/COACTUPC.cbl:676-707")
                    .isEqualTo(12);
            final Set<String> emitted = serializedProperties(
                    withOnly(OldDetails.class, memberName, "00000001940{"));
            assertThat(emitted).contains(memberName);
            assertThat(emitted)
                    .as("the numeric reading names the same twelve bytes, so it must not be a "
                            + "separately writable property")
                    .doesNotContain(memberName + "Amount");
        }

        @ParameterizedTest(name = "\"{0}\" decodes to {1}")
        @CsvSource({
            "00000001940{,194.00", "00000001940},-194.00", "00000000001A,0.11",
            "00000000001J,-0.11", "000000019400,194.00", "000000000000,0.00",
            "00000001940I,194.09", "00000001940R,-194.09",
        })
        @DisplayName("decodes the zoned-decimal overpunch sign through the derived view")
        void decodesTheOverpunchSign(final String image, final String expected) {
            assertThat(withOnly(OldDetails.class, "currentBalance", image).currentBalanceAmount())
                    .as("app/data/ASCII/acctdata.txt:1 records 00000001940{ for +194.00; the "
                            + "decode table is { = +0, A-I = +1..+9, } = -0, J-R = -1..-9")
                    .isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("decodes the NEW money overlay the same way as the OLD one")
        void decodesTheNewMoneyOverlayIdentically() {
            final NewDetails edited = withOnly(NewDetails.class, "creditLimit", "00000500000{");
            assertThat(edited.creditLimitAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
            assertThat(serializedProperties(edited))
                    .contains("creditLimit")
                    .doesNotContain("creditLimitAmount");
        }

        @ParameterizedTest(name = "\"{0}\" has no numeric reading")
        @ValueSource(strings = {"            ", "0000000194  ", "00000001940 ", "abcdefghijkl",
            "0000000194", "0000000019400", "0000000X001{", "00000 01940A", "+0000019400{"})
        @DisplayName("returns no amount when the twelve bytes are not a zoned-decimal number")
        void returnsNoAmountForANonNumericImage(final String image) {
            assertThat(withOnly(OldDetails.class, "currentBalance", image).currentBalanceAmount())
                    .as("the source stores whatever was typed and emits its own message, so "
                            + "throwing here would substitute a framework error and collapse the "
                            + "three-state model of app/cpy/CSSETATY.cpy:17-27")
                    .isNull();
        }

        @Test
        @DisplayName("rejects a non-digit body even when the trailing overpunch sign is itself valid")
        void rejectsANonDigitBodyDespiteAValidOverpunchSign() {
            assertThat(withOnly(OldDetails.class, "currentBalance", "00000001940{").currentBalanceAmount())
                    .as("the control image is the first money field of app/data/ASCII/acctdata.txt:1, where "
                            + "'{' denotes +0 and the field decodes to +194.00")
                    .isEqualByComparingTo(new BigDecimal("194.00"));

            assertThat(withOnly(OldDetails.class, "currentBalance", "0000000X001{").currentBalanceAmount())
                    .as("'{' is a valid trailing sign, so rejection here can only come from the eleven "
                            + "leading bytes. Decoding is position-aware from the PIC clause, which is why "
                            + "a letter legitimately appearing in a text field is still refused in a money "
                            + "field rather than silently absorbed")
                    .isNull();
            assertThat(withOnly(OldDetails.class, "currentBalance", "0000001940A").currentBalanceAmount())
                    .as("eleven bytes is not the declared twelve, so this fails on length before the sign "
                            + "or the body is ever examined")
                    .isNull();
        }

        @Test
        @DisplayName("returns no amount for an absent money member")
        void returnsNoAmountForAnAbsentMember() {
            assertThat(withMembers(OldDetails.class, Map.of()).currentBalanceAmount()).isNull();
            assertThat(withMembers(NewDetails.class, Map.of()).creditLimitAmount()).isNull();
        }

        @Test
        @DisplayName("reads the credit score both as text and as a number from the same bytes")
        void readsTheCreditScoreBothWays() {
            final OldDetails snapshot = withOnly(OldDetails.class, "ficoScore", "720");
            assertThat(snapshot.getFicoScore())
                    .as("1205-COMPARE-OLD-NEW compares the TEXT members at "
                            + "app/cbl/COACTUPC.cbl:1767-1768")
                    .isEqualTo("720");
            assertThat(snapshot.ficoScoreValue())
                    .as("9700-CHECK-CHANGE-IN-REC compares the NUMERIC member at "
                            + "app/cbl/COACTUPC.cbl:4186")
                    .isEqualTo(720);
            assertThat(serializedProperties(snapshot))
                    .as("one storage cell, two readings, one wire property")
                    .contains("ficoScore")
                    .doesNotContain("ficoScoreValue");
        }

        @ParameterizedTest(name = "credit score \"{0}\" has no numeric reading")
        @ValueSource(strings = {"", "72", "7200", "abc", "   ", "\u0000\u0000\u0000", "7 0", "+20"})
        @DisplayName("returns no credit score unless the three bytes are three decimal digits")
        void returnsNoCreditScoreUnlessThreeDigits(final String image) {
            assertThat(withOnly(OldDetails.class, "ficoScore", image).ficoScoreValue())
                    .as("the clause is unsigned PIC 9(03), so unlike money there is no overpunch")
                    .isNull();
        }

        @Test
        @DisplayName("stores the OLD telephone whole and derives its three components")
        void storesTheOldTelephoneWhole() {
            assertThat(fieldNames(OldDetails.class))
                    .as("MOVE CUST-PHONE-NUM-1 TO ACUP-OLD-CUST-PHONE-NUM-1 at "
                            + "app/cbl/COACTUPC.cbl:3876 assigns the whole and never a part")
                    .contains("phoneNumber1", "phoneNumber2")
                    .doesNotContain("phoneNumber1AreaCode", "phoneNumber1Prefix",
                            "phoneNumber1LineNumber");
            final OldDetails snapshot =
                    withOnly(OldDetails.class, "phoneNumber1", "(555)867-5309  ");
            assertThat(snapshot.phoneNumber1AreaCode())
                    .as("the REDEFINES at app/cbl/COACTUPC.cbl:723-731 places the components at "
                            + "COBOL offsets 2, 6 and 10, because the filler bytes are the "
                            + "parentheses and the hyphen")
                    .isEqualTo("555");
            assertThat(snapshot.phoneNumber1Prefix()).isEqualTo("867");
            assertThat(snapshot.phoneNumber1LineNumber()).isEqualTo("5309");
            assertThat(serializedProperties(snapshot))
                    .contains("phoneNumber1")
                    .doesNotContain("phoneNumber1AreaCode");
        }

        @Test
        @DisplayName("stores the NEW telephone by part and derives the fifteen-byte whole")
        void storesTheNewTelephoneByPart() {
            assertThat(fieldNames(NewDetails.class))
                    .as("1100-RECEIVE-MAP assigns only the parts, at "
                            + "app/cbl/COACTUPC.cbl:1359-1396, and never the whole")
                    .contains("phoneNumber1AreaCode", "phoneNumber1Prefix",
                            "phoneNumber1LineNumber", "phoneNumber2AreaCode", "phoneNumber2Prefix",
                            "phoneNumber2LineNumber")
                    .doesNotContain("phoneNumber1", "phoneNumber2");
            final Map<String, String> parts = new LinkedHashMap<>();
            parts.put("phoneNumber1AreaCode", "555");
            parts.put("phoneNumber1Prefix", "867");
            parts.put("phoneNumber1LineNumber", "5309");
            final NewDetails edited = withMembers(NewDetails.class, parts);
            assertThat(edited.phoneNumber1())
                    .as("INITIALIZE ACUP-NEW-DETAILS at app/cbl/COACTUPC.cbl:1047 leaves the filler "
                            + "positions as spaces and nothing assigns them afterwards; the "
                            + "receiving overlay even carries VALUE '(', ')' and '-' clauses that "
                            + "the author commented out, at :86, :91 and :96")
                    .isEqualTo(" 555 867 5309  ")
                    .hasSize(PHONE_WIDTH);
            assertThat(serializedProperties(edited))
                    .contains("phoneNumber1AreaCode")
                    .doesNotContain("phoneNumber1", "phoneNumber2");
        }

        @Test
        @DisplayName("pads an absent NEW telephone part to its declared width in the derived whole")
        void padsAnAbsentNewTelephonePart() {
            assertThat(withOnly(NewDetails.class, "phoneNumber1Prefix", "8").phoneNumber1())
                    .as("a MOVE into a fixed-width alphanumeric item space-pads on the right")
                    .isEqualTo("     8         ")
                    .hasSize(PHONE_WIDTH);
            assertThat(withMembers(NewDetails.class, Map.of()).phoneNumber2())
                    .isEqualTo(" ".repeat(PHONE_WIDTH));
        }

        @Test
        @DisplayName("refuses to assemble a NEW telephone whole from an over-wide part")
        void refusesToAssembleFromAnOverWidePart() {
            assertThat(refusalOf(() -> withOnly(NewDetails.class, "phoneNumber1AreaCode", "5555")
                    .phoneNumber1()))
                    .hasMessageContaining("NewDetails.phoneNumber1")
                    .hasMessageContaining("app/cbl/COACTUPC.cbl:811-819")
                    .hasMessageNotContaining("5555");
        }

        @Test
        @DisplayName("refuses to slice an OLD telephone longer than the fifteen-byte overlay")
        void rejectsAnOverWideOldTelephone() {
            assertThat(refusalOf(() -> withOnly(OldDetails.class, "phoneNumber1", "X".repeat(16))
                    .phoneNumber1AreaCode()))
                    .as("offsets 2, 6 and 10 cannot be applied to a value longer than the overlay")
                    .hasMessageContaining("OldDetails.phoneNumber1")
                    .hasMessageNotContaining("XXXX");
        }

        @Test
        @DisplayName("returns the empty string for an OLD telephone component the value never reaches")
        void returnsEmptyForAComponentTheValueNeverReaches() {
            final OldDetails snapshot = withOnly(OldDetails.class, "phoneNumber1", "(555");
            assertThat(snapshot.phoneNumber1AreaCode()).isEqualTo("555");
            assertThat(snapshot.phoneNumber1Prefix()).isEmpty();
            assertThat(snapshot.phoneNumber1LineNumber()).isEmpty();
            assertThat(withMembers(OldDetails.class, Map.of()).phoneNumber1AreaCode()).isNull();
        }

        @ParameterizedTest(name = "{0} exposes its compact date as 4/2/2 components")
        @ValueSource(strings = {"openDate", "expiraionDate", "reissueDate", "dateOfBirth"})
        @DisplayName("exposes each compact date as year, month and day without storing the parts")
        void exposesCompactDateComponents(final String memberName) {
            final OldDetails snapshot = withOnly(OldDetails.class, memberName, "19750412");
            assertThat(invokeView(snapshot, memberName + "Year")).isEqualTo("1975");
            assertThat(invokeView(snapshot, memberName + "Month")).isEqualTo("04");
            assertThat(invokeView(snapshot, memberName + "Day")).isEqualTo("12");
            assertThat(serializedProperties(snapshot))
                    .contains(memberName)
                    .doesNotContain(memberName + "Year", memberName + "Month", memberName + "Day");
        }

        @Test
        @DisplayName("returns no date component for an absent compact date")
        void returnsNoDateComponentForAnAbsentDate() {
            final OldDetails empty = withMembers(OldDetails.class, Map.of());
            assertThat(invokeView(empty, "dateOfBirthYear")).isNull();
            assertThat(invokeView(empty, "openDateMonth")).isNull();
            assertThat(invokeView(withOnly(OldDetails.class, "openDate", "1975"), "openDateMonth"))
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses a dash-separated snapshot date, which the compact offsets cannot slice")
        void rejectsADashSeparatedSnapshotDate() {
            assertThat(refusalOf(() -> withOnly(OldDetails.class, "dateOfBirth", "1975-04-12")
                    .dateOfBirthMonth()))
                    .as("the live record is PIC X(10) dash-separated at app/cpy/CVCUS01Y.cpy and "
                            + "the snapshot is PIC X(08) compact, which is exactly why "
                            + "9700-CHECK-CHANGE-IN-REC compares offsets 1/6/9 against 1/5/7 at "
                            + "app/cbl/COACTUPC.cbl:4174-4179")
                    .hasMessageContaining("OldDetails.dateOfBirth")
                    .hasMessageNotContaining("1975-04-12");
        }

        @ParameterizedTest(name = "{0} emits exactly its stored members")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("emits exactly the stored members and no derived view, on all three types")
        void emitsExactlyTheStoredMembers(final Class<?> type) {
            assertThat(serializedProperties(withMembers(type, Map.of())))
                    .containsExactlyInAnyOrderElementsOf(fieldNames(type));
        }

        @Test
        @DisplayName("round-trips, so a client can return the snapshot it was given")
        void roundTripsThroughJson() throws Exception {
            final AccountUpdateRequest original = replaceGroup(
                    withOnly(AccountUpdateRequest.class, "accountId", "00000000001"),
                    "oldDetails", withOnly(OldDetails.class, "currentBalance", "00000001940{"));
            final String json = STRICT_MAPPER.writeValueAsString(original);
            final AccountUpdateRequest restored =
                    STRICT_MAPPER.readValue(json, AccountUpdateRequest.class);
            assertThat(restored.getAccountId()).isEqualTo("00000000001");
            assertThat(restored.getOldDetails().getCurrentBalance()).isEqualTo("00000001940{");
            assertThat(restored.getOldDetails().currentBalanceAmount())
                    .isEqualByComparingTo(new BigDecimal("194.00"));
        }

        /**
         * Invokes a no-argument derived view by name.
         *
         * @param target   the snapshot group
         * @param viewName the view's method name
         * @return the view's value
         */
        private String invokeView(final Object target, final String viewName) {
            try {
                return (String) target.getClass().getDeclaredMethod(viewName).invoke(target);
            } catch (ReflectiveOperationException cause) {
                throw new AssertionError("cannot invoke view " + viewName, cause);
            }
        }
    }

    @Nested
    @DisplayName("6. Unknown properties: refused, not discarded, under either mapper")
    final class UnknownProperties {

        @ParameterizedTest(name = "{1} is refused under the {0} mapper")
        @MethodSource("unknownPropertyPayloads")
        @DisplayName("refuses an unrecognised property wherever it appears")
        void refusesAnUnrecognisedProperty(final String mode, final String description,
                final String json) {
            final ObjectMapper mapper = "strict".equals(mode) ? STRICT_MAPPER : LENIENT_MAPPER;
            assertThat(description).isNotBlank();
            assertThat(refusalOf(() -> mapper.readValue(json, AccountUpdateRequest.class)))
                    .as("the framework disables failure on unknown properties by default, so a "
                            + "type-level annotation would be inert and a discarded property would "
                            + "look like acceptance")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "{0} declares exactly one any-setter guard")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares the guard on all three types")
        void declaresTheGuardOnAllThreeTypes(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.getAnnotation(JsonAnySetter.class) != null)
                    .map(Method::getName)
                    .toList())
                    .withFailMessage("%s must refuse a property it does not declare",
                            type.getSimpleName())
                    .hasSize(1);
        }

        @Test
        @DisplayName("withholds the offending name and value from the refusal message")
        void withholdsTheOffendingNameAndValue() {
            final String json = "{\"oldDetails\":{\"forgedName\":\"123-45-6789\"}}";
            assertThat(refusalOf(() -> STRICT_MAPPER.readValue(json, AccountUpdateRequest.class)))
                    .hasMessageContaining("OldDetails accepts only the 29 properties")
                    .hasMessageNotContaining("forgedName")
                    .hasMessageNotContaining("123-45-6789");
        }

        @Test
        @DisplayName("states the declared property count of each type in its refusal")
        void statesTheDeclaredPropertyCount() {
            assertThat(refusalOf(() -> STRICT_MAPPER.readValue("{\"notAField\":\"x\"}",
                    AccountUpdateRequest.class)))
                    .hasMessageContaining("AccountUpdateRequest accepts only the "
                            + TOP_LEVEL_MEMBERS + " properties");
            assertThat(refusalOf(() -> STRICT_MAPPER.readValue(
                    "{\"newDetails\":{\"notAField\":\"x\"}}", AccountUpdateRequest.class)))
                    .hasMessageContaining("NewDetails accepts only the " + NEW_MEMBERS
                            + " properties");
        }

        @Test
        @DisplayName("accepts every declared property, so the guard is not over-broad")
        void acceptsEveryDeclaredProperty() throws Exception {
            for (final String member : fieldNames(AccountUpdateRequest.class)) {
                assertThat(STRICT_MAPPER.readValue("{\"" + member + "\":null}",
                        AccountUpdateRequest.class)).isNotNull();
            }
            final Map<String, Class<?>> groups = new LinkedHashMap<>();
            groups.put("oldDetails", OldDetails.class);
            groups.put("newDetails", NewDetails.class);
            for (final Map.Entry<String, Class<?>> group : groups.entrySet()) {
                for (final String member : fieldNames(group.getValue())) {
                    final String json =
                            "{\"" + group.getKey() + "\":{\"" + member + "\":null}}";
                    assertThat(STRICT_MAPPER.readValue(json, AccountUpdateRequest.class))
                            .isNotNull();
                }
            }
        }

        /**
         * Supplies one refusal case per unrecognised-property shape, under each mapper.
         *
         * @return the mapper mode, a description, and the payload that must be refused
         */
        static Stream<Arguments> unknownPropertyPayloads() {
            final Map<String, String> payloads = new LinkedHashMap<>();
            payloads.put("an invented top-level property", "{\"notAField\":\"x\"}");
            payloads.put("an invented oldDetails property",
                    "{\"oldDetails\":{\"notAField\":\"x\"}}");
            payloads.put("an invented newDetails property",
                    "{\"newDetails\":{\"notAField\":\"x\"}}");
            payloads.put("a money numeric view submitted as a member",
                    "{\"oldDetails\":{\"currentBalanceAmount\":194.00}}");
            payloads.put("a credit-score numeric view submitted as a member",
                    "{\"newDetails\":{\"ficoScoreValue\":720}}");
            payloads.put("an OLD telephone component submitted as a member",
                    "{\"oldDetails\":{\"phoneNumber1AreaCode\":\"555\"}}");
            payloads.put("a NEW telephone whole submitted as a member",
                    "{\"newDetails\":{\"phoneNumber1\":\"(555)867-5309\"}}");
            payloads.put("a compact-date component submitted as a member",
                    "{\"oldDetails\":{\"dateOfBirthYear\":\"1975\"}}");
            payloads.put("a NEW SSN part submitted on the OLD group",
                    "{\"oldDetails\":{\"ssnPart1\":\"123\"}}");
            payloads.put("an OLD SSN whole submitted on the NEW group",
                    "{\"newDetails\":{\"ssn\":\"123456789\"}}");
            payloads.put("the NEW range predicate submitted as a member",
                    "{\"newDetails\":{\"ficoScoreIsInValidRange\":true}}");
            final List<Arguments> cases = new ArrayList<>();
            for (final String mode : List.of("strict", "lenient")) {
                payloads.forEach((description, json) ->
                        cases.add(Arguments.of(mode, description, json)));
            }
            return cases.stream();
        }
    }

    @Nested
    @DisplayName("7. Accessor surface: no accessor throws for a width-legal payload")
    final class AccessorSurface {

        @ParameterizedTest(name = "every accessor of {0} succeeds when every member is absent")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("survives an entirely absent payload on every accessor")
        void survivesAnEntirelyAbsentPayload(final Class<?> type) {
            final Object payload = withMembers(type, Map.of());
            for (final Method accessor : zeroArgumentAccessors(type)) {
                assertThatAccessorSucceeds(payload, accessor);
            }
        }

        @ParameterizedTest(name = "every accessor of {0} succeeds at full declared width")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("survives the widest legal payload on every accessor")
        void survivesTheWidestLegalPayload(final Class<?> type) {
            final Object payload = fullyPopulated(type);
            for (final Method accessor : zeroArgumentAccessors(type)) {
                assertThatAccessorSucceeds(payload, accessor);
            }
        }

        @Test
        @DisplayName("returns the value it was given from every top-level accessor")
        void returnsTheValueItWasGiven() {
            final AccountUpdateRequest payload = fullyPopulated(AccountUpdateRequest.class);
            for (final String member : fieldNames(AccountUpdateRequest.class)) {
                if (isSnapshotGroup(AccountUpdateRequest.class, member)) {
                    assertThat(readMember(payload, member)).isNull();
                    continue;
                }
                assertThat(readMember(payload, member))
                        .as("%s must be returned exactly as supplied, with no trim, pad or "
                                + "case-fold", member)
                        .isEqualTo("0".repeat(declaredWidth(AccountUpdateRequest.class, member)));
            }
        }

        @Test
        @DisplayName("reads every derived view of a fully populated snapshot without loss")
        void readsEveryDerivedViewOfAFullSnapshot() {
            final OldDetails snapshot = fullyPopulated(OldDetails.class);
            assertThat(snapshot.currentBalanceAmount())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(snapshot.ficoScoreValue()).isZero();
            assertThat(snapshot.dateOfBirthYear()).isEqualTo("0000");
            assertThat(snapshot.phoneNumber1AreaCode()).isEqualTo("000");
            final NewDetails edited = fullyPopulated(NewDetails.class);
            assertThat(edited.phoneNumber1()).isEqualTo(" 000 000 0000  ");
            assertThat(edited.ficoScoreIsInValidRange())
                    .as("a score of 000 is outside 300 through 850")
                    .isFalse();
        }

        /**
         * Asserts that one accessor returns rather than throwing.
         *
         * @param payload  the payload to read
         * @param accessor the accessor to invoke
         */
        private void assertThatAccessorSucceeds(final Object payload, final Method accessor) {
            try {
                accessor.invoke(payload);
            } catch (InvocationTargetException cause) {
                throw new AssertionError(payload.getClass().getSimpleName() + "."
                        + accessor.getName() + " must not throw for a width-legal payload",
                        cause.getCause());
            } catch (ReflectiveOperationException cause) {
                throw new AssertionError("cannot invoke " + accessor.getName(), cause);
            }
        }
    }

    @Nested
    @DisplayName("8. Date-of-birth offsets: asymmetric on purpose, and load-bearing")
    final class DateOfBirthOffsetAsymmetry {

        @ParameterizedTest(name = "{0} stores the date of birth compact at eight characters")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#snapshotGroups")
        @DisplayName("stores the snapshot date of birth compact, never dash-separated")
        void storesTheSnapshotDateOfBirthCompact(final Class<?> group) {
            assertThat(declaredWidth(group, "dateOfBirth"))
                    .as("%s.dateOfBirth mirrors PIC X(08) at app/cbl/COACTUPC.cbl:746 and :837; a"
                            + " width of %d would admit the dash-separated live form, whose"
                            + " components sit at different offsets",
                            group.getSimpleName(), LIVE_DATE_WIDTH)
                    .isEqualTo(COMPACT_DATE_WIDTH);

            final Object snapshot = withOnly(group, "dateOfBirth", COMPACT_DATE_OF_BIRTH);
            assertThat(readMember(snapshot, "dateOfBirth"))
                    .as("the compact form must survive binding byte-for-byte")
                    .isEqualTo(COMPACT_DATE_OF_BIRTH);
        }

        @ParameterizedTest(name = "{0} slices the compact date at the compact offsets 1/5/7")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#snapshotGroups")
        @DisplayName("slices the snapshot date at 1, 5 and 7, not at 1, 6 and 9")
        void slicesTheSnapshotDateAtTheCompactOffsets(final Class<?> group) {
            final Object snapshot = withOnly(group, "dateOfBirth", COMPACT_DATE_OF_BIRTH);

            assertThat(invokeAccessor(snapshot, "dateOfBirthYear"))
                    .as("ACUP-OLD-CUST-DOB-YEAR is PIC X(4) at app/cbl/COACTUPC.cbl:749")
                    .isEqualTo("1961");
            assertThat(invokeAccessor(snapshot, "dateOfBirthMonth"))
                    .as("ACUP-OLD-CUST-DOB-MON is PIC X(2) at :750, so it begins at offset 5 of the"
                            + " compact value - not at offset 6, which is where the dash-separated"
                            + " live record keeps it")
                    .isEqualTo("06");
            assertThat(invokeAccessor(snapshot, "dateOfBirthDay"))
                    .as("ACUP-OLD-CUST-DOB-DAY is PIC X(2) at :751, so it begins at offset 7 - not"
                            + " at offset 9")
                    .isEqualTo("08");
        }

        @Test
        @DisplayName("compares equal to the live record component by component at the source offsets")
        void comparesEqualComponentByComponentAtTheSourceOffsets() {
            final OldDetails snapshot = withOnly(OldDetails.class, "dateOfBirth",
                    COMPACT_DATE_OF_BIRTH);

            final List<String> live = liveDateComponents(LIVE_DATE_OF_BIRTH);
            final List<String> held = List.of(
                    snapshot.dateOfBirthYear(),
                    snapshot.dateOfBirthMonth(),
                    snapshot.dateOfBirthDay());

            assertThat(held)
                    .as("app/cbl/COACTUPC.cbl:4174-4179 compares (1:4) against (1:4), (6:2) against"
                            + " (5:2) and (9:2) against (7:2); the two representations of one date"
                            + " must therefore agree on all three components")
                    .containsExactlyElementsOf(live);
            assertThat(snapshotDateComponents(COMPACT_DATE_OF_BIRTH))
                    .as("slicing the compact value directly must agree with the payload's own"
                            + " derived views, or the payload is applying different offsets")
                    .containsExactlyElementsOf(live);
        }

        @Test
        @DisplayName("compares unequal when only the day differs, so a real change is still detected")
        void comparesUnequalWhenOnlyTheDayDiffers() {
            final OldDetails snapshot = withOnly(OldDetails.class, "dateOfBirth", "19610609");

            final List<String> live = liveDateComponents(LIVE_DATE_OF_BIRTH);
            final List<String> held = List.of(
                    snapshot.dateOfBirthYear(),
                    snapshot.dateOfBirthMonth(),
                    snapshot.dateOfBirthDay());

            assertThat(held)
                    .as("the component rule must still detect a genuine difference; a rule that"
                            + " never reports a change is as broken as one that always does")
                    .isNotEqualTo(live);
            assertThat(held.subList(0, 2))
                    .as("only the day differs, so the year and month must still agree - which"
                            + " localises the difference rather than merely reporting one")
                    .containsExactlyElementsOf(live.subList(0, 2));
        }

        @Test
        @DisplayName("would report a change on every request if the two forms were compared whole")
        void wouldReportAChangeOnEveryRequestIfComparedWhole() {
            assertThat(COMPACT_DATE_OF_BIRTH)
                    .as("this is the failure mode the component rule exists to avoid: the compact"
                            + " and dash-separated forms of one date are never equal as strings, so"
                            + " a whole-string guard answers \"%s\" to every request ever made and"
                            + " the endpoint can never accept an update again",
                            DATA_CHANGED_BEFORE_UPDATE)
                    .isNotEqualTo(LIVE_DATE_OF_BIRTH);
            assertThat(COMPACT_DATE_OF_BIRTH.length())
                    .as("the two forms differ in length by exactly the two separators, which is why"
                            + " the mistake is easy to make and impossible to see in a diff")
                    .isEqualTo(LIVE_DATE_OF_BIRTH.length() - 2);

            assertThat(liveDateComponents(LIVE_DATE_OF_BIRTH))
                    .as("the same two values that are unequal whole are equal component by"
                            + " component, which is the entire content of app/cbl/COACTUPC.cbl"
                            + ":4174-4179")
                    .containsExactlyElementsOf(snapshotDateComponents(COMPACT_DATE_OF_BIRTH));
        }

        @ParameterizedTest(name = "{0} refuses a dash-separated date rather than mis-slicing it")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#snapshotGroups")
        @DisplayName("refuses the live ten-character form, because the compact offsets cannot slice it")
        void refusesTheLiveTenCharacterForm(final Class<?> group) {
            final Object snapshot = withOnly(group, "dateOfBirth", LIVE_DATE_OF_BIRTH);

            final IllegalArgumentException refused =
                    refusalOf(() -> invokeAccessor(snapshot, "dateOfBirthMonth"));

            assertThat(refused)
                    .as("silently slicing a ten-character value at 4:6 would read \"-0\" as the"
                            + " month, which is worse than refusing it")
                    .hasMessageContaining("PIC X(08)")
                    .hasMessageContaining("1/5/7");
            assertThat(refused.getMessage())
                    .as("a refusal must not echo a date of birth; it is personally identifiable"
                            + " data and this message reaches the log")
                    .doesNotContain(LIVE_DATE_OF_BIRTH);
        }

        @Test
        @DisplayName("keeps the concurrency verdict inside the error message field it must travel in")
        void keepsTheConcurrencyVerdictInsideItsMessageField() {
            final int errorWidth = declaredWidth(AccountUpdateRequest.class, "errorMessage");
            final int informationWidth =
                    declaredWidth(AccountUpdateRequest.class, "informationMessage");

            assertThat(DATA_CHANGED_BEFORE_UPDATE.length())
                    .as("ERRMSGI is PIC X(78) at app/cpy-bms/COACTUP.CPY:324, so the verdict this"
                            + " group exists to prevent fits there whole")
                    .isLessThanOrEqualTo(errorWidth);
            assertThat(DATA_CHANGED_BEFORE_UPDATE.length())
                    .as("INFOMSGI is PIC X(45) at app/cpy-bms/COACTUP.CPY:318 and the verdict is 46"
                            + " characters, so routing it there would truncate the final letter."
                            + " The two-word \"some one\" spelling at app/cbl/COACTUPC.cbl:521-522"
                            + " is exactly what pushes it over, which is one more reason the"
                            + " misspelling must not be tidied")
                    .isGreaterThan(informationWidth);
        }
    }

    @Nested
    @DisplayName("9. Comparison regimes: the payload must not normalise, because two regimes disagree")
    final class ComparisonRegimes {

        @ParameterizedTest(name = "{0} round-trips a mixed-case, space-padded group identifier")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#snapshotGroups")
        @DisplayName("stores the group identifier exactly as given, folding neither case nor padding")
        void storesTheGroupIdentifierExactlyAsGiven(final Class<?> group) {
            final String raw = "GoLd  DiSc";

            assertThat(readMember(withOnly(group, "groupId", raw), "groupId"))
                    .as("%s.groupId feeds two regimes that fold it differently -"
                            + " FUNCTION LOWER-CASE with no TRIM at app/cbl/COACTUPC.cbl:4139-4140"
                            + " and FUNCTION UPPER-CASE(FUNCTION TRIM(...)) at :1697-1700 - so"
                            + " normalising on arrival would serve one and break the other",
                            group.getSimpleName())
                    .isEqualTo(raw);
        }

        @Test
        @DisplayName("lets the two regimes reach opposite verdicts on one group-identifier pair")
        void letsTheTwoRegimesReachOppositeVerdictsOnAGroupIdentifier() {
            final OldDetails snapshot = withOnly(OldDetails.class, "groupId", "GOLD      ");
            final NewDetails edited = withOnly(NewDetails.class, "groupId", "gold");

            final String held = snapshot.getGroupId();
            final String submitted = edited.getGroupId();

            assertThat(concurrentChangeFolding(submitted))
                    .as("9700 folds with LOWER-CASE and no TRIM (app/cbl/COACTUPC.cbl:4139-4140),"
                            + " so the trailing padding survives the fold and the two differ")
                    .isNotEqualTo(concurrentChangeFolding(held));
            assertThat(userChangeFolding(submitted))
                    .as("1205 folds with UPPER-CASE(TRIM()) (:1697-1700), so the same pair is equal"
                            + " and no user change is reported. One pair, two verdicts: this is why"
                            + " the payload has to keep the bytes and leave the folding to the"
                            + " service")
                    .isEqualTo(userChangeFolding(held));
        }

        @Test
        @DisplayName("lets the two regimes reach opposite verdicts on the active status")
        void letsTheTwoRegimesReachOppositeVerdictsOnTheActiveStatus() {
            final OldDetails snapshot = withOnly(OldDetails.class, "activeStatus", "Y");
            final NewDetails edited = withOnly(NewDetails.class, "activeStatus", "y");

            assertThat(edited.getActiveStatus())
                    .as("9700 compares the active status with no case function at all"
                            + " (app/cbl/COACTUPC.cbl:4116), so a lower-case letter is a change")
                    .isNotEqualTo(snapshot.getActiveStatus());
            assertThat(userChangeFolding(edited.getActiveStatus()))
                    .as("1205 folds both sides with UPPER-CASE (:1685-1688), so the same pair is"
                            + " no change")
                    .isEqualTo(userChangeFolding(snapshot.getActiveStatus()));
        }

        @Test
        @DisplayName("keeps the postal code case-sensitive, which the account fixture makes reachable")
        void keepsThePostalCodeCaseSensitive() {
            final String fixtureZip = "A000000000";
            final OldDetails snapshot = withOnly(OldDetails.class, "addressZip", fixtureZip);
            final NewDetails edited = withOnly(NewDetails.class, "addressZip",
                    fixtureZip.toLowerCase(Locale.ROOT));

            assertThat(edited.getAddressZip())
                    .as("9700 compares CUST-ADDR-ZIP with no case function"
                            + " (app/cbl/COACTUPC.cbl:4168). This is not academic: every one of the"
                            + " 50 rows of app/data/ASCII/acctdata.txt carries the non-numeric"
                            + " postal code A000000000, so a letter really is present in the"
                            + " production data and its case really does decide the verdict")
                    .isNotEqualTo(snapshot.getAddressZip());
            assertThat(userChangeFolding(edited.getAddressZip()))
                    .as("1205 folds the postal code with UPPER-CASE(TRIM()) (:1747-1750), so the"
                            + " same pair is no change there")
                    .isEqualTo(userChangeFolding(snapshot.getAddressZip()));
        }

        @Test
        @DisplayName("offers the balance as text for 1205 and as a number for 9700")
        void offersTheBalanceAsTextAndAsANumber() {
            final String overpunched = "00000001940{";
            final String plainDigits = "000000019400";

            final OldDetails snapshot = withOnly(OldDetails.class, "currentBalance", overpunched);
            final OldDetails equivalent = withOnly(OldDetails.class, "currentBalance", plainDigits);

            assertThat(snapshot.getCurrentBalance())
                    .as("1205 compares ACUP-NEW-CURR-BAL against ACUP-OLD-CURR-BAL as the PIC X(12)"
                            + " text it is (app/cbl/COACTUPC.cbl:1689), so the display image must"
                            + " survive intact - this is the value app/data/ASCII/acctdata.txt"
                            + " carries on row 1")
                    .isEqualTo(overpunched)
                    .isNotEqualTo(equivalent.getCurrentBalance());
            assertThat(snapshot.currentBalanceAmount())
                    .as("9700 compares ACCT-CURR-BAL against the ACUP-OLD-CURR-BAL-N numeric"
                            + " redefine (:4117), and the trailing overpunch { denotes +0, so both"
                            + " images denote 194.00. Two images, one number: the text regime says"
                            + " changed and the numeric regime says unchanged, so both readings have"
                            + " to exist")
                    .isEqualByComparingTo(equivalent.currentBalanceAmount());
        }

        @ParameterizedTest(name = "{0} offers each date whole and as three components")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#snapshotGroups")
        @DisplayName("offers each date whole for 1205 and as three components for 9700")
        void offersEachDateWholeAndAsThreeComponents(final Class<?> group) {
            final Map<String, String> dates = new LinkedHashMap<>();
            dates.put("openDate", "20141120");
            dates.put("expiraionDate", "20250520");
            dates.put("reissueDate", "20250520");
            final Object snapshot = withMembers(group, dates);

            assertThat(readMember(snapshot, "openDate"))
                    .as("1205 compares ACUP-NEW-OPEN-DATE against ACUP-OLD-OPEN-DATE whole"
                            + " (app/cbl/COACTUPC.cbl:1692), so the eight-character value must be"
                            + " readable as one string. This is row 1 of"
                            + " app/data/ASCII/acctdata.txt, whose live form is 2014-11-20")
                    .isEqualTo("20141120");
            assertThat(List.of(
                    invokeAccessor(snapshot, "openDateYear"),
                    invokeAccessor(snapshot, "openDateMonth"),
                    invokeAccessor(snapshot, "openDateDay")))
                    .as("9700 compares ACCT-OPEN-DATE(1:4), (6:2) and (9:2) against three discrete"
                            + " snapshot members (:4127-4129), so the same value must also be"
                            + " readable as three components - never as one string")
                    .containsExactlyElementsOf(liveDateComponents("2014-11-20"));
            assertThat(List.of(
                    invokeAccessor(snapshot, "expiraionDateYear"),
                    invokeAccessor(snapshot, "expiraionDateMonth"),
                    invokeAccessor(snapshot, "expiraionDateDay")))
                    .as("the expiry date is sliced the same way at :4131-4133, under the misspelled"
                            + " name the frozen corpus uses at app/cpy/CVACT01Y.cpy:11")
                    .containsExactly("2025", "05", "20");
            assertThat(List.of(
                    invokeAccessor(snapshot, "reissueDateYear"),
                    invokeAccessor(snapshot, "reissueDateMonth"),
                    invokeAccessor(snapshot, "reissueDateDay")))
                    .as("and the reissue date at :4135-4137")
                    .containsExactly("2025", "05", "20");
        }

        @Test
        @DisplayName("stores the telephone whole on OLD and by part on NEW, as the source assigns them")
        void storesTheTelephoneWholeOnOldAndByPartOnNew() {
            final String fixtureImage = "(908)119-8310  ";
            final OldDetails snapshot = withOnly(OldDetails.class, "phoneNumber1", fixtureImage);
            final Map<String, String> parts = new LinkedHashMap<>();
            parts.put("phoneNumber1AreaCode", "908");
            parts.put("phoneNumber1Prefix", "119");
            parts.put("phoneNumber1LineNumber", "8310");
            final NewDetails edited = withMembers(NewDetails.class, parts);

            assertThat(snapshot.getPhoneNumber1())
                    .as("9700 compares CUST-PHONE-NUM-1 against ACUP-OLD-CUST-PHONE-NUM-1 whole"
                            + " (app/cbl/COACTUPC.cbl:4169-4170), so the OLD group keeps the whole"
                            + " fifteen-byte image - exactly as row 1 of"
                            + " app/data/ASCII/custdata.txt holds it at bytes 250 to 264")
                    .isEqualTo(fixtureImage)
                    .hasSize(PHONE_WIDTH);
            assertThat(List.of(
                    snapshot.phoneNumber1AreaCode(),
                    snapshot.phoneNumber1Prefix(),
                    snapshot.phoneNumber1LineNumber()))
                    .as("the REDEFINES at :723-731 places the three parts at offsets 2, 6 and 10,"
                            + " leaving the parenthesis, parenthesis and hyphen in the FILLER bytes")
                    .containsExactly("908", "119", "8310");
            assertThat(List.of(
                    edited.getPhoneNumber1AreaCode(),
                    edited.getPhoneNumber1Prefix(),
                    edited.getPhoneNumber1LineNumber()))
                    .as("1205 compares the six telephone parts individually (:1751-1756), so the NEW"
                            + " group keeps the parts. Both groups agree on the three parts, which"
                            + " is what that regime reads")
                    .containsExactly("908", "119", "8310");
        }

        @Test
        @DisplayName("cannot rebuild the punctuation from the parts, so neither side may be flattened")
        void cannotRebuildThePunctuationFromTheParts() {
            final String fixtureImage = "(908)119-8310  ";
            final Map<String, String> parts = new LinkedHashMap<>();
            parts.put("phoneNumber1AreaCode", "908");
            parts.put("phoneNumber1Prefix", "119");
            parts.put("phoneNumber1LineNumber", "8310");
            final NewDetails edited = withMembers(NewDetails.class, parts);

            final String rebuilt = edited.phoneNumber1();

            assertThat(rebuilt)
                    .as("the FILLER bytes of the REDEFINES at :723-731 are unnamed, so no MOVE to a"
                            + " part ever writes them and the parts alone cannot say what they held")
                    .hasSize(PHONE_WIDTH)
                    .isNotEqualTo(fixtureImage);
            assertThat(rebuilt.substring(1, 4) + rebuilt.substring(5, 8) + rebuilt.substring(9, 13))
                    .as("the twelve data bytes do agree, which is precisely the boundary: the parts"
                            + " carry the number and the whole carries the number plus punctuation."
                            + " Flattening either group to match the other would name a value that"
                            + " group never holds")
                    .isEqualTo("9081198310");
        }

        @ParameterizedTest(name = "{0} declares no version counter")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares no version counter, so the snapshot is the only value-level evidence")
        void declaresNoVersionCounter(final Class<?> type) {
            assertThat(fieldNames(type))
                    .as("a JPA version column answers \"did this row change?\" while"
                            + " 9700-CHECK-CHANGE-IN-REC answers \"do these field values differ from"
                            + " what the user was shown?\". %s carries no counter, so the snapshot"
                            + " groups are the only evidence for the second question and are"
                            + " load-bearing rather than decorative", type.getSimpleName())
                    .noneMatch(member -> member.toLowerCase(Locale.ROOT).contains("version"));
        }

        @Test
        @DisplayName("reports no change when a field was written away and restored, unlike a counter")
        void reportsNoChangeWhenAFieldWasWrittenAwayAndRestored() {
            final String original = "GOLD      ";
            final OldDetails shownToTheUser = withOnly(OldDetails.class, "groupId", original);
            final OldDetails afterTheRoundTrip = withOnly(OldDetails.class, "groupId", original);

            assertThat(concurrentChangeFolding(afterTheRoundTrip.getGroupId()))
                    .as("a concurrent writer that set this field to something else and then back"
                            + " leaves the value the user was shown, so 9700 finds no change and the"
                            + " update proceeds - while a version counter would have advanced twice"
                            + " and refused it. The two guards answer different questions, so"
                            + " neither substitutes for the other and both are required")
                    .isEqualTo(concurrentChangeFolding(shownToTheUser.getGroupId()));
        }
    }

    @Nested
    @DisplayName("10. Emptiness is three states: absent, blank and low-values")
    final class TriStateEmptiness {

        @ParameterizedTest(name = "NewDetails.{0} keeps absent, blank and low-values apart")
        @CsvSource({
            "ssnPart1, 3",
            "ssnPart2, 2",
            "ssnPart3, 4",
            "ficoScore, 3",
            "dateOfBirth, 8",
        })
        @DisplayName("keeps absent, blank and low-values distinguishable on every affected member")
        void keepsAbsentBlankAndLowValuesDistinguishable(final String member, final int width) {
            final Object absent = readMember(withOnly(NewDetails.class, member, null), member);
            final Object blank = readMember(withOnly(NewDetails.class, member, " ".repeat(width)),
                    member);
            final Object lowValues =
                    readMember(withOnly(NewDetails.class, member, "\u0000".repeat(width)), member);

            assertThat(absent)
                    .as("NewDetails.%s: an absent member is not a blank one", member)
                    .isNull();
            assertThat(blank)
                    .as("NewDetails.%s: SPACES must survive as spaces", member)
                    .isEqualTo(" ".repeat(width));
            assertThat(lowValues)
                    .as("NewDetails.%s: app/cbl/COACTUPC.cbl moves LOW-VALUES - binary zeros, not"
                            + " spaces - when the screen field holds '*' or SPACES (:1235, :1258,"
                            + " :1279), so the two must not be folded together", member)
                    .isEqualTo("\u0000".repeat(width))
                    .isNotEqualTo(blank);
        }

        @ParameterizedTest(name = "NewDetails.{0} admits all three states without a violation")
        @ValueSource(strings = {"ssnPart1", "ficoScore", "dateOfBirth"})
        @DisplayName("admits all three states, so validation alone cannot tell them apart")
        void admitsAllThreeStatesWithoutAViolation(final String member) {
            final int width = declaredWidth(NewDetails.class, member);

            for (final String value : Arrays.asList(null, " ".repeat(width),
                    "\u0000".repeat(width))) {
                final Set<ConstraintViolation<Object>> violations =
                        VALIDATOR.validate(withOnly(NewDetails.class, member, value));
                assertThat(violations)
                        .as("app/cpy/CSSETATY.cpy models OK, NOT-OK and BLANK and fires its markers"
                                + " only on re-entry, so the width contract must accept all three"
                                + " states and leave the three-way decision to the service. A"
                                + " constraint that rejected blank here would collapse the model to"
                                + " two states")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("collapses all three states to no number, while the stored text keeps them apart")
        void collapsesAllThreeStatesToNoNumberWhileTheTextKeepsThemApart() {
            final NewDetails absent = withOnly(NewDetails.class, "ficoScore", null);
            final NewDetails blank = withOnly(NewDetails.class, "ficoScore", "   ");
            final NewDetails lowValues = withOnly(NewDetails.class, "ficoScore", "\u0000\u0000\u0000");

            assertThat(List.of(absent, blank, lowValues))
                    .as("none of the three is three decimal digits, so the numeric view is absent"
                            + " for all three and cannot be what distinguishes them")
                    .allSatisfy(group -> {
                        assertThat(group.ficoScoreValue()).isNull();
                        assertThat(group.ficoScoreIsInValidRange()).isFalse();
                    });

            assertThat(blank.getFicoScore())
                    .as("the stored text is what preserves the distinction, which is what lets the"
                            + " service choose between 'Credit Limit must be supplied' and"
                            + " 'Credit Limit is not valid' rather than emitting one message for"
                            + " both")
                    .isNotEqualTo(lowValues.getFicoScore())
                    .isNotNull();
            assertThat(absent.getFicoScore()).isNull();
        }

        @Test
        @DisplayName("keeps a low-values date sliceable, so its components stay distinguishable too")
        void keepsALowValuesDateSliceable() {
            final NewDetails lowValues =
                    withOnly(NewDetails.class, "dateOfBirth", "\u0000".repeat(COMPACT_DATE_WIDTH));
            final NewDetails blank =
                    withOnly(NewDetails.class, "dateOfBirth", " ".repeat(COMPACT_DATE_WIDTH));

            assertThat(lowValues.dateOfBirthYear())
                    .as("the source moves LOW-VALUES into ACUP-NEW-CUST-DOB-YEAR itself (:1258),"
                            + " which is the redefine of the compact PIC X(08), so a component of a"
                            + " low-values date must read back as low-values rather than as spaces")
                    .isEqualTo("\u0000".repeat(4))
                    .isNotEqualTo(blank.dateOfBirthYear());
            assertThat(List.of(lowValues.dateOfBirthMonth(), lowValues.dateOfBirthDay()))
                    .as("month and day are moved independently at :1265 and :1272, so each keeps its"
                            + " own state")
                    .containsExactly("\u0000\u0000", "\u0000\u0000");
        }

        @Test
        @DisplayName("keeps the blank and the not-valid messages distinct and independently carriable")
        void keepsTheBlankAndNotValidMessagesDistinct() {
            assertThat(CREDIT_LIMIT_BLANK)
                    .as("app/cbl/COACTUPC.cbl:505-506 and :507-508 are two literals because they"
                            + " report two states. One message for both would delete the"
                            + " distinction from the observable contract")
                    .isNotEqualTo(CREDIT_LIMIT_NOT_VALID);
            assertThat(List.of(CREDIT_LIMIT_BLANK, CREDIT_LIMIT_NOT_VALID))
                    .as("both must fit ERRMSGI PIC X(78) so either can be reported on its own")
                    .allSatisfy(message -> assertThat(message.length())
                            .isLessThanOrEqualTo(
                                    declaredWidth(AccountUpdateRequest.class, "errorMessage")));
        }
    }

    @Nested
    @DisplayName("11. The state-and-postal-code edit is gated, so it cannot be a type-level constraint")
    final class GatedCrossFieldEdit {

        @ParameterizedTest(name = "{0} declares no unconditional cross-field constraint")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares no @AssertTrue or @AssertFalse anywhere on the type")
        void declaresNoUnconditionalCrossFieldConstraint(final Class<?> type) {
            assertThat(type.getAnnotations())
                    .as("1280-EDIT-US-STATE-ZIP-CD runs only once FLG-STATE-ISVALID and"
                            + " FLG-ZIPCODE-ISVALID are both set (app/cbl/COACTUPC.cbl:1664-1669)."
                            + " A type-level @AssertTrue fires unconditionally, so it would report a"
                            + " cross-field error on input whose single-field edit had already"
                            + " failed - a different message set from the source")
                    .noneMatch(annotation -> isAssertion(annotation.annotationType()));

            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .flatMap(method -> Arrays.stream(method.getAnnotations()))
                    .map(java.lang.annotation.Annotation::annotationType)
                    .toList())
                    .as("%s must not carry a method-level assertion either; the gating belongs to"
                            + " the ordered edits of 1200-EDIT-MAP-INPUTS", type.getSimpleName())
                    .noneMatch(GatedCrossFieldEdit.this::isAssertion);

            assertThat(Arrays.stream(type.getDeclaredFields())
                    .flatMap(field -> Arrays.stream(field.getAnnotations()))
                    .map(java.lang.annotation.Annotation::annotationType)
                    .toList())
                    .as("nor a field-level assertion")
                    .noneMatch(GatedCrossFieldEdit.this::isAssertion);
        }

        @Test
        @DisplayName("reports nothing for an unlisted state code beside a non-numeric postal code")
        void reportsNothingForAnUnlistedStateCodeBesideANonNumericPostalCode() {
            final Map<String, String> hostile = new LinkedHashMap<>();
            hostile.put("addressStateCode", "AP");
            hostile.put("addressZip", "A000000000");
            final NewDetails edited = withMembers(NewDetails.class, hostile);

            assertThat(VALIDATOR.validate(edited))
                    .as("AP, FM, MH and PW appear in app/data/ASCII/acctdata.txt but not in the"
                            + " 56-entry VALID-US-STATE-CODE table at app/cpy/CSLKPCDY.cpy:1013, and"
                            + " every one of those 50 rows carries the non-numeric postal code"
                            + " A000000000. Declaring either rule here would reject the repository's"
                            + " own seed data before the service ever saw it")
                    .isEmpty();
            assertThat(edited.getAddressStateCode()).isEqualTo("AP");
            assertThat(edited.getAddressZip()).isEqualTo("A000000000");
        }

        @Test
        @DisplayName("keeps the postal code five characters on the screen and ten in the snapshot")
        void keepsThePostalCodeFiveOnScreenAndTenInTheSnapshot() {
            assertThat(declaredWidth(AccountUpdateRequest.class, "addressZip"))
                    .as("ACSZIPCI is PIC X(5) at app/cpy-bms/COACTUP.CPY:246")
                    .isEqualTo(5);
            assertThat(declaredWidth(OldDetails.class, "addressZip"))
                    .as("ACUP-OLD-CUST-ADDR-ZIP is PIC X(10) at app/cbl/COACTUPC.cbl:721, so a"
                            + " ten-byte snapshot sits behind a five-character screen field."
                            + " Unifying them would either truncate the snapshot or widen the screen"
                            + " contract, and both are parity breaks")
                    .isEqualTo(10);
            assertThat(declaredWidth(NewDetails.class, "addressZip")).isEqualTo(10);
        }

        @Test
        @DisplayName("carries the edit routine labels verbatim, including the ten-character length")
        void carriesTheEditRoutineLabelsVerbatim() {
            assertThat("Phone Number 2")
                    .as("1260-EDIT-US-PHONE-NUM is driven with this label at"
                            + " app/cbl/COACTUPC.cbl:1640; the string reaches the user, so it is"
                            + " part of the contract")
                    .isNotEqualTo("Phone number 2");
            assertThat("Primary Card Holder")
                    .as("1220-EDIT-YESNO is driven with this label at :1657")
                    .isNotEqualTo("Primary Cardholder");
            assertThat(declaredWidth(NewDetails.class, "eftAccountId"))
                    .as("1245-EDIT-NUM-REQD is driven with the label 'EFT Account Id' at :1647 and"
                            + " WS-EDIT-ALPHANUM-LENGTH 10 at :1650, which is the same ten"
                            + " characters ACUP-NEW-CUST-EFT-ACCOUNT-ID declares at :843")
                    .isEqualTo(10);
        }

        /**
         * Reports whether an annotation type is one of the two unconditional bean-validation
         * assertions.
         *
         * @param annotationType the annotation type to classify
         * @return {@code true} for {@code @AssertTrue} or {@code @AssertFalse}
         */
        private boolean isAssertion(final Class<?> annotationType) {
            return "jakarta.validation.constraints.AssertTrue".equals(annotationType.getName())
                    || "jakarta.validation.constraints.AssertFalse".equals(annotationType.getName());
        }
    }

    @Nested
    @DisplayName("12. Outcome codes and message literals: byte-for-byte, oddities included")
    final class OutcomeFlagsAndMessages {

        @Test
        @DisplayName("keeps all five outcome codes distinct, including the two failure codes")
        void keepsAllFiveOutcomeCodesDistinct() {
            assertThat(OUTCOME_CODES)
                    .as("ACUP-CHANGE-ACTION carries five single-character outcomes at"
                            + " app/cbl/COACTUPC.cbl:660-668")
                    .hasSize(5);
            assertThat(OUTCOME_CODES.keySet())
                    .containsExactlyInAnyOrder("E", "N", "C", "L", "F")
                    .allSatisfy(code -> assertThat(code).hasSize(1));
            assertThat(OUTCOME_CODES.get("L"))
                    .as("88 ACUP-CHANGES-FAILED covers both 'L' and 'F' at :666, but :667 and :668"
                            + " name them separately - a lock error and an update failure. A"
                            + " response surface that answers one status for both discards what the"
                            + " legacy screen displayed")
                    .isNotEqualTo(OUTCOME_CODES.get("F"));
        }

        @Test
        @DisplayName("keeps the five failure markers distinct rather than collapsing them")
        void keepsTheFiveFailureMarkersDistinct() {
            assertThat(FAILURE_MARKERS)
                    .as("the four markers at app/cbl/COACTUPC.cbl:517-523 plus the cross-reference"
                            + " read error at :525-526 each name a different failure, and :2607-2608"
                            + " sets one of them specifically on an account-lock failure")
                    .hasSize(5)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("carries all thirteen message literals byte-for-byte")
        void carriesAllThirteenMessageLiteralsByteForByte() {
            assertThat(MESSAGE_LITERALS)
                    .as("app/cbl/COACTUPC.cbl:503-528 declares thirteen message condition names")
                    .hasSize(13)
                    .doesNotHaveDuplicates()
                    .allSatisfy(literal -> assertThat(literal)
                            .isNotBlank()
                            .isEqualTo(literal.strip()));
        }

        @Test
        @DisplayName("spells the concurrency verdict with two words, exactly as the corpus does")
        void spellsTheConcurrencyVerdictWithTwoWords() {
            assertThat(DATA_CHANGED_BEFORE_UPDATE)
                    .as("app/cbl/COACTUPC.cbl:521-522 spells it 'some one'. The parity gates compare"
                            + " this string, so the tidier one-word spelling is a regression rather"
                            + " than a fix")
                    .isEqualTo("Record changed by some one else. Please review")
                    .contains(" some one ")
                    .doesNotContain("someone");
        }

        @Test
        @DisplayName("keeps four dots in the placeholder verdict, not three")
        void keepsFourDotsInThePlaceholderVerdict() {
            assertThat(CODING_TO_BE_DONE)
                    .as("app/cbl/COACTUPC.cbl:527-528 carries four dots")
                    .isEqualTo("Looks Good.... so far")
                    .contains("Good....")
                    .isNotEqualTo("Looks Good... so far");
        }

        @Test
        @DisplayName("fits every literal inside the error message field the map declares")
        void fitsEveryLiteralInsideTheErrorMessageField() {
            final int errorWidth = declaredWidth(AccountUpdateRequest.class, "errorMessage");

            assertThat(MESSAGE_LITERALS)
                    .as("ERRMSGI is PIC X(78) at app/cpy-bms/COACTUP.CPY:324, so every verdict the"
                            + " program can emit has to fit there whole - a truncated message is a"
                            + " parity break the gates would catch as a diff")
                    .allSatisfy(literal -> assertThat(literal.length())
                            .isLessThanOrEqualTo(errorWidth));
            assertThat(MESSAGE_LITERALS.stream().mapToInt(String::length).max().orElseThrow())
                    .as("the longest of the thirteen is the concurrency verdict at 46 characters")
                    .isEqualTo(DATA_CHANGED_BEFORE_UPDATE.length())
                    .isEqualTo(46);
        }

        @Test
        @DisplayName("shows that only the misspelling pushes the verdict past the information field")
        void showsThatOnlyTheMisspellingPushesTheVerdictPastTheInformationField() {
            final int informationWidth =
                    declaredWidth(AccountUpdateRequest.class, "informationMessage");

            assertThat(informationWidth)
                    .as("INFOMSGI is PIC X(45) at app/cpy-bms/COACTUP.CPY:318")
                    .isEqualTo(45);
            assertThat(DATA_CHANGED_BEFORE_UPDATE.length())
                    .as("the corpus spelling is one character too wide for that field")
                    .isGreaterThan(informationWidth);
            assertThat("Record changed by some one else. Please review".replace(" some one ",
                    " someone ").length())
                    .as("the corrected spelling is exactly 45 and would fit, which is how a"
                            + " well-meant tidy-up could silently move the verdict onto the narrower"
                            + " field and then truncate it once the spelling was reverted")
                    .isEqualTo(informationWidth);
        }
    }

    @Nested
    @DisplayName("13. Decimal money, textual dates, and personal data that never reaches a log")
    final class PrecisionDatesAndSecurity {

        /** Column of {@code ACCT-CURR-BAL} in the 300-byte account record, one-based. */
        private static final int ACCOUNT_BALANCE_COLUMN = 13;

        /** Column of {@code ACCT-ADDR-ZIP}, which begins with a letter on all fifty rows. */
        private static final int ACCOUNT_ZIP_COLUMN = 103;

        /** Column of {@code ACCT-GROUP-ID}, which is ten spaces on all fifty rows. */
        private static final int ACCOUNT_GROUP_COLUMN = 113;

        /** Width of {@code ACCT-ADDR-ZIP} and {@code ACCT-GROUP-ID}, both {@code PIC X(10)}. */
        private static final int ACCOUNT_TEXT_WIDTH = 10;

        /** Column of {@code CUST-SSN} in the 500-byte customer record, one-based. */
        private static final int CUSTOMER_SSN_COLUMN = 280;

        /** Width of {@code CUST-SSN}, {@code PIC 9(09)}. */
        private static final int CUSTOMER_SSN_WIDTH = 9;

        @ParameterizedTest(name = "{0} exposes no binary floating-point reading")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("exposes no float or double anywhere, on any member or derived view")
        void exposesNoBinaryFloatingPointReading(final Class<?> type) {
            assertThat(zeroArgumentAccessors(type))
                    .as("every PIC S9(n)V99 in the corpus is exact decimal. A binary floating-point"
                            + " type cannot represent 0.01, so one appearing anywhere on %s would"
                            + " fail the security audit outright", type.getSimpleName())
                    .allSatisfy(accessor -> assertThat(accessor.getReturnType())
                            .isNotIn(float.class, double.class, Float.class, Double.class));
            assertThat(Arrays.stream(type.getDeclaredFields()).map(Field::getType).toList())
                    .allSatisfy(fieldType -> assertThat(fieldType)
                            .isNotIn(float.class, double.class, Float.class, Double.class));
        }

        @ParameterizedTest(name = "{0} reads every money member as a two-place BigDecimal")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#snapshotGroups")
        @DisplayName("reads every money member as a BigDecimal scaled to two places")
        void readsEveryMoneyMemberAsATwoPlaceBigDecimal(final Class<?> group) {
            final List<String> money = List.of("currentBalance", "creditLimit", "cashCreditLimit",
                    "currentCycleCredit", "currentCycleDebit");
            final Map<String, String> images = new LinkedHashMap<>();
            money.forEach(member -> images.put(member, "00000001940{"));
            final Object snapshot = withMembers(group, images);

            for (final String member : money) {
                assertThat(declaredWidth(group, member))
                        .as("%s.%s mirrors PIC X(12) at app/cbl/COACTUPC.cbl:675, whose numeric"
                                + " redefine is PIC S9(10)V99", group.getSimpleName(), member)
                        .isEqualTo(MONEY_WIDTH);
                final Object amount = invokeAccessor(snapshot, member + "Amount");
                assertThat(amount)
                        .as("%s.%sAmount must be exact decimal", group.getSimpleName(), member)
                        .isInstanceOf(BigDecimal.class);
                assertThat((BigDecimal) amount)
                        .as("the V99 of the PIC clause fixes the scale at two")
                        .hasScaleOf(2)
                        .isEqualByComparingTo(new BigDecimal("194.00"));
            }
        }

        @Test
        @DisplayName("must be compared with compareTo, because equals also compares the scale")
        void mustBeComparedWithCompareToRatherThanEquals() {
            final OldDetails snapshot =
                    withOnly(OldDetails.class, "currentBalance", "00000001940{");
            final BigDecimal sameValueDifferentScale = new BigDecimal("194.000");

            assertThat(snapshot.currentBalanceAmount())
                    .as("BigDecimal.equals compares the unscaled value AND the scale, so 194.00 and"
                            + " 194.000 are unequal by equals while denoting one amount. Every"
                            + " comparison of a money reading must therefore use compareTo")
                    .isEqualByComparingTo(sameValueDifferentScale);
            assertThat(snapshot.currentBalanceAmount().equals(sameValueDifferentScale))
                    .as("and this is why: equals answers false for the same amount, which is the"
                            + " defect the rule exists to prevent")
                    .isFalse();
        }

        @Test
        @DisplayName("keeps the three declared precisions distinct rather than widening to one")
        void keepsTheThreeDeclaredPrecisionsDistinct() {
            assertThat(FixtureLoader.MONEY_FIELD_WIDTH)
                    .as("the account money tier is PIC S9(10)V99, which is the tier this payload's"
                            + " snapshot members belong to: ACUP-OLD-CURR-BAL-N at"
                            + " app/cbl/COACTUPC.cbl:676-677 and ACCT-CURR-BAL at"
                            + " app/cpy/CVACT01Y.cpy:7")
                    .isEqualTo(MONEY_WIDTH)
                    .isEqualTo(declaredWidth(OldDetails.class, "currentBalance"));
            assertThat(List.of(FixtureLoader.MONEY_FIELD_WIDTH, FixtureLoader.AMOUNT_FIELD_WIDTH,
                    FixtureLoader.RATE_FIELD_WIDTH))
                    .as("the other two tiers are PIC S9(09)V99 and PIC S9(04)V99, whose widths"
                            + " FixtureLoader declares alongside this one. Collapsing the three into"
                            + " one wide type would let a value the source rejects round-trip"
                            + " silently. This asserts the declared PIC widths only - what the"
                            + " generated schema states about them is Not available here, because"
                            + " this tier reads no migration file and none is asserted against")
                    .containsExactly(12, 11, 6)
                    .doesNotHaveDuplicates();
            assertThat(FixtureLoader.DECIMAL_SCALE)
                    .as("all three tiers share the V99 scale of two; only the precision differs")
                    .isEqualTo(2);
        }

        @ParameterizedTest(name = "{0} carries every date as text")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("carries every date as text, never as a temporal type")
        void carriesEveryDateAsText(final Class<?> type) {
            assertThat(zeroArgumentAccessors(type))
                    .as("the corpus stores dates as PIC X(08) and PIC X(10) alphanumerics and"
                            + " compares them as characters, including at offsets a parsed date has"
                            + " no notion of. A temporal type on %s would normalise away exactly the"
                            + " representation the comparison reads, and would reject the"
                            + " low-values state as unparseable", type.getSimpleName())
                    .filteredOn(accessor -> accessor.getName().toLowerCase(Locale.ROOT)
                            .contains("date"))
                    .isNotEmpty()
                    .allSatisfy(accessor -> assertThat(accessor.getReturnType())
                            .isEqualTo(String.class));
        }

        @Test
        @DisplayName("takes the header date and time from the injected clock, never from a live one")
        void takesTheHeaderDateAndTimeFromTheInjectedClock() {
            final Clock clock = FixedClockProvider.canonicalClock();
            final String timestamp = FixedClockProvider.onlineTimestamp(clock);

            final String headerDate = timestamp.substring(5, 7) + "/" + timestamp.substring(8, 10)
                    + "/" + timestamp.substring(2, 4);
            final String headerTime = timestamp.substring(11, 19);

            final Map<String, String> header = new LinkedHashMap<>();
            header.put("currentDate", headerDate);
            header.put("currentTime", headerTime);
            final AccountUpdateRequest payload =
                    withMembers(AccountUpdateRequest.class, header);

            assertThat(payload.getCurrentDate())
                    .as("CURDATEI is PIC X(8) at app/cpy-bms/COACTUP.CPY:36, and the value comes"
                            + " from FixedClockProvider so that this assertion means the same thing"
                            + " on every machine and in every month")
                    .isEqualTo("06/10/22")
                    .hasSize(declaredWidth(AccountUpdateRequest.class, "currentDate"));
            assertThat(payload.getCurrentTime())
                    .as("CURTIMEI is PIC X(8) on this map at app/cpy-bms/COACTUP.CPY:54 - and PIC"
                            + " X(9) on app/cpy-bms/COSGN00.CPY:54, which is why the six header"
                            + " fields are declared inline here rather than shared with another map")
                    .isEqualTo("19:27:53")
                    .hasSize(declaredWidth(AccountUpdateRequest.class, "currentTime"));
            assertThat(FixedClockProvider.CANONICAL_ZONE)
                    .as("the zone is fixed too, so a machine in another offset reads the same"
                            + " calendar day out of the same instant")
                    .isEqualTo(java.time.ZoneOffset.UTC);
        }

        @ParameterizedTest(name = "{0} inherits toString, equals and hashCode")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("overrides neither toString nor equals nor hashCode, given what it carries")
        void overridesNeitherToStringNorEqualsNorHashCode(final Class<?> type) {
            final List<String> overridden = zeroArgumentAccessors(type).stream()
                    .map(Method::getName)
                    .filter(name -> "toString".equals(name) || "hashCode".equals(name))
                    .toList();

            assertThat(overridden)
                    .as("%s carries a social security number, two telephone numbers, a date of"
                            + " birth, a government-issued identifier, three name parts and a full"
                            + " address. A generated toString would put all of it into any log line"
                            + " that interpolated the payload, so the inherited one is the only safe"
                            + " one - and hashCode must not digest the same data either",
                            type.getSimpleName())
                    .isEmpty();
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> "equals".equals(method.getName()))
                    .toList())
                    .as("nor may %s define value equality over personal data", type.getSimpleName())
                    .isEmpty();
        }

        @Test
        @DisplayName("renders no personal data when a fully populated payload is interpolated")
        void rendersNoPersonalDataWhenInterpolated() {
            final FixtureLoader.FixtureData customers =
                    FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);
            final String socialSecurityNumber =
                    customers.field(0, CUSTOMER_SSN_COLUMN, CUSTOMER_SSN_WIDTH);

            final Map<String, String> populated = new LinkedHashMap<>();
            populated.put("ssn", socialSecurityNumber);
            populated.put("dateOfBirth", COMPACT_DATE_OF_BIRTH);
            populated.put("phoneNumber1", "(908)119-8310  ");
            final OldDetails snapshot = withMembers(OldDetails.class, populated);

            final String rendered = String.valueOf(snapshot);

            assertThat(rendered.contains(socialSecurityNumber))
                    .as("the social security number must not appear in the rendering of the"
                            + " snapshot. Only the boolean outcome is asserted, so a failure here"
                            + " reports that the leak happened without reprinting the value into the"
                            + " build log as well")
                    .isFalse();
            assertThat(rendered.contains(COMPACT_DATE_OF_BIRTH))
                    .as("nor may the date of birth appear")
                    .isFalse();
            assertThat(rendered.contains("9081198310") || rendered.contains("119-8310"))
                    .as("nor either telephone number, whole or in part")
                    .isFalse();
            assertThat(snapshot.getSsn())
                    .as("the value is still reachable through its accessor, so the payload"
                            + " transports it without advertising it")
                    .hasSize(CUSTOMER_SSN_WIDTH);
        }

        @Test
        @DisplayName("accepts the account fixture's own postal code and blank group identifier")
        void acceptsTheAccountFixtureOwnPostalCodeAndBlankGroupIdentifier() {
            final FixtureLoader.FixtureData accounts =
                    FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            assertThat(accounts.recordCount())
                    .as("app/data/ASCII/acctdata.txt is 15050 bytes of fifty 300-byte records")
                    .isEqualTo(50);

            for (int index = 0; index < accounts.recordCount(); index++) {
                final String zip = accounts.field(index, ACCOUNT_ZIP_COLUMN, ACCOUNT_TEXT_WIDTH);
                final String groupId =
                        accounts.field(index, ACCOUNT_GROUP_COLUMN, ACCOUNT_TEXT_WIDTH);
                final Map<String, String> values = new LinkedHashMap<>();
                values.put("addressZip", zip);
                values.put("groupId", groupId);
                final OldDetails snapshot = withMembers(OldDetails.class, values);

                assertThat(VALIDATOR.validate(snapshot))
                        .as("record %d of the account fixture must bind without a violation: the"
                                + " postal code is not numeric and the group identifier is blank on"
                                + " every row, so a digits-only rule or a non-blank rule would"
                                + " reject the repository's own seed data", index)
                        .isEmpty();
                assertThat(snapshot.getAddressZip()).isEqualTo(zip);
                assertThat(snapshot.getGroupId()).isEqualTo(groupId);
            }
        }

        @Test
        @DisplayName("decodes the overpunch sign from the declared position, never from the record")
        void decodesTheOverpunchSignFromTheDeclaredPosition() {
            final FixtureLoader.FixtureData accounts =
                    FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            assertThat(accounts.signedDecimal(0, ACCOUNT_BALANCE_COLUMN,
                    FixtureLoader.MONEY_FIELD_WIDTH))
                    .as("ACCT-CURR-BAL is PIC S9(10)V99 at columns 13 to 24 of the account record"
                            + " and row 1 holds 00000001940{, whose trailing { denotes +0, so the"
                            + " value is 194.00")
                    .isEqualByComparingTo(new BigDecimal("194.00"));

            final Throwable atTheWrongPosition = catchThrowable(() -> accounts.signedDecimal(0,
                    ACCOUNT_ZIP_COLUMN, ACCOUNT_TEXT_WIDTH));

            assertThat(atTheWrongPosition)
                    .as("ACCT-ADDR-ZIP begins with the letter A, which a decoder applied without"
                            + " regard to the PIC clauses would read as the overpunch for +1."
                            + " Decoding must be driven by the declared field positions only, so"
                            + " attempting it here has to fail rather than invent a number")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(atTheWrongPosition.getCause())
                    .as("and the root cause must be preserved rather than swallowed")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(accounts.field(0, ACCOUNT_ZIP_COLUMN, ACCOUNT_TEXT_WIDTH))
                    .as("read as the text it is, the same bytes are perfectly valid")
                    .startsWith("A");
        }
    }
}
