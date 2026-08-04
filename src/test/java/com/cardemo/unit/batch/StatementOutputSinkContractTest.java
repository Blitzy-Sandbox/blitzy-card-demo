/*
 * ******************************************************************
 * Program     : StatementOutputSinkContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (output-sink security contract)
 * Function    : Prove that no persisted value can reach the statement
 *               HTML sink as markup, and that neither statement key
 *               segment can be influenced beyond its field contract -
 *               while the 80-byte text sink and the 100-byte HTML
 *               geometry stay byte-faithful to the legacy layout.
 * Source      : app/cbl/CBSTM03A.CBL:L149 (HTML-FIXED-LN PIC X(100))
 *               app/cbl/CBSTM03A.CBL:L529-L530 (HTML-L11 account banner)
 *               app/cbl/CBSTM03A.CBL:L560-L592 (name and address STRINGs)
 *               app/cbl/CBSTM03A.CBL:L613-L633 (HTML-BSIC-LN rows)
 *               app/cbl/CBSTM03A.CBL:L686-L716 (HTML-TRAN-LN cells)
 *               app/cpy/CVCUS01Y.cpy:6-11 (the PIC X name/address fields)
 *               app/cpy/COSTM01.CPY:29 (TRNX-DESC PIC X(100))
 *               app/cpy/CVACT01Y.cpy:5 (ACCT-ID PIC 9(11))
 *               app/jcl/CREASTMT.JCL:L91, :L94, :L96 (the two datasets)
 *               @ 7756d89
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
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.service.shared.FileService;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.item.Chunk;

/**
 * The output-sink security contract of the two statement emitters.
 *
 * <h2>What this class proves</h2>
 *
 * <p>Two distinct claims, both about data that a caller controls and neither about arithmetic.
 *
 * <p><strong>Nothing persisted can become markup.</strong> Eleven rows of the statement HTML interpolate
 * stored values into tags - the account banner, the customer name row, three address rows, three
 * basic-detail rows and three transaction cells - and every field feeding them is {@code PIC X}
 * ({@code app/cpy/CVCUS01Y.cpy:6-11}, {@code app/cpy/COSTM01.CPY:29}), which accepts {@code <} as readily
 * as a letter. The proof used here is deliberately not "the payload I sent does not appear": that only
 * tests the payloads someone thought of. It is that <strong>the markup skeleton is identical whether the
 * data is benign or hostile</strong> - the same tags, in the same order, and the same count of every
 * markup-significant character. A single unescaped character anywhere in any of the twenty-two sites
 * across the two classes changes that skeleton and fails the assertion.
 *
 * <p><strong>Nothing persisted can move an object key.</strong> The account and month arguments become
 * key segments, so each is checked against the form its field contract allows -
 * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:5} and the {@code yyyy-MM} month convention -
 * and every traversal, separator, control-character and percent-escape spelling is refused before a key
 * is composed at all.
 *
 * <h2>What it also protects, in the opposite direction</h2>
 *
 * <p>An escape that quietly rewrote legitimate statements would be its own defect. Two assertions guard
 * against that. Benign data must be emitted <strong>character-identically</strong>, asserted against
 * literal expected records rather than against a re-implementation of the composition. And the
 * {@value com.cardemo.model.dto.StatementTransaction#STATEMENT_TEXT_RECORD_LENGTH}-byte text sink must
 * remain byte-faithful even for hostile data, because it is not markup and interprets nothing - so
 * escaping it would be a parity regression rather than a fix.
 *
 * <h2>Why the geometry assertions are not incidental</h2>
 *
 * <p>An entity costs up to six characters where the character it replaces cost one, so escaping inside a
 * fixed-length record can overflow it. Overflow in {@code StatementWriter} is an abend, which would let
 * stored data halt a batch job; in {@code StatementProcessor} it is an exception from the padding helper.
 * Either would convert an injection into an outage. The escape therefore fits the escaped form to the room
 * the row leaves, and the tests below drive a value to exactly the budget, one past it, and far past it.
 *
 * <h2>How to run</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Dtest=StatementOutputSinkContractTest test}. No container, no database, no
 * profile: the writer is built over a mocked object-storage client and the processor over a mocked file
 * service.
 */
@DisplayName("statement output sinks - no persisted value becomes markup or moves an object key")
class StatementOutputSinkContractTest {

    /** The bucket name handed to the writer; never contacted, because the client is a mock. */
    private static final String BUCKET = "carddemo-statements";

    /** An account identifier of the only accepted form, {@code ACCT-ID PIC 9(11)} zero-padded. */
    private static final String ACCOUNT_ID = "00000000001";

    /** The card number every fixture record is keyed on, {@code CARD-NUM PIC X(16)}. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A statement month of the only accepted form. */
    private static final String STATEMENT_MONTH = "2024-03";

    /** The generation the tests open under, so the composed key is fully determined. */
    private static final long GENERATION = 7L;

    /** Record width of the text sink, {@code FD-STMTFILE-REC PIC X(80)}. */
    private static final int TEXT_WIDTH = StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH;

    /** Record width of the HTML sink, {@code HTML-FIXED-LN PIC X(100)}. */
    private static final int HTML_WIDTH = StatementTransaction.STATEMENT_HTML_RECORD_LENGTH;

    /** The five entities the escape may emit; every {@code &} in the output must begin one of them. */
    private static final List<String> ENTITIES = List.of("&amp;", "&lt;", "&gt;", "&quot;", "&#39;");

    /** Matches one whole tag, for the markup-skeleton comparison. */
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    // =============================================================================================
    // Hostile payloads. Each targets a different escape mistake rather than repeating one idea.
    // =============================================================================================

    /** The canonical script injection: proves {@code <} and {@code >} are both handled. */
    private static final String SCRIPT_TAG = "<script>alert(1)</script>";

    /** An event-handler injection carrying single spaces, so it survives the delimiter helpers. */
    private static final String IMG_ONERROR = "<img src=x onerror=alert(1)>";

    /** A scheme-bearing anchor: proves a URL is inert once the tag cannot form. */
    private static final String ANCHOR_JAVASCRIPT = "<a href=\"javascript:alert(1)\">x</a>";

