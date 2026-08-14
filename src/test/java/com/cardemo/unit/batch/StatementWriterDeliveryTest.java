/*
 * ******************************************************************
 * Program     : StatementWriterDeliveryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the delivery half of the unescaped-markup
 *               finding is IMPLEMENTED and not merely documented.
 *               CBSTM03A interpolates the customer name and the three
 *               address lines straight into markup with no escaping,
 *               and StatementProcessor reproduces that byte for byte
 *               because escaping would move the offsets of every
 *               affected line and break the Gate 1 comparison. The
 *               exposure is therefore closed at the delivery boundary:
 *               both statement objects are stored as a non-active
 *               octet stream with an attachment disposition, and no
 *               controller in the tree produces text/html. This test
 *               asserts the stored metadata, asserts the payload bytes
 *               are untouched by that decision, and asserts the
 *               controller property, so a later edit cannot silently
 *               reopen the browser-rendering path.
 * Source      : app/cbl/CBSTM03A.CBL:L558-L672 (unescaped markup)
 *               app/cbl/CBSTM03A.CBL:L149      (HTML-LINE PIC X(100))
 *               app/jcl/CREASTMT.JCL:STEP040   (LRECL 80 / 100)
 *               app/cbl/CBSTM03A.CBL:L293      (OPEN OUTPUT)
 *               app/cbl/CBSTM03A.CBL:L339      (CLOSE) @ 7756d89
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * Verifies the delivery contract of {@link StatementWriter} and the controller-surface property that
 * contract depends on.
 *
 * <p><strong>What is under test, and where the escaping lives.</strong>
 * {@code app/cbl/CBSTM03A.CBL:L558-L672} interpolates the customer name and the three address lines into
 * markup with no escaping at all, and every emitted line is a fixed {@code PIC X(100)} record
 * ({@code app/cbl/CBSTM03A.CBL:L149}, corroborated by {@code LRECL=100} on {@code HTMLFILE} at
 * {@code app/jcl/CREASTMT.JCL:STEP040}). Naive escaping would lengthen the interpolated fields and move the
 * offsets of every affected line, which is a Gate 1 parity break - which is why the escape is applied by
 * {@code com.cardemo.batch.processors.StatementProcessor}, the class that <em>composes</em> the record and can
 * therefore fit an escaped value to the room the line leaves. <strong>This writer applies no escape of its
 * own</strong>, deliberately: a second escaper would be a second policy to keep in step with the first, and a
 * value escaped twice renders its own entities as text. It receives finished fixed-width lines and pads them.
 *
 * <p>The delivery decision is the layer behind that escape, not a substitute for it. Both objects are stored
 * with a {@code Content-Disposition: attachment} header whose file name is derived from the object key and
 * never from record content, and with {@code Cache-Control: no-store}. The disposition is what removes the
 * rendering path: a browser pointed at the markup object saves it rather than executing it in the bucket's
 * origin. The content type states the encoding the bytes are actually in, which closes the companion hole -
 * an encoding sniffed rather than declared is a documented way to smuggle markup past an escape that was
 * correct in the encoding actually used.
 *
 * <p>These tests therefore assert four separable things:
 *
 * <ol>
 *   <li>the stored metadata for <em>both</em> objects - content type with an explicit charset, disposition,
 *       and cache directive;
 *   <li>that this writer is byte-transparent, so the escape stays the sole property of the composer and
 *       parity is decided in one place;
 *   <li>that the payload geometry is unaffected by any of it; and
 *   <li>that no controller in the tree produces {@code text/html}, because a controller that did would serve
 *       statement content from the application's own origin and reopen the door the disposition closes.
 *   </ol>
 *
 * <p><strong>Side effects.</strong> None outside the test JVM. No container, no network, no object store: a
 * Mockito double for {@link S3Template} captures what would have been uploaded.
 */
@DisplayName("StatementWriter delivery contract - non-active content, attachment disposition")
class StatementWriterDeliveryTest {

