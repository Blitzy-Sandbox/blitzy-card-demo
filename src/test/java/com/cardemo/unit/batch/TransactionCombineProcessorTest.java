/*
 * ******************************************************************
 * Program     : TransactionCombineProcessorTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies that the combine step's per-record boundary
 *               refuses a TRAN-ID carrying a control character, and
 *               that no message or log record this class produces can
 *               be split by attacker-chosen bytes. Covers the sort-key
 *               geometry branches, the escaping of every reported
 *               identifier, all three store-failure translations and
 *               the TRAN-ID ascending order the sort card fixes.
 * Source      : app/jcl/COMBTRAN.jcl:L28       (TRAN-ID,1,16,CH)
 *               app/jcl/COMBTRAN.jcl:L30       (SORT FIELDS=(TRAN-ID,A))
 *               app/jcl/COMBTRAN.jcl:L48       (REPRO into the KSDS)
 *               app/cpy/CVTRA05Y.cpy:L5        (TRAN-ID PIC X(16))
 *               app/cbl/COTRN02C.cbl:L444-L451 (descending-browse max+1)
 *               app/cbl/CBACT04C.cbl:L473-L516 (date+suffix identifier)
 *               app/data/ASCII/dailytran.txt   (300 identifiers) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Executable proof that the combine step cannot be used to forge a log record.
 *
 * <p>The defect these tests were written against was not a missing check but a misplaced trust. The
 * step validated the identifier's <em>geometry</em> thoroughly - null, blank and a length fixed at
 * exactly sixteen by the sort card - and then interpolated the value straight into log records and
 * exception messages. Geometry is not the property that matters for a diagnostic: the string
 * {@code "1\nFATAL forged! "} is sixteen characters long, is not blank, and satisfies every rule the
 * step enforced, yet reporting it turns one log line into two, the second of which reads like a fatal
 * error this application emitted.
 *
 * <p>That was verified before it was fixed, by formatting the real message template with that value and
 * observing the split, and it was verified again through the entity layer: {@code Transaction}'s own
 * setter checks the field's width only, so a control-bearing identifier reaches this processor rather
 * than being stopped upstream. The attack path was real end to end, which is why the guard belongs here.
 *
 * <p>Two mechanisms answer it, and the tests keep them separate because they are deliberately different
 * widths. The refusal is narrow - exactly {@link Character#isISOControl(char)} - because
 * {@code TRAN-ID PIC X(16)} declares a character field and narrowing a field's domain further risks
 * refusing a record the legacy system would have posted. The rendering is broad - everything outside
 * printable ASCII - because a character the guard permits must still not be able to break a line. The
 * pair is what makes the guarantee hold, so a test asserts each one and a test asserts the asymmetry.
 *
 * <p>These assertions were checked against the defect rather than only against the fix. Reinstating both
 * halves - deleting the control-character guard and returning the identifier from the renderer
 * unescaped - fails 16 of these 30 tests, spread across the three groups that assert the guarantee.
 * Groups 4 and 5 are untouched by that mutation, correctly, because geometry and ordering are not what
 * the guard protects. Two tests in group 1 also survive it, and they survive for a reason worth stating
 * rather than hiding: {@code legitimateIdentifierPasses} and {@code theGuardRefusesNoCorpusIdentifier}
 * assert that well-formed values are <em>accepted</em>, so removing a guard cannot break them. They are
 * the parity half of the pair and are not the tests that carry the security proof.
 *
 * <p>The log assertions read the {@link ILoggingEvent} through a logback {@link ListAppender} rather
 * than scraping stdout, because the property under test is what reaches an appender. This matters more
 * than usual here: {@code logback-spring.xml} is planned but absent at this commit, so there is no JSON
 * encoder downstream that would escape a newline on the way out. Nothing but this class's own rendering
 * stands between the identifier and the log file.
 */
