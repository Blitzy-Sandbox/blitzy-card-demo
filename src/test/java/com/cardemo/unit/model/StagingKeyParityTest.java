/*
 * ******************************************************************
 * Program     : StagingKeyParityTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves DailyTransaction.ingestSequence is a technical staging
 *               key that occupies none of the 350 record bytes: two records
 *               differing only in the ordinal emit byte-identical 430-byte
 *               reject images through RejectWriter.
 * Source      : app/cpy/CVTRA06Y.cpy:L2        (RECLN = 350)
 *               app/cbl/CBTRN02C.cbl:L185,L206 (WS-TRANSACTION-COUNT)
 *               app/cbl/CBTRN02C.cbl:L446-L465 (2500-WRITE-REJECT-REC)
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * The staging key: {@code DailyTransaction.ingestSequence} is a fourteenth mapped property on a
 * thirteen-field record, and this class proves it cannot reach a byte of that record.
 *
 * <p>{@code app/cpy/CVTRA06Y.cpy:L2} declares {@code RECLN = 350} and its thirteen fields account for all 350
 * bytes, so any additional mapped property is by construction outside the layout. The staging key exists
 * because the input is an unkeyed physical sequential dataset whose identity is a record's position in the
 * file - the ordinal the source itself counts as {@code WS-TRANSACTION-COUNT} at
 * {@code app/cbl/CBTRN02C.cbl:L185}, incremented at {@code :L206} - and because {@code DALYTRAN-ID} may
 * legitimately repeat and therefore cannot be the key.
 *
 * <p>A synthetic key is only safe if it is invisible at the fixed-width boundary. The one place a staged
 * record is re-serialised is {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465}, whose
 * Java form is {@code RejectWriter}: {@code :L447} moves the whole 350-byte record image and {@code :L448}
 * appends the 80-byte trailer, for the 430 bytes the reject dataset declares. The assertions below drive that
 * writer with records that differ <em>only</em> in the staging key and require the emitted bytes to be
 * identical - which is a stronger statement than reading the writer's source, because it would fail the moment
 * any future edit interpolated the ordinal into the image.
 */
@DisplayName("DailyTransaction: the staging key is table metadata and never record content")
class StagingKeyParityTest {

    /** The thirteen copybook widths, in declaration order, summing to 350. */
    private static final int[] FIELD_WIDTHS = {16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26, 20};

    @Nested
    @DisplayName("1. The layout has no room for a fourteenth field")
    class TheLayoutIsFull {

        @Test
        @DisplayName("The thirteen copybook fields plus the trailing filler sum to exactly 350")
        void theCopybookFieldsFillTheRecord() {
            assertThat(Arrays.stream(FIELD_WIDTHS).sum())
                    .as("app/cpy/CVTRA06Y.cpy:L2 declares RECLN = 350")
                    .isEqualTo(RejectCode.REJECT_TRAN_DATA_LENGTH);
        }

        @Test
        @DisplayName("The reject record is that 350 plus an 80-byte trailer, and nothing else")
        void theRejectRecordIsThreeHundredAndFiftyPlusEighty() {
            assertThat(RejectCode.VALIDATION_TRAILER_LENGTH).isEqualTo(80);
            assertThat(RejectCode.REJECT_RECORD_LENGTH).isEqualTo(430);
        }

        @Test
        @DisplayName("The staging key is the only mapped property outside the copybook, and its column name "
                + "says so by omitting the dalytran_ prefix")
        void theStagingKeyIsTheOnlyOneOutsideTheCopybook() {
            final List<String> withoutThePrefix = new ArrayList<>();
            for (Field field : DailyTransaction.class.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                final jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
                if (column != null && !column.name().startsWith("dalytran_")) {
                    withoutThePrefix.add(column.name());
                }
            }
            // The naming convention is the marker: a reader can separate the thirteen record columns from the
            // one synthetic column by name alone, with no note to consult.
            assertThat(withoutThePrefix).containsExactly("ingest_seq");
        }

        @Test
        @DisplayName("The staging key is not database-generated, so re-staging the same file repeats it")
        void theStagingKeyIsAssignedByTheLoader() throws NoSuchFieldException {
            // A database allocator keeps counting across a truncate-and-reload, so a generated value could
            // not equal the source's own read counter and the two would stop being comparable.
            final Field key = DailyTransaction.class.getDeclaredField("ingestSequence");
            assertThat(key.getAnnotation(jakarta.persistence.GeneratedValue.class)).isNull();
            assertThat(key.getAnnotation(jakarta.persistence.Id.class)).isNotNull();
        }
    }

    @Nested
    @DisplayName("2. The fixed-width boundary cannot see it")
    class TheBoundaryCannotSeeIt {

        @Test
        @DisplayName("Two records differing only in the staging key emit byte-identical 430-byte images")
        void theStagingKeyChangesNoByteOfTheEmittedRecord() throws IOException {
            final byte[] first = emit(staged(1L));
            final byte[] last = emit(staged(999_999_999L));

            assertThat(first).hasSize(RejectCode.REJECT_RECORD_LENGTH);
            assertThat(last).hasSize(RejectCode.REJECT_RECORD_LENGTH);
            assertThat(last)
                    .as("the ordinal spans the whole NUMERIC(9) domain between these two records, so if any "
                            + "of it reached the image the two would differ")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("A null staging key emits the same image too, so persistence state cannot leak in")
        void anAbsentStagingKeyChangesNothingEither() throws IOException {
            assertThat(emit(staged(null)))
                    .as("a record composed before its ordinal is assigned serialises identically")
                    .isEqualTo(emit(staged(42L)));
        }

        @Test
        @DisplayName("The emitted image is the thirteen fields at their declared widths, ordinal absent")
        void theEmittedImageIsTheThirteenFields() throws IOException {
            final String image = new String(emit(staged(7L)), StandardCharsets.US_ASCII);

            // Bytes 1-16 are DALYTRAN-ID, not the ordinal: the group move at :L447 starts at the record's
            // first field, and the ordinal has no offset to start at.
            assertThat(image.substring(0, 16)).isEqualTo("0000000000000001");
            assertThat(image).doesNotContain("000000007");
            // Bytes 351-354 are the four-digit reason code of the trailer, :L448 - zero-padded to the
            // declared PIC 9(04) width, so 101 renders as 0101.
            assertThat(image.substring(350, 354))
                    .isEqualTo("%04d".formatted(
                            Integer.valueOf(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getCode())));
        }
    }