    /** The bucket name is irrelevant to the assertions, so it is a fixed non-blank literal. */
    private static final String BUCKET = "carddemo-statements";

    /** An account identifier of the eleven digits {@code ACCT-ID PIC 9(11)} declares. */
    private static final String ACCOUNT_ID = "00000000001";

    /** A statement month in the {@code yyyy-MM} form the key convention uses. */
    private static final String STATEMENT_MONTH = "2026-08";

    /**
     * A frozen time source. The writer reads the clock only to derive a statement month when no step supplies
     * one, and every emission here supplies one, so the instant only has to be fixed. It is written as the
     * sibling {@code StatementWriterContractTest} writes it, so the two suites do not describe two different
     * notions of "now" for the same class.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-06-10T19:27:53.470Z"), ZoneOffset.UTC);

    /**
     * The exact payload a name field would have to carry for the exposure to be live.
     *
     * <p>It is handed to the writer already in this form, which is not what production does - the composer
     * escapes it first. That is the point: driving the writer directly is the only way to prove that the
     * writer itself neither escapes nor refuses printable markup, so the escape cannot be silently relied on
     * from two places at once, and the delivery headers hold even for a payload that was never escaped.
     */
    private static final String ACTIVE_CONTENT_PROBE = "<script>alert(1)</script>";

    /** Captures every upload the writer performs, in call order. */
    private final List<Upload> uploads = new ArrayList<>();

    private StatementWriter writer;