    /** An attribute breakout: proves the double quote is escaped, not only the angle brackets. */
    private static final String ATTRIBUTE_BREAKOUT = "\" onmouseover=\"alert(1)";

    /** A single-quoted attribute breakout: proves the apostrophe is escaped too. */
    private static final String APOSTROPHE_BREAKOUT = "' onfocus='alert(1)";

    /** A comment breakout, which would otherwise resume parsing inside a commented region. */
    private static final String COMMENT_BREAKOUT = "--><!--x";

    /** A CDATA breakout, the other way out of an escaped region. */
    private static final String CDATA_BREAKOUT = "<![CDATA[x]]>";

    /** An already-escaped value, which must be escaped again rather than passed through. */
    private static final String ALREADY_ESCAPED = "&amp;&lt;";

    /** Carriage return, line feed and NUL, which must not reach a fixed-length record. */
    private static final String CONTROL_CHARACTERS = "A\r\nB\u0000C";

    // =============================================================================================
    // Harness.
    // =============================================================================================

    /**
     * A frozen time source, so the statement month a directly-driven writer derives is deterministic.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-06-10T19:27:53.470Z"), ZoneOffset.UTC);

    /** One writer over one mocked object-storage client, so each emission is independent. */
    private static final class WriterHarness {

        private final S3Template s3Template = mock(S3Template.class);

        private final StatementWriter writer = new StatementWriter(
                this.s3Template, mock(FileStatusMapper.class), FIXED_CLOCK, BUCKET);
    }

    /** What one completed statement emission produced. */
    private record Emission(
            Map<String, String> keys,
            String textPayload,
            String htmlPayload,
            ObjectMetadata textMetadata,
            ObjectMetadata htmlMetadata) {

        List<String> textRecords() {
            return split(this.textPayload, TEXT_WIDTH);
        }

        List<String> htmlRecords() {
            return split(this.htmlPayload, HTML_WIDTH);
        }
    }

    private static WriterHarness harness() {
        return new WriterHarness();
    }

    /**
     * Composes one statement from the supplied values and drives it through the writer, capturing
     * everything that reached storage.
     *
     * <p><strong>Why the composition runs for real rather than from a fixture.</strong> Escaping and
     * record composition belong to {@link StatementProcessor}; {@link StatementWriter} pads an
     * already-composed line and <em>refuses</em> one that would not fit its record area. Neither half is
     * provable alone: an escape is only safe if the escaped form still fits the record, and the record
     * width is only honoured if the escape fitted it. Driving the real composer into the real emitter is
     * therefore the only proof of the property that matters - that a hostile value is neutralised
     * <em>and</em> the batch step does not abend because of it.
     *
     * <p>The per-account lifecycle is driven explicitly rather than through
     * {@link StatementWriter#write(Chunk)} so that the statement month and the generation are the fixed
     * ones the key assertions name; {@code theItemWriterEntryPointAppliesTheSameGuards} covers the chunk
     * entry point itself.
     *
     * @param data the caller-controlled statement values, hostile or benign
     * @return what was emitted
     */
    private static Emission emit(StatementData data) {
        return emit(compose(data));
    }

    /**
     * Drives one already-composed statement through the writer and captures what reached storage.
     *
     * @param statement the composed statement
     * @return what was emitted
     */
    private static Emission emit(StatementProcessor.Statement statement) {
        WriterHarness harness = harness();
        harness.writer.openStatementOutputs(ACCOUNT_ID, STATEMENT_MONTH, GENERATION);
        for (String line : statement.textLines()) {
            harness.writer.writeStatementLine(line);
        }
        for (String fragment : statement.htmlLines()) {
            harness.writer.writeHtmlFragment(fragment);
        }
        Map<String, String> keys = harness.writer.closeStatementOutputs();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InputStream> bodyCaptor = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<ObjectMetadata> metadataCaptor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(harness.s3Template, times(2))
                .upload(eq(BUCKET), keyCaptor.capture(), bodyCaptor.capture(), metadataCaptor.capture());

        return new Emission(
                keys,
                read(bodyCaptor.getAllValues().get(0)),
                read(bodyCaptor.getAllValues().get(1)),
                metadataCaptor.getAllValues().get(0),
                metadataCaptor.getAllValues().get(1));
    }

    private static String read(InputStream body) {
        try (InputStream stream = body) {
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the captured payload could not be read", unreadable);
        }
    }

    private static List<String> split(String payload, int width) {
        assertThat(payload.length() % width)
                .as("payload of %d characters must be a whole number of %d-character records",
                        payload.length(), width)
                .isZero();
        List<String> records = new ArrayList<>();
        for (int offset = 0; offset < payload.length(); offset += width) {
            records.add(payload.substring(offset, offset + width));
        }
        return records;
    }

    /**
     * Every value on a statement that a caller controls, in the field that carries it.
     *
     * <p>Only these ten are free text. The account identifier, the balance and the credit score are
     * {@code PIC 9} fields ({@code app/cpy/CVACT01Y.cpy:5}, {@code :L9}, {@code app/cpy/CVCUS01Y.cpy:20})
     * and are parsed as numbers out of the record before composition, so no markup can be expressed in
     * them at all; the object-key segments they feed are proved separately by {@code KeySegmentAllowList}.
     *
     * @param firstName {@code CUST-FIRST-NAME}, {@code PIC X(25)}
     * @param middleName {@code CUST-MIDDLE-NAME}, {@code PIC X(25)}
     * @param lastName {@code CUST-LAST-NAME}, {@code PIC X(25)}
     * @param addressLine1 {@code CUST-ADDR-LINE-1}, {@code PIC X(50)}
     * @param addressLine2 {@code CUST-ADDR-LINE-2}, {@code PIC X(50)}
     * @param city {@code CUST-ADDR-LINE-3}, {@code PIC X(50)}
     * @param stateCode {@code CUST-ADDR-STATE-CD}, {@code PIC X(2)}
     * @param countryCode {@code CUST-ADDR-COUNTRY-CD}, {@code PIC X(3)}
     * @param postalCode {@code CUST-ADDR-ZIP}, {@code PIC X(10)}
     * @param description {@code TRNX-DESC}, {@code PIC X(100)}, the widest free-text field of the two
     */
    private record StatementData(
            String firstName, String middleName, String lastName,
            String addressLine1, String addressLine2, String city,
            String stateCode, String countryCode, String postalCode,
            String description) {
    }

