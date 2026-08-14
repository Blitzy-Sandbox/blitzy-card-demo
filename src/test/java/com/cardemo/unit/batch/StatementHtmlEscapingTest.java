/*
 * ******************************************************************
 * Program     : StatementHtmlEscapingTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves that customer and account values carrying markup
 *               cannot escape their surrounding element in the HTML
 *               statement stream, that the encoding is applied exactly
 *               once, and that the 100-byte HTML and 80-byte text record
 *               widths survive the expansion escaping causes.
 * Source      : app/cbl/CBSTM03A.CBL:L149  (PIC X(100) HTML line)
 *               app/cbl/CBSTM03A.CBL:L628  (FICO score label)
 *               app/cpy/CVCUS01Y.cpy       (name and address widths)
 *               app/cpy/CVACT01Y.cpy       (300-byte account record)
 *               app/jcl/CREASTMT.JCL:STEP040 (LRECL 80 and 100) @ 7756d89
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
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.service.shared.FileService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The cross-site-scripting boundary of the statement writer, asserted against hostile field values.
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} emits its HTML statement from a run of literal fragments over a single
 * {@code PIC X(100)} field and interpolates customer and account values between them. On a 3270 terminal
 * writing to a sequential dataset that was inert; the same construction reaching a browser as
 * {@code text/html} is not, because a value carrying markup closes the surrounding element and the rest
 * of the value is parsed as markup. The source cannot be cited as authority for the absence of escaping:
 * it had no browser to protect, so it took no position on the question.
 *
 * <p>These tests drive {@link StatementProcessor#process(CardCrossReference)} with a mocked
 * {@link FileService} whose fixed-width payloads carry markup in every interpolated position, and assert
 * three properties that have to hold together. The markup must not survive; the entity encoding must be
 * present; and every emitted line must still be exactly
 * {@code StatementTransaction.STATEMENT_HTML_RECORD_LENGTH} characters, because escaping expands a value
 * and a fixed-width record that grew is as broken as one that leaked.
 */
@DisplayName("StatementProcessor: hostile field values cannot escape their HTML element")
class StatementHtmlEscapingTest {

    /** Width of every HTML record, from the {@code PIC X(100)} of {@code app/cbl/CBSTM03A.CBL:L149}. */
    private static final int HTML_LINE_WIDTH = 100;

    /** Width of every text record, {@code LRECL=80} on {@code STMTFILE}. */
    private static final int TEXT_LINE_WIDTH = 80;

    /** {@code CUST-ID PIC 9(09)} plus the three {@code PIC X(25)} name parts and three {@code X(50)} lines. */
    private static final int CUSTOMER_RECORD_LENGTH = 500;

    /** {@code ACCT-ID PIC 9(11)} leading a 300-byte {@code app/cpy/CVACT01Y.cpy} record. */
    private static final int ACCOUNT_RECORD_LENGTH = 300;

    /**
     * A payload that closes an element, opens a script and re-opens the element, plus an attribute
     * breakout. Twenty-five characters exactly, so it fits {@code CUST-FIRST-NAME PIC X(25)} whole and the
     * assertions are not weakened by the field truncating the interesting part away.
     */
    private static final String HOSTILE_25 = "</p><script>x=1</script>&";

    /** The same idea at fifty characters, for the three {@code CUST-ADDR-LINE-n PIC X(50)} fields. */
    private static final String HOSTILE_50 = "\"><img src=x onerror='alert(1)'></p><script>y</script>";

    /** A benign name of the same width as {@link #HOSTILE_25}, for the invariance comparison. */
    private static final String BENIGN_25 = "MARGARET GOLD............";

    /** A benign address line of the same width as {@link #HOSTILE_50}. */
    private static final String BENIGN_50 = "1 HIGH STREET.....................................";