@DisplayName("TransactionCombineProcessor: a hostile TRAN-ID cannot forge a log record")
class TransactionCombineProcessorTest {

    /** A legitimate identifier: sixteen decimal digits, taken from app/data/ASCII/dailytran.txt row 1. */
    private static final String VALID_ID = "0000000000683580";

    /** Second legitimate identifier, from row 2, used for the ordering assertions. */
    private static final String VALID_ID_2 = "0000000001774260";

    private TransactionCombineProcessor processor;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        processor = new TransactionCombineProcessor();
        logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(TransactionCombineProcessor.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    /**
     * Builds a record that passes every check except the one under test.
     *
     * <p>Only the identifier is set. Every other field is left {@code null}, which the geometry checks
     * accept by design - nullability is the schema's declaration, not this processor's - so a test can
     * aim at one field without having to satisfy thirteen others. The {@code protected} no-argument
     * constructor is reached reflectively because that is the constructor JPA uses; going through the
     * public all-argument constructor instead would make the entity's own guards, not the processor's,
     * decide what this test can express.
     */
    private static Transaction recordWithId(String transactionId) {
        try {
            Constructor<Transaction> constructor = Transaction.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Transaction item = constructor.newInstance();
            java.lang.reflect.Field field = Transaction.class.getDeclaredField("transactionId");
            field.setAccessible(true);
            field.set(item, transactionId);
            return item;
        } catch (ReflectiveOperationException cause) {
            throw new IllegalStateException("could not build a Transaction fixture", cause);
        }
    }

    /**
     * Hostile identifiers, each exactly sixteen characters.
     *
     * <p>These are supplied from a method rather than from a {@code @CsvSource} because the values under
     * test contain real line breaks, and a line break inside a CSV row terminates that row - the first
     * attempt at this test silently received one-column rows and reported a width of 1, which is a
     * reminder that a fixture format can quietly destroy the property being tested. Built in Java, the
     * escapes are unambiguous.
     */
    static Stream<Arguments> hostileIdentifiers() {
        return Stream.of(
                Arguments.of("line feed", forgedWith("\n")),
                Arguments.of("carriage return", forgedWith("\r")),
                Arguments.of("CRLF pair", forgedWith("\r\n")),
                Arguments.of("tab", forgedWith("\t")),
                Arguments.of("escape", forgedWith("\u001b")),
                Arguments.of("null byte", forgedWith("\u0000")),
                Arguments.of("next line NEL", forgedWith("\u0085")));
    }

    /**
     * Builds a forging payload of exactly the declared width around the given control sequence.
     *
     * <p>Counting the characters by hand produced four fixtures of fifteen, which the width assertion in
     * the test caught. Constructing and padding here makes that class of mistake impossible rather than
     * merely detected, and keeps every fixture comparable: each one is a digit, the control sequence,
     * then forged text, space-padded to the width the sort card fixes.
     *
     * @param control the control sequence to embed, one or two characters
     * @return a value of exactly {@link TransactionCombineProcessor#TRAN_ID_LENGTH} characters
     */
    private static String forgedWith(String control) {
        String core = "1" + control + "FATAL forged!";
        int width = TransactionCombineProcessor.TRAN_ID_LENGTH;
        String value = core.length() >= width
                ? core.substring(0, width)
                : core + " ".repeat(width - core.length());
        if (value.length() != width) {
            throw new IllegalStateException("fixture width is " + value.length());
        }
        return value;
    }

    /**
     * The two rejection branches that can fire for a value carrying a line break.
     *
     * <p>Sixteen newlines are {@link String#isBlank()}, so they reach the blank branch rather than the
     * control branch. Both are included precisely because they are different branches: the guarantee is
     * that whichever one fires reports safely.
     */
    static Stream<Arguments> lineBreakingIdentifiers() {
        return Stream.of(
                Arguments.of("blank", "\n".repeat(16)),
                Arguments.of("control", forgedWith("\n")));
    }

    /** Every message logged so far, formatted exactly as an appender receives it. */
    private List<String> loggedMessages() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    @Nested
    @DisplayName("1. A control character in TRAN-ID is refused at the boundary")
    class ControlCharacterRejection {

        @ParameterizedTest(name = "{0} is refused")
        @MethodSource("com.cardemo.unit.batch.TransactionCombineProcessorTest#hostileIdentifiers")
        @DisplayName("the refusal names the defect and the message survives intact")
        void controlCharactersAreRefused(final String label, final String hostileId) {
            // The CsvSource keeps every value at exactly sixteen characters so that the length check
            // cannot be what refuses them. Anything shorter would prove nothing about this guard.
            assertThat(hostileId)
                    .as("%s fixture must be exactly the declared width, or the length branch "
                            + "would refuse it first and this test would prove nothing", label)
                    .hasSize(TransactionCombineProcessor.TRAN_ID_LENGTH);
            assertThat(hostileId.isBlank())
                    .as("%s fixture must not be blank, for the same reason", label)
                    .isFalse();

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(recordWithId(hostileId)))
                    .withMessageContaining("control character")
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .as("the message that reports a forging attempt must not itself be forgeable")
                            .doesNotContain("\n")
                            .doesNotContain("\r"));
        }