    /**
     * Benign values. The address deliberately differs from {@code 410 Terry Ave N} and
     * {@code Seattle WA 99999}, which are the <em>bank's own</em> hard-coded banner literals at
     * {@code app/cbl/CBSTM03A.CBL:L171-L174}: reusing them would make a per-record assertion match two
     * records and prove nothing about either.
     *
     * @param description the transaction description to carry
     * @return the benign bundle
     */
    private static StatementData benign(String description) {
        return new StatementData(
                "LAWRENCE", "J", "THOMAS",
                "1200 Pine Street", "Apt 42", "Redmond", "WA", "USA", "99999",
                description);
    }

    /**
     * Hostile values in every free-text field at once.
     *
     * <p>Placement is not arbitrary. {@code app/cbl/CBSTM03A.CBL:L462-L481} assembles the name and the
     * third address line with {@code STRING ... DELIMITED BY ' '}, so each of those five fields is copied
     * only as far as its first space and a payload carrying one would be truncated before it ever reached
     * the sink - which would make an absence assertion pass for the wrong reason. The four space-bearing
     * payloads therefore go in the two address lines, which are plain {@code MOVE}s at {@code :L470-L471},
     * and in the description, which is a plain {@code MOVE} at {@code :L677}.
     *
     * @param description the transaction description to carry
     * @return the hostile bundle
     */
    private static StatementData hostile(String description) {
        return new StatementData(
                SCRIPT_TAG, "M", "LAST",
                ANCHOR_JAVASCRIPT, CDATA_BREAKOUT, COMMENT_BREAKOUT,
                "<b", ">i", "'z'",
                description);
    }

    /**
     * Composes one statement by running the real processor over record fixtures carrying the data.
     *
     * @param data the caller-controlled values, hostile or benign
     * @return the composed statement, never {@code null}
     */
    private static StatementProcessor.Statement compose(StatementData data) {
        FileService fileService = mock(FileService.class);
        when(fileService.readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE))
                .thenReturn(transactionRecord(data.description()));
        when(fileService.readNext(FileService.Dd.TRNXFILE)).thenReturn(Optional.empty());
        when(fileService.readByKey(eq(FileService.Dd.CUSTFILE), any(), anyInt()))
                .thenReturn(customerRecord(data));
        when(fileService.readByKey(eq(FileService.Dd.ACCTFILE), any(), anyInt()))
                .thenReturn(accountRecord());

        StatementProcessor processor = new StatementProcessor(fileService);
        StatementProcessor.Statement statement =
                processor.process(new CardCrossReference(CARD_NUMBER, 1L, 1L));

