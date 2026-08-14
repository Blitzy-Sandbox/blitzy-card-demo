/*
 * ******************************************************************
 * Program     : FileStatusDiagnosticEncodingTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves that an unrecognised COBOL FILE STATUS cannot forge
 *               a log record or drive an operator terminal through the
 *               IllegalArgumentException classify raises: every character
 *               outside printable ASCII is encoded, the encoding is
 *               injective, and no recognised status changes behaviour.
 * Source      : app/cbl/CBTRN02C.cbl:L707-L711 (abend 999, RC 12)
 *               app/cbl/CBTRN02C.cbl:L714-L727 (9910-DISPLAY-IO-STATUS)
 *               app/cbl/CBTRN02C.cbl:L142-L144 (APPL-RESULT guard) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.enums.FileStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The log-injection boundary of {@link FileStatus#classify(String)}.
 *
 * <p>{@code classify} throws when the status is not one this enum recognises, and the value it names in the
 * message is by definition the one that failed recognition - untrusted, arbitrary, and destined for a log
 * through the exception message. A value carrying a carriage return and a line feed could therefore forge a
 * second log record; one carrying an ANSI escape could rewrite the terminal of whoever tails the log
 * (CWE-117). The class already owns {@link FileStatus#escapeForDiagnostics(String)}; the defect was owning
 * the encoder and not applying it at this site.
 *
 * <p>These tests assert the encoder is applied, that the encoding is injective so a forged record cannot
 * impersonate an encoded one, and that classification of every legitimate status is unaffected.
 */
@DisplayName("FileStatus.classify: an unrecognised status cannot forge a log record")
class FileStatusDiagnosticEncodingTest {

    /**
     * The escape prefix, assembled rather than written.
     * <p>
     * The two characters backslash and {@code u} cannot appear adjacent in Java source even inside a string
     * literal: the compiler's unicode-escape preprocessor runs before the lexer and treats a {@code u}
     * preceded by an even number of backslashes as the start of an escape, so the literal would be rewritten
     * into whatever code point followed it. Concatenating the two halves keeps the source lexable while
     * producing the exact two characters the encoder emits.
     */
    private static final String ESCAPE_PREFIX = "\\" + "u";

    @Nested
    @DisplayName("1. No control character reaches the diagnostic")
    class ControlCharactersAreEncoded {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "\r\n", "\n\n", "\r", "\u001b[2J", "\u0000", "\u007f", "a\nb", "\t\t", "\u0085", "\u2028",
        })
        @DisplayName("A hostile status yields a message with no character outside printable ASCII")
        void theMessageIsPrintableAsciiOnly(final String hostileStatus) {
            final String message = messageFor(hostileStatus);

            assertThat(message.chars())
                    .allSatisfy(character -> assertThat(character).isBetween(0x20, 0x7e));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"\r\n", "\n", "\r", "a\nb"})
        @DisplayName("Neither a carriage return nor a line feed survives, so no second record can be forged")
        void noLineBreakSurvives(final String hostileStatus) {
            final String message = messageFor(hostileStatus);

            assertThat(message).doesNotContain("\r").doesNotContain("\n");
            assertThat(message.lines()).hasSize(1);
        }

        @Test
        @DisplayName("A line break is rendered as its escape rather than dropped, so nothing is lost")
        void theBreakIsEncodedNotRemoved() {
            assertThat(messageFor("\r\n"))
                    .contains(ESCAPE_PREFIX + "000d")
                    .contains(ESCAPE_PREFIX + "000a");
        }

        @Test
        @DisplayName("An escape sequence intended for the operator's terminal is neutralised")
        void theAnsiEscapeIsNeutralised() {
            final String message = messageFor("\u001b[2J");

            assertThat(message).doesNotContain("\u001b");
            assertThat(message).contains(ESCAPE_PREFIX + "001b");
        }
    }

    @Nested
    @DisplayName("2. The encoding is injective, so a forged record cannot impersonate an encoded one")
    class TheEncodingIsInjective {

        @Test
        @DisplayName("A literal backslash-u sequence in the value is distinguishable from an encoded break")
        void aLiteralEscapeIsNotConfusedWithARealOne() {
            // A backslash followed by u000a, typed literally, must not render identically to a real line
            // feed that the encoder rendered, or a forged record could be made to look sanitised.
            final String literalEscape = ESCAPE_PREFIX + "000a";
            assertThat(messageFor(literalEscape)).isNotEqualTo(messageFor("\n"));
            assertThat(messageFor(literalEscape)).contains("\\" + literalEscape);
        }

        @Test
        @DisplayName("A null status is named as null rather than as an empty bracket pair")
        void aNullStatusIsNamedExplicitly() {
            // escapeForDiagnostics answers the empty string for null by design, which at this site would be
            // indistinguishable from a status of two spaces. The literal is not attacker-supplied.
            assertThat(messageFor(null)).contains("[null]");
            assertThat(messageFor("  ")).contains("[  ]");
            assertThat(messageFor(null)).isNotEqualTo(messageFor("  "));
        }
    }

    @Nested
    @DisplayName("3. Encoding the diagnostic changes no classification outcome")
    class ClassificationIsUnaffected {

        @ParameterizedTest(name = "[{index}] status {0}")
        @ValueSource(strings = {"00", "04", "10", "22", "23", "35", "90", "91", "99"})
        @DisplayName("Every status the enum recognises still classifies without throwing")
        void everyRecognisedStatusStillClassifies(final String recognised) {
            assertThat(FileStatus.classify(recognised)).isNotNull();
            assertThat(FileStatus.tryClassify(recognised)).isPresent();
        }

        @Test
        @DisplayName("An unrecognised status still names the recognised set and the legacy abend contract")
        void theDiagnosticStillTeaches() {
            final String message = messageFor("ZZ");

            assertThat(message).contains("IO-STATUS PIC X(02)");
            assertThat(message).contains("[ZZ]");
            assertThat(message).contains("999");
            assertThat(message).contains("app/cbl/CBTRN02C.cbl:L707-L711");
        }
    }

    /**
     * Captures the message {@code classify} rejects a status with.
     *
     * @param ioStatus the status to reject, which may be {@code null}
     * @return the exception message, never {@code null}
     */
    private static String messageFor(final String ioStatus) {
        final IllegalArgumentException rejection =
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .isThrownBy(() -> FileStatus.classify(ioStatus))
                        .actual();
        assertThat(rejection.getMessage()).isNotNull();
        return rejection.getMessage();
    }
}
