/*
 * ******************************************************************
 * Program     : ValidationExceptionTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that ValidationException reproduces the two
 *               field-error states of the CSSETATY REPLACING template -
 *               FLG-<field>-NOT-OK and FLG-<field>-BLANK - which are
 *               deliberately distinct because the template renders them
 *               differently: both turn the field red, but only the blank
 *               state additionally moves '*' into the output field.
 * Source      : app/cpy/CSSETATY.cpy:L17-L26 (the parameterised template) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L1470 (SET ACUP-CHANGES-NOT-OK) @ 7756d89
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

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ValidationException;
import com.cardemo.exception.ValidationException.FailureKind;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link ValidationException} and its nested {@link FailureKind} enum - the typed form of the
 * per-field error markers the {@code CSSETATY} template applies.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code app/cpy/CSSETATY.cpy} is not a data layout; it is a parameterised procedural template resolved
 * through {@code COPY … REPLACING}. Its whole body is:
 *
 * <pre>
 * IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER
 *     MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O
 *     IF FLG-(TESTVAR1)-BLANK
 *         MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
 *     END-IF
 * END-IF
 * </pre>
 *
 * <p>Two facts follow, and they are the reason this enum has exactly two constants rather than one:
 *
 * <ul>
 *   <li><strong>Both states share the red-attribute outcome</strong>, so any code that only needs "is this
 *       field in error" can treat them alike.</li>
 *   <li><strong>Only {@code BLANK} takes the second, nested branch</strong> that moves {@code '*'} into the
 *       output field. A blank field is therefore <em>not</em> merely a species of invalid field - it renders
 *       differently on the screen. Merging the two constants would lose the asterisk.</li>
 *   </ul>
 *
 * <p>This class also pins the type's own normalisation policy, which is a fourth distinct policy within the
 * same exception package and must not be inferred from its siblings: {@code fieldName} is stored
 * <strong>exactly as supplied</strong>, with no strip and no blank-to-null conversion, and the blank check
 * is deferred to {@link ValidationException#hasFieldName()} at read time.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=ValidationExceptionTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>One default is in play and it is asserted from four directions: a {@code null} {@link FailureKind} is
 * substituted with {@link FailureKind#INVALID}. The rationale is that an unclassified field error is still an
 * error, and {@code INVALID} is the weaker of the two claims - it turns the field red without asserting the
 * asterisk that {@code BLANK} would.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The two-constant assertion fails.</strong> A third kind was added, or the two were merged.
 *       The template supports exactly two rendering outcomes; a third would have no counterpart.</li>
 *   <li><strong>A default-substitution assertion fails.</strong> The {@code null} handling changed. Note the
 *       substitution is deliberately towards {@code INVALID}, never {@code BLANK}, because defaulting to
 *       {@code BLANK} would add an asterisk the program never asked for.</li>
 *   <li><strong>A {@code hasFieldName} assertion fails.</strong> The read-time blank check moved into the
 *       constructor. That is a legitimate design but it is a change: {@code getFieldName()} would then stop
 *       returning what the caller passed.</li>
 *   </ul>
 *
 * @see ValidationException
 */
class ValidationExceptionTest {

    private static final String FIELD = "ACCTSTTS";

    private static final String MESSAGE = "Account Status must be Y or N";

    @Nested
    @DisplayName("the FailureKind enum: exactly the two states the CSSETATY template renders")
    class FailureKindContract {

        @Test
        @DisplayName("there are exactly two kinds, matching the template's two rendering outcomes")
        void thereAreExactlyTwoKinds() {
            assertThat(FailureKind.values())
                    .as("app/cpy/CSSETATY.cpy tests FLG-<field>-NOT-OK and FLG-<field>-BLANK and nothing "
                            + "else; a third kind would have no rendering branch to correspond to")
                    .hasSize(2)
                    .containsExactly(FailureKind.INVALID, FailureKind.BLANK);
        }

        @Test
        @DisplayName("INVALID is declared first, so it is the natural default of the pair")
        void invalidIsDeclaredFirst() {
            assertThat(FailureKind.INVALID.ordinal())
                    .as("declaration order matters here only in that INVALID is the weaker claim - red "
                            + "attribute without the asterisk - which is what makes it the safe default")
                    .isZero();
            assertThat(FailureKind.BLANK.ordinal()).isEqualTo(1);
        }

