/*
 ******************************************************************
 * Program     : TransactionBackupGenerationIntegrationTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 AWS integration test (Failsafe tier)
 * Function    : Verifies against a real emulated object store that
 *               TransactionBackupReader opens the generation a prior step
 *               wrote - the exact key handed forward - rather than whichever
 *               key happens to be lexicographically greatest, and that a
 *               350-character image survives the object-store round trip
 *               byte for byte with a blank TRAN-PROC-TS still 26 spaces.
 * Source      : app/proc/TRANREPT.prc:21-31  @ 7756d89 (STEP01R writes
 *               AWS.M2.CARDDEMO.TRANSACT.BKUP(+1))
 * Source      : app/proc/TRANREPT.prc:36-37  @ 7756d89 (STEP05R reads (0) as
 *               SORTIN, which the catalogue guarantees is that same dataset)
 * Source      : app/jcl/DEFGDGB.jcl          @ 7756d89 (the TRANSACT.BKUP
 *               generation data group base)
 * Source      : app/cpy/CVTRA05Y.cpy:4-18    @ 7756d89 (the 350-byte
 *               transaction offset map)
 * Source      : app/cbl/CBTRN03C.cbl         @ 7756d89 (the report program the
 *               generation feeds)
 * Note        : No COBOL analogue for the resolution order itself - object
 *               storage has no catalogue, so the guarantee the mainframe got
 *               for free has to be reconstructed and therefore proved.
 ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 ******************************************************************
 */
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cardemo.batch.readers.TransactionBackupReader;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.item.ExecutionContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Integration tests for {@code com.cardemo.batch.readers.TransactionBackupReader} against a real emulated
 * object store, at traceability anchor commit {@code 7756d89}.
 *
 * <h2>1. What it does</h2>
 *
 * <p>A review found that <strong>no test imported, instantiated or class-referenced this reader</strong>, and
 * that the evidence it needed included a cloud-backed tier. Its unit suite,
 * {@code src/test/java/com/cardemo/unit/batch/TransactionBackupReaderTest.java}, pins the decoder and the
 * resolution precedence against a mocked store. This class proves the same precedence against a real one, and
 * that is not a duplicate: a mocked {@code listObjects} returns whatever the test told it to, whereas here the
 * listing is produced by the store itself from objects that were actually written, in the order the store
 * chooses to report them.
 *
 * <p>Three properties are asserted:
 *
 * <ul>
 *   <li><strong>The key one step writes is the key the next step opens.</strong>
 *       {@code app/proc/TRANREPT.prc:L21-L31} writes {@code TRANSACT.BKUP(+1)} at {@code STEP01R} and
 *       {@code :L36-L37} reads {@code (0)} as {@code SORTIN} at {@code STEP05R}; the mainframe catalogue makes
 *       those the same dataset by definition. Object storage has no catalogue, so the tests below write a
 *       generation whose key sorts <em>before</em> a retained older one and prove the reader still opens the
 *       one that was handed forward.
 *   <li><strong>A 350-character image round-trips byte for byte</strong> through a real put and get, with a
 *       blank {@code TRAN-PROC-TS} still 26 spaces. The sort specification at {@code STEP05R} addresses this
 *       record by byte offset, so a single character of drift moves every field after it.
 *   <li><strong>An absent generation fails rather than reporting on no data</strong>, because an empty report
 *       looks like a result.
 * </ul>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>Bound to the Failsafe tier by living under {@code src/test/java/com/cardemo/integration}. It needs a
 * container runtime, because the superclass starts the object-store emulator through Testcontainers.
 *
 * <pre>
 * ./mvnw -B -ntp -Ddependency-check.skip=true -Dit.test=TransactionBackupGenerationIntegrationTest verify
 * ./mvnw -B -ntp -Ddependency-check.skip=true clean verify
 * </pre>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>Every bucket is created through {@code createVersionedBucket(scopedResourceName(...))}, so its name is
 * derived from this class and cannot collide with another suite's, and the superclass removes it and all of
 * its versions afterwards. No container, no static field and no property source is declared here. The reader
 * is constructed directly rather than autowired, because it is step-scoped and there is no step; the real
 * {@code S3Template} is injected as its object store and a real {@link FileStatusMapper} as its status
 * translator. The transaction relation is mocked, because these tests exercise the object path only and the
 * relational path has its own coverage in the repository tier.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Blocker.</strong> A failure of the hand-off test means the report step can read a different
 *       generation from the one the backup step wrote, so the report covers the wrong period and nothing in
 *       the output says so.
 *   <li><strong>High.</strong> A failure of the round-trip test means the object boundary is not
 *       byte-transparent - a charset, a line ending or a trailing-space trim has crept in - and the sort
 *       offsets no longer address the fields they name.
 *   <li><strong>High.</strong> A failure of the absent-generation test means an empty report would be
 *       published as a successful one.
 *   <li><strong>Blocked, not failed.</strong> If the container runtime is unavailable the whole class is
 *       blocked at superclass initialisation. That is a missing prerequisite rather than a defect, and the
 *       unit suite still covers the decoder and the precedence.
 * </ul>
 */
