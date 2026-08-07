/*
 * ******************************************************************
 * Program     : WriterObjectStoragePolicyTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Pins one object-storage policy across the three batch writers:
 *               the S3Operations interface rather than the template class, a
 *               required non-blank destination bucket with no inline default,
 *               and a typed CardDemo failure that preserves its cause.
 * Source      : app/cbl/CBTRN02C.cbl:L446-L465 (2500-WRITE-REJECT-REC)
 *               app/cbl/CBTRN02C.cbl:L562-L579 (2900-WRITE-TRANSACTION-FILE)
 *               app/jcl/CREASTMT.JCL:STEP040   (STMTFILE + HTMLFILE)
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
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.CardDemoException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * One object-storage policy across all three batch writers: abstraction, bucket validation, exception shape.
 *
 * <p>The three writers in {@code com.cardemo.batch.writers} all send a fixed-width image to object storage, and
 * they had drifted apart on how. Two took the {@code S3Operations} interface and one took the concrete
 * {@code S3Template}; two required their destination bucket with no default while the third defaulted it to a
 * literal; and one rejected only a {@code null} bucket where the others rejected a blank one too. None of that
 * failed anything, because each writer's own contract was internally consistent - which is exactly why a test
 * that spans all three is what pins the policy.
 *
 * <p>The abstraction choice is not cosmetic. Spring Cloud AWS declares its template bean under
 * {@code @ConditionalOnMissingBean(S3Operations.class)}, so a dependency on the interface is satisfied by the
 * auto-configured template and stays satisfied if {@code com.cardemo.config.AwsConfig} supplies its own of
 * either type; a dependency on the class is satisfied only by that one type. The defaulted bucket was the more
 * consequential difference: a destination that falls back to a literal writes somewhere the operator did not
 * configure rather than failing the context.
 */
@DisplayName("Batch writers: one object-storage policy across all three")
class WriterObjectStoragePolicyTest {

    /**
     * The reject generation prefix, {@code carddemo.aws.s3.gdg-prefixes.daly-rejs}.
     *
     * <p>Supplied non-blank at every site so that the bucket under test is the value the constructor rejects.
     * The prefix has its own guard, and a blank one here would make a bucket assertion pass for the wrong
     * reason.
     */
    private static final String GDG_PREFIX = "gdg/dalyrejs";

    /**
     * The statement writer's time source, fixed so the derived statement month cannot vary between runs.
     *
     * <p>The instant is the one the sibling statement suites use, so two suites cannot disagree about which
     * month a statement is filed under.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-06-10T19:27:53.470Z"), ZoneOffset.UTC);

    /** The three writers this policy governs. */
    private static final List<Class<?>> WRITERS =
            List.of(TransactionWriter.class, RejectWriter.class, StatementWriter.class);

    @Nested
    @DisplayName("1. One abstraction: every writer depends on the interface, never on the template class")
    class OneAbstraction {

        @Test
        @DisplayName("Every writer constructor takes S3Operations")
        void everyWriterTakesTheInterface() {
            WRITERS.forEach(writer -> assertThat(writer.getDeclaredConstructors())
                    .as("%s constructors", writer.getSimpleName())
                    .anySatisfy(constructor -> assertThat(parameterTypes(constructor))
                            .contains(S3Operations.class)));
        }

        @Test
        @DisplayName("No writer constructor takes the concrete S3Template")
        void noWriterTakesTheTemplateClass() {
            // Named reflectively so this assertion states the prohibition without importing the class it
            // prohibits - importing it would be the very coupling being ruled out.
            WRITERS.forEach(writer -> assertThat(writer.getDeclaredConstructors())
                    .as("%s constructors", writer.getSimpleName())
                    .allSatisfy(constructor -> assertThat(parameterTypeNames(constructor))
                            .doesNotContain("io.awspring.cloud.s3.S3Template")));
        }
    }

    @Nested
    @DisplayName("2. One bucket policy: required, and rejected when blank as well as when absent")
    class OneBucketPolicy {

        @Test
        @DisplayName("A blank destination bucket is rejected by every writer")
        void aBlankBucketIsRejectedEverywhere() {
            // An unset property resolves to an empty string rather than to null, so a null-only guard lets a
            // zero-length bucket name reach the object store.
            assertRejects(() -> new TransactionWriter(mock(TransactionRepository.class),
                    mock(S3Operations.class), new FileStatusMapper(), metrics(), "   ", "transact",
                    TransactionWriter.DEFAULT_MAX_INDEXED_OBJECT_KEYS));
            assertRejects(() -> new RejectWriter(mock(S3Operations.class), metrics(),
                    new FileStatusMapper(), "   ", GDG_PREFIX, null));
            assertRejects(() -> new StatementWriter(mock(S3Operations.class),
                    new FileStatusMapper(), FIXED_CLOCK, "   "));
        }