        @ParameterizedTest
        @EnumSource(FailureKind.class)
        @DisplayName("both kinds round-trip through valueOf, so either can be carried in a serialised form")
        void bothKindsRoundTripThroughValueOf(final FailureKind kind) {
            assertThat(FailureKind.valueOf(kind.name()))
                    .as("%s must survive a name round trip; the kind may cross a serialisation boundary "
                            + "inside the exception", kind.name())
                    .isSameAs(kind);
        }

        @Test
        @DisplayName("the constant names carry no payload accessor, because the template needs none")
        void theConstantsCarryNoPayload() {
            assertThat(FailureKind.class.getDeclaredFields())
                    .filteredOn(field -> !field.isSynthetic())
                    .as("unlike ConcurrentUpdateException.Outcome, which carries a legacy message and a "
                            + "change-action code, FailureKind is a bare discriminator: the template "
                            + "derives its rendering from the flag alone, so there is nothing to carry")
                    .allMatch(field -> field.getType().isArray() || field.getType().equals(FailureKind.class));
        }
    }

    @Nested
    @DisplayName("the two static factories, which are the intended construction path")
    class StaticFactories {

        @Test
        @DisplayName("invalidField records the field, the message and the INVALID kind")
        void invalidFieldRecordsTheInvalidKind() {
            final ValidationException thrown = ValidationException.invalidField(FIELD, MESSAGE);

            assertThat(thrown.getFieldName())
                    .as("the field name is what CSSETATY's TESTVAR1 and SCRNVAR2 parameters resolve to, so "
                            + "it is the key the screen renderer needs to mark the right field red")
                    .isEqualTo(FIELD);
            assertThat(thrown.getFailureKind()).isEqualTo(FailureKind.INVALID);
            assertThat(thrown.getMessage()).isEqualTo(MESSAGE);
            assertThat(thrown.getCause())
                    .as("a validation outcome has no root cause: nothing failed, the input was simply "
                            + "rejected")
                    .isNull();
        }

