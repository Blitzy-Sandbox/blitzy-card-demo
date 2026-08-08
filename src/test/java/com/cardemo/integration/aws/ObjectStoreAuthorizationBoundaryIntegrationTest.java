/*
 * ******************************************************************
 * Program     : ObjectStoreAuthorizationBoundaryIntegrationTest.java
 * Application : CardDemo
 * Type        : Java 25 / JUnit 5 / Testcontainers integration test
 * Function    : Establishes, by measurement rather than assumption,
 *               what the emulated object store does and does not
 *               enforce against a principal that is not the
 *               application's - and thereby pins the reason the
 *               operative control for that store is network
 *               containment.
 * Capability  : NEW - additive authorization evidence. The frozen
 *               corpus has no object store: app/jcl/DEFGDGB.jcl
 *               defines generation data groups whose access control
 *               was RACF's and the catalogue's, neither of which is
 *               reproduced here. There is therefore no COBOL paragraph
 *               to cite; what is asserted is the Rule 1 Clause D
 *               least-privilege property and, per Clause F, an honest
 *               statement of what could not be verified.
 * Source      : app/jcl/DEFGDGB.jcl @ 7756d89 (the seven GDG bases the
 *               three buckets replace)
 * Source      : app/jcl/CREASTMT.JCL:L72,L87 @ 7756d89 (STMTFILE and
 *               HTMLFILE - the statement outputs whose bucket carries
 *               customer-derived content)
 * Source      : app/cpy/CVCUS01Y.cpy @ 7756d89 (the 500-byte customer
 *               record, including the nine-digit government
 *               identifier, that reaches statements)
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
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * F-S13. Measures the authorization boundary around the emulated object store, including the statements
 * bucket specifically.
 *
 * <h2>What this test is for, and why it is written as a measurement</h2>
 *
 * <p>The finding is that the emulator is host-published while holding personal data and while IAM
 * enforcement is unset. Remediating it required answering a question that no amount of configuration reading
 * settles: <em>does this emulator actually enforce anything?</em> Guessing either way would have been
 * negligent - assuming enforcement would have justified leaving the port open, and assuming none would have
 * justified skipping the setting.
 *
 * <p>So the question was measured, and this class is the measurement kept executable. It asserts three
 * things:
 *
 * <ol>
 *   <li>the application's own principal can read and write its own buckets, so nothing below is an artefact
 *       of a broken harness;</li>
 *   <li>requests that are structurally invalid - an unprovisioned bucket, a key that does not exist - are
 *       refused, so the store is not simply answering yes to everything; and</li>
 *   <li><strong>a foreign principal is NOT isolated by the emulator.</strong> This is the characterisation
 *       that matters, and it is asserted deliberately rather than hidden.</li>
 * </ol>
 *
 * <h2>The measured limitation, stated plainly</h2>
 *
 * <p>Against {@code localstack/localstack:4.14.0} - the community image this project pins - policy
 * enforcement, cross-account isolation and bucket policies are <strong>not implemented</strong>. An arbitrary
 * access key reads, writes and deletes another principal's objects; a cross-account key receives a successful
 * bucket probe; and an explicit deny policy is accepted by the API and then ignored. Setting
 * {@code ENFORCE_IAM} does not change this, and {@code docker-compose.yml} says so where it sets it.
 *
 * <p><strong>Therefore the operative control is network containment, not authorization.</strong>
 * {@code docker-compose.yml} binds the emulator's edge port to the loopback interface through
 * {@code CARDDEMO_BIND_ADDRESS}. That is why the bind address is documented there as the security boundary
 * rather than as a convenience, and this class is the evidence for that reasoning.
 *
 * <h2>Why asserting insecure behaviour is the right thing here</h2>
 *
 * <p>A test that asserted isolation would fail, and a test that skipped the question would let a future
 * reader assume isolation exists. This one records reality and <strong>fails if reality improves</strong>:
 * should a licensed or later emulator begin enforcing, {@link ForeignPrincipalIsNotIsolated} breaks, and the
 * correct response is to strengthen the assertion and re-evaluate whether the loopback bind is still the only
 * control - not to delete the test. The failure message says exactly that.
 *
 * <p>Nothing here weakens the application's own guards. {@code com.cardemo.config.AwsConfig} refuses a
 * non-allowlisted endpoint and refuses anything but a static credentials provider, and
 * {@code com.cardemo.unit.config.AwsConfigSecurityGuardTest} covers both exhaustively. This class is about
 * the store, not about the client.
 */