    /**
     * Builds a processor over a store whose customer name and address fields carry the supplied values.
     *
     * @param namePart the value placed in all three {@code PIC X(25)} name fields
     * @param addressLine the value placed in all three {@code PIC X(50)} address fields
     * @return a processor ready for a single {@code process} call, never {@code null}
     */
    private static StatementProcessor processorOver(final String namePart, final String addressLine) {
        final FileService fileService = mock(FileService.class);

        when(fileService.readAcceptingSecondaryStatus(eq(FileService.Dd.TRNXFILE)))
                .thenReturn(" ".repeat(350));
        when(fileService.readNext(eq(FileService.Dd.TRNXFILE))).thenReturn(Optional.empty());
        when(fileService.readByKey(eq(FileService.Dd.CUSTFILE), anyString(), anyInt()))
                .thenReturn(customerRecord(namePart, addressLine));
        when(fileService.readByKey(eq(FileService.Dd.ACCTFILE), anyString(), anyInt()))
                .thenReturn(accountRecord());

        return new StatementProcessor(fileService);
    }

    /**
     * Builds the 500-byte customer image the processor slices.
     *
     * @param namePart the value for each of the three name fields
     * @param addressLine the value for each of the three address fields
     * @return exactly {@value #CUSTOMER_RECORD_LENGTH} characters
     */
    private static String customerRecord(final String namePart, final String addressLine) {
        StringBuilder record = new StringBuilder(CUSTOMER_RECORD_LENGTH);
        record.append("000000001");                       // CUST-ID PIC 9(09)
        record.append(fit(namePart, 25));                 // CUST-FIRST-NAME
        record.append(fit(namePart, 25));                 // CUST-MIDDLE-NAME
        record.append(fit(namePart, 25));                 // CUST-LAST-NAME
        record.append(fit(addressLine, 50));              // CUST-ADDR-LINE-1
        record.append(fit(addressLine, 50));              // CUST-ADDR-LINE-2
        record.append(fit(addressLine, 50));              // CUST-ADDR-LINE-3
        return fit(record.toString(), CUSTOMER_RECORD_LENGTH);
    }

    /**
     * Builds a well-formed 300-byte account image: the account identifier and the FICO score are numeric
     * fields, so hostile text belongs in the customer record rather than here.
     *
     * @return exactly {@value #ACCOUNT_RECORD_LENGTH} characters
     */
    private static String accountRecord() {
        StringBuilder record = new StringBuilder(ACCOUNT_RECORD_LENGTH);
        record.append("00000000011");                     // ACCT-ID PIC 9(11)
        record.append('Y');                               // ACCT-ACTIVE-STATUS PIC X(01)
        record.append("000000019400");                    // ACCT-CURR-BAL
        record.append("000000202000");                    // ACCT-CREDIT-LIMIT
        record.append("000000102000");                    // ACCT-CASH-CREDIT-LIMIT
        return fit(record.toString(), ACCOUNT_RECORD_LENGTH);
    }

    /**
     * Pads on the right or truncates on the right, which is what a COBOL {@code MOVE} into an
     * alphanumeric item of the given width does.
     *
     * @param value the value to fit, never {@code null}
     * @param width the target width
     * @return exactly {@code width} characters
     */
    private static String fit(final String value, final int width) {
        return value.length() >= width ? value.substring(0, width)
                : value + " ".repeat(width - value.length());
    }

    /**
     * Drives one statement over the hostile store.
     *
     * @return the rendered statement, never {@code null}
     */
    private static StatementProcessor.Statement renderHostileStatement() {
        return processorOver(HOSTILE_25, HOSTILE_50)
                .process(new CardCrossReference("4111111111111111", 1L, 11L));
    }

    /**
     * Drives one statement over a store holding benign values of identical width.
     *
     * @return the rendered statement, never {@code null}
     */
    private static StatementProcessor.Statement renderBenignStatement() {
        return processorOver(BENIGN_25, BENIGN_50)
                .process(new CardCrossReference("4111111111111111", 1L, 11L));
    }

    /**
     * Counts one character in a line.
     *
     * @param line the line to scan, never {@code null}
     * @param character the character to count
     * @return the number of occurrences
     */
    private static long countOf(final String line, final char character) {
        return line.chars().filter(candidate -> candidate == character).count();
    }

    @Nested
    @DisplayName("1. The markup does not survive into the HTML stream")
    class MarkupIsNeutralised {

        @Test
        @DisplayName("No HTML line reopens a tag from an interpolated value")
        void noInterpolatedTagSurvives() {
            final List<String> html = renderHostileStatement().htmlLines();

            assertThat(html).isNotEmpty();
            assertThat(html).noneMatch(line -> line.contains("<script"));
            assertThat(html).noneMatch(line -> line.contains("</script"));
            assertThat(html).noneMatch(line -> line.contains("<img"));
        }