        @Test
        @DisplayName("missingField records the field, the message and the BLANK kind")
        void missingFieldRecordsTheBlankKind() {
            final ValidationException thrown = ValidationException.missingField(FIELD, MESSAGE);

            assertThat(thrown.getFailureKind())
                    .as("BLANK is what takes the nested branch at app/cpy/CSSETATY.cpy:L23-L25 and moves "
                            + "'*' into the output field; using INVALID here would drop the asterisk")
                    .isEqualTo(FailureKind.BLANK);
            assertThat(thrown.getFieldName()).isEqualTo(FIELD);
            assertThat(thrown.getMessage()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("the two factories differ ONLY in the kind, which is the asterisk distinction")
        void theTwoFactoriesDifferOnlyInTheKind() {
            final ValidationException invalid = ValidationException.invalidField(FIELD, MESSAGE);
            final ValidationException blank = ValidationException.missingField(FIELD, MESSAGE);

            assertThat(invalid.getFieldName()).isEqualTo(blank.getFieldName());
            assertThat(invalid.getMessage()).isEqualTo(blank.getMessage());
            assertThat(invalid.getFailureKind())
                    .as("both states turn the field red; the kind is the only thing that decides whether "
                            + "the asterisk is also written, so it must be the only difference")
                    .isNotEqualTo(blank.getFailureKind());
        }

        @Test
        @DisplayName("the factories accept a null field name without throwing")
        void theFactoriesAcceptANullFieldName() {
            final ValidationException thrown = ValidationException.invalidField(null, MESSAGE);

            assertThat(thrown.getFieldName()).isNull();
            assertThat(thrown.hasFieldName())
                    .as("a form-level rather than field-level rejection has no field to mark, and that "
                            + "must be expressible without a fabricated placeholder name")
                    .isFalse();
        }

        @Test
        @DisplayName("the factory argument order is field-then-message, the reverse of the constructor")
        void theFactoryArgumentOrderIsFieldThenMessage() {
            final ValidationException viaFactory = ValidationException.invalidField(FIELD, MESSAGE);
            final ValidationException viaConstructor =
                    new ValidationException(MESSAGE, FIELD, FailureKind.INVALID);

            assertThat(viaFactory.getFieldName())
                    .as("the factory reads invalidField(field, message) while the constructor reads "
                            + "(message, field, kind); both are String-typed so a transposition would "
                            + "compile silently - this assertion is the guard against that")
                    .isEqualTo(viaConstructor.getFieldName())
                    .isEqualTo(FIELD);
            assertThat(viaFactory.getMessage()).isEqualTo(viaConstructor.getMessage()).isEqualTo(MESSAGE);
        }
    }

    @Nested
    @DisplayName("the null-kind substitution, which resolves towards INVALID and never towards BLANK")
    class NullKindSubstitution {

        @Test
        @DisplayName("the message-only constructor defaults the kind to INVALID, not null")
        void messageOnlyConstructorDefaultsToInvalid() {
            final ValidationException thrown = new ValidationException(MESSAGE);

            assertThat(thrown.getFailureKind())
                    .as("observed behaviour, pinned deliberately: unlike ConcurrentUpdateException, which "
                            + "leaves a null outcome null, this type substitutes a non-null default - so a "
                            + "caller may always dereference getFailureKind() without a null check")
                    .isEqualTo(FailureKind.INVALID);
            assertThat(thrown.getFieldName()).isNull();
        }

        @Test
        @DisplayName("the message-and-cause constructor also defaults the kind to INVALID")
        void messageAndCauseConstructorDefaultsToInvalid() {
            final IOException cause = new IOException("date service rejected the value");
            final ValidationException thrown = new ValidationException(MESSAGE, cause);

            assertThat(thrown.getFailureKind()).isEqualTo(FailureKind.INVALID);
            assertThat(thrown.getFieldName()).isNull();
            assertThat(thrown.getCause())
                    .as("a validation failure that wraps a lower-level rejection must keep it: the date "
                            + "utility's feedback code is the actionable detail")
                    .isSameAs(cause);
        }

        @Test
        @DisplayName("an explicitly null kind on the three-argument constructor becomes INVALID")
        void explicitNullKindOnThreeArgumentConstructorBecomesInvalid() {
            final ValidationException thrown = new ValidationException(MESSAGE, FIELD, null);

            assertThat(thrown.getFailureKind())
                    .as("substituting towards INVALID is the conservative choice: it turns the field red "
                            + "without asserting the asterisk that BLANK would add, so the substitution "
                            + "never renders more than the caller claimed")
                    .isEqualTo(FailureKind.INVALID)
                    .isNotEqualTo(FailureKind.BLANK);
            assertThat(thrown.getFieldName()).isEqualTo(FIELD);
        }

        @Test
        @DisplayName("an explicitly null kind on the four-argument constructor becomes INVALID")
        void explicitNullKindOnFourArgumentConstructorBecomesInvalid() {
            final IOException cause = new IOException("lookup table unavailable");
            final ValidationException thrown = new ValidationException(MESSAGE, FIELD, null, cause);

            assertThat(thrown.getFailureKind()).isEqualTo(FailureKind.INVALID);
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @ParameterizedTest
        @EnumSource(FailureKind.class)
        @DisplayName("a non-null kind is never substituted, in either the three- or four-argument form")
        void aNonNullKindIsNeverSubstituted(final FailureKind kind) {
            assertThat(new ValidationException(MESSAGE, FIELD, kind).getFailureKind())
                    .as("%s was supplied explicitly and must survive the constructor unchanged", kind.name())
                    .isSameAs(kind);
            assertThat(new ValidationException(MESSAGE, FIELD, kind, null).getFailureKind())
                    .isSameAs(kind);
        }

        @ParameterizedTest
        @EnumSource(FailureKind.class)
        @DisplayName("getFailureKind is total: it never returns null on any construction path")
        void getFailureKindIsTotal(final FailureKind kind) {
            assertThat(new ValidationException(MESSAGE).getFailureKind()).isNotNull();
            assertThat(new ValidationException(MESSAGE, (Throwable) null).getFailureKind()).isNotNull();
            assertThat(new ValidationException(MESSAGE, FIELD, kind).getFailureKind()).isNotNull();
            assertThat(new ValidationException(MESSAGE, FIELD, null, null).getFailureKind())
                    .as("every path yields a usable kind, which is what lets a screen renderer branch "
                            + "without a null guard")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("the fieldName policy: stored raw, blank-checked at read time by hasFieldName")
    class FieldNamePolicy {

        @Test
        @DisplayName("a present field name makes hasFieldName true")
        void aPresentFieldNameMakesHasFieldNameTrue() {
            assertThat(ValidationException.invalidField(FIELD, MESSAGE).hasFieldName())
                    .as("a named field is what the renderer needs in order to resolve CSSETATY's SCRNVAR2 "
                            + "parameter to a concrete screen field")
                    .isTrue();
        }

        @Test
        @DisplayName("a null field name makes hasFieldName false")
        void aNullFieldNameMakesHasFieldNameFalse() {
            assertThat(new ValidationException(MESSAGE).hasFieldName()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "     ", "\t", "\n"})
        @DisplayName("a blank field name makes hasFieldName false but is STILL returned verbatim")
        void aBlankFieldNameIsFalseButStillReturnedVerbatim(final String blank) {
            final ValidationException thrown = ValidationException.invalidField(blank, MESSAGE);

            assertThat(thrown.hasFieldName())
                    .as("a blank name cannot identify a screen field, so the renderer must be told there "
                            + "is nothing to mark")
                    .isFalse();
            assertThat(thrown.getFieldName())
                    .as("observed behaviour, pinned deliberately: this type does NOT convert blank to null "
                            + "in the constructor - it stores the value raw and defers the emptiness "
                            + "question to hasFieldName(). RecordNotFoundException converts blank to null, "
                            + "and ConcurrentUpdateException converts blank to null AND strips. Three "
                            + "sibling types, three policies; none may be inferred from another")
                    .isEqualTo(blank)
                    .isNotNull();
        }

        @Test
        @DisplayName("a padded field name is NOT stripped, unlike the ConcurrentUpdateException policy")
        void aPaddedFieldNameIsNotStripped() {
            final ValidationException thrown = ValidationException.invalidField("  ACCTSTTS  ", MESSAGE);

            assertThat(thrown.getFieldName())
                    .as("observed behaviour: no strip is applied here, so a caller passing a "
                            + "space-padded COBOL field name gets it back padded; the value is a symbolic "
                            + "map field name supplied by our own code, not a data value read from a record")
                    .isEqualTo("  ACCTSTTS  ");
            assertThat(thrown.hasFieldName())
                    .as("hasFieldName uses isBlank(), which is false for a padded non-empty value, so a "
                            + "padded name still counts as present")
                    .isTrue();
        }

        @Test
        @DisplayName("hasFieldName and getFieldName agree on every non-blank value")
        void hasFieldNameAndGetFieldNameAgreeOnNonBlankValues() {
            final ValidationException thrown = ValidationException.missingField("CUSTFNAM", MESSAGE);

            assertThat(thrown.hasFieldName()).isTrue();
            assertThat(thrown.getFieldName())
                    .as("when hasFieldName reports true, getFieldName must yield something a renderer can "
                            + "actually use")
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("hierarchy placement and the parity of a rejection against a failure")
    class HierarchyPlacement {

        @Test
        @DisplayName("it is a CardDemoException, so the shared boundary handler catches it")
        void itIsACardDemoException() {
            assertThat(new ValidationException(MESSAGE))
                    .isInstanceOf(CardDemoException.class)
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("the message is a legacy screen literal and is carried through untouched")
        void theMessageIsCarriedThroughUntouched() {
            final String legacy = "Acct Status must be Y or N";
            final ValidationException thrown = ValidationException.invalidField("ACSTTUS", legacy);

            assertThat(thrown.getMessage())
                    .as("these strings are displayed verbatim by the legacy screen and are compared byte "
                            + "for byte by the parity gate; no trimming, casing or punctuation change")
                    .isEqualTo(legacy);
        }

        @Test
        @DisplayName("it is distinct from every I/O translation, because a rejection is not a failure")
        void itIsDistinctFromTheIoTranslations() {
            assertThat(ValidationException.class.getSuperclass())
                    .as("a rejected input is a business outcome, not a FILE STATUS; it must not be "
                            + "catchable as one of the I/O exception types")
                    .isEqualTo(CardDemoException.class);
        }
    }
}