@DisplayName("F-S13: the object store's authorization boundary, measured rather than assumed")
class ObjectStoreAuthorizationBoundaryIntegrationTest extends AbstractAwsIntegrationTest {

    /**
     * A principal that is not the application's.
     *
     * <p>Deliberately not a plausible key: it must be recognisable at a glance as test-only material, and it
     * must not begin {@code AKIA} or {@code ASIA} - {@code AwsConfig} refuses those outright, and a value
     * that could be mistaken for a live key has no place in a source file.
     */
    private static final String FOREIGN_ACCESS_KEY = "foreign-principal-not-a-real-key";

    /** The foreign principal's secret. Not a secret in any real sense; see above. */
    private static final String FOREIGN_SECRET_KEY = "foreign-principal-not-a-real-secret";

    /** A bucket no {@code localstack-init/init-aws.sh} run provisions and no application property names. */
    private static final String UNPROVISIONED_BUCKET = "carddemo-not-a-provisioned-bucket";

    /** Object body standing in for a generated statement; short, and identifiable in a failure message. */
    private static final String STATEMENT_BODY = "STATEMENT CONTENT STANDING IN FOR CUSTOMER-DERIVED OUTPUT";

    /** The context's environment, used only to recover the emulator endpoint the harness registered. */
    @Autowired
    private Environment environment;