    /**
     * Builds a resource view of one object the fake store holds.
     *
     * @param key the object key, never {@code null}
     * @param bytes its content, never {@code null}
     * @return a resource reporting that key, length and content, never {@code null}
     */
    private static S3Resource storedResource(final String key, final byte[] bytes) {
        final S3Resource resource = mock(S3Resource.class);
        when(resource.getFilename()).thenReturn(key);
        when(resource.contentLength()).thenReturn(Long.valueOf(bytes.length));
        try {
            when(resource.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(bytes));
        } catch (final IOException impossible) {
            throw new IllegalStateException("stubbing cannot fail", impossible);
        }
        return resource;
    }

    /**
     * Drives the reject writer and returns the exact bytes it handed to object storage.
     *
     * @param transaction the staged record to re-serialise, never {@code null}
     * @return the emitted image, never {@code null}
     * @throws IOException if the captured stream cannot be read
     */
    private static byte[] emit(final DailyTransaction transaction) throws IOException {
        final S3Operations objectStorage = mock(S3Operations.class);
        // The writer uploads each chunk as a complete part object and assembles the ONE (+1) generation from
        // the parts at close - findings H-04 and M-06 - so the bytes are observed by keeping a small fake
        // store rather than by capturing a stream.
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final java.util.Map<String, byte[]> objects = new java.util.LinkedHashMap<>();
        when(objectStorage.upload(anyString(), anyString(), org.mockito.Mockito.any(java.io.InputStream.class),
                org.mockito.Mockito.any(io.awspring.cloud.s3.ObjectMetadata.class))).thenAnswer(invocation -> {
                    final String key = invocation.getArgument(1, String.class);
                    final byte[] bytes;
                    try (java.io.InputStream body = invocation.getArgument(2, java.io.InputStream.class)) {
                        bytes = body.readAllBytes();
                    }
                    objects.put(key, bytes);
                    if (!key.contains("/parts/")) {
                        sink.reset();
                        sink.write(bytes);
                    }
                    return storedResource(key, bytes);
                });
        when(objectStorage.download(anyString(), anyString())).thenAnswer(invocation -> {
            final String key = invocation.getArgument(1, String.class);
            return storedResource(key, objects.getOrDefault(key, new byte[0]));
        });
        when(objectStorage.objectExists(anyString(), anyString())).thenAnswer(invocation ->
                Boolean.valueOf(objects.containsKey(invocation.getArgument(1, String.class))));

        // The part listing and the part deletion go through the paging client rather than through
        // S3Operations, because a single ListObjectsV2 truncates at a thousand keys and a run rejecting more
        // records than that staged more parts than one page could name - finding H-11. One page is enough
        // here: this test emits a single record.
        final S3Client objectStoreClient = mock(S3Client.class);
        when(objectStoreClient.listObjectsV2(org.mockito.Mockito.any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> {
                    final String prefix = invocation.getArgument(0, ListObjectsV2Request.class).prefix();
                    final List<S3Object> contents = new ArrayList<>();
                    for (final String key : new java.util.ArrayList<>(objects.keySet())) {
                        if (key.startsWith(prefix)) {
                            contents.add(S3Object.builder().key(key).build());
                        }
                    }
                    return ListObjectsV2Response.builder()
                            .contents(contents)
                            .isTruncated(Boolean.FALSE)
                            .build();
                });
        when(objectStoreClient.listObjectsV2Paginator(org.mockito.Mockito.any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> new ListObjectsV2Iterable(objectStoreClient,
                        invocation.getArgument(0, ListObjectsV2Request.class)));
        when(objectStoreClient.deleteObjects(org.mockito.Mockito.any(DeleteObjectsRequest.class)))
                .thenAnswer(invocation -> {
                    for (final ObjectIdentifier identifier
                            : invocation.getArgument(0, DeleteObjectsRequest.class).delete().objects()) {
                        objects.remove(identifier.key());
                    }
                    return DeleteObjectsResponse.builder().build();
                });

        final RejectWriter writer = new RejectWriter(objectStorage, objectStoreClient,
                new MetricsConfig(new SimpleMeterRegistry()), new FileStatusMapper(),
                "carddemo-batch-output", "gdg/dalyrejs", null);

        writer.writeReject(transaction, RejectCode.ACCOUNT_RECORD_NOT_FOUND);
        writer.close();

        return sink.toByteArray();
    }

    /**
     * A staged record whose thirteen copybook fields are fixed and whose ordinal is the only variable.
     *
     * @param ingestSequence the staging key, permitted to be {@code null}
     * @return the staged record, never {@code null}
     */
    private static DailyTransaction staged(final Long ingestSequence) {
        return new DailyTransaction(ingestSequence, "0000000000000001", "PR", 1, "POS TERM",
                "A PURCHASE", new BigDecimal("12.34"), 1L, "A MERCHANT", "A CITY", "0000012345",
                "4111111111111111", "2024-01-01 00:00:00.0000", "2024-01-01 00:00:00.0000");
    }
}
