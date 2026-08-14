/*
 * ******************************************************************
 * Program     : TransactionBackupReaderTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test (Java 25 / Spring Boot 3.5.11)
 * Function    : Proves the current-generation resolution of
 *               com.cardemo.batch.readers.TransactionBackupReader, which stands
 *               in for the AWS.M2.CARDDEMO.TRANSACT.BKUP(0) reference at
 *               app/proc/TRANREPT.prc:L37 after app/proc/TRANREPT.prc:L31 has
 *               written AWS.M2.CARDDEMO.TRANSACT.BKUP(+1). Two defects are the
 *               subject: an unpaginated listing that resolved a stale
 *               generation past one page of keys (H-07), and a carried
 *               execution-context key that was read without being confined to
 *               the configured generation namespace (M-11).
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.readers.TransactionBackupReader;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.item.ExecutionContext;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * The two resolution defects of the {@code TRANSACT.BKUP} reader, asserted against the reader itself.
 *
 * <h2>1. What this proves and why it matters</h2>
 *
 * <p>{@code app/proc/TRANREPT.prc:L27}-{@code :L31} writes {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} and
 * {@code :L36}-{@code :L37} then reads that generation. On the mainframe the catalogue makes
 * "the current generation" exact. There is no catalogue here, so the reader resolves it as the
 * lexicographically greatest key under the configured base - which is only equivalent to "the newest" if
 * <strong>every</strong> key is considered.
 *
 * <ul>
 *   <li><strong>H-07, High.</strong> The listing used to be a single page. Past one page of keys the maximum
 *       was taken over an arbitrary subset, so the report was produced from a stale backup while looking
 *       entirely healthy - a wrong answer indistinguishable from a right one. The first group below drives
 *       more than one page of keys and asserts both that every page was requested and that the greatest key
 *       across all of them is the one resolved.</li>
 *   <li><strong>M-11, Medium.</strong> A promoted key arrives from an execution context, which is untrusted
 *       input: it used to be logged and read verbatim, so a substituted value redirected the read to any other
 *       object in the same bucket and a control byte in it could forge a log line. The second group asserts
 *       that a key outside the configured namespace is refused before anything is read, and that a legitimate
 *       one is still honoured.</li>
 * </ul>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Ddependency-check.skip=true test}. This class is collected by Surefire: it lives
 * under {@code com/cardemo/unit} and starts no container, so it needs no runtime beyond the JVM.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>The reader is constructed directly with the {@code object-storage} selector, the default page size, a
 * bucket and the {@code gdg/transact-bkup} base that {@code application.yml} declares. No Spring context is
 * started, so no property source participates.
 *
 * <h2>4. Common failure modes</h2>
 *
 * <ul>
 *   <li>A failure in the first group means the listing stopped being paged, so the current generation can go
 *       stale again. Check that the reader calls {@code listObjectsV2Paginator} and not
 *       {@code S3Operations.listObjects}, which returns one page.</li>
 *   <li>A failure in the second group means a carried key reached a read without being confined to the
 *       configured prefix. That is a cross-prefix read, not a cosmetic validation gap.</li>
 * </ul>
 */
@DisplayName("TransactionBackupReader: the (0) generation resolution (H-07, M-11)")
class TransactionBackupReaderTest {

    /** The bucket the reader is configured with; a name, never an address. */
    private static final String BUCKET = "carddemo-batch-output";

    /** The generation base, exactly as {@code src/main/resources/application.yml} declares it. */
    private static final String GENERATION_BASE = "gdg/transact-bkup";

    /** Keys per page, matching the service's own maximum, so the paging boundary is the real one. */
    private static final int KEYS_PER_PAGE = 1000;

    /**
     * The execution-context entry the reader checkpoints its resolved generation under.
     *
     * <p>Named here rather than read from the reader, because the reader keeps it private on purpose: it is a
     * checkpoint, not a public contract. Stating it in the test is what makes a rename visible as a failing
     * assertion rather than as a silently unchecked one.
     */
    private static final String CONTEXT_KEY_GENERATION_OBJECT_KEY = "carddemo.gdg.transact-bkup.objectKey";