    /**
     * Builds a client authenticated as a principal that is not the application's, against the same endpoint.
     *
     * <p>The endpoint is read back from the property the harness registered rather than reconstructed, so
     * this client addresses exactly the emulator the application addresses - which is the whole point: a
     * different endpoint would prove nothing about isolation.
     *
     * @return an open client the caller must close, never {@code null}
     */
    private S3Client foreignPrincipalClient() {
        final String endpoint = environment.getProperty("spring.cloud.aws.s3.endpoint");
        assertThat(endpoint)
                .as("the harness registers the emulator endpoint; without it this test would silently "
                        + "address a different store and prove nothing")
                .isNotBlank();
        final String region = environment.getProperty("spring.cloud.aws.region.static", "us-east-1");

        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FOREIGN_ACCESS_KEY, FOREIGN_SECRET_KEY)))
                .build();
    }

    /**
     * Writes one object to the statements bucket as the application's own principal.
     *
     * @param key the object key; must not be {@code null}
     */
    private void putStatementObject(final String key) {
        s3Client().putObject(
                request -> request.bucket(statementsBucket()).key(key),
                RequestBody.fromString(STATEMENT_BODY, StandardCharsets.UTF_8));
    }

    /**
     * The positive control. Everything else in this class is only meaningful if this passes.
     */
    @Nested
    @DisplayName("the application's own principal reaches its own buckets")
    class ApplicationPrincipalIsAdmitted {

        @Test
        @DisplayName("a statement object written by the application is readable by the application")
        void applicationReadsItsOwnStatementObject() {
            final String key = scopedResourceName("own-principal") + "/statement.txt";
            putStatementObject(key);

            assertThat(s3Client().getObjectAsBytes(
                            request -> request.bucket(statementsBucket()).key(key))
                    .asString(StandardCharsets.UTF_8))
                    .as("the harness must be able to read what it just wrote, or every negative assertion "
                            + "below would be indistinguishable from a broken fixture")
                    .isEqualTo(STATEMENT_BODY);
        }

        @Test
        @DisplayName("all three provisioned buckets exist and are addressable")
        void allThreeBucketsExist() {
            for (final String bucket :
                    new String[] {batchInputBucket(), batchOutputBucket(), statementsBucket()}) {
                assertThat(catchThrowable(() -> s3Client().headBucket(r -> r.bucket(bucket))))
                        .as("bucket '%s' is named by an application property and provisioned by "
                                + "localstack-init/init-aws.sh, so it must resolve", bucket)
                        .isNull();
            }
        }
    }

    /**
     * The store is not simply permissive about everything: structurally invalid requests are refused. This
     * bounds the characterisation below - it shows the emulator does reject, so its failure to isolate is a
     * specific gap rather than a blanket absence of checking.
     */
    @Nested
    @DisplayName("structurally invalid requests are refused, so the store is not answering yes to everything")
    class InvalidRequestsAreRefused {

        @Test
        @DisplayName("an unprovisioned bucket is refused, to the application's own principal included")
        void unprovisionedBucketIsRefused() {
            assertThat(catchThrowable(() ->
                    s3Client().headBucket(request -> request.bucket(UNPROVISIONED_BUCKET))))
                    .as("a bucket nothing provisions must not resolve; if it did, the three-bucket surface "
                            + "would be unbounded and localstack-init/init-aws.sh would prove nothing")
                    .isInstanceOf(S3Exception.class);
        }

        @Test
        @DisplayName("a key that was never written is refused rather than answered with empty content")
        void absentKeyIsRefused() {
            assertThat(catchThrowable(() -> s3Client().getObjectAsBytes(request -> request
                    .bucket(statementsBucket())
                    .key(scopedResourceName("never-written") + "/absent.txt"))))
                    .as("an absent key must fail rather than yield an empty body, because the batch load "
                            + "paths distinguish 'no generation yet' from 'an empty generation'")
                    .isInstanceOf(S3Exception.class);
        }

        @Test
        @DisplayName("the foreign principal is refused on an unprovisioned bucket too")
        void foreignPrincipalIsRefusedOnAnUnprovisionedBucket() {
            try (S3Client foreign = foreignPrincipalClient()) {
                final Throwable refusal = catchThrowable(() ->
                        foreign.headBucket(request -> request.bucket(UNPROVISIONED_BUCKET)));
                assertThat(refusal)
                        .as("existence, not authorization, is what is being checked here - and it is checked "
                                + "for every principal alike")
                        .isInstanceOfAny(NoSuchBucketException.class, S3Exception.class);
            }
        }
    }

    /**
     * The characterisation that justifies the network control.
     *
     * <p><strong>These assertions deliberately record insecure behaviour.</strong> Read the class
     * documentation before changing them: if one of them starts failing, the emulator has begun enforcing,
     * which is good news that must be acted on rather than suppressed.
     */
    @Nested
    @DisplayName("a foreign principal is NOT isolated - which is why the edge port is loopback-bound")
    class ForeignPrincipalIsNotIsolated {

        /**
         * The message every assertion in this group shares, so that a failure explains itself without the
         * reader having to find this file's documentation first.
         */
        private static final String IMPROVEMENT_NOTICE =
                "This assertion records a MEASURED LIMITATION of localstack/localstack:4.14.0 community "
                        + "edition, which implements no policy enforcement, no cross-account isolation and "
                        + "no bucket policies. If this test now FAILS, the emulator has started enforcing: "
                        + "that is an improvement. Strengthen this assertion to require the refusal, and "
                        + "re-evaluate whether the loopback bind in docker-compose.yml is still the only "
                        + "control protecting these objects. Do NOT delete the test, and do NOT relax the "
                        + "bind.";

        @Test
        @DisplayName("a foreign principal can READ a statement object it does not own")
        void foreignPrincipalReadsAStatementObject() {
            final String key = scopedResourceName("foreign-read") + "/statement.txt";
            putStatementObject(key);

            try (S3Client foreign = foreignPrincipalClient()) {
                final String read = foreign.getObjectAsBytes(
                                request -> request.bucket(statementsBucket()).key(key))
                        .asString(StandardCharsets.UTF_8);

                assertThat(read)
                        .as("The statements bucket is the one the finding names specifically, because "
                                + "app/jcl/CREASTMT.JCL:L72,L87 write customer-derived content into it. A "
                                + "principal with an arbitrary key read it in full. %s", IMPROVEMENT_NOTICE)
                        .isEqualTo(STATEMENT_BODY);
            }
        }

        @Test
        @DisplayName("a foreign principal can LIST the statements bucket it does not own")
        void foreignPrincipalListsTheStatementsBucket() {
            final String key = scopedResourceName("foreign-list") + "/statement.txt";
            putStatementObject(key);

            try (S3Client foreign = foreignPrincipalClient()) {
                assertThat(foreign.listObjectsV2(request -> request.bucket(statementsBucket())).contents())
                        .as("listing discloses the key space - and statement keys are structured by account "
                                + "and month, so the listing itself is disclosure even before any object is "
                                + "fetched. %s", IMPROVEMENT_NOTICE)
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("a foreign principal can DELETE a statement object it does not own")
        void foreignPrincipalDeletesAStatementObject() {
            final String key = scopedResourceName("foreign-delete") + "/statement.txt";
            putStatementObject(key);

            try (S3Client foreign = foreignPrincipalClient()) {
                foreign.deleteObject(request -> request.bucket(statementsBucket()).key(key));
            }

            assertThat(catchThrowable(() -> s3Client().getObjectAsBytes(
                    request -> request.bucket(statementsBucket()).key(key))))
                    .as("the object is gone, so the exposure is not read-only: an unisolated principal can "
                            + "destroy generated output as well as read it. %s", IMPROVEMENT_NOTICE)
                    .isInstanceOf(S3Exception.class);
        }

        @Test
        @DisplayName("the same absence of isolation holds for the batch input and output buckets")
        void foreignPrincipalReachesTheOtherTwoBuckets() {
            try (S3Client foreign = foreignPrincipalClient()) {
                for (final String bucket : new String[] {batchInputBucket(), batchOutputBucket()}) {
                    assertThat(catchThrowable(() -> foreign.listObjectsV2(r -> r.bucket(bucket))))
                            .as("the output bucket holds the reject records of app/cbl/CBTRN02C.cbl and the "
                                    + "transaction images, both of which carry card and account numbers, so "
                                    + "the gap is not confined to statements. Bucket '%s'. %s",
                                    bucket, IMPROVEMENT_NOTICE)
                            .isNull();
                }
            }
        }
    }

    /**
     * Finding M-11, severity High: what the emulator does not refuse, the application refuses.
     *
     * <p>The group above measures the gap - a principal with an arbitrary key can write into the batch input
     * bucket, which is where {@code app/jcl/POSTTRAN.jcl} reads {@code DALYTRAN} from. This group is the
     * closure: the same planted object, reached through the same emulator, is <b>refused by the reader</b>
     * before a record is parsed, and only an object vouched for with the application's own key is read.
     *
     * <p>These tests are the live counterpart of the unit-tier authenticity group. The unit tier proves the
     * rules with a stubbed store; this proves the same rules over real object metadata written by a real
     * client, which is the only place a mistake about how the store reports user metadata would show up.
     */
    @Nested
    @DisplayName("M-11: a planted input object is refused by the application, whatever the emulator allows")
    class PlantedInputIsRefusedByTheApplication {

        /** The writer identity a legitimate feed claims. Attribution, inside the authenticated manifest. */
        private static final String WRITER = "carddemo-fixture-feed";

        /**
         * Plants one object in the batch input bucket as the foreign principal, with the metadata given.
         *
         * @param key the object key
         * @param body the object content
         * @param metadata the user metadata to attach, possibly empty
         */
        private void plantInputObject(final String key, final String body,
                final Map<String, String> metadata) {

            try (S3Client foreign = foreignPrincipalClient()) {
                foreign.putObject(
                        request -> request.bucket(batchInputBucket()).key(key).metadata(metadata),
                        RequestBody.fromBytes(body.getBytes(StandardCharsets.ISO_8859_1)));
            }
        }

        /**
         * Builds a reader bound to the real emulator and to one planted object.
         *
         * <p>The repository collaborator is a mock because the {@code fixed-width} path never touches it -
         * asserted in the unit tier - so a database is not a prerequisite of this proof.
         *
         * @param key the object key to read
         * @return the reader, never {@code null}
         */
        private DailyTransactionReader readerFor(final String key) {
            return new DailyTransactionReader(Mockito.mock(DailyTransactionRepository.class), s3Template(),
                    new FileStatusMapper(), "fixed-width", 100, batchInputBucket(), key, applicationKey());
        }

        /**
         * Recovers the signing key the context is running with.
         *
         * @return the key, never blank
         */
        private String applicationKey() {
            final String key = environment.getProperty("carddemo.security.jwt.signing-key");
            assertThat(key)
                    .as("the authenticity key is derived from the application signing key, so a context "
                            + "without one could not prove anything here")
                    .isNotBlank();
            return key;
        }

        /**
         * A single valid 350-character {@code DALYTRAN} image, taken from the shipped fixture.
         *
         * <p>Returned with <b>no record separator</b>, which is the geometry the reader under test accepts
         * and the geometry this application's fixed-width writers emit.
         * {@code app/cbl/CBTRN02C.cbl:L66-L69} gives {@code DALYTRAN} one fixed 350-character record group
         * with no delimiter, so {@code DailyTransactionReader} refuses a trailing {@code LF} or {@code CR} as
         * proof that an object was not written by this application. An earlier form of this helper appended
         * {@code '\n'} because the frozen ASCII fixture carries one per line, and that made the positive
         * control below fail on record geometry before it could reach the authenticity assertion it exists to
         * make. The fixture's own line terminators belong to the line-oriented readers - the seed migration
         * and the test fixture loader - and not to an object planted on the store.
         *
         * @return exactly 350 characters, with no terminator
         * @throws IOException if the frozen fixture cannot be read
         */
        private String oneFixtureRecord() throws IOException {
            final String fixture = Files.readString(Path.of("app/data/ASCII/dailytran.txt"),
                    StandardCharsets.ISO_8859_1);
            return fixture.substring(0, 350);
        }

        /**
         * The complete, valid envelope for a body at a key.
         *
         * @param key the object key the envelope is bound to
         * @param body the content the envelope vouches for
         * @return the metadata to attach
         */
        private Map<String, String> validEnvelope(final String key, final String body) {
            final byte[] content = body.getBytes(StandardCharsets.ISO_8859_1);
            final String digest = DailyTransactionReader.InputObjectEnvelope.hexadecimal(
                    DailyTransactionReader.InputObjectEnvelope.newDigest().digest(content));
            return Map.of(
                    DailyTransactionReader.InputObjectEnvelope.METADATA_WRITER, WRITER,
                    DailyTransactionReader.InputObjectEnvelope.METADATA_CONTENT_SHA256, digest,
                    DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE,
                    DailyTransactionReader.InputObjectEnvelope.sign(batchInputBucket(), key, content.length,
                            WRITER, digest, applicationKey()));
        }

        @Test
        @DisplayName("an object the foreign principal planted with no envelope is refused, and no record is "
                + "parsed")
        void aPlantedUnsignedObjectIsRefused() throws IOException {
            final String key = scopedResourceName("m11-unsigned") + "/dailytran.txt";
            plantInputObject(key, oneFixtureRecord(), Map.of());
            final DailyTransactionReader reader = readerFor(key);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("the emulator admitted the write - the group above measures that - so the "
                            + "application is the control that has to refuse the read")
                    .isThrownBy(() -> reader.open(new ExecutionContext()))
                    .satisfies(refusal -> assertThat(refusal.getAbendReason())
                            .isEqualTo("DALYTRAN INPUT NOT AUTHENTIC"));

            s3Client().deleteObject(request -> request.bucket(batchInputBucket()).key(key));
        }

        @Test
        @DisplayName("the same object, vouched for with the application's own key, is read")
        void aVouchedForObjectIsRead() throws IOException {
            final String key = scopedResourceName("m11-signed") + "/dailytran.txt";
            final String body = oneFixtureRecord();
            plantInputObject(key, body, validEnvelope(key, body));
            final DailyTransactionReader reader = readerFor(key);

            reader.open(new ExecutionContext());
            final DailyTransaction first = reader.read();
            reader.close();

            assertThat(first)
                    .as("the refusal above must be the envelope's absence and not some other failure of this "
                            + "harness, so the positive control reads the same bytes through the same path")
                    .isNotNull();
            assertThat(first.getTransactionId()).isNotBlank();

            s3Client().deleteObject(request -> request.bucket(batchInputBucket()).key(key));
        }

        @Test
        @DisplayName("a body swapped under a valid envelope is refused, which a signature check alone would "
                + "have accepted")
        void aBodySwapUnderAValidEnvelopeIsRefused() throws IOException {
            final String key = scopedResourceName("m11-swapped") + "/dailytran.txt";
            final String vouchedFor = oneFixtureRecord();
            // Same length, different content: the manifest is genuinely signed and still describes 351 bytes,
            // so only the re-measured digest can tell the difference.
            final String swapped = "9".repeat(vouchedFor.length() - 1) + '\n';
            plantInputObject(key, swapped, validEnvelope(key, vouchedFor));
            final DailyTransactionReader reader = readerFor(key);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()))
                    .satisfies(refusal -> assertThat(refusal.getAbendReason())
                            .isEqualTo("DALYTRAN INPUT NOT AUTHENTIC"));

            s3Client().deleteObject(request -> request.bucket(batchInputBucket()).key(key));
        }
    }
}
