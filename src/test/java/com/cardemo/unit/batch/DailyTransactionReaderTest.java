/*
 * ******************************************************************
 * Component   : DailyTransactionReaderTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Direct evidence for DailyTransactionReader: the three worked
 *               trailing-sign overpunch decodes, the 26-space DALYTRAN-PROC-TS,
 *               the ten-character OPERATOR source, the 250-to-50 type split
 *               across the whole 300-row fixture, both the repository and the
 *               fixed-width input paths, the FILE STATUS IS: NNNN rendering,
 *               and the abend carrying code 999 with culprit CBTRN02C.
 * Source      : app/cbl/CBTRN02C.cbl  (0000-DALYTRAN-OPEN, mainline READ loop,
 *                                     1000-DALYTRAN-GET-NEXT,
 *                                     9910-DISPLAY-IO-STATUS,
 *                                     9999-ABEND-PROGRAM)
 *               app/cpy/CVTRA06Y.cpy  (350-byte DALYTRAN record layout)
 *               app/jcl/POSTTRAN.jcl  (the job that supplies DALYTRAN)
 *               app/data/ASCII/dailytran.txt (300 rows, the Gate 1 fixture)
 *                                                          @ 7756d89
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
 * permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.unit.model.FixtureLoader;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

/**
 * Unit tests for {@code com.cardemo.batch.readers.DailyTransactionReader}, the {@code DALYTRAN} half of
 * {@code app/cbl/CBTRN02C.cbl}'s mainline, at traceability anchor commit {@code 7756d89}.
 *
 * <h2>1. What it does</h2>
 *
 * <p>A review found that <strong>no test imported, instantiated or class-referenced this reader</strong>,
 * while the reader's own documentation stated that its suites "must assert" a specific list of values. This
 * class discharges that list item by item, so the claim is evidence rather than an instruction:
 *
 * <ul>
 *   <li>the three worked decodes named in that documentation - {@code 0000005047G} to {@code 504.77},
 *       {@code 0000009190}<code>&#125;</code> to {@code -919.00} and {@code 0000000678H} to {@code 67.88},
 *       all at scale 2;
 *   <li>that {@code DALYTRAN-PROC-TS} arrives as 26 spaces and is neither {@code null} nor empty;
 *   <li>that {@code OPERATOR} passes through as a ten-character source, untrimmed;
 *   <li>that the type split across the fixture is 250 rows of {@code 01} to 50 rows of {@code 03};
 *   <li>that both input paths - {@code repository} and {@code fixed-width} - are reachable and neither
 *       branch is dead;
 *   <li>that the status rendering is the source's own four-character {@code FILE STATUS IS: NNNN} field; and
 *   <li>that all 300 rows of {@code app/data/ASCII/dailytran.txt} decode, with the negative amounts left
 *       negative.
 * </ul>
 *
 * <p>Two properties are asserted here that a passing decode alone would not prove. The overpunch is read
 * from <strong>one</strong> position, so the letters that occur legitimately inside a merchant name are never
 * mistaken for signs - the AAP calls position-aware decoding out explicitly, because a scan-and-replace
 * implementation passes every happy-path test and corrupts real data. And a negative amount is
 * <strong>never</strong> normalised to its magnitude, because {@code app/cbl/CBTRN02C.cbl}'s cycle-debit
 * accumulator legitimately holds negative values and the over-limit formula subtracts it.
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>Bound to the Surefire tier by its {@code *Test} name under {@code src/test/java/com/cardemo/unit}. The
 * repository and the object store are mocked and {@link FileStatusMapper} is real, so no container, database
 * or cloud emulator is needed.
 *
 * <pre>
 * ./mvnw -B -ntp -Dtest=DailyTransactionReaderTest test
 * ./mvnw -B -ntp clean verify
 * </pre>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>The reader is built through its public constructor with literal configuration values, because every
 * property name, offset, status code and message constant on it is {@code private}. The fixture is read
 * through {@link FixtureLoader}, which loads {@code src/test/resources/dailytran.txt} - byte-identical to
 * {@code app/data/ASCII/dailytran.txt}, verified by comparison, and never trimmed, copied or edited here.
 * Log output is captured with a Logback {@link ListAppender} attached to the reader's own logger and
 * detached again after every test, so no assertion depends on a shipped log level.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Blocker.</strong> A failure in the sign group means an amount decoded with the wrong sign or
 *       the wrong scale. Every downstream balance, the over-limit formula and the reject decision are then
 *       wrong, and the fixture carries genuinely negative amounts, so this is not hypothetical.
 *   <li><strong>Blocker.</strong> A failure of the position-awareness test means the decoder is scanning for
 *       a sign character rather than reading byte 143. Merchant names in the fixture contain the letters
 *       {@code A}-{@code R}, so such an implementation corrupts amounts silently.
 *   <li><strong>High.</strong> A failure in the field-contract group means an offset moved; every field after
 *       it is then misread, and the 26-space processing timestamp is the field the posting step overwrites.
 *   <li><strong>High.</strong> A failure in the status or abend group means an I/O fault no longer abends
 *       with code 999 and culprit {@code CBTRN02C}, so a truncated or unavailable input could be mistaken
 *       for a short but successful run.
 *   <li><strong>Medium.</strong> A failure in the restart group means the checkpoint no longer resumes
 *       correctly, which double-posts or skips rows on a restarted job.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("DailyTransactionReader: CBTRN02C's DALYTRAN read path")
class DailyTransactionReaderTest {

    /** The fixed record width of {@code app/cpy/CVTRA06Y.cpy}, and of every row of the fixture. */
    private static final int RECORD_LENGTH = 350;

    /** The bucket name used on the {@code fixed-width} path; a literal, never an endpoint or a URL. */
    private static final String INPUT_BUCKET = "carddemo-batch-input";

    /** The reader's own default object key, restated because the constant on it is private. */
    private static final String OBJECT_KEY = "dalytran/dailytran.txt";

    /**
     * The signing key the object-authenticity key is derived from, at least the thirty-two bytes the
     * algorithm requires. A test literal, never a shipped default: finding M-11 forbids any committed key.
     */
    private static final String SIGNING_KEY = "daily-transaction-reader-test-signing-key-0123456789";

    /** The writer identity the stubbed object claims, carried inside the authenticated manifest. */
    private static final String WRITER = "carddemo-fixture-feed";

    /** The {@code repository} input selector. */
    private static final String SOURCE_REPOSITORY = "repository";

    /** The {@code fixed-width} input selector. */
    private static final String SOURCE_FIXED_WIDTH = "fixed-width";

    /** The prefix of the source's own status line, {@code app/cbl/CBTRN02C.cbl:L714-L727}. */
    private static final String STATUS_LINE_PREFIX = "FILE STATUS IS: NNNN";

    /** The open-failure line the source displays at {@code app/cbl/CBTRN02C.cbl:L247}. */
    private static final String ERROR_OPENING = "ERROR OPENING DALYTRAN";

    /** The read-failure line the source displays at {@code app/cbl/CBTRN02C.cbl:L363}. */
    private static final String ERROR_READING = "ERROR READING DALYTRAN FILE";

    /** The abend line the source displays at {@code app/cbl/CBTRN02C.cbl:L708}. */
    private static final String ABENDING_PROGRAM = "ABENDING PROGRAM";

    /** The program the abend names as culprit, {@code app/cbl/CBTRN02C.cbl}. */
    private static final String ABEND_CULPRIT = "CBTRN02C";

    /** A 26-space processing timestamp: the state every fixture row carries before posting stamps it. */
    private static final String BLANK_TIMESTAMP = " ".repeat(26);

    /** A populated originating timestamp in the fixture's own format. */
    private static final String ORIGINATING_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** The staged relation, mocked: only {@code count} and the ordered finder are ever called. */
    @Mock private DailyTransactionRepository repository;

    /** The object store, mocked: only {@code objectExists} and {@code download} are ever called. */
    @Mock private S3Operations objectStorage;

    /** Captures the reader's own log events so the DISPLAY-parity lines can be asserted. */
    private ListAppender<ILoggingEvent> appender;

    /** The reader's logger, raised to {@code TRACE} for the duration of each test. */
    private ch.qos.logback.classic.Logger logger;

    /** The level the reader's logger carried before this test, restored afterwards. */
    private Level originalLevel;

    /** Builds the collaborators and attaches the log appender. */
    @BeforeEach
    void buildCollaboratorsAndCaptureLogs() {
        appender = new ListAppender<>();
        appender.start();
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DailyTransactionReader.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
    }

    /** Detaches the appender and restores the level, so no test leaks logging state into another. */
    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    /**
     * Builds a reader on the {@code repository} path.
     *
     * @param pageSize rows per database round trip
     * @return the reader, never {@code null}
     */
    private DailyTransactionReader repositoryReader(final int pageSize) {
        return new DailyTransactionReader(repository, objectStorage, new FileStatusMapper(),
                SOURCE_REPOSITORY, pageSize, "", OBJECT_KEY, SIGNING_KEY);
    }

    /**
     * Builds a reader on the {@code fixed-width} path.
     *
     * @return the reader, never {@code null}
     */
    private DailyTransactionReader fixedWidthReader() {
        return new DailyTransactionReader(repository, objectStorage, new FileStatusMapper(),
                SOURCE_FIXED_WIDTH, 100, INPUT_BUCKET, OBJECT_KEY, SIGNING_KEY);
    }

    /**
     * Stubs the object store to serve the supplied 350-character images concatenated with no separator.
     *
     * <p>Undelimited deliberately, and this helper used to append a newline after every image. That shape is
     * not the dataset: {@code app/cbl/CBTRN02C.cbl:L66-L69} declares one fixed 350-character record group
     * over the {@code ORGANIZATION IS SEQUENTIAL} file of {@code :L29-L32}, so the record boundary is the
     * record length and the object carries no delimiter at all. Building the body the old way meant every
     * case on this path exercised a tolerance the production reader no longer has, and none of them
     * exercised the shape the reader actually receives.
     *
     * @param images the record images, each already exactly {@value #RECORD_LENGTH} characters
     */
    private void stubObject(final List<String> images) {
        final StringBuilder body = new StringBuilder(images.size() * RECORD_LENGTH);
        images.forEach(body::append);
        stubObjectBody(body.toString());
    }

    /**
     * Stubs the object store to serve exactly the supplied body, byte for byte, separators included.
     *
     * @param body the object content, byte for byte
     */
    private void stubObjectBody(final String body) {
        stubObjectBody(body, true, true);
    }

    /**
     * Stubs the object store to serve exactly the supplied body, with control over its authenticity envelope.
     *
     * <p>Finding M-11: every {@code fixed-width} test reaches the reader through here, so the envelope is
     * built in ONE place and every existing test is authenticated by construction rather than by each test
     * remembering to sign. The two flags exist so the refusal paths can be exercised without a second stub.
     *
     * @param body the object content, byte for byte
     * @param signed whether the object carries a valid code for its own manifest
     * @param digestMatches whether the declared content digest is the digest of {@code body}
     */
    private void stubObjectBody(final String body, final boolean signed, final boolean digestMatches) {
        final S3Resource resource = Mockito.mock(S3Resource.class);
        final byte[] content = body.getBytes(StandardCharsets.ISO_8859_1);
        final String declaredDigest = digestMatches
                ? sha256HexOf(content)
                : sha256HexOf("a different body of the same declared length".getBytes(StandardCharsets.UTF_8));
        Mockito.when(resource.contentLength()).thenReturn(Long.valueOf(content.length));
        Mockito.when(resource.metadata()).thenReturn(java.util.Map.of(
                DailyTransactionReader.InputObjectEnvelope.METADATA_WRITER, WRITER,
                DailyTransactionReader.InputObjectEnvelope.METADATA_CONTENT_SHA256, declaredDigest,
                DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE,
                signed
                        ? DailyTransactionReader.InputObjectEnvelope.sign(INPUT_BUCKET, OBJECT_KEY,
                                content.length, WRITER, declaredDigest, SIGNING_KEY)
                        : "v1=00"));
        Mockito.when(objectStorage.objectExists(INPUT_BUCKET, OBJECT_KEY)).thenReturn(Boolean.TRUE);
        Mockito.when(objectStorage.download(INPUT_BUCKET, OBJECT_KEY)).thenReturn(resource);
        try {
            // A fresh stream per download call: the reader opens once per open(), and a shared stream would
            // make a second open() in the same test read from where the first one stopped.
            Mockito.when(resource.getInputStream()).thenAnswer(invocation ->
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (final IOException impossible) {
            throw new IllegalStateException("stubbing getInputStream cannot perform I/O", impossible);
        }
    }

    /**
     * Renders the SHA-256 of content the way the producer contract requires: lowercase hexadecimal.
     *
     * <p>Uses the published contract rather than a second implementation, because a test that reimplemented
     * the rendering would prove only that the two implementations agreed with each other.
     *
     * @param content the bytes to digest
     * @return the rendering, never {@code null}
     */
    private static String sha256HexOf(final byte[] content) {
        return DailyTransactionReader.InputObjectEnvelope.hexadecimal(
                DailyTransactionReader.InputObjectEnvelope.newDigest().digest(content));
    }

    /**
     * Stubs the staged relation to serve the supplied rows in ordinal order.
     *
     * @param rows the staged rows, in the order the finder promises
     */
    private void stubRelation(final List<DailyTransaction> rows) {
        Mockito.when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                        Mockito.anyLong(), Mockito.any(Pageable.class)))
                .thenAnswer(invocation -> keysetWindow(rows,
                        ((Long) invocation.getArgument(0)).longValue(), invocation.getArgument(1)));
    }

    /**
     * Serves one keyset window of a stubbed staging relation.
     *
     * <p>The reader seeks rather than skips: it asks for the rows whose ingestion ordinal is strictly
     * greater than the cursor it has already emitted, which is what lets a resumed run re-enter the
     * primary-key index at its checkpoint instead of walking and discarding everything before it. The
     * same finder serves the one-row reachability probe {@code open()} issues and every refill the read
     * loop issues, so this one answer covers both.
     *
     * @param rows the staged rows, in the order the finder promises
     * @param cursor the highest ordinal already emitted; rows at or below it are not served
     * @param request the window the reader asked for, whose page size bounds the slice
     * @return the slice, reporting a successor whenever rows remain beyond the window
     */
    private static Slice<DailyTransaction> keysetWindow(final List<DailyTransaction> rows,
            final long cursor, final Pageable request) {
        final List<DailyTransaction> remaining = rows.stream()
                .filter(row -> row.getIngestSequence().longValue() > cursor)
                .toList();
        final int windowSize = Math.min(request.getPageSize(), remaining.size());
        return new SliceImpl<>(List.copyOf(remaining.subList(0, windowSize)), request,
                windowSize < remaining.size());
    }

    /**
     * Composes one 350-character image from its thirteen declared fields plus the trailing filler.
     *
     * <p>Every field is placed at the offset {@code app/cpy/CVTRA06Y.cpy} declares and padded to its exact
     * declared width, so a caller cannot silently produce a short record and a caller that means to produce
     * one must say so by calling {@link #truncate(String, int)}.
     *
     * @param transactionId {@code DALYTRAN-ID}, 16
     * @param typeCode {@code DALYTRAN-TYPE-CD}, 2
     * @param categoryCode {@code DALYTRAN-CAT-CD}, 4
     * @param source {@code DALYTRAN-SOURCE}, 10
     * @param description {@code DALYTRAN-DESC}, 100
     * @param amountField {@code DALYTRAN-AMT}, 11, trailing-sign overpunched
     * @param merchantId {@code DALYTRAN-MERCHANT-ID}, 9
     * @param merchantName {@code DALYTRAN-MERCHANT-NAME}, 50
     * @param merchantCity {@code DALYTRAN-MERCHANT-CITY}, 50
     * @param merchantZip {@code DALYTRAN-MERCHANT-ZIP}, 10
     * @param cardNumber {@code DALYTRAN-CARD-NUM}, 16
     * @param origTs {@code DALYTRAN-ORIG-TS}, 26
     * @param procTs {@code DALYTRAN-PROC-TS}, 26
     * @return the image, exactly {@value #RECORD_LENGTH} characters
     */
    private static String image(final String transactionId, final String typeCode, final String categoryCode,
            final String source, final String description, final String amountField, final String merchantId,
            final String merchantName, final String merchantCity, final String merchantZip,
            final String cardNumber, final String origTs, final String procTs) {

        final StringBuilder record = new StringBuilder(RECORD_LENGTH);
        record.append(pad(transactionId, 16)).append(pad(typeCode, 2)).append(pad(categoryCode, 4))
                .append(pad(source, 10)).append(pad(description, 100)).append(pad(amountField, 11))
                .append(pad(merchantId, 9)).append(pad(merchantName, 50)).append(pad(merchantCity, 50))
                .append(pad(merchantZip, 10)).append(pad(cardNumber, 16)).append(pad(origTs, 26))
                .append(pad(procTs, 26)).append(" ".repeat(20));

        if (record.length() != RECORD_LENGTH) {
            throw new IllegalStateException("composed image is " + record.length()
                    + " characters; app/cpy/CVTRA06Y.cpy declares " + RECORD_LENGTH);
        }
        return record.toString();
    }

    /**
     * Composes a routine image whose only interesting field is the amount.
     *
     * @param amountField the eleven-character overpunched amount field
     * @return the image, exactly {@value #RECORD_LENGTH} characters
     */
    private static String imageWithAmount(final String amountField) {
        return image("0000000000000001", "01", "0001", "POS TERM", "A ROUTINE PURCHASE", amountField,
                "800000000", "MERCHANT", "CITY", "12345", "4859452612877065", ORIGINATING_TIMESTAMP,
                BLANK_TIMESTAMP);
    }

    /**
     * Pads or refuses: right-pads with spaces to the declared width, and refuses anything wider.
     *
     * @param value the field value
     * @param width the declared width
     * @return the value at exactly {@code width} characters
     */
    private static String pad(final String value, final int width) {
        final String supplied = value == null ? "" : value;
        if (supplied.length() > width) {
            throw new IllegalArgumentException("'" + supplied + "' is " + supplied.length()
                    + " characters but the copybook declares " + width);
        }
        return supplied + " ".repeat(width - supplied.length());
    }

    /**
     * Returns the leading {@code keep} characters of an image, producing a deliberately short record.
     *
     * @param full the full image
     * @param keep how many characters to keep
     * @return the truncated image
     */
    private static String truncate(final String full, final int keep) {
        return full.substring(0, keep);
    }

    /**
     * Drains a reader to exhaustion.
     *
     * @param reader the reader, already opened
     * @return every record it produced, in order
     */
    private static List<DailyTransaction> drain(final DailyTransactionReader reader) {
        final List<DailyTransaction> produced = new ArrayList<>();
        DailyTransaction next = reader.read();
        while (next != null) {
            produced.add(next);
            next = reader.read();
        }
        return produced;
    }

    /**
     * Returns every message the reader logged, in order.
     *
     * @return the formatted messages
     */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Builds a staged row with a distinguishable identifier, for the {@code repository} path.
     *
     * @param ordinal the ingestion ordinal, which is the relation's primary key and its scan order
     * @param transactionId the sixteen-character identifier
     * @return the staged row, never {@code null}
     */
    private static DailyTransaction stagedRow(final long ordinal, final String transactionId) {
        return new DailyTransaction(Long.valueOf(ordinal), transactionId, "01", Integer.valueOf(1),
                pad("POS TERM", 10), pad("A ROUTINE PURCHASE", 100), new BigDecimal("504.77"),
                Long.valueOf(800_000_000L), pad("MERCHANT", 50), pad("CITY", 50), pad("12345", 10),
                "4859452612877065", ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP);
    }

    /** The three worked decodes and the sign rules behind them. */
    @Nested
    @DisplayName("1. trailing-sign overpunch decoding")
    final class OverpunchDecoding {

        @ParameterizedTest(name = "{0} decodes to {1}")
        @CsvSource({
            "0000005047G, 504.77",
            "0000009190}, -919.00",
            "0000000678H, 67.88"
        })
        @DisplayName("the three worked decodes the reader's own documentation names")
        void theThreeWorkedDecodes(final String amountField, final String expected) {
            stubObject(List.of(imageWithAmount(amountField)));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            assertThat(produced).hasSize(1);
            // isEqualByComparingTo, never equals: BigDecimal equality is scale-sensitive and the parity
            // comparison is made on value at two decimal places.
            assertThat(produced.get(0).getAmount()).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(produced.get(0).getAmount().scale())
                    .as("PIC S9(09)V99 and column dalytran_amt NUMERIC(11,2) both fix the scale at 2")
                    .isEqualTo(2);
        }

        @ParameterizedTest(name = "overpunch {0} yields {1}")
        @CsvSource({
            "00000000000, 0.00",
            "0000000000{, 0.00",
            "0000000000}, 0.00",
            "0000000000A, 0.01",
            "0000000000I, 0.09",
            "0000000000J, -0.01",
            "0000000000R, -0.09",
            "00000000009, 0.09"
        })
        @DisplayName("the whole overpunch table: braces for zero, A-I positive, J-R negative")
        void theWholeOverpunchTable(final String amountField, final String expected) {
            stubObject(List.of(imageWithAmount(amountField)));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            assertThat(produced.get(0).getAmount()).isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("a negative amount stays negative and is never normalised to its magnitude")
        void aNegativeAmountIsNeverNormalised() {
            stubObject(List.of(imageWithAmount("0000009190}")));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final BigDecimal decoded = drain(reader).get(0).getAmount();
            reader.close();

            // app/cbl/CBTRN02C.cbl:L547-L552 adds a negative amount to the cycle DEBIT accumulator, so that
            // accumulator legitimately holds negative values, and the over-limit formula at :L393-L422
            // SUBTRACTS it. Any absolute-value normalisation anywhere on this path breaks that arithmetic.
            assertThat(decoded).isNegative().isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(decoded.abs())
                    .as("stated explicitly so a future normalisation is caught here rather than in a balance")
                    .isNotEqualByComparingTo(decoded);
        }

        @Test
        @DisplayName("the sign is read from byte 143 only, so letters inside a merchant name are just text")
        void theSignIsReadFromOnePositionOnly() {
            // Every one of A-R appears in these fields. A decoder that scanned the record for a sign
            // character, or replaced one wherever it found it, would pick one of them up here.
            final String decoy = image("0000000000000002", "01", "0001", "POS TERM",
                    "REFUND JAR IJ QR ABCDEFGHIJKLMNOPQR", "0000005047G", "800000000",
                    "ABCDEFGHIJKLMNOPQR MERCHANT", "JACKSONVILLE ABIJR", "12345", "4859452612877065",
                    ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP);
            stubObject(List.of(decoy));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final DailyTransaction produced = drain(reader).get(0);
            reader.close();

            assertThat(produced.getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(produced.getMerchantName()).startsWith("ABCDEFGHIJKLMNOPQR");
            assertThat(produced.getDescription()).contains("JAR IJ QR");
        }

        @ParameterizedTest(name = "overpunch '{0}' is refused")
        @ValueSource(strings = { "0000005047*", "0000005047S", "0000005047Z", "0000005047 ", "0000005047+",
            "0000005047-" })
        @DisplayName("an unrecognised terminal character is refused rather than guessed at")
        void anUnrecognisedOverpunchIsRefused(final String amountField) {
            stubObject(List.of(imageWithAmount(amountField)));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final DataIntegrityException refusal =
                    catchThrowableOfType(DataIntegrityException.class, reader::read);

            assertThat(refusal).isNotNull();
            // Reported by POSITION, never by value, so no part of the record reaches the message.
            assertThat(refusal.getMessage()).contains("143").doesNotContain(amountField);
        }

        @Test
        @DisplayName("a non-digit anywhere in the leading ten characters is refused by position")
        void aNonDigitInsideTheAmountIsRefused() {
            stubObject(List.of(imageWithAmount("00000O5047G")));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final DataIntegrityException refusal =
                    catchThrowableOfType(DataIntegrityException.class, reader::read);

            assertThat(refusal).isNotNull();
            assertThat(refusal.getMessage()).contains("138").contains("133").contains("143");
        }
    }

    /** The field contract: every offset, and the two timestamps. */
    @Nested
    @DisplayName("2. the 350-byte field contract of CVTRA06Y")
    final class FieldContract {

        @Test
        @DisplayName("all thirteen fields land at the offsets the copybook declares")
        void everyFieldLandsAtItsDeclaredOffset() {
            final String populated = image("0000000000683580", "01", "0007", "POS TERM  ", "PURCHASE OF FUEL",
                    "0000005047G", "800000000", "STOKES-MUELLER", "NORTH LILYBERG", "36903-3350",
                    "4859452612877065", ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP);
            stubObject(List.of(populated));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final DailyTransaction produced = drain(reader).get(0);
            reader.close();

            assertThat(produced.getTransactionId()).isEqualTo("0000000000683580");
            assertThat(produced.getTypeCode()).isEqualTo("01");
            assertThat(produced.getCategoryCode()).isEqualTo(Integer.valueOf(7));
            assertThat(produced.getTransactionSource()).isEqualTo("POS TERM  ");
            assertThat(produced.getDescription()).startsWith("PURCHASE OF FUEL").hasSize(100);
            assertThat(produced.getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(produced.getMerchantId()).isEqualTo(Long.valueOf(800_000_000L));
            assertThat(produced.getMerchantName()).startsWith("STOKES-MUELLER").hasSize(50);
            assertThat(produced.getMerchantCity()).startsWith("NORTH LILYBERG").hasSize(50);
            assertThat(produced.getMerchantZip()).isEqualTo("36903-3350");
            assertThat(produced.getCardNumber()).isEqualTo("4859452612877065");
            assertThat(produced.getOrigTs()).isEqualTo(ORIGINATING_TIMESTAMP).hasSize(26);
            assertThat(produced.getProcTs()).hasSize(26);
        }

        @Test
        @DisplayName("a blank DALYTRAN-PROC-TS is 26 spaces, and is neither null nor empty")
        void theProcessingTimestampIsTwentySixSpaces() {
            stubObject(List.of(imageWithAmount("0000005047G")));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final DailyTransaction produced = drain(reader).get(0);
            reader.close();

            // Not null and not empty: the posting step stamps this field, and a null would become a
            // NOT NULL violation while an empty string would fail the PIC X(26) width guard.
            assertThat(produced.getProcTs()).isNotNull().isNotEmpty().isEqualTo(BLANK_TIMESTAMP);
            assertThat(produced.getProcTs()).hasSize(26).isBlank();
        }

        @Test
        @DisplayName("OPERATOR passes through as a ten-character source, padded and untrimmed")
        void theOperatorSourcePassesThroughAtTenCharacters() {
            final String operatorRow = image("0000000000000003", "03", "0001", "OPERATOR",
                    "AN OPERATOR ORIGINATED ADJUSTMENT", "0000000678H", "800000000", "MERCHANT", "CITY",
                    "12345", "4859452612877065", ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP);
            stubObject(List.of(operatorRow));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final DailyTransaction produced = drain(reader).get(0);
            reader.close();

            // PIC X(10), so the value is 'OPERATOR' plus two spaces. Trimming it here would change the
            // stored column and break the byte-exact comparison the parity gate makes.
            assertThat(produced.getTransactionSource()).isEqualTo("OPERATOR  ").hasSize(10);
            assertThat(produced.getTypeCode()).isEqualTo("03");
        }

        @Test
        @DisplayName("a record that is not exactly 350 characters is refused, not read on regardless")
        void aShortRecordIsRefused() {
            stubObjectBody(truncate(imageWithAmount("0000005047G"), 349));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final DataIntegrityException refusal =
                    catchThrowableOfType(DataIntegrityException.class, reader::read);

            assertThat(refusal).isNotNull();
            // No field offset after the truncation point can be trusted, so the record is refused rather
            // than decoded from whatever happens to be there.
            assertThat(refusal.getMessage()).contains("349").contains("350");
        }

        @Test
        @DisplayName("a carriage return or line feed between records is refused, not consumed")
        void aRecordSeparatorIsRefused() {
            final String first = imageWithAmount("0000005047G");
            final String second = imageWithAmount("0000009190}");
            stubObjectBody(first + "\r\n" + second + "\r\n");
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final DataIntegrityException refusal =
                    catchThrowableOfType(DataIntegrityException.class, reader::read);

            assertThat(refusal).isNotNull();
            // Tolerating the separator was the defect. app/cbl/CBTRN02C.cbl:L66-L69 declares one fixed
            // 350-character record group with no delimiter, so a separator can only mean the object was not
            // written by this application or was truncated in transfer - and consuming it made corrupt
            // geometry indistinguishable from valid input, because every record after the first stray byte
            // would be decoded as though it were still aligned.
            assertThat(refusal.getMessage())
                    .contains("separator")
                    .contains("0x0D")
                    .contains("CBTRN02C");
        }

        @Test
        @DisplayName("a line feed after the last record is refused too, so a trailing newline cannot pass")
        void aTrailingLineFeedIsRefused() {
            stubObjectBody(imageWithAmount("0000005047G") + "\n");
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final DataIntegrityException refusal =
                    catchThrowableOfType(DataIntegrityException.class, reader::read);

            assertThat(refusal).isNotNull();
            // 351 bytes is not a whole multiple of 350. The rule is enforced by construction rather than by
            // a length probe: a separator after a whole record is refused here, and a short final read is
            // refused as a truncated record, so no object of a non-multiple length can be consumed silently.
            assertThat(refusal.getMessage()).contains("separator").contains("0x0A");
        }

        @Test
        @DisplayName("an undelimited object yields its records, which is the RECFM=FB shape")
        void anUndelimitedObjectDecodes() {
            stubObjectBody(imageWithAmount("0000005047G") + imageWithAmount("0000000678H"));
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            // The mainframe dataset carries no delimiter, so this is the only shape the fixed-width path
            // accepts, and the object length is exactly twice the record length.
            assertThat(produced).hasSize(2);
            assertThat(produced.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(produced.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("67.88"));
        }
    }

    /** The repository path, and the lifecycle contract shared by both paths. */
    @Nested
    @DisplayName("3. the repository input path")
    final class RepositoryPath {

        @Test
        @DisplayName("rows are served in ordinal order across a page boundary")
        void rowsAreServedInOrdinalOrderAcrossPages() {
            stubRelation(List.of(stagedRow(1L, "0000000000000001"), stagedRow(2L, "0000000000000002"),
                    stagedRow(3L, "0000000000000003"), stagedRow(4L, "0000000000000004"),
                    stagedRow(5L, "0000000000000005")));
            final DailyTransactionReader reader = repositoryReader(2);
            reader.open(new ExecutionContext());

            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            assertThat(produced).extracting(DailyTransaction::getTransactionId)
                    .containsExactly("0000000000000001", "0000000000000002", "0000000000000003",
                            "0000000000000004", "0000000000000005");
            assertThat(reader.getRecordsRead()).isEqualTo(5L);
        }

        @Test
        @DisplayName("an empty staging relation is a successful run of zero records, not a fault")
        void anEmptyRelationIsASuccessfulRun() {
            stubRelation(List.of());
            final DailyTransactionReader reader = repositoryReader(100);
            reader.open(new ExecutionContext());

            assertThat(reader.read()).isNull();
            reader.close();

            // FILE STATUS '10' is end of file, not an error: the source's mainline simply never enters the
            // loop body. The condition is stated in the log rather than left to be inferred from silence.
            assertThat(reader.getRecordsRead()).isZero();
            assertThat(loggedMessages())
                    .as("the condition is stated, not left to be inferred from an absence of records")
                    .anyMatch(line -> line.contains("nothing left to read"));
            assertThat(loggedMessages()).noneMatch(line -> line.equals(ERROR_OPENING));
        }

        @Test
        @DisplayName("read() keeps answering null after exhaustion rather than restarting the scan")
        void readKeepsAnsweringNullAfterExhaustion() {
            stubRelation(List.of(stagedRow(1L, "0000000000000001")));
            final DailyTransactionReader reader = repositoryReader(100);
            reader.open(new ExecutionContext());

            assertThat(reader.read()).isNotNull();
            assertThat(reader.read()).isNull();
            assertThat(reader.read())
                    .as("the end-of-file latch is MOVE 'Y' TO END-OF-FILE at app/cbl/CBTRN02C.cbl:L361, "
                            + "which the source never clears inside the run")
                    .isNull();
            reader.close();
        }

        @Test
        @DisplayName("read() before open() is refused, because the source opens unconditionally first")
        void readBeforeOpenIsRefused() {
            final DailyTransactionReader reader = repositoryReader(100);

            assertThatIllegalStateException().isThrownBy(reader::read)
                    .withMessageContaining("app/cbl/CBTRN02C.cbl:L195");
        }

        @Test
        @DisplayName("a store failure on read abends with code 999 and culprit CBTRN02C, cause retained")
        void aStoreFailureOnReadAbends() {
            final QueryTimeoutException timeout = new QueryTimeoutException("the store timed out");
            final List<DailyTransaction> staged = List.of(stagedRow(1L, "0000000000000001"));
            final AtomicInteger seeks = new AtomicInteger();
            Mockito.when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                            Mockito.anyLong(), Mockito.any(Pageable.class)))
                    .thenAnswer(invocation -> {
                        // open() proves the relation reachable with a one-row seek. That seek must succeed,
                        // or the abend under test would be raised on the open rather than on the read.
                        if (seeks.getAndIncrement() == 0) {
                            return keysetWindow(staged,
                                    ((Long) invocation.getArgument(0)).longValue(),
                                    invocation.getArgument(1));
                        }
                        throw timeout;
                    });
            final DailyTransactionReader reader = repositoryReader(100);
            reader.open(new ExecutionContext());

            final FatalProcessingException abend =
                    catchThrowableOfType(FatalProcessingException.class, reader::read);

            assertThat(abend).isNotNull();
            assertThat(abend.getAbendCode())
                    .isEqualTo(Integer.toString(FatalProcessingException.BATCH_ABEND_CODE));
            assertThat(abend.getAbendCulprit()).isEqualTo(ABEND_CULPRIT);
            assertThat(abend).hasCause(timeout);
            assertThat(loggedMessages()).containsSequence(ERROR_READING,
                    STATUS_LINE_PREFIX + "9048", ABENDING_PROGRAM);
        }

        @ParameterizedTest(name = "source=\"{0}\"")
        @ValueSource(strings = { "REPOSITORY", "Repository", "fixed_width", "FIXED-WIDTH" })
        @DisplayName("the selector is case-insensitive and accepts a hyphen or an underscore")
        void theSelectorIsCaseInsensitive(final String configured) {
            assertThat(new DailyTransactionReader(repository, objectStorage, new FileStatusMapper(),
                    configured, 100, INPUT_BUCKET, OBJECT_KEY, SIGNING_KEY))
                    .as("both branches are reachable, so neither is dead code")
                    .isNotNull();
        }

        @Test
        @DisplayName("a selector naming neither path is refused at construction, not at first read")
        void anUnknownSelectorIsRefusedAtConstruction() {
            assertThatIllegalArgumentException().isThrownBy(() -> new DailyTransactionReader(
                    repository, objectStorage, new FileStatusMapper(), "vsam", 100, INPUT_BUCKET,
                    OBJECT_KEY, SIGNING_KEY))
                    .withMessageContaining("repository")
                    .withMessageContaining("fixed-width");
        }

        @Test
        @DisplayName("the fixed-width path refuses to be built without a bucket")
        void theFixedWidthPathRequiresABucket() {
            assertThatIllegalArgumentException().isThrownBy(() -> new DailyTransactionReader(
                    repository, objectStorage, new FileStatusMapper(), SOURCE_FIXED_WIDTH, 100, "  ",
                    OBJECT_KEY, SIGNING_KEY))
                    .withMessageContaining("carddemo.aws.s3.batch-input-bucket");
        }
    }

    /** The status rendering and the abend contract on the object path. */
    @Nested
    @DisplayName("4. FILE STATUS rendering and the abend")
    final class StatusRenderingAndAbend {

        @Test
        @DisplayName("an absent object abends, logging the open failure then the four-character status")
        void anAbsentObjectAbends() {
            Mockito.when(objectStorage.objectExists(INPUT_BUCKET, OBJECT_KEY)).thenReturn(Boolean.FALSE);
            final DailyTransactionReader reader = fixedWidthReader();

            final FatalProcessingException abend = catchThrowableOfType(FatalProcessingException.class,
                    () -> reader.open(new ExecutionContext()));

            assertThat(abend).isNotNull();
            assertThat(abend.getAbendCulprit()).isEqualTo(ABEND_CULPRIT);
            assertThat(abend.getAbendCode())
                    .isEqualTo(Integer.toString(FatalProcessingException.BATCH_ABEND_CODE));
            // FILE STATUS '35' is numeric with a first byte other than '9', so 9910-DISPLAY-IO-STATUS
            // renders four zeros with the status in positions three and four: '0035'.
            assertThat(loggedMessages()).containsSequence(ERROR_OPENING,
                    STATUS_LINE_PREFIX + "0035", ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("a download failure renders the 9x family as its expanded three-digit subcode")
        void aDownloadFailureRendersTheNineFamily() {
            Mockito.when(objectStorage.objectExists(INPUT_BUCKET, OBJECT_KEY)).thenReturn(Boolean.TRUE);
            final IllegalStateException refused = new IllegalStateException("the object store refused");
            Mockito.when(objectStorage.download(INPUT_BUCKET, OBJECT_KEY)).thenThrow(refused);
            final DailyTransactionReader reader = fixedWidthReader();

            final FatalProcessingException abend = catchThrowableOfType(FatalProcessingException.class,
                    () -> reader.open(new ExecutionContext()));

            assertThat(abend).isNotNull();
            assertThat(abend).hasCause(refused);
            // A first byte of '9' takes the other arm of 9910-DISPLAY-IO-STATUS: the byte is copied through
            // and the second is expanded from binary into three digits, which for '0' (48) is '048'.
            assertThat(loggedMessages()).contains(STATUS_LINE_PREFIX + "9048");
        }

        @Test
        @DisplayName("every rendered status is exactly four characters after the fixed prefix")
        void everyRenderedStatusIsFourCharacters() {
            Mockito.when(objectStorage.objectExists(INPUT_BUCKET, OBJECT_KEY)).thenReturn(Boolean.FALSE);
            final DailyTransactionReader reader = fixedWidthReader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()));

            final Set<String> statusLines = new LinkedHashSet<>(loggedMessages().stream()
                    .filter(line -> line.startsWith(STATUS_LINE_PREFIX))
                    .toList());
            assertThat(statusLines).isNotEmpty().allSatisfy(line ->
                    assertThat(line.substring(STATUS_LINE_PREFIX.length()))
                            .as("IO-STATUS-04 is PIC X(4); a different width is a parity diff in a line the "
                                    + "end-to-end gate compares against the legacy baseline")
                            .hasSize(4));
        }

        @Test
        @DisplayName("the abend message names the logical file, the operation and the rendered status only")
        void theAbendMessageNamesNoRecord() {
            Mockito.when(objectStorage.objectExists(INPUT_BUCKET, OBJECT_KEY)).thenReturn(Boolean.FALSE);
            final DailyTransactionReader reader = fixedWidthReader();

            final FatalProcessingException abend = catchThrowableOfType(FatalProcessingException.class,
                    () -> reader.open(new ExecutionContext()));

            assertThat(abend).isNotNull();
            assertThat(abend.getAbendMessage()).contains("DALYTRAN").contains("OPEN")
                    .contains(STATUS_LINE_PREFIX + "0035");
            // No card number: it is a sixteen-character PAN and Rule 1 clause D forbids it in a log or a
            // message. The record never reaches the abend payload at all.
            assertThat(abend.getAbendMessage()).doesNotContain("4859452612877065");
        }
    }

    /** The restart cursor written to and restored from the execution context. */
    @Nested
    @DisplayName("5. the restart cursor")
    final class RestartCursor {

        @Test
        @DisplayName("update() publishes the record count and the last identifier under stable keys")
        void updatePublishesTheCursor() {
            stubObject(List.of(imageWithAmount("0000005047G"), imageWithAmount("0000000678H")));
            final DailyTransactionReader reader = fixedWidthReader();
            final ExecutionContext context = new ExecutionContext();
            reader.open(context);

            drain(reader);
            reader.update(context);
            reader.close();

            assertThat(context.getLong("DailyTransactionReader.recordsRead")).isEqualTo(2L);
            assertThat(context.getString("DailyTransactionReader.lastTransactionId"))
                    .isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("open() with a checkpoint resumes after the rows already emitted")
        void openResumesAfterTheCheckpoint() {
            stubRelation(List.of(
                    stagedRow(1L, "0000000000000001"),
                    stagedRow(2L, "0000000000000002"),
                    stagedRow(3L, "0000000000000003"),
                    stagedRow(4L, "0000000000000004")));
            final ExecutionContext context = new ExecutionContext();
            context.putLong("DailyTransactionReader.recordsRead", 2L);
            context.putString("DailyTransactionReader.lastTransactionId", "0000000000000002");
            final DailyTransactionReader reader = repositoryReader(2);

            reader.open(context);
            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            // Two rows were already posted, so a restart must neither re-post them nor skip past the third.
            assertThat(produced).extracting(DailyTransaction::getTransactionId)
                    .containsExactly("0000000000000003", "0000000000000004");
        }

        @Test
        @DisplayName("update() tolerates a null context rather than failing the step")
        void updateToleratesANullContext() {
            stubRelation(List.of());
            final DailyTransactionReader reader = repositoryReader(100);
            reader.open(new ExecutionContext());

            reader.update(null);
            reader.close();

            assertThat(reader.getRecordsRead()).isZero();
        }

        @Test
        @DisplayName("a reused instance starts cold, so no previous position survives an open()")
        void aReusedInstanceStartsCold() {
            stubObject(List.of(imageWithAmount("0000005047G"), imageWithAmount("0000000678H")));
            final DailyTransactionReader reader = fixedWidthReader();

            reader.open(new ExecutionContext());
            assertThat(drain(reader)).hasSize(2);
            reader.close();

            reader.open(new ExecutionContext());
            final List<DailyTransaction> second = drain(reader);
            reader.close();

            assertThat(second).as("every cursor field is reset first, so the second run reads from the top")
                    .hasSize(2);
            assertThat(reader.getRecordsRead()).isEqualTo(2L);
        }

        @Test
        @DisplayName("open() tolerates a null context, so the reader is drivable without a step")
        void openToleratesANullContext() {
            stubRelation(List.of());
            final DailyTransactionReader reader = repositoryReader(100);

            reader.open(null);

            assertThat(reader.read())
                    .as("a test or a diagnostic harness may drive the reader without a step, and refusing a "
                            + "null context would make that impossible for no gain. The null branch at "
                            + "DailyTransactionReader:992 is a handled case rather than a guarded "
                            + "assumption, and it is distinct from update(null): open() decides whether to "
                            + "restore a checkpoint, update() decides whether to write one, and exercising "
                            + "the second says nothing about the first")
                    .isNull();
            reader.close();
            assertThat(reader.getRecordsRead())
                    .as("and the cold start really happened: no checkpoint was restored from nowhere")
                    .isZero();
        }

        @Test
        @DisplayName("a failed open() leaves the reader closed, so a later read reports the wiring error")
        void aFailedOpenLeavesTheReaderClosed() {
            Mockito.when(objectStorage.objectExists(INPUT_BUCKET, OBJECT_KEY)).thenReturn(Boolean.FALSE);
            final DailyTransactionReader reader = fixedWidthReader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("an absent object is FILE STATUS '35' and abends, which is the precondition for "
                            + "this test rather than its subject")
                    .isThrownBy(() -> reader.open(new ExecutionContext()));

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("""
                            The open failed, so the reader must still be CLOSED - and a read against a \
                            closed reader reports the wiring error rather than end of file. Answering null \
                            here would be the damaging outcome: a step whose open() abended would look like \
                            a step whose input was legitimately empty, and the run would report success \
                            having posted nothing. The state is asserted through the observable read \
                            because fileOpen is private, which is the correct level to assert it at.""")
                    .isThrownBy(reader::read)
                    .withMessageContaining("read() called before open(ExecutionContext)");
            assertThat(reader.getRecordsRead())
                    .as("and nothing was counted as read")
                    .isZero();
        }
    }

    /** The whole 300-row fixture, decoded end to end. */
    @Nested
    @DisplayName("6. all 300 rows of app/data/ASCII/dailytran.txt")
    final class TheWholeFixture {

        @Test
        @DisplayName("every row decodes, and the type split is 250 of 01 to 50 of 03")
        void theWholeFixtureDecodesWithTheDocumentedSplit() {
            final List<String> rows = fixtureRows();
            stubObject(rows);
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            assertThat(rows).hasSize(300).allSatisfy(row -> assertThat(row).hasSize(RECORD_LENGTH));
            assertThat(produced).hasSize(300);
            assertThat(reader.getRecordsRead()).isEqualTo(300L);
            assertThat(produced.stream().filter(row -> "01".equals(row.getTypeCode())).count())
                    .isEqualTo(250L);
            assertThat(produced.stream().filter(row -> "03".equals(row.getTypeCode())).count())
                    .isEqualTo(50L);
        }

        @Test
        @DisplayName("the source split matches the type split: 250 POS TERM to 50 OPERATOR, ten characters")
        void theSourceSplitMatchesTheTypeSplit() {
            stubObject(fixtureRows());
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            assertThat(produced).allSatisfy(row ->
                    assertThat(row.getTransactionSource()).hasSize(10));
            assertThat(produced.stream()
                    .filter(row -> "POS TERM  ".equals(row.getTransactionSource())).count())
                    .isEqualTo(250L);
            assertThat(produced.stream()
                    .filter(row -> "OPERATOR  ".equals(row.getTransactionSource())).count())
                    .isEqualTo(50L);
        }

        @Test
        @DisplayName("every one of the 300 processing timestamps is 26 spaces")
        void everyProcessingTimestampIsBlank() {
            stubObject(fixtureRows());
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            assertThat(produced).allSatisfy(row -> {
                assertThat(row.getProcTs()).isNotNull().isNotEmpty().hasSize(26).isBlank();
                assertThat(row.getOrigTs()).hasSize(26).isNotBlank();
            });
        }

        @Test
        @DisplayName("the fixture carries genuinely negative amounts, and they arrive negative")
        void theFixtureCarriesNegativeAmounts() {
            final List<String> rows = fixtureRows();
            stubObject(rows);
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            final long negatives = produced.stream()
                    .filter(row -> row.getAmount().signum() < 0)
                    .count();
            final Set<Character> overpunches = new LinkedHashSet<>();
            rows.forEach(row -> overpunches.add(Character.valueOf(row.charAt(142))));

            // Both a positive and a negative overpunch are present, which is what makes this fixture
            // exercise the cycle-debit branch of app/cbl/CBTRN02C.cbl:L547-L552 rather than only the credit
            // branch. The AAP forbids normalising the file for exactly this reason.
            assertThat(overpunches).contains(Character.valueOf('{'), Character.valueOf('}'));
            assertThat(negatives).isPositive();
            assertThat(produced).allSatisfy(row -> assertThat(row.getAmount().scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("the first three rows are the three worked decodes, in fixture order")
        void theFirstThreeRowsAreTheWorkedDecodes() {
            stubObject(fixtureRows());
            final DailyTransactionReader reader = fixedWidthReader();
            reader.open(new ExecutionContext());

            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            // The three decodes the reader's own documentation names are rows 1, 2 and 3 of the fixture, so
            // the worked examples above are not synthetic: they are this file.
            assertThat(produced.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(produced.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(produced.get(2).getAmount()).isEqualByComparingTo(new BigDecimal("67.88"));
        }

        @Test
        @DisplayName("the reader issues exactly one download and one existence probe per open")
        void theReaderOpensTheObjectOnce() {
            stubObject(fixtureRows());
            final DailyTransactionReader reader = fixedWidthReader();
            final AtomicInteger reads = new AtomicInteger();

            reader.open(new ExecutionContext());
            drain(reader).forEach(row -> reads.incrementAndGet());
            reader.close();

            assertThat(reads).hasValue(300);
            Mockito.verify(objectStorage, Mockito.times(1)).objectExists(INPUT_BUCKET, OBJECT_KEY);
            Mockito.verify(objectStorage, Mockito.times(1)).download(INPUT_BUCKET, OBJECT_KEY);
            // The relation is never touched on this path, which is what makes the two branches independent.
            Mockito.verifyNoInteractions(repository);
        }

        /**
         * Loads the 300 fixture rows through the shared loader, unmodified.
         *
         * @return the rows, each exactly {@value #RECORD_LENGTH} characters
         */
        private static List<String> fixtureRows() {
            final FixtureLoader.FixtureData data =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            if (data.recordWidth() != RECORD_LENGTH) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "dailytran.txt declares record width %d; app/cpy/CVTRA06Y.cpy declares %d",
                        Integer.valueOf(data.recordWidth()), Integer.valueOf(RECORD_LENGTH)));
            }
            return data.records();
        }
    }

    /** Construction guards, so a misconfigured bean fails at startup rather than mid-step. */
    @Nested
    @DisplayName("7. construction guards")
    final class ConstructionGuards {

        @Test
        @DisplayName("every collaborator is guarded, and the message names the one that was absent")
        void everyCollaboratorIsGuarded() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DailyTransactionReader(null, objectStorage,
                            new FileStatusMapper(), SOURCE_REPOSITORY, 100, "", OBJECT_KEY, SIGNING_KEY))
                    .withMessageContaining("dailyTransactionRepository");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DailyTransactionReader(repository, null,
                            new FileStatusMapper(), SOURCE_REPOSITORY, 100, "", OBJECT_KEY, SIGNING_KEY))
                    .withMessageContaining("objectStorage");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DailyTransactionReader(repository, objectStorage, null,
                            SOURCE_REPOSITORY, 100, "", OBJECT_KEY, SIGNING_KEY))
                    .withMessageContaining("fileStatusMapper");
        }

        @ParameterizedTest(name = "pageSize={0}")
        @ValueSource(ints = { 0, -1, Integer.MIN_VALUE })
        @DisplayName("a non-positive page size is refused, because a fetch of zero rows never terminates")
        void aNonPositivePageSizeIsRefused(final int pageSize) {
            assertThatIllegalArgumentException().isThrownBy(() -> new DailyTransactionReader(
                    repository, objectStorage, new FileStatusMapper(), SOURCE_REPOSITORY, pageSize, "",
                    OBJECT_KEY, SIGNING_KEY));
        }

        @Test
        @DisplayName("a blank object key is refused on the path that needs one")
        void aBlankObjectKeyIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new DailyTransactionReader(
                    repository, objectStorage, new FileStatusMapper(), SOURCE_FIXED_WIDTH, 100,
                    INPUT_BUCKET, "   ", SIGNING_KEY));
        }

        @Test
        @DisplayName("the reader is an ItemStreamReader, so the framework owns the lifecycle")
        void theReaderIsAnItemStreamReader() {
            assertThat(org.springframework.batch.item.ItemStreamReader.class)
                    .as("the paragraph loop of app/cbl/CBTRN02C.cbl:L202 becomes the framework calling "
                            + "read() until it answers null, so open, update and close must be published")
                    .isAssignableFrom(DailyTransactionReader.class);
        }
    }

    /**
     * Guards the one assumption the object-path stubs rest on: the reader reads characters, not bytes.
     *
     * <p>Kept as its own test rather than folded into a group, because it is a statement about the stub
     * harness above as much as about the reader.
     */
    @Test
    @DisplayName("the object body is decoded as single-byte characters, so offsets are byte offsets")
    void theObjectBodyIsDecodedAsSingleByteCharacters() {
        // A high-range single-byte character inside the description. Under ISO-8859-1 it is one character,
        // so byte 143 remains the overpunch; under a multi-byte charset every later offset would shift.
        final String withHighRange = image("0000000000000004", "01", "0001", "POS TERM",
                "CAF\u00c9 PURCHASE", "0000005047G", "800000000", "MERCHANT", "CITY", "12345",
                "4859452612877065", ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP);
        stubObject(List.of(withHighRange));
        final DailyTransactionReader reader = fixedWidthReader();
        reader.open(new ExecutionContext());

        final List<DailyTransaction> produced = drain(reader);
        reader.close();

        assertThat(produced).hasSize(1);
        assertThat(produced.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(produced.get(0).getDescription()).startsWith("CAF\u00c9 PURCHASE");
    }

    /**
     * Guards the harness itself: the stub must never hand the reader a stream it has already drained.
     *
     * @throws IOException never; declared because {@link InputStream} operations are checked
     */
    @Test
    @DisplayName("the stub serves a fresh stream per download, so a second open() is not a short read")
    void theStubServesAFreshStreamPerDownload() throws IOException {
        stubObject(List.of(imageWithAmount("0000005047G")));

        final S3Resource downloaded = objectStorage.download(INPUT_BUCKET, OBJECT_KEY);
        final InputStream first = downloaded.getInputStream();
        final InputStream second = objectStorage.download(INPUT_BUCKET, OBJECT_KEY).getInputStream();

        // The envelope the helper establishes is asserted here too, and not only the stream. The reader's
        // open() reads the declared length and the three metadata entries of finding M-11 before it reads a
        // byte of body, so a harness that stubbed them and never exercised them would be describing a
        // precondition it had not proved - which strict stubs reports as an unnecessary stubbing rather than
        // letting it pass quietly.
        assertThat(downloaded.contentLength())
                .as("the declared length the envelope is computed over")
                .isEqualTo(RECORD_LENGTH);
        assertThat(downloaded.metadata())
                .as("and the three entries the envelope carries, so the helper is fully exercised")
                .containsKeys(DailyTransactionReader.InputObjectEnvelope.METADATA_WRITER,
                        DailyTransactionReader.InputObjectEnvelope.METADATA_CONTENT_SHA256,
                        DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE);

        assertThat(objectStorage.objectExists(INPUT_BUCKET, OBJECT_KEY))
                .as("the helper establishes the whole precondition the reader's open() checks - the object "
                        + "exists AND downloads - so both halves are asserted here rather than one. Strict "
                        + "stubs is what makes that omission visible: a half-exercised helper reports as an "
                        + "unnecessary stubbing rather than passing quietly")
                .isTrue();
        assertThat(first).isNotSameAs(second);
        assertThat(first.readAllBytes())
                .as("one image, undelimited: the stubbed body is exactly one record and carries no separator")
                .hasSize(RECORD_LENGTH);
        assertThat(second.readAllBytes())
                .as("a shared stream would make the reusable-instance test above pass for the wrong reason")
                .hasSize(RECORD_LENGTH);
        first.close();
        second.close();
    }

    /**
     * Stubs an input object with an arbitrary envelope, so a refusal path can be exercised precisely.
     *
     * @param body the object content, byte for byte
     * @param metadata the user metadata to serve, exactly as given
     * @param declaredLength the content length the store reports, which need not match {@code body}
     * @return the stubbed object, so a test can assert what was and was not asked of it
     */
    private S3Resource stubObjectWithEnvelope(final String body, final java.util.Map<String, String> metadata,
            final long declaredLength) {

        final S3Resource resource = Mockito.mock(S3Resource.class);
        Mockito.lenient().when(resource.contentLength()).thenReturn(Long.valueOf(declaredLength));
        Mockito.lenient().when(resource.metadata()).thenReturn(metadata);
        Mockito.when(objectStorage.objectExists(INPUT_BUCKET, OBJECT_KEY)).thenReturn(Boolean.TRUE);
        Mockito.when(objectStorage.download(INPUT_BUCKET, OBJECT_KEY)).thenReturn(resource);
        try {
            Mockito.lenient().when(resource.getInputStream()).thenAnswer(invocation ->
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (final IOException impossible) {
            throw new IllegalStateException("stubbing getInputStream cannot perform I/O", impossible);
        }
        return resource;
    }

    /**
     * Builds a complete, valid envelope for a body, then applies one substitution to it.
     *
     * @param body the object content the envelope describes
     * @param member the metadata member to replace, or {@code null} to leave the envelope intact
     * @param replacement the value to put in its place
     * @return the metadata map to serve
     */
    private static java.util.Map<String, String> envelopeFor(final String body, final String member,
            final String replacement) {

        final byte[] content = body.getBytes(StandardCharsets.ISO_8859_1);
        final String digest = sha256HexOf(content);
        final java.util.Map<String, String> envelope = new java.util.LinkedHashMap<>();
        envelope.put(DailyTransactionReader.InputObjectEnvelope.METADATA_WRITER, WRITER);
        envelope.put(DailyTransactionReader.InputObjectEnvelope.METADATA_CONTENT_SHA256, digest);
        envelope.put(DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE,
                DailyTransactionReader.InputObjectEnvelope.sign(INPUT_BUCKET, OBJECT_KEY, content.length,
                        WRITER, digest, SIGNING_KEY));
        if (member != null) {
            envelope.put(member, replacement);
        }
        return java.util.Map.copyOf(envelope);
    }

    /**
     * Finding M-11: the external input object is authenticated before a record is parsed.
     *
     * <p>Every test here asserts <em>two</em> things, because either alone would be satisfiable by a broken
     * implementation: that the refusal happened, and that <b>no content was consumed</b>. A control that
     * refuses only after the records have been read and posted is not a control.
     */
    @Nested
    @DisplayName("8. M-11: the input object is refused unless it is vouched for")
    class InputObjectAuthenticity {

        /**
         * Creates the authenticity group.
         *
         * <p>Declared explicitly because the enclosing class declares its own constructors.
         */
        InputObjectAuthenticity() {
            // Intentionally empty; each test stubs the envelope it needs.
        }

        @Test
        @DisplayName("a validly vouched-for object is read, and the log names the writer, never the key")
        void aVouchedForObjectIsRead() {
            stubObject(List.of(imageWithAmount("0000005047G")));
            final DailyTransactionReader reader = fixedWidthReader();

            reader.open(new ExecutionContext());
            final List<DailyTransaction> produced = drain(reader);
            reader.close();

            assertThat(produced).hasSize(1);
            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("authenticity verified")
                            && message.contains(WRITER)
                            && !message.contains(OBJECT_KEY));
        }

        @Test
        @DisplayName("an object carrying no envelope at all is refused, and nothing is read")
        void anUnsignedObjectIsRefused() {
            final S3Resource resource = stubObjectWithEnvelope(imageWithAmount("0000005047G") + "\n",
                    java.util.Map.of(), RECORD_LENGTH + 1L);
            final DailyTransactionReader reader = fixedWidthReader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()))
                    .satisfies(refusal -> assertThat(refusal.getAbendReason())
                            .isEqualTo("DALYTRAN INPUT NOT AUTHENTIC"));

            verifyNothingWasRead(resource);
        }

        @Test
        @DisplayName("a forged code is refused before the content is even read")
        void aForgedCodeIsRefused() {
            final String body = imageWithAmount("0000005047G") + "\n";
            final S3Resource resource = stubObjectWithEnvelope(body,
                    envelopeFor(body, DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE,
                            "v1=" + "ab".repeat(32)),
                    body.length());
            final DailyTransactionReader reader = fixedWidthReader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()));

            verifyNothingWasRead(resource);
        }

        @ParameterizedTest
        @CsvSource({
            "v2=00,a version this implementation does not produce",
            "00,no version prefix at all",
            "v1=,an empty code",
            "v1=ABCDEF,uppercase hexadecimal",
            "v1=abc,an odd number of digits",
        })
        @DisplayName("a malformed code is refused, whatever way it is malformed")
        void aMalformedCodeIsRefused(final String presented, final String description) {
            final String body = imageWithAmount("0000005047G") + "\n";
            final S3Resource resource = stubObjectWithEnvelope(body,
                    envelopeFor(body, DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE,
                            presented),
                    body.length());
            final DailyTransactionReader reader = fixedWidthReader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as(description)
                    .isThrownBy(() -> reader.open(new ExecutionContext()));

            verifyNothingWasRead(resource);
        }

        @Test
        @DisplayName("a valid envelope copied onto a different body of the same length is refused")
        void aBodySubstitutionUnderAValidEnvelopeIsRefused() {
            // The failure mode a signature check ALONE would accept: the manifest is genuinely signed, the
            // declared length still matches, and only the bytes are somebody else's. This is why the digest
            // pass exists.
            stubObjectBody(imageWithAmount("0000005047G") + "\n", true, false);
            final DailyTransactionReader reader = fixedWidthReader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()))
                    .satisfies(refusal -> assertThat(refusal.getAbendReason())
                            .isEqualTo("DALYTRAN INPUT NOT AUTHENTIC"));

            assertThat(drainIsUnreachable(reader)).isTrue();
        }

        @Test
        @DisplayName("a declared length that disagrees with the content is refused")
        void aLengthDisagreementIsRefused() {
            final String body = imageWithAmount("0000005047G") + "\n";
            // Signed for a length the body does not have, so the manifest verifies against its own claim and
            // the measured pass is what catches the truncation.
            final byte[] content = body.getBytes(StandardCharsets.ISO_8859_1);
            final String digest = sha256HexOf(content);
            final long lie = content.length + 1L;
            stubObjectWithEnvelope(body, java.util.Map.of(
                    DailyTransactionReader.InputObjectEnvelope.METADATA_WRITER, WRITER,
                    DailyTransactionReader.InputObjectEnvelope.METADATA_CONTENT_SHA256, digest,
                    DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE,
                    DailyTransactionReader.InputObjectEnvelope.sign(INPUT_BUCKET, OBJECT_KEY, lie, WRITER,
                            digest, SIGNING_KEY)), lie);
            final DailyTransactionReader reader = fixedWidthReader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "carddemo\nforged log line",
            "",
            "0123456789012345678901234567890123456789012345678901234567890123456789",
        })
        @DisplayName("an unusable writer identity is refused, so it can never reach a log line")
        void anUnusableWriterIsRefused(final String writer) {
            final String body = imageWithAmount("0000005047G") + "\n";
            final byte[] content = body.getBytes(StandardCharsets.ISO_8859_1);
            final String digest = sha256HexOf(content);
            final java.util.Map<String, String> envelope = new java.util.LinkedHashMap<>();
            envelope.put(DailyTransactionReader.InputObjectEnvelope.METADATA_WRITER, writer);
            envelope.put(DailyTransactionReader.InputObjectEnvelope.METADATA_CONTENT_SHA256, digest);
            envelope.put(DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE,
                    DailyTransactionReader.InputObjectEnvelope.sign(INPUT_BUCKET, OBJECT_KEY, content.length,
                            writer, digest, SIGNING_KEY));
            final S3Resource resource = stubObjectWithEnvelope(body, envelope, content.length);
            final DailyTransactionReader reader = fixedWidthReader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()));

            verifyNothingWasRead(resource);
        }

        @Test
        @DisplayName("the refusal names the dataset and the bucket, and never the key, writer or digest")
        void theRefusalDisclosesNothing() {
            final String body = imageWithAmount("0000005047G") + "\n";
            stubObjectWithEnvelope(body, java.util.Map.of(), body.length());
            final DailyTransactionReader reader = fixedWidthReader();

            final FatalProcessingException refusal = catchThrowableOfType(FatalProcessingException.class,
                    () -> reader.open(new ExecutionContext()));

            assertThat(refusal).isNotNull();
            assertThat(refusal.getAbendCode())
                    .as("the abend payload of app/cpy/CSMSG02Y.cpy, unchanged for this refusal")
                    .isEqualTo("999");
            assertThat(refusal.getMessage())
                    .contains("DALYTRAN")
                    .contains(INPUT_BUCKET)
                    .doesNotContain(OBJECT_KEY)
                    .doesNotContain(WRITER);
        }

        @Test
        @DisplayName("an object validly signed for another location does not verify here")
        void aSignatureIsBoundToItsBucketAndKey() {
            // Binding bucket and key into the manifest is what stops a stale, genuinely signed generation
            // being copied over today's input and accepted.
            final String body = imageWithAmount("0000005047G") + "\n";
            final byte[] content = body.getBytes(StandardCharsets.ISO_8859_1);
            final String digest = sha256HexOf(content);
            stubObjectWithEnvelope(body, java.util.Map.of(
                    DailyTransactionReader.InputObjectEnvelope.METADATA_WRITER, WRITER,
                    DailyTransactionReader.InputObjectEnvelope.METADATA_CONTENT_SHA256, digest,
                    DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE,
                    DailyTransactionReader.InputObjectEnvelope.sign(INPUT_BUCKET,
                            "dalytran/2000-01-01/dailytran.txt", content.length, WRITER, digest,
                            SIGNING_KEY)), content.length);
            final DailyTransactionReader reader = fixedWidthReader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()));
        }

        @Test
        @DisplayName("the code matches the documented producer recipe, computed independently here")
        void theCodeMatchesTheDocumentedRecipe() throws Exception {
            // Computed with javax.crypto directly rather than through the contract, so this test pins the
            // RECIPE - the derivation label, the two-step key derivation and the six-line canonical manifest -
            // instead of merely agreeing with the implementation about itself. A producer outside this
            // repository has only the documentation, so the documentation is what has to be true.
            final String body = imageWithAmount("0000005047G") + "\n";
            final byte[] content = body.getBytes(StandardCharsets.ISO_8859_1);
            final String digest = sha256HexOf(content);
            final String label = DailyTransactionReader.InputObjectEnvelope.KEY_DERIVATION_LABEL;
            final String manifest = label + '\n' + INPUT_BUCKET + '\n' + OBJECT_KEY + '\n'
                    + content.length + '\n' + WRITER + '\n' + digest;

            final javax.crypto.Mac derivation = javax.crypto.Mac.getInstance("HmacSHA256");
            derivation.init(new javax.crypto.spec.SecretKeySpec(
                    SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            final byte[] purposeKey = derivation.doFinal(label.getBytes(StandardCharsets.UTF_8));
            final javax.crypto.Mac code = javax.crypto.Mac.getInstance("HmacSHA256");
            code.init(new javax.crypto.spec.SecretKeySpec(purposeKey, "HmacSHA256"));
            final String expected = "v1=" + DailyTransactionReader.InputObjectEnvelope.hexadecimal(
                    code.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));

            assertThat(DailyTransactionReader.InputObjectEnvelope.sign(INPUT_BUCKET, OBJECT_KEY,
                    content.length, WRITER, digest, SIGNING_KEY))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("the object path cannot be selected without the key the envelope is derived from")
        void theObjectPathRequiresTheSigningKey() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DailyTransactionReader(repository, objectStorage,
                            new FileStatusMapper(), SOURCE_FIXED_WIDTH, 100, INPUT_BUCKET, OBJECT_KEY, "  "))
                    .withMessageContaining("carddemo.security.jwt.signing-key")
                    .withMessageContaining("no unverified mode");
        }

        @Test
        @DisplayName("the repository path needs no key, because it reads nothing external")
        void theRepositoryPathNeedsNoKey() {
            assertThat(new DailyTransactionReader(repository, objectStorage, new FileStatusMapper(),
                    SOURCE_REPOSITORY, 100, "", OBJECT_KEY, ""))
                    .as("both branches must stay reachable; requiring a key here would be a false prerequisite")
                    .isNotNull();
        }

        /**
         * Asserts that a refusal happened before any content was consumed.
         *
         * @param resource the stubbed object the reader was pointed at
         */
        private void verifyNothingWasRead(final S3Resource resource) {
            try {
                Mockito.verify(resource, Mockito.never()).getInputStream();
            } catch (final IOException impossible) {
                throw new IllegalStateException("verifying a stub cannot perform I/O", impossible);
            }
        }

        /**
         * Reports whether the reader refuses to hand out a record after a failed open.
         *
         * @param reader the reader whose open was refused
         * @return true when reading is refused rather than silently returning records
         */
        private boolean drainIsUnreachable(final DailyTransactionReader reader) {
            try {
                reader.read();
                return false;
            } catch (final IllegalStateException refused) {
                return true;
            }
        }
    }
}