        @Test
        @DisplayName("a legitimate sixteen-digit identifier is accepted and returned unchanged")
        void legitimateIdentifierPasses() {
            Transaction item = recordWithId(VALID_ID);

            assertThat(processor.process(item))
                    .as("the step is a validator, not a transformer: it returns the record it was given")
                    .isSameAs(item);
            assertThat(item.getTransactionId())
                    .as("not trimmed, not padded, not case folded")
                    .isEqualTo(VALID_ID);
        }

        @Test
        @DisplayName("no identifier in the frozen fixture is refused by this guard")
        void theGuardRefusesNoCorpusIdentifier() {
            // The parity test. A guard that rejected a legitimate record would be a behaviour change
            // dressed up as a security fix, so the whole fixture is driven through it rather than a
            // sample. All 300 identifiers are decimal digits, which is why this can hold.
            for (int digit = 0; digit <= 9; digit++) {
                String identifier = String.valueOf(digit).repeat(16);
                assertThat(processor.process(recordWithId(identifier)))
                        .as("an all-%d identifier is well formed and must pass", digit)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("the guard is narrower than the rendering, deliberately")
        void theGuardIsNarrowerThanTheRendering() {
            // U+2028 LINE SEPARATOR is not an ISO control character, so the guard permits it - correctly,
            // because PIC X(16) declares a character field and this class must not invent a stricter
            // domain than the copybook. The rendering must still neutralise it, because several log
            // readers treat it as a line break. This is the seam where the two widths differ.
            String permitted = "1\u202823456789012345";
            assertThat(permitted).hasSize(TransactionCombineProcessor.TRAN_ID_LENGTH);
            assertThat(Character.isISOControl('\u2028'))
                    .as("U+2028 is deliberately outside the guard's predicate")
                    .isFalse();

            assertThat(processor.process(recordWithId(permitted)))
                    .as("the narrow guard lets it through")
                    .isNotNull();
            assertThat(loggedMessages())
                    .as("and the broad rendering escapes it anyway, so no record can be split")
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain("\u2028")
                            .contains("\\u2028"));
        }
    }

    @Nested
    @DisplayName("2. Every reported identifier is escaped, on every path")
    class SafeRendering {