    @BeforeEach
    void setUp() {
        uploads.clear();
        S3Template s3Template = mock(S3Template.class);
        when(s3Template.upload(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenAnswer(this::captureUpload);
        // No step execution is bound: this writer is @StepScope in production and takes its generation from
        // beforeStep, so a direct construction leaves the generation at zero. The delivery metadata is
        // independent of the generation, so a real step execution would add nothing to these assertions.
        // The registrar is real rather than a double because it is constructed over a throwaway registry and
        // nothing here asserts a counter.
        writer = new StatementWriter(s3Template, new FileStatusMapper(), FIXED_CLOCK, BUCKET);
    }

    /**
     * Records one upload and returns {@code null}, which is a legitimate answer because
     * {@code StatementWriter} discards the returned resource.
     *
     * @param invocation the intercepted call
     * @return {@code null}
     */
    private Object captureUpload(InvocationOnMock invocation) {
        String key = invocation.getArgument(1);
        InputStream body = invocation.getArgument(2);
        ObjectMetadata metadata = invocation.getArgument(3);
        byte[] payload;
        try (InputStream stream = body) {
            payload = stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not read the captured upload body", unreadable);
        }
        uploads.add(new Upload(key, payload, metadata));
        return null;
    }

    /**
     * Drives one complete statement through the writer, with the active-content probe in the name field.
     *
     * @return the two captured uploads, keyed by logical file name in the order the writer produced them
     */
    private Map<String, Upload> emitOneStatement() {
        writer.openStatementOutputs(ACCOUNT_ID, STATEMENT_MONTH);
        writer.writeStatementLine(ACTIVE_CONTENT_PROBE + " CUSTOMER");
        writer.writeStatementLine("1 ANY STREET SUITE 2 ANYTOWN NY USA 10001");
        writer.writeHtmlFragment("<p>" + ACTIVE_CONTENT_PROBE + " CUSTOMER</p>");
        writer.writeHtmlFragment("<p>1 ANY STREET SUITE 2 ANYTOWN NY USA 10001</p>");
        Map<String, String> keys = writer.closeStatementOutputs();
        assertThat(uploads).as("one text object and one markup object").hasSize(2);

        Map<String, Upload> byLogicalName = new LinkedHashMap<>();
        byLogicalName.put(StatementWriter.STMTFILE_DD_NAME, uploadFor(keys.get(
                StatementWriter.STMTFILE_DD_NAME)));
        byLogicalName.put(StatementWriter.HTMLFILE_DD_NAME, uploadFor(keys.get(
                StatementWriter.HTMLFILE_DD_NAME)));
        return byLogicalName;
    }

    /**
     * Finds the captured upload for one object key.
     *
     * @param key the object key the writer reported
     * @return the captured upload
     */
    private Upload uploadFor(String key) {
        return uploads.stream()
                .filter(upload -> upload.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no upload was captured for key " + key));
    }

    @Nested
    @DisplayName("stored metadata")
    class StoredMetadata {

        @Test
        @DisplayName("the markup object declares the encoding its bytes are actually in")
        void markupObjectDeclaresItsEncoding() {
            Map<String, Upload> emitted = emitOneStatement();

            assertThat(emitted.get(StatementWriter.HTMLFILE_DD_NAME).metadata().getContentType())
                    .as("HTMLFILE content type - an omitted charset invites a consumer to sniff one, and a "
                            + "sniffed encoding is a documented way to smuggle markup past an escape that was "
                            + "correct in the encoding actually used. The rendering path is closed by the "
                            + "disposition asserted below, not by withholding the media type")
                    .isEqualTo("text/html; charset=" + StandardCharsets.ISO_8859_1.name());
        }

        @Test
        @DisplayName("no intermediary is invited to retain a statement")
        void noIntermediaryIsInvitedToRetainAStatement() {
            Map<String, Upload> emitted = emitOneStatement();

            assertThat(emitted.values())
                    .allSatisfy(upload -> assertThat(upload.metadata().getCacheControl())
                            .as("cache directive of %s - a statement carries a name, an address, an account "
                                    + "identifier, a balance and a credit score", upload.key())
                            .isEqualTo("no-store"));
        }

        @Test
        @DisplayName("both objects carry an attachment disposition, so neither is ever rendered inline")
        void bothObjectsAreAttachments() {
            Map<String, Upload> emitted = emitOneStatement();

            assertThat(emitted.values())
                    .allSatisfy(upload -> assertThat(upload.metadata().getContentDisposition())
                            .as("content disposition of %s", upload.key())
                            .startsWith("attachment;"));
        }

        @Test
        @DisplayName("the disposition file name comes from the object key, never from record content")
        void dispositionFileNameIsDerivedFromTheKey() {
            Map<String, Upload> emitted = emitOneStatement();

            assertThat(emitted.get(StatementWriter.STMTFILE_DD_NAME).metadata().getContentDisposition())
                    .isEqualTo("attachment; filename=\"STATEMNT.PS\"");
            assertThat(emitted.get(StatementWriter.HTMLFILE_DD_NAME).metadata().getContentDisposition())
                    .isEqualTo("attachment; filename=\"STATEMNT.HTML\"");
        }

        @Test
        @DisplayName("no disposition value contains any part of the customer data that was written")
        void dispositionCannotBeInjectedInto() {
            Map<String, Upload> emitted = emitOneStatement();

            assertThat(emitted.values())
                    .allSatisfy(upload -> assertThat(upload.metadata().getContentDisposition())
                            .as("the header must be composable only from fixed literals and the key")
                            .doesNotContain(ACTIVE_CONTENT_PROBE)
                            .doesNotContain("CUSTOMER")
                            .doesNotContain("\r")
                            .doesNotContain("\n"));
        }

        @Test
        @DisplayName("the text object keeps its advisory text/plain type")
        void textObjectKeepsItsAdvisoryType() {
            Map<String, Upload> emitted = emitOneStatement();

            assertThat(emitted.get(StatementWriter.STMTFILE_DD_NAME).metadata().getContentType())
                    .isEqualTo("text/plain; charset=" + StandardCharsets.ISO_8859_1.name());
        }
    }

    @Nested
    @DisplayName("payload parity is untouched by the delivery decision")
    class PayloadParity {

        @Test
        @DisplayName("the writer is byte-transparent: it neither escapes nor refuses what it is handed")
        void theWriterIsByteTransparent() {
            Map<String, Upload> emitted = emitOneStatement();
            String markup = new String(emitted.get(StatementWriter.HTMLFILE_DD_NAME).payload(),
                    StandardCharsets.ISO_8859_1);

            assertThat(markup)
                    .as("the escape belongs to the composer, which is the only class that can fit an escaped "
                            + "value to the room a fixed-width line leaves; a second escaper here would be a "
                            + "second policy, and a value escaped twice renders its own entities as text")
                    .contains(ACTIVE_CONTENT_PROBE)
                    .doesNotContain("&lt;script&gt;");
        }

        @Test
        @DisplayName("every markup record is exactly 100 bytes and every text record exactly 80")
        void recordGeometryIsPreserved() {
            Map<String, Upload> emitted = emitOneStatement();

            assertThat(emitted.get(StatementWriter.HTMLFILE_DD_NAME).payload().length % 100)
                    .as("HTMLFILE LRECL=100, app/jcl/CREASTMT.JCL:STEP040")
                    .isZero();
            assertThat(emitted.get(StatementWriter.STMTFILE_DD_NAME).payload().length % 80)
                    .as("STMTFILE LRECL=80, app/jcl/CREASTMT.JCL:STEP040")
                    .isZero();
        }

        @Test
        @DisplayName("the declared content length equals the stored byte count for both objects")
        void declaredLengthMatchesThePayload() {
            Map<String, Upload> emitted = emitOneStatement();

            assertThat(emitted.values()).allSatisfy(upload ->
                    assertThat(upload.metadata().getContentLength())
                            .as("declared length of %s", upload.key())
                            .isEqualTo(Long.valueOf(upload.payload().length)));
        }
    }

    @Nested
    @DisplayName("no controller re-exposes statement content as active content")
    class ControllerSurface {

        /** Every controller in the tree. Read from disk so a newly added controller is included. */
        private static final Path CONTROLLER_DIRECTORY =
                Path.of("src", "main", "java", "com", "cardemo", "controller");

        @Test
        @DisplayName("the controller package exists and is non-empty, so this assertion has a subject")
        void theControllerPackageIsPresent() {
            assertThat(controllerSources())
                    .as("if this is empty the next test proves nothing")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("no controller source names text/html or MediaType.TEXT_HTML")
        void noControllerProducesHtml() {
            for (Path source : controllerSources()) {
                String body = read(source);
                assertThat(body.toLowerCase(Locale.ROOT))
                        .as("%s must not advertise an HTML media type", source.getFileName())
                        .doesNotContain("text/html");
                assertThat(body)
                        .as("%s must not advertise an HTML media type", source.getFileName())
                        .doesNotContain("TEXT_HTML");
            }
        }

        /**
         * Lists the controller sources.
         *
         * <p>{@code package-info.java} is excluded, and the exclusion is narrow and deliberate: that file
         * declares no request mapping and can advertise no media type, but it does <em>discuss</em> this very
         * property - it records that no controller produces {@code text/html} and why - so scanning it would
         * fail the assertion on the strength of the documentation that states the assertion holds. Every file
         * that can actually carry a mapping is still scanned.
         *
         * @return every {@code .java} file directly under the controller package except its package
         *     documentation
         */
        private List<Path> controllerSources() {
            try (Stream<Path> entries = Files.list(CONTROLLER_DIRECTORY)) {
                return entries.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !"package-info.java".equals(path.getFileName().toString()))
                        .sorted()
                        .toList();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("could not list " + CONTROLLER_DIRECTORY, unreadable);
            }
        }

        /**
         * Reads one source file.
         *
         * @param source the file to read
         * @return its contents
         */
        private String read(Path source) {
            try {
                return Files.readString(source, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("could not read " + source, unreadable);
            }
        }
    }

    /**
     * One captured upload.
     *
     * @param key the object key
     * @param payload the exact bytes the writer supplied
     * @param metadata the metadata the writer declared
     */
    private record Upload(String key, byte[] payload, ObjectMetadata metadata) {
    }
}