@DisplayName("TransactionBackupReader against a real object store - the generation hand-off")
class TransactionBackupGenerationIntegrationTest extends AbstractAwsIntegrationTest {

    /** The generation prefix, matching the configured {@code gdg/transact-bkup}. */
    private static final String GENERATION_PREFIX = "gdg/transact-bkup";

    /** The {@code object-storage} input selector. */
    private static final String SOURCE_OBJECT_STORAGE = "object-storage";

    /** The job-execution-context entry a prior step writes the generation key to. */
    private static final String CONTEXT_KEY_GENERATION = "carddemo.gdg.transact-bkup.objectKey";

    /**
     * The generation {@code STEP01R} just wrote, named so that it sorts <em>before</em> the retained one.
     *
     * <p>Deliberate, and the whole point: a promoted key that also happened to be the greatest would let a
     * listing-only implementation pass these tests.
     */
    private static final String WRITTEN_GENERATION_KEY =
            GENERATION_PREFIX + "/20220610-192753/G0001V00";

    /** A retained older generation whose key sorts lexicographically <em>after</em> the one just written. */
    private static final String RETAINED_GENERATION_KEY =
            GENERATION_PREFIX + "/20991231-235959/G0009V00";

    /** A blank processing timestamp: 26 spaces, the state an unposted backed-up transaction carries. */
    private static final String BLANK_TIMESTAMP = " ".repeat(26);

    /** A populated originating timestamp in the corpus's own format. */
    private static final String ORIGINATING_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * Builds a reader over the supplied bucket, with the supplied promoted key.
     *
     * @param bucket the versioned generation bucket
     * @param promotedKey the key a prior step promoted, or {@code null} when none was
     * @return the reader, never {@code null}
     */
    private TransactionBackupReader readerOver(final String bucket, final String promotedKey) {
        return new TransactionBackupReader(Mockito.mock(TransactionRepository.class), s3Template(),
                s3Client(), new FileStatusMapper(), SOURCE_OBJECT_STORAGE, 100, bucket, GENERATION_PREFIX,
                promotedKey);
    }

