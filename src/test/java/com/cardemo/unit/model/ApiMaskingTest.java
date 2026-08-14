/*
 * ******************************************************************
 * Program     : ApiMaskingTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 unit test
 * Function    : Verifies the two emission rules of ApiMasking: the
 *               card-number mask that keeps a primary account number
 *               out of an HTTP response body, and the control-character
 *               neutralisation that keeps a caller-supplied value from
 *               forging a log record.
 * Source      : app/cpy/CVACT02Y.cpy:L5 (CARD-NUM PIC X(16), the
 *               16-byte primary account number the 3270 screens
 *               displayed in full) @ 7756d89
 * Source      : app/cpy-bms/COSGN00.CPY (USERIDI PIC X(8)),
 *               app/cpy-bms/COUSR02.CPY, app/cpy-bms/COTRN02.CPY
 *               - the request field contracts whose values reach a
 *               diagnostic rendering @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.dto.ApiMasking;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two rules by which a value is reduced before it may be emitted.
 *
 * <p>The masking rule protects a value the caller must not <em>see</em>. The neutralisation rule protects a
 * log record from a value the caller <em>chose</em>. They live in one class because both answer the same
 * question - how may this value be emitted - and they are tested together for the same reason.
 *
 * <p>The neutralisation assertions exist because six request and response types render their own fields into a
 * diagnostic string. Every field they render is declared {@code String} and arrives from a JSON request body,
 * so a CR or LF in any of them forged log records in the exact shape a reader trusts. The timing is what made
 * it reachable rather than theoretical: {@code @Size} runs after Jackson has constructed the record, and a
 * validation failure is precisely the occasion on which something renders the offending instance - so the
 * rendering has to be safe on an instance that never passed validation and never will.
 */
@DisplayName("ApiMasking - the card mask, and the rendering that cannot forge a log record")
class ApiMaskingTest {

    /** A value long enough to prove the diagnostic bound truncates, and distinctive enough to trace. */
    private static final String OVER_LONG = "z".repeat(ApiMasking.DIAGNOSTIC_MAX_LENGTH + 40);

    @Nested
    @DisplayName("the card-number mask")
    class CardNumberMask {

        @Test
        @DisplayName("a sixteen-digit number keeps only its last four, and its length is preserved")
        void sixteenDigitsKeepOnlyTheLastFour() {
            final String masked = ApiMasking.maskCardNumber("4111111111111111");

            assertThat(masked)
                    .as("the last four identify the card to its holder; every earlier position is masked")
                    .isEqualTo("*" .repeat(12) + "1111");
            assertThat(masked)
                    .as("length is preserved so a sixteen-digit number stays distinguishable from a "
                            + "malformed one without disclosing the number")
                    .hasSize(16);
        }

        @Test
        @DisplayName("a value of four characters or fewer is masked in full")
        void shortValuesAreMaskedEntirely() {
            assertThat(ApiMasking.maskCardNumber("1234"))
                    .as("showing the last four of a four-character value would show all of it")
                    .isEqualTo("****");
        }

        @Test
        @DisplayName("null and blank are returned unchanged, so absence stays distinct from masking")
        void nullAndBlankAreUnchanged() {
            assertThat(ApiMasking.maskCardNumber(null)).isNull();
            assertThat(ApiMasking.maskCardNumber("   "))
                    .as("the card-list rows of app/cbl/COCRDLIC.cbl are padded to the seven-row table "
                            + "depth with blanks, and masking a blank filler row would invent a card")
                    .isEqualTo("   ");
        }

        @Test
        @DisplayName("trailing padding is preserved and the mask applies to the significant extent only")
        void trailingPaddingIsPreserved() {
            assertThat(ApiMasking.maskCardNumber("4111111111111111    "))
                    .as("the symbolic maps declare fixed-width fields, so padding is significant")
                    .isEqualTo("*".repeat(12) + "1111    ");
        }
    }

    @Nested
    @DisplayName("the diagnostic rendering: no character may terminate a log record")
    class DiagnosticRendering {

        /**
         * The code points are given as decimal numbers rather than as escape sequences.
         *
         * <p>{@code @CsvSource} does not process Java escape sequences, so a cell written as a backslash
         * followed by {@code r} arrives as those two printable characters - which
         * {@link ApiMasking#forDiagnostics(String)} correctly leaves alone, making the test assert nothing at
         * all while appearing to cover the case. A number cannot be misread that way.
         *
         * @param name the character's Unicode name, for the failure message
         * @param codePoint the character to inject, as a decimal code point
         */
        @ParameterizedTest(name = "{0} (U+{1}) is escaped rather than emitted")
        @CsvSource({
            "CARRIAGE RETURN,       13",
            "LINE FEED,             10",
            "TAB,                    9",
            "NUL,                    0",
            "UNIT SEPARATOR,        31",
            "DEL,                  127",
            "NEL,                  133",
            "LINE SEPARATOR,      8232",
            "PARAGRAPH SEPARATOR, 8233",
        })
        @DisplayName("every control character and Unicode separator becomes its own code point")
        void controlCharactersBecomeCodePoints(final String name, final int codePoint) {
            final char injected = (char) codePoint;
            // Assembled without the two-character escape-introducing sequence appearing in this source: Java
            // translates unicode escapes before lexing, so writing it directly can make the compiler read the
            // format specifier as malformed hexadecimal.
            final String expectedEscape = "\\" + String.format(java.util.Locale.ROOT, "u%04X", codePoint);

            final String rendered = ApiMasking.forDiagnostics("AB" + injected + "CD");

            assertThat(rendered)
                    .as("%s must not survive as itself: the log format writes one record per line, so this "
                            + "character would forge a record boundary", name)
                    .isEqualTo("AB" + expectedEscape + "CD");
            assertThat(rendered.indexOf(injected))
                    .as("and the raw character must be absent from the rendering entirely")
                    .isEqualTo(-1);
        }