        @Test
        @DisplayName("An absent destination bucket is rejected by every writer")
        void anAbsentBucketIsRejectedEverywhere() {
            assertRejects(() -> new TransactionWriter(mock(TransactionRepository.class),
                    mock(S3Operations.class), new FileStatusMapper(), metrics(), null, "transact",
                    TransactionWriter.DEFAULT_MAX_INDEXED_OBJECT_KEYS));
            assertRejects(() -> new RejectWriter(mock(S3Operations.class), metrics(),
                    new FileStatusMapper(), null, GDG_PREFIX, null));
            assertRejects(() -> new StatementWriter(mock(S3Operations.class),
                    new FileStatusMapper(), FIXED_CLOCK, null));
        }

        @Test
        @DisplayName("No writer declares an inline default for its destination bucket")
        void noWriterDefaultsItsBucket() {
            // A @Value default on a destination is what lets an unconfigured environment write somewhere the
            // operator never named. All three bucket properties are supplied without a fallback by
            // src/main/resources/application.yml, so no default is needed and none may be reintroduced.
            WRITERS.forEach(writer -> assertThat(bucketPlaceholders(writer))
                    .as("%s bucket placeholders", writer.getSimpleName())
                    .isNotEmpty()
                    .allSatisfy(placeholder -> assertThat(placeholder)
                            .as("[%s] must carry no ':' fallback", placeholder)
                            .doesNotContain(":")));
        }
    }

    @Nested
    @DisplayName("3. One exception policy: a storage failure becomes a typed CardDemo failure with its cause")
    class OneExceptionPolicy {

        @Test
        @DisplayName("A rejected upload becomes a CardDemoException carrying the cause, on every writer")
        void aRejectedUploadIsTypedAndKeepsItsCause() {
            for (WriterUnderTest subject : subjects()) {
                final RuntimeException storageFailure =
                        new IllegalStateException("object storage rejected the upload");
                subject.failStorage().accept(storageFailure);

                assertThatExceptionOfType(CardDemoException.class)
                        .as("%s must route a storage failure through FileStatusMapper", subject.name())
                        .isThrownBy(subject.emit())
                        .withRootCauseInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("Every upload a writer makes addresses the bucket it was configured with, and no other")
        void everyWriterAddressesItsConfiguredBucket() {
            for (WriterUnderTest subject : subjects()) {
                assertThatCode(subject.emit()).doesNotThrowAnyException();

                // The count differs legitimately: the statement writer emits two objects per statement,
                // because app/jcl/CREASTMT.JCL:STEP040 declares two DD names - STMTFILE at LRECL 80 and
                // HTMLFILE at LRECL 100. What must hold for all of them is the destination, so every
                // captured bucket argument is asserted rather than a single invocation.
                assertThat(subject.capturedBuckets().get())
                        .as("%s must write only to its configured bucket", subject.name())
                        .isNotEmpty()
                        .containsOnly(subject.bucket());
            }
        }
    }

    /**
     * One writer prepared for emission, with the object store it will address.
     *
     * @param name the writer's simple name, never {@code null}
     * @param bucket the bucket it was configured with, never {@code null}
     * @param objectStorage the mock it will call, never {@code null}
     * @param emit the call that drives exactly one upload, never {@code null}
     * @param failStorage makes this writer's store reject the write it is about to attempt. All three writers
     *     reach the store the same way, through {@code upload} with a declared content length: two hand it a
     *     finished buffer, and {@link RejectWriter} hands it one chunk's durable part, assembling the single
     *     {@code (+1)} generation from the parts at close - findings H-04 and M-06. The failure injection
     *     still travels with the subject rather than being assumed by the assertion, so a writer that later
     *     reaches the store differently does not silently stop being exercised
     * @param capturedBuckets reports every bucket this writer addressed, read from whichever call it makes
     */
    private record WriterUnderTest(String name, String bucket, S3Operations objectStorage,
            ThrowingCallable emit, Consumer<RuntimeException> failStorage,
            Supplier<List<String>> capturedBuckets) {
    }

    /**
     * Reports every bucket argument a writer passed to {@code upload}.
     *
     * @param objectStorage the mock to interrogate, never {@code null}
     * @return the captured bucket names, never {@code null}
     */
    private static List<String> uploadedBuckets(final S3Operations objectStorage) {
        final ArgumentCaptor<String> buckets = ArgumentCaptor.forClass(String.class);
        verify(objectStorage, atLeastOnce())
                .upload(buckets.capture(), anyString(), any(), any(ObjectMetadata.class));
        return buckets.getAllValues();
    }

    /**
     * Builds one prepared subject per writer, each with its own mock so the assertions cannot interfere.
     *
     * @return the three subjects, never {@code null}
     */
    private static List<WriterUnderTest> subjects() {
        final S3Operations forTransactions = mock(S3Operations.class);
        final TransactionWriter transactionWriter = new TransactionWriter(mock(TransactionRepository.class),
                forTransactions, new FileStatusMapper(), metrics(), "carddemo-batch-output", "transact",
                TransactionWriter.DEFAULT_MAX_INDEXED_OBJECT_KEYS);
        transactionWriter.beforeStep(MetaDataInstanceFactory.createStepExecution());

        final S3Operations forRejects = mock(S3Operations.class);
        final RejectWriter rejectWriter = new RejectWriter(forRejects, metrics(), new FileStatusMapper(),
                "carddemo-batch-output", GDG_PREFIX, null);

        final S3Operations forStatements = mock(S3Operations.class);
        final StatementWriter statementWriter = new StatementWriter(forStatements,
                new FileStatusMapper(), FIXED_CLOCK, "carddemo-statements");

        return List.of(
                new WriterUnderTest("TransactionWriter", "carddemo-batch-output", forTransactions,
                        () -> transactionWriter.write(
                                org.springframework.batch.item.Chunk.of(postedTransaction())),
                        failure -> doThrow(failure).when(forTransactions)
                                .upload(anyString(), anyString(), any(), any(ObjectMetadata.class)),
                        () -> uploadedBuckets(forTransactions)),
                new WriterUnderTest("RejectWriter", "carddemo-batch-output", forRejects,
                        () -> rejectWriter.writeReject(stagedTransaction(),
                                RejectCode.ACCOUNT_RECORD_NOT_FOUND),
                        failure -> doThrow(failure).when(forRejects)
                                .upload(anyString(), anyString(), any(), any(ObjectMetadata.class)),
                        () -> uploadedBuckets(forRejects)),
                new WriterUnderTest("StatementWriter", "carddemo-statements", forStatements, () -> {
                    statementWriter.openStatementOutputs("00000000001", "2024-01");
                    statementWriter.closeStatementOutputs();
                },
                        failure -> doThrow(failure).when(forStatements)
                                .upload(anyString(), anyString(), any(), any(ObjectMetadata.class)),
                        () -> uploadedBuckets(forStatements)));
    }

    /**
     * Asserts that a construction is rejected with a message naming the property, so an operator reading the
     * startup failure learns which value to set rather than only that something was null.
     *
     * @param construction the construction to attempt, never {@code null}
     */
    private static void assertRejects(final ThrowingCallable construction) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(construction)
                .withMessageContaining("must be configured with a non-blank value");
    }