    /**
     * Writes one generation object holding the supplied 350-character images, newline separated.
     *
     * @param bucket the bucket to write into
     * @param key the generation object key
     * @param images the record images
     */
    private void writeGeneration(final String bucket, final String key, final List<String> images) {
        final StringBuilder body = new StringBuilder(images.size() * (TRANSACTION_RECORD_LENGTH + 1));
        images.forEach(image -> {
            if (image.length() != TRANSACTION_RECORD_LENGTH) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "image is %d characters; app/cpy/CVTRA05Y.cpy declares %d",
                        Integer.valueOf(image.length()), Integer.valueOf(TRANSACTION_RECORD_LENGTH)));
            }
            body.append(image).append('\n');
        });
        s3Client().putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(body.toString().getBytes(StandardCharsets.ISO_8859_1)));
    }

    /**
     * Composes one 350-character image from the thirteen fields {@code app/cpy/CVTRA05Y.cpy} declares.
     *
     * @param transactionId {@code TRAN-ID}, 16
     * @param typeCode {@code TRAN-TYPE-CD}, 2
     * @param categoryCode {@code TRAN-CAT-CD}, 4
     * @param source {@code TRAN-SOURCE}, 10
     * @param description {@code TRAN-DESC}, 100
     * @param amountField {@code TRAN-AMT}, 11, trailing-sign overpunched
     * @param merchantId {@code TRAN-MERCHANT-ID}, 9
     * @param merchantName {@code TRAN-MERCHANT-NAME}, 50
     * @param merchantCity {@code TRAN-MERCHANT-CITY}, 50
     * @param merchantZip {@code TRAN-MERCHANT-ZIP}, 10
     * @param cardNumber {@code TRAN-CARD-NUM}, 16
     * @param origTs {@code TRAN-ORIG-TS}, 26
     * @param procTs {@code TRAN-PROC-TS}, 26
     * @return the image, exactly 350 characters
     */
    private static String image(final String transactionId, final String typeCode, final String categoryCode,
            final String source, final String description, final String amountField, final String merchantId,
            final String merchantName, final String merchantCity, final String merchantZip,
            final String cardNumber, final String origTs, final String procTs) {

        return pad(transactionId, 16) + pad(typeCode, 2) + pad(categoryCode, 4) + pad(source, 10)
                + pad(description, 100) + pad(amountField, 11) + pad(merchantId, 9) + pad(merchantName, 50)
                + pad(merchantCity, 50) + pad(merchantZip, 10) + pad(cardNumber, 16) + pad(origTs, 26)
                + pad(procTs, 26) + " ".repeat(20);
    }

    /**
     * Re-emits a decoded transaction as the image it was decoded from, so the round trip can be compared.
     *
     * @param decoded the transaction the reader produced
     * @param originalAmountField the overpunched field the image carried, a presentation of the amount rather
     *     than a property of it and therefore not recomputable
     * @return the re-emitted image, exactly 350 characters
     */
    private static String reEmit(final Transaction decoded, final String originalAmountField) {
        return image(decoded.getTransactionId(), decoded.getTypeCode(),
                String.format(Locale.ROOT, "%04d", decoded.getCategoryCode()),
                decoded.getTransactionSource(), decoded.getDescription(), originalAmountField,
                String.format(Locale.ROOT, "%09d", decoded.getMerchantId()), decoded.getMerchantName(),
                decoded.getMerchantCity(), decoded.getMerchantZip(), decoded.getCardNumber(),
                decoded.getOrigTs(), decoded.getProcTs());
    }

    /**
     * Right-pads to the declared width, and refuses anything wider.
     *
     * @param value the field value
     * @param width the declared width
     * @return the value at exactly {@code width} characters
     */
    private static String pad(final String value, final int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException("'" + value + "' is " + value.length()
                    + " characters but the copybook declares " + width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Drains a reader to exhaustion.
     *
     * @param reader the reader, already opened
     * @return every record it produced, in order
     */
    private static List<Transaction> drain(final TransactionBackupReader reader) {
        final List<Transaction> produced = new ArrayList<>();
        Transaction next = reader.read();
        while (next != null) {
            produced.add(next);
            next = reader.read();
        }
        return produced;
    }

    /** The step-to-step generation hand-off, proved against a real listing. */
    @Nested
    @DisplayName("1. the key STEP01R writes is the key STEP05R opens")
    final class GenerationHandOff {

        @Test
        @DisplayName("the written generation is opened even though a retained one sorts later")
        void theWrittenGenerationIsOpenedNotTheGreatestKey() {
            final String bucket = createVersionedBucket(scopedResourceName("handoff"));
            writeGeneration(bucket, RETAINED_GENERATION_KEY, List.of(
                    image("0000000000000099", "01", "0001", "POS TERM", "STALE GENERATION ROW",
                            "0000000001A", "800000000", "STALE", "STALE", "99999", "4859452612877099",
                            ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP)));
            writeGeneration(bucket, WRITTEN_GENERATION_KEY, List.of(
                    image("0000000000683580", "01", "0001", "POS TERM", "THE GENERATION JUST WRITTEN",
                            "0000005047G", "800000000", "MERCHANT", "CITY", "12345", "4859452612877065",
                            ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP)));

            // The premise, asserted rather than assumed: the store's own listing really does report the
            // retained key as the greatest, so a listing-only reader would open the wrong generation.
            assertThat(currentGenerationKey(bucket, GENERATION_PREFIX))
                    .contains(RETAINED_GENERATION_KEY);

            final TransactionBackupReader reader = readerOver(bucket, WRITTEN_GENERATION_KEY);
            reader.open(new ExecutionContext());
            final String resolvedWhileOpen = reader.getResolvedGenerationObjectKey();
            final List<Transaction> produced = drain(reader);
            reader.close();

            assertThat(resolvedWhileOpen).isEqualTo(WRITTEN_GENERATION_KEY);
            assertThat(produced).hasSize(1);
            assertThat(produced.get(0).getTransactionId()).isEqualTo("0000000000683580");
            assertThat(produced.get(0).getDescription()).startsWith("THE GENERATION JUST WRITTEN");
            assertThat(produced.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
        }

        @Test
        @DisplayName("the key a first reader resolves is the key a second reader inherits from the context")
        void theResolvedKeyIsInheritedThroughTheExecutionContext() {
            final String bucket = createVersionedBucket(scopedResourceName("inherit"));
            final String row = image("0000000000683580", "01", "0001", "POS TERM", "CARRIED FORWARD",
                    "0000005047G", "800000000", "MERCHANT", "CITY", "12345", "4859452612877065",
                    ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP);
            writeGeneration(bucket, RETAINED_GENERATION_KEY, List.of(row));
            writeGeneration(bucket, WRITTEN_GENERATION_KEY, List.of(row));
            final ExecutionContext shared = new ExecutionContext();

            final TransactionBackupReader first = readerOver(bucket, WRITTEN_GENERATION_KEY);
            first.open(shared);
            drain(first);
            first.update(shared);
            first.close();

            // A second reader with NOTHING promoted would fall back to the listing and open the retained
            // generation. It opens the written one instead, because the first reader published its choice.
            final TransactionBackupReader second = readerOver(bucket, null);
            second.open(shared);
            final String inherited = second.getResolvedGenerationObjectKey();
            second.close();

            assertThat(shared.getString(CONTEXT_KEY_GENERATION)).isEqualTo(WRITTEN_GENERATION_KEY);
            assertThat(inherited).isEqualTo(WRITTEN_GENERATION_KEY);
            assertThat(inherited).isNotEqualTo(RETAINED_GENERATION_KEY);
        }

        @Test
        @DisplayName("with nothing promoted the standalone (0) semantic resolves the greatest key")
        void withNothingPromotedTheGreatestKeyResolves() {
            final String bucket = createVersionedBucket(scopedResourceName("standalone"));
            writeGeneration(bucket, WRITTEN_GENERATION_KEY, List.of(
                    image("0000000000000001", "01", "0001", "POS TERM", "EARLIER", "0000005047G",
                            "800000000", "MERCHANT", "CITY", "12345", "4859452612877065",
                            ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP)));
            writeGeneration(bucket, RETAINED_GENERATION_KEY, List.of(
                    image("0000000000000002", "01", "0001", "POS TERM", "GREATEST", "0000000678H",
                            "800000000", "MERCHANT", "CITY", "12345", "4859452612877066",
                            ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP)));

            final TransactionBackupReader reader = readerOver(bucket, null);
            reader.open(new ExecutionContext());
            final String resolvedWhileOpen = reader.getResolvedGenerationObjectKey();
            final List<Transaction> produced = drain(reader);
            reader.close();

            // A real fallback, not dead code: a step run on its own has no prior step to inherit from, which
            // is what a relative (0) reference means with no (+1) in the same job.
            assertThat(resolvedWhileOpen).isEqualTo(RETAINED_GENERATION_KEY)
                    .isEqualTo(currentGenerationKey(bucket, GENERATION_PREFIX).orElseThrow());
            assertThat(produced.get(0).getDescription()).startsWith("GREATEST");
        }

        @Test
        @DisplayName("an empty generation base abends rather than reporting on no data")
        void anEmptyGenerationBaseAbends() {
            final String bucket = createVersionedBucket(scopedResourceName("empty"));
            final TransactionBackupReader reader = readerOver(bucket, null);

            final FatalProcessingException abend = catchThrowableOfType(FatalProcessingException.class,
                    () -> reader.open(new ExecutionContext()));

            assertThat(abend).isNotNull();
            assertThat(abend.getAbendCulprit()).isEqualTo("TRANREPT");
            assertThat(abend.getAbendCode())
                    .isEqualTo(Integer.toString(FatalProcessingException.BATCH_ABEND_CODE));
            assertThat(abend.getAbendMessage()).contains("AWS.M2.CARDDEMO.TRANSACT.BKUP");
            assertThat(currentGenerationKey(bucket, GENERATION_PREFIX)).isEmpty();
        }
    }

    /** The byte-exact round trip across the real object boundary. */
    @Nested
    @DisplayName("2. a 350-character image survives the object boundary unchanged")
    final class ObjectBoundaryRoundTrip {

        @Test
        @DisplayName("a fully populated image put and got back re-emits byte for byte")
        void aFullyPopulatedImageSurvivesByteForByte() {
            final String bucket = createVersionedBucket(scopedResourceName("roundtrip"));
            final String original = image("0000000000683580", "07", "0042", "POS TERM  ",
                    "PURCHASE OF FUEL AT A SERVICE STATION", "0000005047G", "800000000",
                    "STOKES-MUELLER AND DAUGHTERS", "NORTH LILYBERG", "36903-3350", "4859452612877065",
                    ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP);
            writeGeneration(bucket, WRITTEN_GENERATION_KEY, List.of(original));

            final TransactionBackupReader reader = readerOver(bucket, WRITTEN_GENERATION_KEY);
            reader.open(new ExecutionContext());
            final Transaction decoded = drain(reader).get(0);
            reader.close();

            assertThat(original).hasSize(TRANSACTION_RECORD_LENGTH);
            assertThat(reEmit(decoded, "0000005047G"))
                    .as("app/proc/TRANREPT.prc:STEP05R addresses this record by byte offset - card number at "
                            + "263 for 16, processing date at 305 for 10 - so a single character of drift "
                            + "anywhere ahead of a field moves that field")
                    .isEqualTo(original)
                    .hasSize(TRANSACTION_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a blank TRAN-PROC-TS crosses the boundary as 26 spaces, not as null or empty")
        void theBlankProcessingTimestampCrossesTheBoundary() {
            final String bucket = createVersionedBucket(scopedResourceName("procts"));
            writeGeneration(bucket, WRITTEN_GENERATION_KEY, List.of(
                    image("0000000000683580", "01", "0001", "POS TERM", "UNPOSTED", "0000009190}",
                            "800000000", "MERCHANT", "CITY", "12345", "4859452612877065",
                            ORIGINATING_TIMESTAMP, BLANK_TIMESTAMP)));

            final TransactionBackupReader reader = readerOver(bucket, WRITTEN_GENERATION_KEY);
            reader.open(new ExecutionContext());
            final Transaction decoded = drain(reader).get(0);
            reader.close();

            assertThat(decoded.getProcTs()).isNotNull().isNotEmpty().hasSize(26).isBlank()
                    .isEqualTo(BLANK_TIMESTAMP);
            assertThat(decoded.getOrigTs()).isEqualTo(ORIGINATING_TIMESTAMP).hasSize(26);
            // The negative amount crosses the boundary negative: the report totals depend on the sign, and
            // the cycle-debit accumulator of app/cbl/CBTRN02C.cbl legitimately holds negative values.
            assertThat(decoded.getAmount()).isNegative()
                    .isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(decoded.getAmount().scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("many images cross the boundary in order, each one byte-exact")
        void manyImagesCrossTheBoundaryInOrder() {
            final String bucket = createVersionedBucket(scopedResourceName("many"));
            final List<String> amountFields = List.of("0000005047G", "0000009190}", "0000000678H");
            final List<String> originals = new ArrayList<>(amountFields.size());
            for (int index = 0; index < amountFields.size(); index++) {
                originals.add(image(String.format(Locale.ROOT, "%016d", Integer.valueOf(index + 1)), "01",
                        "0001", "POS TERM", "ROW " + (index + 1), amountFields.get(index), "800000000",
                        "MERCHANT", "CITY", "12345", "4859452612877065", ORIGINATING_TIMESTAMP,
                        BLANK_TIMESTAMP));
            }
            writeGeneration(bucket, WRITTEN_GENERATION_KEY, originals);

            final TransactionBackupReader reader = readerOver(bucket, WRITTEN_GENERATION_KEY);
            reader.open(new ExecutionContext());
            final List<Transaction> decoded = drain(reader);
            final long recordsRead = reader.getRecordsRead();
            reader.close();

            assertThat(decoded).hasSize(originals.size());
            assertThat(recordsRead).isEqualTo(originals.size());
            for (int index = 0; index < originals.size(); index++) {
                assertThat(reEmit(decoded.get(index), amountFields.get(index)))
                        .as("record %d of %d", Integer.valueOf(index + 1), Integer.valueOf(originals.size()))
                        .isEqualTo(originals.get(index));
            }
            assertThat(decoded).extracting(Transaction::getTransactionId)
                    .containsExactly("0000000000000001", "0000000000000002", "0000000000000003");
        }

        @Test
        @DisplayName("the object written is exactly 351 bytes per record: 350 plus one terminator")
        void theObjectGeometryIsBytePredictable() {
            final String bucket = createVersionedBucket(scopedResourceName("geometry"));
            final List<String> originals = List.of(
                    image("0000000000000001", "01", "0001", "POS TERM", "ONE", "0000005047G", "800000000",
                            "MERCHANT", "CITY", "12345", "4859452612877065", ORIGINATING_TIMESTAMP,
                            BLANK_TIMESTAMP),
                    image("0000000000000002", "01", "0001", "POS TERM", "TWO", "0000005047G", "800000000",
                            "MERCHANT", "CITY", "12345", "4859452612877065", ORIGINATING_TIMESTAMP,
                            BLANK_TIMESTAMP));
            writeGeneration(bucket, WRITTEN_GENERATION_KEY, originals);

            final byte[] stored = s3Client().getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(WRITTEN_GENERATION_KEY).build()).asByteArray();

            // Stated as bytes rather than characters: the reader decodes ISO-8859-1, which is why a byte
            // count and a character count coincide here and would not under a multi-byte charset.
            assertThat(stored).hasSize(originals.size() * (TRANSACTION_RECORD_LENGTH + 1));
            assertThat(new String(stored, StandardCharsets.ISO_8859_1))
                    .startsWith(originals.get(0))
                    .contains(originals.get(1));
        }
    }
}