    private S3Operations objectStorage;
    private S3Client objectStoreClient;

    @BeforeEach
    void buildCollaborators() {
        objectStorage = mock(S3Operations.class);
        objectStoreClient = mock(S3Client.class);
    }

    /**
     * Builds the reader on the {@code object-storage} path.
     *
     * @param promotedKey the key a prior step promoted, or {@code null} for the standalone path
     * @return the reader under test, never {@code null}
     */
    private TransactionBackupReader reader(final String promotedKey) {
        return new TransactionBackupReader(
                mock(TransactionRepository.class),
                objectStorage,
                objectStoreClient,
                new FileStatusMapper(),
                "object-storage",
                TransactionBackupReader.DEFAULT_PAGE_SIZE,
                BUCKET,
                GENERATION_BASE,
                promotedKey);
    }

    /**
     * Composes the key one generation of the base is written under.
     *
     * @param jobInstanceId the writing job instance, zero-padded so lexical order tracks numeric order
     * @return the object key, never {@code null}
     */
    private static String generationKey(final long jobInstanceId) {
        return String.format(Locale.ROOT, "%s/%019d/%019d.dat", GENERATION_BASE, jobInstanceId,
                jobInstanceId);
    }

    /**
     * Stubs the client so that {@code count} keys are returned across as many pages as the service would use.
     *
     * <p>A real {@link ListObjectsV2Iterable} is returned rather than a mocked one, so the paging protocol -
     * truncation flag, continuation token, one request per page - is exercised rather than simulated. The keys
     * are deliberately produced in ascending order and the <em>last</em> page carries the greatest one, which is
     * exactly the arrangement a single-page listing gets wrong.
     *
     * @param count how many keys exist under the base
     */
    private void stubPagedListing(final int count) {
        final List<ListObjectsV2Response> pages = new ArrayList<>();
        for (int start = 0; start < count; start += KEYS_PER_PAGE) {
            final int end = Math.min(start + KEYS_PER_PAGE, count);
            final List<S3Object> contents = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                contents.add(S3Object.builder().key(generationKey(index)).build());
            }
            final boolean truncated = end < count;
            pages.add(ListObjectsV2Response.builder()
                    .contents(contents)
                    .isTruncated(Boolean.valueOf(truncated))
                    .nextContinuationToken(truncated ? "token-" + end : null)
                    .build());
        }