        @Test
        @DisplayName("a full forged log line is neutralised, prefix and all")
        void aForgedLogLineIsNeutralised() {
            final String forgery = "ADMIN001\r\n2026-08-04 INFO  c.c.s.AuthenticationService : identity "
                    + "established for ATTACKER";

            final String rendered = ApiMasking.forDiagnostics(forgery);

            assertThat(rendered)
                    .as("the payload is retained for investigation - a reader needs to see what arrived - "
                            + "but it can no longer be a separate line")
                    .contains("ATTACKER")
                    .doesNotContain("\r")
                    .doesNotContain("\n");
            assertThat(rendered.lines().count())
                    .as("and the whole rendering is exactly one line, which is the property that matters")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the escape names the code point rather than hiding it behind a placeholder")
        void theEscapeNamesTheCodePoint() {
            assertThat(ApiMasking.forDiagnostics("\r"))
                    .as("a reader investigating a hostile request needs to know WHICH byte arrived; a fixed "
                            + "placeholder would neutralise the character and the evidence together")
                    .isEqualTo("\\u000D");
        }

        @Test
        @DisplayName("an over-long value is truncated and says so, stating its original length")
        void anOverLongValueIsTruncatedAndSaysSo() {
            final String rendered = ApiMasking.forDiagnostics(OVER_LONG);

            assertThat(rendered)
                    .as("the bound is needed because the length constraints have not necessarily run: an "
                            + "instance being rendered BECAUSE it failed validation may hold any length")
                    .startsWith("z".repeat(ApiMasking.DIAGNOSTIC_MAX_LENGTH))
                    .contains("...(" + OVER_LONG.length() + " chars)");
            assertThat(rendered.length())
                    .as("and a bounded record never silently hides that it was bounded: the retained prefix "
                            + "plus the marker is shorter than the value that arrived")
                    .isNotEqualTo(OVER_LONG.length());
        }

        @Test
        @DisplayName("a value at exactly the bound is not truncated and carries no marker")
        void aValueAtTheBoundIsNotTruncated() {
            final String exact = "y".repeat(ApiMasking.DIAGNOSTIC_MAX_LENGTH);

            assertThat(ApiMasking.forDiagnostics(exact))
                    .as("the bound is inclusive, so the off-by-one that would truncate a legitimate "
                            + "maximum-length value is excluded")
                    .isEqualTo(exact)
                    .doesNotContain("chars)");
        }

        @Test
        @DisplayName("truncation happens before escaping, so the bound cannot be exceeded by expansion")
        void truncationPrecedesEscaping() {
            final String hostile = "\r".repeat(ApiMasking.DIAGNOSTIC_MAX_LENGTH * 2);

            final String rendered = ApiMasking.forDiagnostics(hostile);

            assertThat(rendered)
                    .as("each escape is six characters, so escaping first and truncating second would let a "
                            + "control-character run inflate the record sixfold - the opposite of the bound's "
                            + "purpose. Only the retained prefix is escaped.")
                    .startsWith("\\u000D")
                    .hasSize(ApiMasking.DIAGNOSTIC_MAX_LENGTH * 6
                            + ("...(" + hostile.length() + " chars)").length());
        }

        @ParameterizedTest(name = "a benign value [{0}] is returned unchanged")
        @ValueSource(strings = {"ADMIN001", "USER0001", "COSGN00C", "CC00", "  padded  ", "a-b_c.d", "0"})
        @DisplayName("a value with nothing to neutralise is returned byte for byte")
        void benignValuesAreUnchanged(final String benign) {
            assertThat(ApiMasking.forDiagnostics(benign))
                    .as("the rule must be invisible on legitimate input, or it would change what every "
                            + "ordinary log record says")
                    .isEqualTo(benign);
        }

        @Test
        @DisplayName("null is preserved, so an absent field stays distinct from an empty one")
        void nullIsPreserved() {
            assertThat(ApiMasking.forDiagnostics(null))
                    .as("a rendering that turned null into \"\" would make a missing field and a blank field "
                            + "indistinguishable in exactly the diagnostics used to tell them apart")
                    .isNull();
            assertThat(ApiMasking.forDiagnostics("")).isEmpty();
        }

        @Test
        @DisplayName("the bound is derived from the widest declared field, with room to spare")
        void theBoundIsDerivedFromTheFieldContracts() {
            assertThat(ApiMasking.DIAGNOSTIC_MAX_LENGTH)
                    .as("the widest PIC X(n) any symbolic map in app/cpy-bms contributes is 80 characters, "
                            + "so the bound must exceed it comfortably rather than clip legitimate values")
                    .isGreaterThan(80)
                    .isEqualTo(128);
        }
    }

    @Test
    @DisplayName("the class is a rule holder and refuses instantiation")
    void theClassRefusesInstantiation() throws ReflectiveOperationException {
        final Constructor<ApiMasking> constructor = ApiMasking.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatExceptionOfType(InvocationTargetException.class)
                .as("two static rules and no state: an instance would imply per-instance behaviour that "
                        + "does not exist")
                .isThrownBy(constructor::newInstance)
                .withCauseInstanceOf(AssertionError.class);
    }
}