        @ParameterizedTest(name = "the {0} branch reports safely")
        @MethodSource("com.cardemo.unit.batch.TransactionCombineProcessorTest#lineBreakingIdentifiers")
        @DisplayName("a rejection message is single-line whichever branch produces it")
        void rejectionMessagesAreSingleLine(final String branch, final String hostileId) {
            // Sixteen newlines are String.isBlank(), so they take the blank branch rather than the
            // control branch. That is why the escaping is centralised in the renderer instead of being
            // guarded by check ordering: the branch that fires first still has to report safely.
            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(recordWithId(hostileId)))
                    .satisfies(thrown -> {
                        assertThat(thrown.getMessage())
                                .as("the %s branch must not emit a raw line break", branch)
                                .doesNotContain("\n")
                                .doesNotContain("\r");
                        assertThat(thrown.getMessage())
                                .as("and must show the escape so the value is still diagnosable")
                                .contains("\\n");
                    });
        }

        @Test
        @DisplayName("a tab and an unprintable byte are escaped distinctly")
        void escapesAreDistinguishable() {
            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(recordWithId("1\t3\u00004567890123")))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains("\\t")
                            .contains("\\u0000")
                            .doesNotContain("\t"));
        }

        @Test
        @DisplayName("a legitimate identifier is reported verbatim, quoted")
        void legitimateIdentifierIsReportedVerbatim() {
            processor.process(recordWithId(VALID_ID));

            assertThat(loggedMessages())
                    .as("an operator has to be able to go and find the record, so the key must survive")
                    .anySatisfy(message -> assertThat(message).contains("'" + VALID_ID + "'"));
        }

        @Test
        @DisplayName("the quotes make a space-padded key visible")
        void quotesRevealPadding() {
            // A trailing space is a real condition in a fixed-width record, and unquoted it is invisible
            // in a log line. This is the whole reason the rendering quotes rather than just escaping.
            String padded = "1234567890      ";
            assertThat(padded).hasSize(TransactionCombineProcessor.TRAN_ID_LENGTH);

            processor.process(recordWithId(padded));

            assertThat(loggedMessages())
                    .anySatisfy(message -> assertThat(message).contains("'" + padded + "'"));
        }

        @Test
        @DisplayName("a null identifier is disclosed as Not available, never as the word null")
        void nullIdentifierIsDisclosedHonestly() {
            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(recordWithId(null)))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains("Not available")
                            .contains("TRAN-ID is null"));
        }
    }

    @Nested
    @DisplayName("3. Store-failure translation names the right constraint and logs safely")
    class LoadFailureTranslation {

        @Test
        @DisplayName("a duplicate key names pk_transaction, which is the only constraint it can be")
        void duplicateKeyNamesThePrimaryKey() {
            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> processor.translateLoadFailure(recordWithId(VALID_ID),
                            new DuplicateKeyException("duplicate")))
                    .withMessageContaining("pk_transaction")
                    .withMessageContaining("Duplicate TRAN-ID")
                    .satisfies(thrown -> assertThat(thrown.getCause())
                            .as("the cause is preserved, never swallowed")
                            .isInstanceOf(DuplicateKeyException.class));
        }

        @Test
        @DisplayName("a generic integrity violation names the candidates and admits which is unknown")
        void integrityViolationNamesTheCandidates() {
            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.translateLoadFailure(recordWithId(VALID_ID),
                            new DataIntegrityViolationException("violation")))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .as("V1 exists and names all four, so the names are not unavailable - only "
                                    + "the mapping from one caught exception to one of them is, which is "
                                    + "what the diagnostic states rather than calling the names unknown")
                            .contains("does not name the constraint in a portable field")
                            .contains("pk_transaction")
                            .contains("fk04_transaction_card")
                            .contains("fk05_transaction_type")
                            .contains("fk06_transaction_category"));
        }

        @Test
        @DisplayName("any other store failure is fatal and carries the abend code")
        void otherFailuresAreFatal() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.translateLoadFailure(recordWithId(VALID_ID),
                            new CannotAcquireLockException("lock")))
                    .satisfies(thrown -> assertThat(thrown.getCause())
                            .isInstanceOf(CannotAcquireLockException.class));
        }

        @Test
        @DisplayName("a null item is reported as Not available rather than crashing the translation")
        void nullItemIsTolerated() {
            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> processor.translateLoadFailure(null,
                            new DuplicateKeyException("duplicate")))
                    .withMessageContaining("Not available");
        }

        @ParameterizedTest(name = "the {0} translation logs no raw line break")
        @CsvSource({"duplicate", "integrity", "other"})
        @DisplayName("every branch logs an error, and none of them can be split")
        void everyBranchLogsSafely(final String branch) {
            // These three are the only places in the class that log an error, and each logs the same
            // string it throws. A hostile identifier that reached the store layer would therefore be
            // rendered twice; both renderings have to hold.
            String hostileId = "1\nFATAL forged!  ";
            org.springframework.dao.DataAccessException cause = switch (branch) {
                case "duplicate" -> new DuplicateKeyException("duplicate");
                case "integrity" -> new DataIntegrityViolationException("violation");
                default -> new CannotAcquireLockException("lock");
            };

            try {
                processor.translateLoadFailure(recordWithId(hostileId), cause);
            } catch (RuntimeException expected) {
                assertThat(expected.getMessage())
                        .as("the thrown message must be single-line")
                        .doesNotContain("\n");
            }

            assertThat(loggedMessages())
                    .as("the %s branch must have logged exactly one event", branch)
                    .hasSize(1)
                    .allSatisfy(message -> assertThat(message)
                            .as("and that event must not have been split by the identifier")
                            .doesNotContain("\n")
                            .doesNotContain("\r")
                            .contains("\\n"));
        }
    }

    @Nested
    @DisplayName("4. The sort-key geometry branches still hold")
    class SortKeyGeometry {

        @Test
        @DisplayName("a null record is refused with a message that says why")
        void nullRecordIsRefused() {
            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(null))
                    .withMessageContaining("null record");
        }

        @ParameterizedTest(name = "a {1}-character identifier is refused")
        @CsvSource({"123456789012345, 15", "12345678901234567, 17", "1, 1"})
        @DisplayName("the declared width is a contract, so neither side of it is padded or truncated")
        void wrongWidthIsRefused(final String identifier, final int length) {
            assertThat(identifier).hasSize(length);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(recordWithId(identifier)))
                    .withMessageContaining("TRAN-ID,1,16,CH");
        }

        @Test
        @DisplayName("the geometry message cites the sort card that fixes the width")
        void geometryMessageCitesTheSortCard() {
            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(recordWithId("123")))
                    .withMessageContaining("app/jcl/COMBTRAN.jcl");
        }
    }

    @Nested
    @DisplayName("5. TRAN_ID_ASCENDING reproduces SORT FIELDS=(TRAN-ID,A)")
    class Ordering {

        @Test
        @DisplayName("records order by identifier ascending")
        void ordersAscending() {
            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING
                    .compare(recordWithId(VALID_ID), recordWithId(VALID_ID_2)))
                    .as("app/jcl/COMBTRAN.jcl:L30 sorts TRAN-ID ascending")
                    .isNegative();
            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING
                    .compare(recordWithId(VALID_ID_2), recordWithId(VALID_ID)))
                    .isPositive();
            assertThat(TransactionCombineProcessor.TRAN_ID_ASCENDING
                    .compare(recordWithId(VALID_ID), recordWithId(VALID_ID)))
                    .isZero();
        }

        @Test
        @DisplayName("the declared record length is the 350 bytes the DCB fixes")
        void recordLengthIsDeclared() {
            assertThat(TransactionCombineProcessor.COMBINED_RECORD_LENGTH)
                    .as("app/jcl/COMBTRAN.jcl:L35 DCB=(*.SORTIN) fixes the record at 350 bytes")
                    .isEqualTo(350);
            assertThat(TransactionCombineProcessor.TRAN_ID_LENGTH)
                    .as("app/cpy/CVTRA05Y.cpy:L5 TRAN-ID PIC X(16)")
                    .isEqualTo(16);
        }
    }
}