        when(objectStoreClient.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> {
                    final ListObjectsV2Request request =
                            invocation.getArgument(0, ListObjectsV2Request.class);
                    final String token = request.continuationToken();
                    if (token == null) {
                        return pages.get(0);
                    }
                    final int offset = Integer.parseInt(token.substring("token-".length()));
                    return pages.get(offset / KEYS_PER_PAGE);
                });
        when(objectStoreClient.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> new ListObjectsV2Iterable(objectStoreClient,
                        invocation.getArgument(0, ListObjectsV2Request.class)));
    }

    /**
     * Opens the reader over an empty generation and reports the key it resolved.
     *
     * <p>The generation is stubbed present and empty, which is a legitimate state - {@code REPRO} of an empty
     * cluster produces an empty object - so {@code open} succeeds and the reader then checkpoints the key it
     * resolved. Reading it back from the context is what makes the resolution observable without reaching a
     * record, and it exercises the same checkpoint entry a restart would consume.
     *
     * @param promotedKey the key a prior step promoted, or {@code null}
     * @param context the step context to open with
     * @return the resolved key, never {@code null}
     */
    private String resolvedKey(final String promotedKey, final ExecutionContext context) {
        when(objectStorage.objectExists(anyString(), anyString())).thenReturn(Boolean.TRUE);
        final S3Resource generation = mock(S3Resource.class);
        try {
            when(generation.getInputStream())
                    .thenReturn(new ByteArrayInputStream(new byte[0]));
        } catch (final IOException impossible) {
            throw new IllegalStateException("stubbing cannot fail", impossible);
        }
        when(objectStorage.download(anyString(), anyString())).thenReturn(generation);

        final TransactionBackupReader reader = reader(promotedKey);
        reader.open(context);
        reader.update(context);
        return context.getString(CONTEXT_KEY_GENERATION_OBJECT_KEY, "");
    }

    @Nested
    @DisplayName("H-07: the current generation is resolved across every page of the listing")
    class PagedResolution {

        /** Sole constructor, invoked by the test framework. This group holds no state. */
        PagedResolution() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("more than one page of keys still resolves the greatest key, not the first page's")
        void moreThanOnePageResolvesTheGreatestKey() {
            final int count = KEYS_PER_PAGE + 500;
            stubPagedListing(count);

            final ExecutionContext context = new ExecutionContext();
            assertThat(resolvedKey(null, context))
                    .as("app/proc/TRANREPT.prc:L37 reads the generation :L31 wrote, so the newest key is the "
                            + "only correct answer; a single-page listing would have returned the greatest key "
                            + "of the first %d and reported on a stale backup", Integer.valueOf(KEYS_PER_PAGE))
                    .isEqualTo(generationKey(count - 1L));
        }

        @Test
        @DisplayName("every page of the listing is requested, not just the first")
        void everyPageIsRequested() {
            stubPagedListing(2 * KEYS_PER_PAGE + 1);

            resolvedKey(null, new ExecutionContext());

            verify(objectStoreClient, atLeast(3)).listObjectsV2(any(ListObjectsV2Request.class));
        }

        @Test
        @DisplayName("a single short page is resolved without a continuation request")
        void aSinglePageNeedsNoContinuation() {
            stubPagedListing(3);

            assertThat(resolvedKey(null, new ExecutionContext()))
                    .isEqualTo(generationKey(2L));
            verify(objectStoreClient).listObjectsV2(any(ListObjectsV2Request.class));
        }
    }

    @Nested
    @DisplayName("M-11: a carried generation key is confined to the configured namespace")
    class CarriedKeyValidation {

        /** Sole constructor, invoked by the test framework. This group holds no state. */
        CarriedKeyValidation() {
            // Intentionally empty.
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "gdg/tranrept/0000000000000000001/0000000000000000001.dat",
            "gdg/transact-bkup-other/0000000000000000001.dat",
            "gdg/transact-bkup/../tranrept/0000000000000000001.dat",
            "gdg/transact-bkup//0000000000000000001.dat",
            "gdg/transact-bkup/0000000000000000001/",
            "gdg/transact-bkup/0000000000000000001.dat\nforged log line",
        })
        @DisplayName("a key outside the namespace is refused before anything is read")
        void aKeyOutsideTheNamespaceIsRefused(final String hostileKey) {
            final TransactionBackupReader reader = reader(hostileKey);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("reading the wrong generation is worse than failing, so the refusal is terminal")
                    .isThrownBy(() -> reader.open(new ExecutionContext()))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("the rejected value is exactly what must not be echoed")
                            .doesNotContain(hostileKey));

            verify(objectStorage, org.mockito.Mockito.never()).download(anyString(), anyString());
        }

        @Test
        @DisplayName("a key longer than the object-key limit is refused")
        void anOverLongKeyIsRefused() {
            final String overLong = GENERATION_BASE + "/" + "9".repeat(1100) + ".dat";
            final TransactionBackupReader reader = reader(overLong);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()));
        }

        @Test
        @DisplayName("a key inside the namespace is honoured, and no listing is issued at all")
        void aKeyInsideTheNamespaceIsHonoured() {
            final String promoted = generationKey(11L);

            assertThat(resolvedKey(promoted, new ExecutionContext()))
                    .as("app/proc/TRANREPT.prc:L31 wrote it and :L37 reads exactly it, so no 'latest' guess "
                            + "may override a promoted generation")
                    .isEqualTo(promoted);

            verify(objectStoreClient, org.mockito.Mockito.never())
                    .listObjectsV2Paginator(any(ListObjectsV2Request.class));
        }
    }
}