    /**
     * The metrics facade the two reporting writers take.
     *
     * <p>{@code StatementWriter} is deliberately absent from its callers: that writer reports no series, so
     * it holds no meter owner. See {@code MetricInstrumentOwnershipTest.everyReportingWriterTakesTheFacade}.
     *
     * @return a facade over a fresh registry, never {@code null}
     */
    private static MetricsConfig metrics() {
        return new MetricsConfig(new SimpleMeterRegistry());
    }

    /**
     * Reads a constructor's parameter types.
     *
     * @param constructor the constructor, never {@code null}
     * @return its parameter types, never {@code null}
     */
    private static List<Class<?>> parameterTypes(final Constructor<?> constructor) {
        return List.of(constructor.getParameterTypes());
    }

    /**
     * Reads a constructor's parameter type names.
     *
     * @param constructor the constructor, never {@code null}
     * @return the fully qualified names, never {@code null}
     */
    private static List<String> parameterTypeNames(final Constructor<?> constructor) {
        return Arrays.stream(constructor.getParameterTypes()).map(Class::getName).toList();
    }

    /**
     * Reads the {@code @Value} placeholders a writer declares for a bucket property.
     *
     * @param writer the writer class, never {@code null}
     * @return the placeholder expressions naming a bucket, never {@code null}
     */
    private static List<String> bucketPlaceholders(final Class<?> writer) {
        return Arrays.stream(writer.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterAnnotations())
                        .flatMap(Arrays::stream))
                .filter(org.springframework.beans.factory.annotation.Value.class::isInstance)
                .map(annotation ->
                        ((org.springframework.beans.factory.annotation.Value) annotation).value())
                .filter(placeholder -> placeholder.contains("bucket"))
                .toList();
    }

    /**
     * A posted transaction valid for the 350-byte image.
     *
     * @return the transaction, never {@code null}
     */
    private static Transaction postedTransaction() {
        return new Transaction("0000000000000001", "PR", 1, "POS TERM", "A PURCHASE",
                new BigDecimal("12.34"), 1L, "A MERCHANT", "A CITY", "0000012345", "4111111111111111",
                "2024-01-01 00:00:00.0000", "2024-01-01 00:00:00.0000");
    }

    /**
     * A staged daily transaction valid against every width the entity enforces.
     *
     * @return the staged record, never {@code null}
     */
    private static DailyTransaction stagedTransaction() {
        return new DailyTransaction(1L, "0000000000000001", "PR", 1, "POS TERM", "A PURCHASE",
                new BigDecimal("12.34"), 1L, "A MERCHANT", "A CITY", "0000012345", "4111111111111111",
                "2024-01-01 00:00:00.0000", "2024-01-01 00:00:00.0000");
    }

}