        assertThat(statement).as("the fixtures must compose exactly one statement").isNotNull();
        assertThat(statement.htmlLines()).isNotEmpty();
        assertThat(statement.textLines()).isNotEmpty();
        return statement;
    }

    /**
     * A well-formed {@code app/cpy/CVCUS01Y.cpy} record carrying the supplied values.
     *
     * @param data the values to place in the free-text fields
     * @return the 500-character customer record
     */
    private static String customerRecord(StatementData data) {
        return field("000000001", 9)
                + field(data.firstName(), 25) + field(data.middleName(), 25)
                + field(data.lastName(), 25)
                + field(data.addressLine1(), 50) + field(data.addressLine2(), 50)
                + field(data.city(), 50)
                + field(data.stateCode(), 2) + field(data.countryCode(), 3)
                + field(data.postalCode(), 10)
                + field("206-555-0100", 15) + field("206-555-0101", 15)
                + field("123456789", 9) + field("GOVT-ID", 20)
                + field("1980-01-01", 10) + field("EFT0000001", 10) + field("Y", 1)
                + field("780", 3) + field("", 168);
    }

    /**
     * A well-formed {@code app/cpy/CVACT01Y.cpy} record. Every {@code PIC S9(10)V99} money field is
     * exactly twelve characters of digits, because the last character is the zoned-decimal sign
     * carrier: a space there is neither a digit nor an overpunch and the parser refuses it.
     *
     * @return the 300-character account record
     */
    private static String accountRecord() {
        return field("00000000001", 11) + field("Y", 1)
                + field("000000123450", 12) + field("000000500000", 12) + field("000000100000", 12)
                + field("2020-01-01", 10) + field("2030-01-01", 10) + field("2025-01-01", 10)
                + field("000000000000", 12) + field("000000000000", 12)
                + field("99999", 10) + field("DEFAULT", 10) + field("", 178);
    }

    /**
     * A well-formed {@code app/cpy/COSTM01.CPY} statement record carrying the supplied description.
     *
     * @param description the {@code TRNX-DESC} value
     * @return the 350-character statement record
     */
    private static String transactionRecord(String description) {
        return field(CARD_NUMBER, 16) + field("0000000000000001", 16)
                + field("01", 2) + field("0001", 4) + field("POS", 10)
                + field(description, 100) + field("00000001234", 11)
                + field("000000001", 9) + field("MERCHANT", 50) + field("CITY", 50)
                + field("99999", 10)
                + field("2024-03-01-10.11.12.120000", 26)
                + field("2024-03-01-10.11.12.1200", 26)
                + field("", 20);
    }

    /**
     * Renders one fixed-width field, truncating or space-padding to the declared width.
     *
     * @param value the value, {@code null} treated as empty
     * @param width the declared field width
     * @return exactly {@code width} characters
     */
    private static String field(String value, int width) {
        String content = value == null ? "" : value;
        return content.length() > width
                ? content.substring(0, width)
                : content + " ".repeat(width - content.length());
    }

    // =============================================================================================
    // Shared assertions.
    // =============================================================================================

    /**
     * Every tag in the payload, in order. Two payloads with the same skeleton contain the same markup, so
     * any character that escaped the escape shows up as a difference here.
     */
    private static List<String> markupSkeleton(String payload) {
        List<String> tags = new ArrayList<>();
        Matcher matcher = TAG.matcher(payload);
        while (matcher.find()) {
            tags.add(matcher.group());
        }
        return tags;
    }

    private static long count(String payload, char character) {
        return payload.chars().filter(codePoint -> codePoint == character).count();
    }

    /**
     * Asserts that every {@code &} in the payload begins a complete entity. No literal in either class's
     * fragment table contains an ampersand, so an ampersand that begins nothing is necessarily a
     * half-written entity - the exact damage that fitting before escaping would cause.
     */
    private static void assertNoTruncatedEntity(String payload) {
        for (int index = payload.indexOf('&'); index >= 0; index = payload.indexOf('&', index + 1)) {
            String tail = payload.substring(index);
            assertThat(ENTITIES.stream().anyMatch(tail::startsWith))
                    .as("the ampersand at position %d begins no complete entity: %s",
                            index, tail.substring(0, Math.min(8, tail.length())))
                    .isTrue();
        }
    }

    private static String recordStartingWith(List<String> records, String prefix) {
        List<String> matches = records.stream().filter(record -> record.startsWith(prefix)).toList();
        assertThat(matches).as("exactly one record must start with '%s'", prefix).hasSize(1);
        return matches.get(0);
    }

    private static String padded(String content, int width) {
        return content + " ".repeat(width - content.length());
    }

    // =============================================================================================
    // F16 - the object key segments.
    // =============================================================================================

    @Nested
    @DisplayName("F16 - an object key segment cannot be influenced beyond its field contract")
    class KeySegmentAllowList {

        private static Stream<String> refusedAccountIdentifiers() {
            return Stream.of(
                    "../../etc/passwd",
                    "..",
                    "../00000000001",
                    "..\\00000000001",
                    "/00000000001",
                    "00000000001/",
                    "000/0000001",
                    "%2e%2e%2f00000000001",
                    "00000000001%00",
                    "000000000012",
                    "0000000000a",
                    "0000000000 ",
                    " 0000000001",
                    "1",
                    "0",
                    "0000000001",
                    "1\n2",
                    "1\r2",
                    "1\u00002",
                    "1\t2",
                    "0000000000\u007f",
                    "",
                    "   ",
                    "account=1",
                    "\u00e9");
        }

        private static Stream<String> refusedStatementMonths() {
            return Stream.of(
                    "2024-13",
                    "2024-00",
                    "2024-1",
                    "2024/01",
                    "2024-01-01",
                    "24-01",
                    "../2024-01",
                    "2024-01/",
                    "/2024-01",
                    "2024-01\n",
                    "2024-01 ",
                    " 2024-01",
                    "202X-01",
                    "%2e%2e/2024-01",
                    "",
                    "  ",
                    "month=2024-01");
        }

        private static Stream<String> acceptedAccountIdentifiers() {
            return Stream.of("00000000001", "00000000000", "12345678901", "99999999999", "10000000000");
        }

        private static Stream<String> acceptedStatementMonths() {
            return Stream.of("2024-01", "2024-09", "2024-10", "2024-12", "1999-06");
        }

        @ParameterizedTest(name = "account [{0}] is refused")
        @MethodSource("refusedAccountIdentifiers")
        @DisplayName("an account identifier outside 9(11) is refused")
        void anAccountIdentifierOutsideItsPictureIsRefused(String candidate) {
            WriterHarness harness = harness();

            assertThatThrownBy(() ->
                    harness.writer.openStatementOutputs(candidate, STATEMENT_MONTH, GENERATION))
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("account identifier must be exactly")
                    .hasMessageContaining("object-key segment");

            verifyNoInteractions(harness.s3Template);
        }

        @ParameterizedTest(name = "month [{0}] is refused")
        @MethodSource("refusedStatementMonths")
        @DisplayName("a statement month outside yyyy-MM is refused")
        void aStatementMonthOutsideItsConventionIsRefused(String candidate) {
            WriterHarness harness = harness();

            assertThatThrownBy(() ->
                    harness.writer.openStatementOutputs(ACCOUNT_ID, candidate, GENERATION))
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("statement month must be a canonical uuuu-MM")
                    .hasMessageContaining("object-key segment");

            verifyNoInteractions(harness.s3Template);
        }

        @ParameterizedTest(name = "account [{0}] is accepted")
        @MethodSource("acceptedAccountIdentifiers")
        @DisplayName("every legitimate account spelling is still accepted")
        void everyLegitimateAccountSpellingIsAccepted(String candidate) {
            WriterHarness harness = harness();

            assertThatCode(() ->
                    harness.writer.openStatementOutputs(candidate, STATEMENT_MONTH, GENERATION))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "month [{0}] is accepted")
        @MethodSource("acceptedStatementMonths")
        @DisplayName("every legitimate month is still accepted")
        void everyLegitimateMonthIsAccepted(String candidate) {
            WriterHarness harness = harness();

            assertThatCode(() -> harness.writer.openStatementOutputs(ACCOUNT_ID, candidate, GENERATION))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null segment is refused before a key is composed, and names which one it was")
        void aNullSegmentIsRefusedNamingWhichOne() {
            WriterHarness harness = harness();

            // Both refusals are the same abend the malformed cases raise, deliberately: an object key is
            // composed from these two segments and nothing else, so there is no state in which a missing
            // one is less serious than a malformed one. The diagnostics still distinguish them, which is
            // what an operator reading a job log needs.
            assertThatThrownBy(() -> harness.writer.openStatementOutputs(null, STATEMENT_MONTH))
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("account identifier must be exactly");
            assertThatThrownBy(() -> harness.writer.openStatementOutputs(ACCOUNT_ID, null))
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("statement month must not be null");

            verifyNoInteractions(harness.s3Template);
        }

        @Test
        @DisplayName("the refusal names the field and its accepted form but never echoes the value")
        void theRefusalNeverEchoesTheRejectedValue() {
            WriterHarness harness = harness();
            String hostile = "../../../../etc/shadow";

            assertThatThrownBy(() ->
                    harness.writer.openStatementOutputs(hostile, STATEMENT_MONTH, GENERATION))
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("account identifier")
                    .hasMessageContaining("ASCII digits")
                    .hasMessageContaining("but a value of length 22 was supplied")
                    .hasMessageNotContaining("etc/shadow")
                    .hasMessageNotContaining("..");
        }

        @Test
        @DisplayName("a refused open leaves no statement open, so nothing partial can be flushed")
        void aRefusedOpenLeavesNoStatementOpen() {
            WriterHarness harness = harness();

            assertThatThrownBy(() ->
                    harness.writer.openStatementOutputs("../x", STATEMENT_MONTH, GENERATION))
                    .isInstanceOf(FatalProcessingException.class);

            assertThatThrownBy(() -> harness.writer.writeStatementLine("anything"))
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("no statement is open");
            assertThat(harness.writer.createdObjectKeys()).isEmpty();
            verifyNoInteractions(harness.s3Template);
        }

        @Test
        @DisplayName("the composed keys carry exactly the five intended segments")
        void theComposedKeysCarryExactlyTheIntendedSegments() {
            Emission emission = emit(benign("PURCHASE"));

            // The generation is zero-padded to nineteen digits, the width of Long.MAX_VALUE, so that the
            // lexicographic order of the keys equals the numeric order of the generations across the whole
            // domain - which is what lets a relative GDG (0) reference resolve as the greatest prefix.
            assertThat(emission.keys())
                    .containsEntry(StatementWriter.STMTFILE_DD_NAME,
                            "statements/account=00000000001/month=2024-03/"
                                    + "generation=0000000000000000007/STATEMNT.PS")
                    .containsEntry(StatementWriter.HTMLFILE_DD_NAME,
                            "statements/account=00000000001/month=2024-03/"
                                    + "generation=0000000000000000007/STATEMNT.HTML");
            assertThat(emission.keys().values()).allSatisfy(key -> assertThat(key)
                    .doesNotContain("..")
                    .doesNotContain("//")
                    .doesNotStartWith("/")
                    .doesNotContain("%"));
        }
    }

    // =============================================================================================
    // F12 - composition into emission. The escape must neutralise the value AND leave a record the
    // emitter will accept, so both halves are driven together.
    // =============================================================================================

    @Nested
    @DisplayName("F12 - no persisted value becomes markup, and none of it abends the step")
    class EndToEndMarkupEscaping {

        @Test
        @DisplayName("the markup skeleton is identical for benign and hostile data")
        void theMarkupSkeletonIsIdenticalForBenignAndHostileData() {
            String benign = emit(benign("PURCHASE AT MERCHANT")).htmlPayload();
            String hostile = emit(hostile(IMG_ONERROR)).htmlPayload();

            assertThat(markupSkeleton(hostile))
                    .as("a single unescaped character would add, remove or alter a tag")
                    .isEqualTo(markupSkeleton(benign));
            assertThat(count(hostile, '<')).isEqualTo(count(benign, '<'));
            assertThat(count(hostile, '>')).isEqualTo(count(benign, '>'));
            assertThat(count(hostile, '"')).isEqualTo(count(benign, '"'));
            assertThat(count(hostile, '\'')).isEqualTo(count(benign, '\''));
        }

        @Test
        @DisplayName("no injected tag survives, and the payload provably arrived to be neutralised")
        void noInjectedTagSurvives() {
            String hostile = emit(hostile(IMG_ONERROR + COMMENT_BREAKOUT)).htmlPayload();

            // The absence assertions. Only tag FORMATION is asserted against: the words href and
            // onerror surviving as escaped text is the correct outcome, not a leak, because a text node
            // executes nothing. Asserting their absence would be asserting the wrong property.
            assertThat(hostile)
                    .doesNotContain("<script")
                    .doesNotContain("</script")
                    .doesNotContain("<img")
                    .doesNotContain("<a href")
                    .doesNotContain("<!--")
                    .doesNotContain("-->")
                    .doesNotContain("<![CDATA[")
                    .doesNotContain("]]>");

            // The presence assertions, without which the absences above would also hold for a payload
            // that never reached the sink at all. One per stream position: the name, an address line and
            // the transaction description.
            assertThat(hostile)
                    .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                    .contains("&lt;a href=&quot;javascript:alert(1)&quot;&gt;")
                    .contains("&lt;![CDATA[x]]&gt;")
                    .contains("&lt;img src=x onerror=alert(1)&gt;");
            assertNoTruncatedEntity(hostile);
        }

        @Test
        @DisplayName("each of the five markup characters is replaced by its entity")
        void eachMarkupCharacterIsReplacedByItsEntity() {
            String hostile = emit(hostile("a<b>c\"d'e&f" + APOSTROPHE_BREAKOUT)).htmlPayload();

            assertThat(hostile)
                    .contains("&lt;")
                    .contains("&gt;")
                    .contains("&quot;")
                    .contains("&#39;")
                    .contains("&amp;");
            assertThat(hostile).doesNotContain("' onfocus='");
        }

        @Test
        @DisplayName("an already-escaped value is escaped again rather than passed through")
        void anAlreadyEscapedValueIsEscapedAgain() {
            String hostile = emit(hostile(ALREADY_ESCAPED)).htmlPayload();

            assertThat(hostile)
                    .as("recognising an entity and passing it through would be the injection")
                    .contains("&amp;amp;&amp;lt;");
        }

        @Test
        @DisplayName("no ampersand anywhere begins a half-written entity")
        void noAmpersandBeginsAHalfWrittenEntity() {
            assertNoTruncatedEntity(emit(hostile(IMG_ONERROR)).htmlPayload());
            assertNoTruncatedEntity(emit(hostile("\"".repeat(60))).htmlPayload());
            assertNoTruncatedEntity(emit(hostile("&".repeat(60))).htmlPayload());
        }

        @Test
        @DisplayName("control characters reach neither sink, and neither sink abends because of one")
        void controlCharactersNeverReachARecord() {
            // Both remediations meet here. The composer replaces every control character with a space,
            // length-neutrally, so the record geometry is untouched; the emitter refuses any record still
            // carrying one, which is what theWriterStillRefusesAControlCharacterOnAnyOtherPath proves.
            // Had only the second been present, a control byte persisted in a customer name would not
            // have corrupted a statement - it would have abended the whole step, which is the outage this
            // pair of guards exists to avoid.
            Emission emission = emit(new StatementData(
                    CONTROL_CHARACTERS, "M", "LAST",
                    "line\r\none", "line\u0000two", COMMENT_BREAKOUT,
                    "WA", "USA", "99999",
                    "desc\r\nwith\u0000controls"));

            assertThat(emission.htmlPayload())
                    .doesNotContain("\r")
                    .doesNotContain("\n")
                    .doesNotContain("\u0000")
                    .doesNotContain("\u001b");
            assertThat(emission.textPayload())
                    .doesNotContain("\r")
                    .doesNotContain("\n")
                    .doesNotContain("\u0000")
                    .doesNotContain("\u001b");
            assertThat(emission.htmlPayload().length() % HTML_WIDTH).isZero();
            assertThat(emission.textPayload().length() % TEXT_WIDTH).isZero();
        }

        @Test
        @DisplayName("the emitter still refuses a control character that reached it by any other path")
        void theWriterStillRefusesAControlCharacterOnAnyOtherPath() {
            WriterHarness harness = harness();
            harness.writer.openStatementOutputs(ACCOUNT_ID, STATEMENT_MONTH, GENERATION);

            // Defence in depth, deliberately kept: the composer is the only producer today, but the two
            // record-appending methods are public and a future step could drive them directly.
            assertThatThrownBy(() -> harness.writer.writeHtmlFragment("<p>a\u0000b</p>"))
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("outside the permitted set");
            assertThatThrownBy(() -> harness.writer.writeStatementLine("a\rb"))
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("outside the permitted set");
            verifyNoInteractions(harness.s3Template);
        }

        @Test
        @DisplayName("a value whose escaped form fits exactly is emitted whole and does not abend")
        void aValueEscapingToExactlyTheBudgetIsEmittedWhole() {
            // The description cell is <p> + value + </p> over a 100-character record, and the value budget
            // is 100 - 3 - 2 - 4 = 91: the composer reserves the two-space delimiter the source appends on
            // every address and detail line. Fifteen double quotes escape to 90 characters, and one plain
            // character brings the escaped form to exactly 91.
            Emission emission = emit(hostile("\"".repeat(15) + "a"));

            assertThat(emission.htmlPayload()).contains("&quot;".repeat(15) + "a");
            assertThat(emission.htmlRecords()).isNotEmpty().allSatisfy(record ->
                    assertThat(record).hasSize(HTML_WIDTH));
            assertNoTruncatedEntity(emission.htmlPayload());
        }

        @Test
        @DisplayName("a value escaping past the budget is trimmed on an entity boundary, never abended")
        void aValueEscapingPastTheBudgetIsTrimmedOnAnEntityBoundary() {
            // Past the exact fit for a sixteenth entity: the fifteen survive and nothing partial follows them.
            // This assertion deliberately does NOT hard-code which plain character is the last one that fits.
            // It did, and the arithmetic was one character out - the field's room affords two plain characters
            // after fifteen entities, not one - which made a passing test into a failing one without any
            // change in the property being tested. What matters is the property: the trim lands on an entity
            // boundary, and what survives is a prefix of the fully escaped value rather than a rewrite of it.
            Emission emission = emit(hostile("\"".repeat(15) + "ab"));

            assertThat(emission.htmlPayload()).contains("&quot;".repeat(15) + "a");
            assertThat(emission.htmlPayload())
                    .as("no sixteenth entity, because the value never offered a sixteenth quote")
                    .doesNotContain("&quot;".repeat(16));
            assertNoTruncatedEntity(emission.htmlPayload());
            assertThat(emission.htmlRecords()).isNotEmpty().allSatisfy(record ->
                    assertThat(record).hasSize(HTML_WIDTH));

            // Far past the budget: fifteen whole entities fit, the sixteenth does not, and no fragment of
            // it is emitted. Fitting the ESCAPED form instead would have left a bisected entity here.
            Emission flooded = emit(hostile("\"".repeat(49)));

            assertThat(flooded.htmlPayload()).contains("&quot;".repeat(15));
            assertThat(flooded.htmlPayload()).doesNotContain("&quot;".repeat(16));
            assertThat(flooded.htmlRecords()).isNotEmpty().allSatisfy(record ->
                    assertThat(record).hasSize(HTML_WIDTH));
            assertNoTruncatedEntity(flooded.htmlPayload());
        }

        @Test
        @DisplayName("a maximal-expansion payload in every field neither overflows a record nor abends")
        void aMaximalExpansionPayloadNeitherOverflowsNorAbends() {
            // Every field is filled with the most expensive character to escape. A 50-character address
            // line of double quotes escapes to 300 characters, so escaping WITHOUT fitting the escaped
            // form would compose a row of some 307 characters into a 100-character record area - which the
            // emitter answers with an abend, turning stored data into a halted batch job. This asserts the
            // budget, not merely the escape.
            Emission emission = emit(new StatementData(
                    "\"".repeat(25), "\"".repeat(25), "\"".repeat(25),
                    "\"".repeat(50), "&".repeat(50), "<".repeat(50),
                    "\"\"", "&&&", "'''''''''",
                    "\"".repeat(100)));

            assertThat(emission.htmlRecords()).isNotEmpty().allSatisfy(record ->
                    assertThat(record).hasSize(HTML_WIDTH));
            assertThat(emission.textRecords()).isNotEmpty().allSatisfy(record ->
                    assertThat(record).hasSize(TEXT_WIDTH));
            assertNoTruncatedEntity(emission.htmlPayload());
        }

        @Test
        @DisplayName("every record is exactly its declared width for hostile data too")
        void everyHtmlRecordKeepsItsDeclaredWidth() {
            Emission emission = emit(hostile(SCRIPT_TAG + ATTRIBUTE_BREAKOUT));

            assertThat(emission.htmlRecords()).isNotEmpty().allSatisfy(record ->
                    assertThat(record).hasSize(HTML_WIDTH));
            assertThat(emission.textRecords()).isNotEmpty().allSatisfy(record ->
                    assertThat(record).hasSize(TEXT_WIDTH));
        }

        @Test
        @DisplayName("the emitter reproduces the composed lines character-identically, padding and no more")
        void theEmitterReproducesTheComposedLinesCharacterIdentically() {
            // The per-row expected records are asserted by ProcessorMarkupEscaping, which owns
            // composition. What is asserted here is the emitter's own contract: it pads to the record area
            // and performs no transform of its own - in particular it does not escape a composed line,
            // which would destroy the markup the line is carrying.
            StatementProcessor.Statement statement = compose(benign("PURCHASE AT MERCHANT"));
            Emission emission = emit(statement);

            assertThat(emission.textRecords()).isEqualTo(
                    statement.textLines().stream().map(line -> padded(line, TEXT_WIDTH)).toList());
            assertThat(emission.htmlRecords()).isEqualTo(
                    statement.htmlLines().stream().map(line -> padded(line, HTML_WIDTH)).toList());
            assertThat(emission.htmlPayload())
                    .as("benign data needs no entity, so an ampersand anywhere would mean the escape "
                            + "had rewritten a legitimate statement")
                    .doesNotContain("&");
        }

        @Test
        @DisplayName("the text sink stays byte-faithful and is deliberately not escaped")
        void theTextSinkStaysByteFaithful() {
            Emission emission = emit(hostile(IMG_ONERROR));

            assertThat(emission.textPayload())
                    .as("the text object is not markup, interprets nothing, and is stored as an "
                            + "attachment; escaping it would be a parity regression, not a fix")
                    .contains(SCRIPT_TAG)
                    .contains(ANCHOR_JAVASCRIPT)
                    .contains(IMG_ONERROR)
                    .doesNotContain("&lt;")
                    .doesNotContain("&quot;")
                    .doesNotContain("&amp;");
            assertThat(emission.textRecords()).allSatisfy(record ->
                    assertThat(record).hasSize(TEXT_WIDTH));
        }

        @Test
        @DisplayName("the ItemWriter entry point applies the same record guards")
        void theItemWriterEntryPointAppliesTheSameGuards() throws Exception {
            WriterHarness harness = harness();

            harness.writer.write(new Chunk<>(List.of(compose(hostile(IMG_ONERROR)))));

            ArgumentCaptor<InputStream> bodyCaptor = ArgumentCaptor.forClass(InputStream.class);
            verify(harness.s3Template, times(2))
                    .upload(eq(BUCKET), any(), bodyCaptor.capture(), any());
            assertThat(split(read(bodyCaptor.getAllValues().get(0)), TEXT_WIDTH))
                    .isNotEmpty().allSatisfy(record -> assertThat(record).hasSize(TEXT_WIDTH));
            assertThat(split(read(bodyCaptor.getAllValues().get(1)), HTML_WIDTH))
                    .isNotEmpty().allSatisfy(record -> assertThat(record).hasSize(HTML_WIDTH));
        }
    }

    // =============================================================================================
    // F12 - the object metadata that stands behind the escape.
    // =============================================================================================

    @Nested
    @DisplayName("F12 - the object metadata is browser-safe in its own right")
    class ObjectMetadataHardening {

        @Test
        @DisplayName("both objects declare the encoding their bytes are actually written in")
        void bothObjectsDeclareTheirEncoding() {
            Emission emission = emit(benign("PURCHASE"));

            assertThat(emission.textMetadata().getContentType())
                    .isEqualTo("text/plain; charset=" + StandardCharsets.ISO_8859_1.name());
            assertThat(emission.htmlMetadata().getContentType())
                    .isEqualTo("text/html; charset=" + StandardCharsets.ISO_8859_1.name());
        }

        @Test
        @DisplayName("both objects are attachments, so the HTML never renders in the bucket's origin")
        void bothObjectsAreAttachments() {
            Emission emission = emit(benign("PURCHASE"));

            assertThat(emission.textMetadata().getContentDisposition())
                    .isEqualTo("attachment; filename=\"STATEMNT.PS\"");
            assertThat(emission.htmlMetadata().getContentDisposition())
                    .isEqualTo("attachment; filename=\"STATEMNT.HTML\"");
        }

        @Test
        @DisplayName("no intermediary is invited to retain a statement")
        void noIntermediaryIsInvitedToRetainAStatement() {
            Emission emission = emit(benign("PURCHASE"));

            assertThat(emission.textMetadata().getCacheControl()).isEqualTo("no-store");
            assertThat(emission.htmlMetadata().getCacheControl()).isEqualTo("no-store");
        }

        @Test
        @DisplayName("the declared length is the payload length and a whole number of records")
        void theDeclaredLengthMatchesThePayload() {
            Emission emission = emit(hostile(IMG_ONERROR));

            assertThat(emission.textMetadata().getContentLength())
                    .isEqualTo(emission.textPayload().getBytes(StandardCharsets.ISO_8859_1).length);
            assertThat(emission.htmlMetadata().getContentLength())
                    .isEqualTo(emission.htmlPayload().getBytes(StandardCharsets.ISO_8859_1).length);
            assertThat(emission.textMetadata().getContentLength() % TEXT_WIDTH).isZero();
            assertThat(emission.htmlMetadata().getContentLength() % HTML_WIDTH).isZero();
        }

        @Test
        @DisplayName("the disposition filename is a constant and cannot be influenced by a caller")
        void theDispositionFilenameCannotBeInfluenced() {
            // A header-splitting payload in every free-text field at once. The account identifier is not
            // among them because it is PIC 9(11) and is refused outright before a key or a header exists,
            // which KeySegmentAllowList proves.
            Emission emission = emit(new StatementData(
                    "\";filename=\"evil.html", "M", "L",
                    "\"; filename=\"evil.html", "\"; filename=\"evil.html", "C",
                    "WA", "USA", "99999",
                    "\"; filename=\"evil.html"));

            assertThat(emission.textMetadata().getContentDisposition())
                    .isEqualTo("attachment; filename=\"STATEMNT.PS\"");
            assertThat(emission.htmlMetadata().getContentDisposition())
                    .isEqualTo("attachment; filename=\"STATEMNT.HTML\"");
        }
    }

    // =============================================================================================
    // F12 - the second markup sink. The review cited only the writer; the processor carries the same
    // eleven interpolations and would have remained exploitable had only the cited file been fixed.
    // =============================================================================================

    @Nested
    @DisplayName("F12 - StatementProcessor: the second markup sink is escaped identically")
    class ProcessorMarkupEscaping {

        /**
         * Drives one statement through the composer alone and returns its HTML lines.
         *
         * <p>This class asserts the lines the composer produces; {@code EndToEndMarkupEscaping} asserts the
         * bytes that reach storage once the emitter has padded them. The division matters: only the first
         * can state a per-row expected record, and only the second can prove that the escaped form still
         * fits the record area.
         *
         * @param firstName the {@code CUST-FIRST-NAME} value, hostile or benign
         * @param addressLine1 the {@code CUST-ADDR-LINE-1} value, hostile or benign
         * @param addressLine2 the {@code CUST-ADDR-LINE-2} value, hostile or benign
         * @param description the {@code TRNX-DESC} value, hostile or benign
         * @return the emitted HTML lines
         */
        private static List<String> processHtml(
                String firstName, String addressLine1, String addressLine2, String description) {
            return compose(new StatementData(
                    firstName, "M", "LAST",
                    addressLine1, addressLine2, "Seattle",
                    "WA", "USA", "99999",
                    description)).htmlLines();
        }

        @Test
        @DisplayName("the markup skeleton is identical for benign and hostile data")
        void theMarkupSkeletonIsIdenticalForBenignAndHostileData() {
            String benign = String.join("",
                    processHtml("LAWRENCE", "1200 Pine Street", "Apt 42", "PURCHASE"));
            String hostile = String.join("", processHtml(
                    SCRIPT_TAG, ANCHOR_JAVASCRIPT, ATTRIBUTE_BREAKOUT, IMG_ONERROR));

            assertThat(markupSkeleton(hostile)).isEqualTo(markupSkeleton(benign));
            assertThat(count(hostile, '<')).isEqualTo(count(benign, '<'));
            assertThat(count(hostile, '>')).isEqualTo(count(benign, '>'));
            assertThat(count(hostile, '"')).isEqualTo(count(benign, '"'));
            assertThat(count(hostile, '\'')).isEqualTo(count(benign, '\''));
        }

        @Test
        @DisplayName("no injected tag survives, and the payload provably arrived to be neutralised")
        void noInjectedTagSurvives() {
            String hostile = String.join("", processHtml(
                    SCRIPT_TAG, ANCHOR_JAVASCRIPT, CDATA_BREAKOUT, IMG_ONERROR + COMMENT_BREAKOUT));

            assertThat(hostile)
                    .doesNotContain("<script")
                    .doesNotContain("<img")
                    .doesNotContain("<a href")
                    .doesNotContain("<!--")
                    .doesNotContain("-->")
                    .doesNotContain("<![CDATA[")
                    .doesNotContain("]]>");
            assertThat(hostile)
                    .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                    .contains("&lt;a href=&quot;javascript:alert(1)&quot;&gt;")
                    .contains("&lt;![CDATA[x]]&gt;")
                    .contains("&lt;img src=x onerror=alert(1)&gt;");
            assertNoTruncatedEntity(hostile);
        }

        @Test
        @DisplayName("every line keeps its declared width, and a flood of entities does not break it")
        void everyLineKeepsItsDeclaredWidth() {
            List<String> flooded = processHtml(
                    "\"".repeat(25), "&".repeat(50), "<".repeat(50), "\"".repeat(100));

            assertThat(flooded).allSatisfy(line -> assertThat(line).hasSize(HTML_WIDTH));
            assertNoTruncatedEntity(String.join("", flooded));
        }

        @Test
        @DisplayName("control characters never reach a line")
        void controlCharactersNeverReachALine() {
            List<String> lines = processHtml(
                    CONTROL_CHARACTERS, "a\r\nb", "c\u0000d", "desc\u001bwith\u007fcontrols");

            String payload = String.join("", lines);
            assertThat(payload)
                    .doesNotContain("\r")
                    .doesNotContain("\n")
                    .doesNotContain("\u0000")
                    .doesNotContain("\u001b")
                    .doesNotContain("\u007f");
        }

        @Test
        @DisplayName("benign data is emitted character-identically, so parity is untouched")
        void benignDataIsEmittedCharacterIdentically() {
            List<String> lines =
                    processHtml("LAWRENCE", "1200 Pine Street", "Apt 42", "PURCHASE AT MERCHANT");

            assertThat(recordStartingWith(lines, "<h3>Statement for Account Number:"))
                    .isEqualTo(padded(
                            "<h3>Statement for Account Number: " + padded(ACCOUNT_ID, 20) + "</h3>",
                            HTML_WIDTH));
            assertThat(recordStartingWith(lines, "<p>1200 Pine Street"))
                    .isEqualTo(padded("<p>1200 Pine Street  </p>", HTML_WIDTH));
            assertThat(recordStartingWith(lines, "<p>Apt 42"))
                    .isEqualTo(padded("<p>Apt 42  </p>", HTML_WIDTH));
            assertThat(recordStartingWith(lines, "<p>PURCHASE AT MERCHANT"))
                    .isEqualTo(padded(
                            "<p>" + padded("PURCHASE AT MERCHANT", 49) + "</p>", HTML_WIDTH));
            assertThat(String.join("", lines))
                    .as("benign data needs no entity, so an ampersand anywhere would mean the escape "
                            + "had rewritten a legitimate statement")
                    .doesNotContain("&");
        }
    }
}