        @Test
        @DisplayName("A hostile value contributes no angle bracket, so the tag structure is unchanged")
        void theValueCannotAlterTheTagStructure() {
            final List<String> hostile = renderHostileStatement().htmlLines();
            final List<String> benign = renderBenignStatement().htmlLines();

            // This is the security property stated exactly. The literal fragments of
            // app/cbl/CBSTM03A.CBL legitimately contain angle brackets; an interpolated value must
            // contribute none. Two renders that differ only in the field values must therefore carry an
            // identical count of '<' and '>' on every line, line for line. If a single bracket of a value
            // survived anywhere, one count would differ and this fails.
            assertThat(hostile).hasSameSizeAs(benign);
            for (int line = 0; line < hostile.size(); line++) {
                assertThat(countOf(hostile.get(line), '<'))
                        .as("'<' count on HTML line %d", line)
                        .isEqualTo(countOf(benign.get(line), '<'));
                assertThat(countOf(hostile.get(line), '>'))
                        .as("'>' count on HTML line %d", line)
                        .isEqualTo(countOf(benign.get(line), '>'));
            }
        }

        @Test
        @DisplayName("An attribute name surviving as inert text is not an escape: it carries no bracket")
        void anAttributeNameInTextIsInert() {
            final String joined = String.join("", renderHostileStatement().htmlLines());

            // The word onerror is not a metacharacter and is not encoded; what makes it harmless is that
            // the '<' that would have opened a tag around it arrives as &lt;. Asserted directly: every
            // occurrence of the word is preceded, within eight characters, by an encoded bracket rather
            // than by a raw one.
            int at = joined.indexOf("onerror");
            assertThat(at).as("the hostile attribute name reached the output as text").isPositive();
            while (at >= 0) {
                final String preceding = joined.substring(Math.max(0, at - 8), at);
                assertThat(preceding)
                        .as("text before <onerror> at %d is not a raw tag", at)
                        .doesNotContain("<");
                at = joined.indexOf("onerror", at + 1);
            }
        }

        @Test
        @DisplayName("The value's angle brackets, quotes and ampersands arrive as entities")
        void theValueArrivesEncoded() {
            final String joined = String.join("", renderHostileStatement().htmlLines());

            assertThat(joined).contains("&lt;");
            assertThat(joined).contains("&gt;");
            assertThat(joined).contains("&amp;");
        }

        @Test
        @DisplayName("An ampersand is encoded once, never double-encoded into &amp;amp;")
        void theAmpersandIsEncodedExactlyOnce() {
            final String joined = String.join("", renderHostileStatement().htmlLines());

            assertThat(joined).doesNotContain("&amp;amp;");
            assertThat(joined).doesNotContain("&amp;lt;");
        }
    }

    @Nested
    @DisplayName("2. Escaping does not break the fixed-width record contract")
    class WidthSurvivesEscaping {

        @Test
        @DisplayName("Every HTML line is exactly one hundred characters")
        void everyHtmlLineKeepsItsWidth() {
            assertThat(renderHostileStatement().htmlLines())
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(line).hasSize(HTML_LINE_WIDTH));
        }

        @Test
        @DisplayName("Every text line is exactly eighty characters")
        void everyTextLineKeepsItsWidth() {
            assertThat(renderHostileStatement().textLines())
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(line).hasSize(TEXT_LINE_WIDTH));
        }

        @Test
        @DisplayName("No HTML line ends inside an unterminated entity")
        void noLineIsCutMidEntity() {
            final List<String> html = renderHostileStatement().htmlLines();

            assertThat(html).allSatisfy(line -> {
                final String trailing = line.stripTrailing();
                final int lastAmpersand = trailing.lastIndexOf('&');
                if (lastAmpersand >= 0) {
                    // Either the run beginning at the last ampersand is a complete entity, or there is no
                    // ampersand left in the line at all. A line that ends "...&a" would be neither.
                    assertThat(trailing.indexOf(';', lastAmpersand))
                            .as("entity beginning at %d in <%s> is terminated", lastAmpersand, trailing)
                            .isGreaterThan(lastAmpersand);
                }
            });
        }
    }
}
