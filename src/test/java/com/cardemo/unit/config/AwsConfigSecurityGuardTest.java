/*
 * ****************************************************************************
 * Program     : AwsConfigSecurityGuardTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies the two controls AwsConfig exists to hold. First, that
 *               no configuration can point a client at anything but the approved
 *               local emulator: the endpoint allowlist is proved against the same
 *               hostile and legitimate corpus that InitAwsScriptGuardTest drives
 *               through localstack-init/init-aws.sh, and the two allowlists are
 *               proved identical so they cannot drift. Second, that every client
 *               the library builds carries a per-attempt deadline, a whole-call
 *               deadline and an explicit retry policy, and that the resolved
 *               credentials provider can never be an ambient chain.
 * Source      : app/csd/CARDDEMO.CSD:L499-L505 (DEFINE TDQUEUE(JOBS))
 *               + app/jcl/DEFGDGB.jcl + app/jcl/DALYREJS.jcl
 *                 + app/jcl/REPTFILE.jcl (the 7 GDG bases)
 *               + app/cbl/CORPT00C.cbl:L517-L523 (EXEC CICS WRITEQ TD)
 *               @ 7756d89
 * ****************************************************************************
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
 * ****************************************************************************
 */
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.config.AwsConfig;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

/**
 * Verifies the cloud-integration controls in {@link AwsConfig}.
 *
 * <h2>What this test is for</h2>
 *
 * <p>Two review findings shaped the class under test, and this file is the evidence for both.
 *
 * <p><strong>High - live AWS was reachable.</strong> An earlier revision declared no endpoint override and no
 * credential in the base profile, which reads as caution and is the opposite: an absent override is the SDK's
 * instruction to resolve the real regional service edge, and an absent credential is the library's instruction
 * to fall back to the ambient provider chain. Both are now mandatory, and {@link AwsConfig} refuses to let the
 * context refresh on an endpoint outside a closed allowlist or on any credentials provider that is not a static
 * one. This file proves the refusals, and proves the acceptances too - an allowlist that refuses everything is
 * not a fix.
 *
 * <p><strong>Medium - no AWS call had a deadline.</strong> The SDK bounds the retry count, not elapsed time,
 * and the report-job publisher blocks on its future with no timeout of its own, so a degraded endpoint could
 * hold an online request thread or a batch step thread indefinitely. This file proves that each of the three
 * customizer beans lands a per-attempt deadline, a whole-call deadline and an explicit attempt count on the
 * real builder the library would hand it, and that doing so does not discard the configuration the library
 * applied first.
 *
 * <h2>The corpus is deliberately shared with the shell guard</h2>
 *
 * <p>The endpoint cases below are the same ones
 * {@code com.cardemo.unit.infrastructure.InitAwsScriptGuardTest} drives through
 * {@code localstack-init/init-aws.sh}. Two guards judge the same variable - the script before it provisions,
 * this class before the context refreshes - and two guards that disagree about what is acceptable are worse
 * than one, because a value that passes provisioning and then fails startup, or the reverse, is diagnosed as a
 * defect in whichever ran second. {@link AllowlistParity} therefore reads the script and asserts the two host
 * allowlists are identical, so neither can be widened alone.
 *
 * <h2>How to run</h2>
 *
 * <p>{@code ./mvnw -B -ntp test -Dtest=AwsConfigSecurityGuardTest}, or the whole tier with
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true test}. No container, no network and no environment
 * variable is required: every case is a constructor call or a builder call.
 *
 * <h2>Thread safety and side effects</h2>
 *
 * <p>Nothing here is shared between tests and nothing reaches the network. The builders are constructed and
 * inspected, never built into clients, so no HTTP client, connection pool or thread is created. The one file
 * read is the provisioning script, read once per test that needs it, read-only.
 */
@DisplayName("AwsConfig: the endpoint allowlist, the credential assertion and the call deadlines")
class AwsConfigSecurityGuardTest {

    /** The provisioning script whose allowlist this class must not drift from. */
    private static final Path INIT_SCRIPT = Path.of("localstack-init", "init-aws.sh");

    /** A valid, allowlisted endpoint used wherever the endpoint is not the thing under test. */
    private static final String VALID_ENDPOINT = "http://localhost:4566";

    /**
     * The per-attempt deadline the class under test must apply.
     *
     * <p>Ten seconds, matching {@code AwsConfig.API_CALL_ATTEMPT_TIMEOUT_SECONDS}. This constant briefly read
     * five, from a parallel remediation that chose its own budget; neither number is a service-level
     * objective, the frozen corpus publishes none, and every other assertion in this repository deliberately
     * pins only the invariant that the per-attempt deadline is present and strictly below the whole-call one.
     * The value that survives is therefore the one the implementation documents against the largest object
     * this application writes.
     */
    private static final Duration EXPECTED_ATTEMPT_DEADLINE = Duration.ofSeconds(10);

    /** The whole-call deadline the class under test must apply. See {@link #EXPECTED_ATTEMPT_DEADLINE}. */
    private static final Duration EXPECTED_CALL_DEADLINE = Duration.ofSeconds(30);

    /** The attempt count the class under test must apply. */
    private static final int EXPECTED_MAX_ATTEMPTS = 3;

    /** Header set before a customizer runs, to prove the customizer preserves rather than replaces. */
    private static final String SURVIVING_HEADER = "X-Carddemo-Preserved";

    /** Value of {@link #SURVIVING_HEADER}, which must still be present afterwards. */
    private static final String SURVIVING_HEADER_VALUE = "set-by-the-library-first";

    /**
     * Builds the class under test with every value valid except the endpoints supplied by the caller.
     *
     * <p>Twelve constructor arguments are a lot to repeat per case, and repeating them is how a case ends up
     * asserting something other than what it claims. Every value here is the one the {@code local} profile
     * would resolve, so a case that varies nothing constructs successfully and a case that varies one thing
     * isolates it.
     *
     * @param s3Endpoint     value for {@code spring.cloud.aws.s3.endpoint}
     * @param sqsEndpoint    value for {@code spring.cloud.aws.sqs.endpoint}
     * @param snsEndpoint    value for {@code spring.cloud.aws.sns.endpoint}
     * @param globalEndpoint value for {@code spring.cloud.aws.endpoint}, normally empty
     * @return the constructed configuration
     */
    private static AwsConfig configWithEndpoints(final String s3Endpoint, final String sqsEndpoint,
            final String snsEndpoint, final String globalEndpoint) {

        return new AwsConfig("us-east-1", "carddemo-batch-input", "carddemo-batch-output",
                "carddemo-statements", "carddemo-report-jobs.fifo", "carddemo-report-jobs",
                "carddemo-notifications", QueueNotFoundStrategy.FAIL,
                s3Endpoint, sqsEndpoint, snsEndpoint, globalEndpoint, "test", "test");
    }

    /**
     * Builds the class under test with the same endpoint on all three services and no global endpoint.
     *
     * @param endpoint the endpoint under test
     * @return the constructed configuration
     */
    private static AwsConfig configWithEndpoint(final String endpoint) {
        return configWithEndpoints(endpoint, endpoint, endpoint, "");
    }

    /**
     * Returns a configuration built with valid values throughout, for the tests that exercise a bean method.
     *
     * @return the constructed configuration
     */
    private static AwsConfig validConfig() {
        return configWithEndpoint(VALID_ENDPOINT);
    }

    /**
     * Returns the static credentials provider the base profile's key pair resolves to.
     *
     * @return a static provider carrying the conventional emulator pair, which is not a secret
     */
    private static AwsCredentialsProvider staticCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }

    /**
     * Pre-seeds a builder with an override configuration, exactly as the library does before any customizer
     * runs, so that a customizer which replaces rather than mutates can be detected.
     *
     * @return a configuration carrying one marker header, standing in for everything the library applies
     */
    private static ClientOverrideConfiguration libraryConfiguration() {
        return ClientOverrideConfiguration.builder()
                .putHeader(SURVIVING_HEADER, SURVIVING_HEADER_VALUE)
                .build();
    }

    /**
     * Asserts one client's resolved override configuration carries the whole policy and nothing was lost.
     *
     * @param configuration the configuration read back off the builder after the customizer ran
     */
    private static void assertPolicyApplied(final ClientOverrideConfiguration configuration) {
        assertThat(configuration.apiCallAttemptTimeout())
                .as("a per-attempt deadline is what stops one slow attempt from consuming the whole call "
                        + "budget, and ten seconds is generous for payloads whose largest is the "
                        + "105,300-byte daily-transaction fixture")
                .contains(EXPECTED_ATTEMPT_DEADLINE);

        assertThat(configuration.apiCallTimeout())
                .as("the whole-call deadline is the finding's actual remedy: the SDK bounds the retry COUNT "
                        + "and not elapsed time, so without this the wall-clock duration of a call is "
                        + "bounded by nothing the application controls")
                .contains(EXPECTED_CALL_DEADLINE);

        assertThat(configuration.retryMode())
                .as("THE THREE RETRY SETTERS ARE MUTUALLY EXCLUSIVE - a mode, a strategy instance and a "
                        + "configurator, each nulling the other two. Naming a mode and then adding a "
                        + "configurator for the attempt count silently discards the mode and leaves the "
                        + "SDK's own default, which is not the standard one, so the policy must name exactly "
                        + "one of the three and nothing else may clear it")
                .contains(RetryMode.STANDARD);

        assertThat(configuration.retryStrategy())
                .as("and it is the MODE that carries the choice on a builder. The mode overload records the "
                        + "mode and leaves this optional empty until the client is built, at which point the "
                        + "SDK resolves the strategy from it - so a test that demanded a strategy here would "
                        + "be demanding an object that does not exist yet, and would pass only if the policy "
                        + "had been written with the strategy-instance setter this rationale argues against")
                .isEmpty();

        // The properties of the strategy the mode resolves to, obtained through the SDK's own public
        // resolver rather than through the internal class the client build path uses.
        final RetryStrategy strategy = AwsRetryStrategy.forRetryMode(RetryMode.STANDARD);

        assertThat(strategy)
                .as("a standard strategy, and specifically the AWS one, because it adds the AWS retry "
                        + "conditions - throttling responses and clock skew - that a generic strategy does "
                        + "not know about")
                .isInstanceOf(StandardRetryStrategy.class);

        assertThat(strategy.maxAttempts())
                .as("three attempts - one initial plus two retries. Every operation this application "
                        + "performs is safe to retry: object writes are PutObject under a deterministic key, "
                        + "and the report publish is a FIFO SendMessage against a queue provisioned "
                        + "ContentBasedDeduplication=true at localstack-init/init-aws.sh:1261, so an "
                        + "identical retried request collapses instead of duplicating a job")
                .isEqualTo(EXPECTED_MAX_ATTEMPTS);

        assertThat(configuration.headers())
                .as("THE CUSTOMIZER MUST MUTATE, NEVER REPLACE. The Consumer form of "
                        + "overrideConfiguration builds a fresh configuration from empty, so a customizer "
                        + "written the convenient way silently discards what the library applied first - "
                        + "the user-agent prefix among it. This header stands in for that configuration")
                .containsEntry(SURVIVING_HEADER, List.of(SURVIVING_HEADER_VALUE));
    }

    /**
     * Reads the provisioning script.
     *
     * @return the script text
     * @throws IOException if the script cannot be read, which is itself a failure worth surfacing
     */
    private static String readInitScript() throws IOException {
        return Files.readString(INIT_SCRIPT, StandardCharsets.UTF_8);
    }

    // ==================================================================
    // 1 - Hostile endpoints are refused before the context can refresh.
    // ==================================================================

    @Nested
    @DisplayName("1. Every hostile endpoint shape aborts startup")
    class HostileEndpoints {

        @ParameterizedTest(name = "[{0}] is refused")
        @ValueSource(strings = {
            // Live service hosts, including the upper-cased spelling a case-sensitive denylist missed.
            "http://s3.AMAZONAWS.COM",
            "https://sqs.us-east-1.AmazonAWS.com",
            // The instance metadata service, over both address families.
            "http://169.254.169.254",
            "http://169.254.169.254:4566",
            "http://[fd00:ec2::254]:4566",
            // Arbitrary hosts, and two that merely look local.
            "http://evil.example.com:4566",
            "http://localhost.evil.com:4566",
            "http://s3.localhost.localstack.cloud:4566",
            // Userinfo: the host is what follows the LAST '@', which is why a denylist is unsound.
            "http://user:pw@localhost:4566@evil.example.com",
            "http://localhost@evil.example.com:4566",
            // An authority this guard will not decode.
            "http://%6c%6f%63alhost:4566",
            // A path, a query and a fragment are each a request-forgery primitive on an endpoint.
            "http://localhost:4566/../evil",
            "http://localhost:4566/_localstack",
            "http://localhost:4566?host=evil.example.com",
            "http://localhost:4566#evil.example.com",
            // Schemes that are not http(s), including the upper-cased spelling.
            "ftp://localhost:4566",
            "file:///etc/passwd",
            "HTTP://localhost:4566",
            "localhost:4566",
            // Structural nonsense.
            "http://:4566",
            "http://localhost:99999",
            "http://localhost:0",
            "http://localhost:abc",
            // Bytes that let a value read as local and resolve elsewhere, or split a log line.
            "http://localhost:4566\nhttp://evil.example.com",
            "http://local host:4566",
            "http://localhost:4566\\evil",
            // Shapes that clear the character screen and then fail to parse as a URL at all. The guard
            // parses rather than pattern-matches, so an unparsable value must be refused rather than
            // squeezed through a regex that happens to accept it.
            "http://local^host:4566",
            "http://[::1:4566",
        })
        @DisplayName("the endpoint is refused and the context cannot refresh")
        void hostileEndpointsAbortStartup(final String endpoint) {
            assertThatThrownBy(() -> configWithEndpoint(endpoint))
                    .as("a refusal must be terminal. There is deliberately no branch in the guard that "
                            + "accepts an unrecognised value, because an allowlist that falls through has "
                            + "the failure mode of a denylist")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint");
        }

        @Test
        @DisplayName("a refusal never quotes the rejected value, so userinfo cannot reach a log")
        void aRefusalNeverQuotesTheValue() {
            final String withCredential = "http://alice:s3cr3t@evil.example.com:4566";

            assertThatThrownBy(() -> configWithEndpoint(withCredential))
                    .isInstanceOf(IllegalStateException.class)
                    .as("this is stricter than the shell guard, which echoes the URL, and deliberately so: "
                            + "a Java diagnostic reaches a log aggregator, and userinfo in an error line is "
                            + "a credential in a log file")
                    .hasMessageNotContaining("s3cr3t")
                    .hasMessageNotContaining("alice")
                    .hasMessageNotContaining(withCredential);
        }

        @Test
        @DisplayName("a hostile GLOBAL endpoint is refused even though no profile sets that key")
        void aHostileGlobalEndpointIsRefused() {
            assertThatThrownBy(() -> configWithEndpoints(VALID_ENDPOINT, VALID_ENDPOINT, VALID_ENDPOINT,
                    "http://169.254.169.254"))
                    .as("the library applies the global key to every client as the fallback beneath the "
                            + "three per-service keys, so a guard that ignored it would leave one way in")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.endpoint");
        }

        @Test
        @DisplayName("each of the three services is judged independently")
        void everyServiceEndpointIsJudged() {
            assertThatThrownBy(() -> configWithEndpoints(VALID_ENDPOINT, "http://evil.example.com:4566",
                    VALID_ENDPOINT, ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.sqs.endpoint");

            assertThatThrownBy(() -> configWithEndpoints(VALID_ENDPOINT, VALID_ENDPOINT,
                    "http://evil.example.com:4566", ""))
                    .as("one valid endpoint must not vouch for another. The three are separate properties "
                            + "and a deployment can get exactly one of them wrong")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.sns.endpoint");
        }

        @ParameterizedTest(name = "an endpoint that is [{0}] is refused")
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank endpoint is refused, because a blank value out-ranks a profile default")
        void aBlankEndpointIsRefused(final String blank) {
            assertThatThrownBy(() -> configWithEndpoint(blank))
                    .as("an absent variable fails placeholder resolution before this constructor is "
                            + "reached, but a variable SET TO THE EMPTY STRING resolves successfully and "
                            + "even wins over a profile default, so the blank case is the one this check "
                            + "exists for")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint");
        }
    }

    // ==================================================================
    // 2 - Every documented spelling still starts. An allowlist that
    //     refuses everything is not a fix.
    // ==================================================================

    @Nested
    @DisplayName("2. Every documented endpoint spelling is accepted")
    class LegitimateEndpoints {

        @ParameterizedTest(name = "[{0}] is accepted")
        @ValueSource(strings = {
            "http://localhost:4566",
            "http://localhost:4566/",
            "http://LOCALHOST:4566",
            "http://localhost.:4566",
            "http://127.0.0.1:4566",
            "http://[::1]:4566",
            "http://localstack:4566",
            "http://localhost.localstack.cloud:4566",
            "http://carddemo-localstack:4566",
            "http://carddemo-localstack-6:4566",
            "http://carddemo-localstack-004:4566",
            "https://localhost:4566",
            "http://localhost:1",
            "http://localhost:65535",
        })
        @DisplayName("the configuration is constructed and startup proceeds")
        void documentedEndpointsAreAccepted(final String endpoint) {
            assertThatCode(() -> configWithEndpoint(endpoint))
                    .as("each spelling is one a developer, a sibling container or the documented setup "
                            + "sequence actually uses, so refusing any of them would break a working path "
                            + "and teach the next reader to widen the allowlist")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an unset global endpoint is the expected state and is not treated as a refusal")
        void anAbsentGlobalEndpointIsAccepted() {
            assertThatCode(() -> configWithEndpoints(VALID_ENDPOINT, VALID_ENDPOINT, VALID_ENDPOINT, ""))
                    .as("no profile sets the global key, so it is bound with an empty default. An empty "
                            + "value must mean 'not set' rather than 'set to something unacceptable'")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a Testcontainers-shaped endpoint on an ephemeral port is accepted")
        void aContainerMappedPortIsAccepted() {
            assertThatCode(() -> configWithEndpoint("http://localhost:32769"))
                    .as("the integration tier registers the emulator container's mapped port, which is "
                            + "ephemeral and unpredictable. The guard judges the HOST, so a mapped port "
                            + "must pass or the whole tier would fail to start")
                    .doesNotThrowAnyException();
        }
    }

    // ==================================================================
    // 3 - The two allowlists must not drift apart.
    // ==================================================================

    @Nested
    @DisplayName("3. The Java allowlist and the shell allowlist are the same allowlist")
    class AllowlistParity {

        @Test
        @DisplayName("every host the script allows is accepted here, and no host is accepted only here")
        void theTwoAllowlistsAgree() throws IOException {
            final Set<String> scriptHosts = hostsDeclaredByScript(readInitScript());

            assertThat(scriptHosts)
                    .as("the script's ALLOWED_ENDPOINT_HOSTS array is the contract this class mirrors, so "
                            + "failing to find it means the array was renamed and this test is no longer "
                            + "reading what it claims to read")
                    .containsExactlyInAnyOrder("localhost", "127.0.0.1", "::1", "localstack",
                            "localhost.localstack.cloud");

            for (final String host : scriptHosts) {
                final String endpoint = "::1".equals(host)
                        ? "http://[" + host + "]:4566"
                        : "http://" + host + ":4566";
                assertThatCode(() -> configWithEndpoint(endpoint))
                        .as("host '%s' is allowlisted by localstack-init/init-aws.sh and must therefore be "
                                + "allowlisted here: a value that provisions successfully and then fails "
                                + "startup is diagnosed as a defect in the application", host)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("the container-name pattern is the script's pattern, suffix rule included")
        void theContainerNamePatternAgrees() throws IOException {
            final Pattern declaration =
                    Pattern.compile("ALLOWED_ENDPOINT_HOST_PATTERN='([^']+)'");
            final Matcher matcher = declaration.matcher(readInitScript());

            assertThat(matcher.find())
                    .as("the script declares the compose container name as a bounded pattern rather than "
                            + "enumerating it, because docker-compose.yml appends an optional -CLONE_INDEX")
                    .isTrue();
            assertThat(matcher.group(1))
                    .as("the two patterns must be spelled identically. A widened suffix rule on either "
                            + "side would admit a host the other refuses")
                    .isEqualTo("^carddemo-localstack(-[0-9]+)?$");
        }

        @Test
        @DisplayName("a subdomain of the emulator's loopback DNS name is refused on both sides")
        void subdomainsAreRefusedDeliberately() throws IOException {
            assertThat(readInitScript())
                    .as("the script records the decision and the reason, so a reader who meets the "
                            + "refusal is not left guessing whether it was an oversight")
                    .contains("Subdomains of localhost.localstack.cloud are deliberately NOT matched");

            assertThatThrownBy(() -> configWithEndpoint("http://s3.localhost.localstack.cloud:4566"))
                    .as("least privilege: the bare host is the only endpoint this project documents, and a "
                            + "virtual-hosted name is not a general service edge anyway")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("neither guard spells the live service domain, so neither can be a denylist")
        void neitherGuardNamesTheLiveDomain() throws IOException {
            final String liveDomain = String.join("", "amazon", "aws", ".com");

            assertThat(readInitScript())
                    .as("an allowlist has no use for the literal, so its absence is the observable "
                            + "difference between an allowlist and the denylist this replaced")
                    .doesNotContain(liveDomain);
            assertThat(Files.readString(Path.of("src", "main", "java", "com", "cardemo", "config",
                            "AwsConfig.java"), StandardCharsets.UTF_8))
                    .as("and the Java guard must hold to the same discipline, in code and in comment")
                    .doesNotContain(liveDomain);
        }

        /**
         * Extracts the hosts the script's allowlist array declares.
         *
         * @param script the script text
         * @return the declared hosts, in declaration order
         */
        private Set<String> hostsDeclaredByScript(final String script) {
            final int start = script.indexOf("ALLOWED_ENDPOINT_HOSTS=(");
            assertThat(start).as("the allowlist array must be present in the script").isNotNegative();
            final int end = script.indexOf(')', start);
            assertThat(end).as("the allowlist array must be terminated").isGreaterThan(start);

            final Matcher entries = Pattern.compile("'([^']+)'").matcher(script.substring(start, end));
            final Set<String> hosts = new LinkedHashSet<>();
            while (entries.find()) {
                hosts.add(entries.group(1));
            }
            return hosts;
        }
    }

    // ==================================================================
    // 4 - The credential half of the same invariant.
    // ==================================================================

    @Nested
    @DisplayName("4. Only a static credentials provider is accepted")
    class CredentialProviderAssertion {

        @Test
        @DisplayName("a static provider is accepted, and all three customizer beans are published")
        void aStaticProviderIsAccepted() {
            final AwsConfig config = validConfig();
            final AwsCredentialsProvider credentials = staticCredentials();

            assertThat(config.cardDemoS3ClientCustomizer(credentials)).isNotNull();
            assertThat(config.cardDemoSqsAsyncClientCustomizer(credentials)).isNotNull();
            assertThat(config.cardDemoSnsClientCustomizer(credentials))
                    .as("three beans, one per enabled service, because the broad AwsSyncClientCustomizer "
                            + "contract is handed a builder that exposes only the HTTP client setters and "
                            + "cannot express an API call deadline at all")
                    .isNotNull();
        }

        @Test
        @DisplayName("the SDK default chain is refused: it is the ambient resolution the AAP excludes")
        void theDefaultChainIsRefused() {
            final AwsConfig config = validConfig();
            final AwsCredentialsProvider ambient = DefaultCredentialsProvider.create();

            assertThatThrownBy(() -> config.cardDemoS3ClientCustomizer(ambient))
                    .as("the library returns this provider when NOTHING is configured, which is exactly "
                            + "the state the earlier revision of the base profile left production in. It "
                            + "reads a shared credentials file, a web-identity token, container "
                            + "credentials and the instance metadata service in turn")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("static provider")
                    .hasMessageContaining("spring.cloud.aws.credentials.access-key");
        }

        @Test
        @DisplayName("a provider chain is refused even when every link in it is static")
        void aChainIsRefusedEvenWhenEveryLinkIsStatic() {
            final AwsConfig config = validConfig();
            final AwsCredentialsProvider chain = AwsCredentialsProviderChain.builder()
                    .credentialsProviders(staticCredentials(), staticCredentials())
                    .build();

            assertThatThrownBy(() -> config.cardDemoSqsAsyncClientCustomizer(chain))
                    .as("a chain means more than one source was configured, which is a configuration this "
                            + "application has no use for and cannot audit at a glance. Refusing it costs "
                            + "nothing because no profile produces one")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("an anonymous provider is refused too: absent credentials are not safe credentials")
        void anAnonymousProviderIsRefused() {
            final AwsConfig config = validConfig();

            assertThatThrownBy(() -> config.cardDemoSnsClientCustomizer(AnonymousCredentialsProvider.create()))
                    .as("the assertion is positive - it names what is acceptable - rather than a list of "
                            + "what is not, so a provider nobody thought about is refused rather than "
                            + "admitted")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a static provider carrying an UNRESOLVED PLACEHOLDER is refused")
        void anUnresolvedPlaceholderCredentialIsRefused() {
            final AwsConfig config = validConfig();
            final AwsCredentialsProvider unresolved = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("${AWS_ACCESS_KEY_ID}", "${AWS_SECRET_ACCESS_KEY}"));

            assertThatThrownBy(() -> config.cardDemoS3ClientCustomizer(unresolved))
                    .as("MEASURED, NOT ASSUMED. Unlike the endpoint, the region and the resource names, the "
                            + "credential pair is bound as configuration properties, and that binding "
                            + "resolves placeholders through a resolver which IGNORES what it cannot "
                            + "resolve - so an unset AWS_ACCESS_KEY_ID binds the property to the literal "
                            + "text of its own placeholder, the library builds a static provider around it, "
                            + "and the context refreshes carrying a credential nobody supplied. A type check "
                            + "alone passes that case; this is what turns it into a startup failure")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unset or unresolved value");
        }

        @Test
        @DisplayName("a blank credential cannot reach the guard, because the SDK refuses to hold one")
        void aBlankCredentialCannotBeConstructed() {
            assertThatThrownBy(() -> AwsBasicCredentials.create("  ", "  "))
                    .as("this is why the blank branch of the guard is documented as defensive rather than "
                            + "expected: the credential type validates its own arguments, so a variable set "
                            + "to the empty string fails inside the library before any check of ours is "
                            + "reached. Asserting it here is what keeps that branch from being read as an "
                            + "untested path - the path does not exist through the standard credential type")
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("the unresolved-credential refusal carries no credential material")
        void theUnresolvedRefusalCarriesNoMaterial() {
            final AwsConfig config = validConfig();
            final AwsCredentialsProvider unresolved = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("AKIAEXAMPLEKEYVALUE", "${AWS_SECRET_ACCESS_KEY}"));

            assertThatThrownBy(() -> config.cardDemoSnsClientCustomizer(unresolved))
                    .isInstanceOf(IllegalStateException.class)
                    .as("the check reads the resolved credential, so it is the one place in this class that "
                            + "holds key material - and it must put none of it in the message")
                    .hasMessageNotContaining("AKIAEXAMPLEKEYVALUE");
        }

        @Test
        @DisplayName("the refusal names the property keys that fix it, and no credential material")
        void theRefusalNamesTheRemedyAndNoSecret() {
            final AwsConfig config = validConfig();

            assertThatThrownBy(() -> config.cardDemoS3ClientCustomizer(DefaultCredentialsProvider.create()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AWS_ACCESS_KEY_ID")
                    .hasMessageContaining("instance-profile")
                    .as("a diagnostic says what to do rather than only what went wrong, and never carries "
                            + "the material itself")
                    .hasMessageNotContaining("test");
        }
    }

    // ==================================================================
    // 5 - Every client carries a deadline and a stated retry policy.
    // ==================================================================

    @Nested
    @DisplayName("5. Each of the three clients gets a per-attempt deadline, a call deadline and a policy")
    class CallDeadlines {

        @Test
        @DisplayName("the object-storage client carries the whole policy, and keeps what the library set")
        void theObjectStorageClientCarriesThePolicy() {
            final S3ClientBuilder builder = S3Client.builder()
                    .overrideConfiguration(libraryConfiguration());

            validConfig().cardDemoS3ClientCustomizer(staticCredentials()).customize(builder);

            assertPolicyApplied(builder.overrideConfiguration());
        }

        @Test
        @DisplayName("the queue client carries the whole policy - the one that bounds an online request")
        void theQueueClientCarriesThePolicy() {
            final SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                    .overrideConfiguration(libraryConfiguration());

            validConfig().cardDemoSqsAsyncClientCustomizer(staticCredentials()).customize(builder);

            assertPolicyApplied(builder.overrideConfiguration());
        }

        @Test
        @DisplayName("the notification client carries the whole policy")
        void theNotificationClientCarriesThePolicy() {
            final SnsClientBuilder builder = SnsClient.builder()
                    .overrideConfiguration(libraryConfiguration());

            validConfig().cardDemoSnsClientCustomizer(staticCredentials()).customize(builder);

            assertPolicyApplied(builder.overrideConfiguration());
        }

        @Test
        @DisplayName("the three clients carry the SAME policy, so no service is quietly laxer")
        void allThreeCarryTheSamePolicy() {
            final AwsConfig config = validConfig();
            final AwsCredentialsProvider credentials = staticCredentials();

            final S3ClientBuilder s3 = S3Client.builder().overrideConfiguration(libraryConfiguration());
            final SqsAsyncClientBuilder sqs =
                    SqsAsyncClient.builder().overrideConfiguration(libraryConfiguration());
            final SnsClientBuilder sns = SnsClient.builder().overrideConfiguration(libraryConfiguration());

            config.cardDemoS3ClientCustomizer(credentials).customize(s3);
            config.cardDemoSqsAsyncClientCustomizer(credentials).customize(sqs);
            config.cardDemoSnsClientCustomizer(credentials).customize(sns);

            final List<Duration> callDeadlines = new ArrayList<>();
            callDeadlines.add(s3.overrideConfiguration().apiCallTimeout().orElseThrow());
            callDeadlines.add(sqs.overrideConfiguration().apiCallTimeout().orElseThrow());
            callDeadlines.add(sns.overrideConfiguration().apiCallTimeout().orElseThrow());

            assertThat(callDeadlines)
                    .as("one policy expressed once, delegated to by all three customizers. Three beans "
                            + "exist because the library types its customizer contracts per service, not "
                            + "because the three services deserve different deadlines")
                    .containsOnly(EXPECTED_CALL_DEADLINE);
        }

        @Test
        @DisplayName("a builder that arrives with no override configuration still gets the policy")
        void aBuilderWithNoExistingConfigurationStillGetsThePolicy() {
            final S3ClientBuilder builder = S3Client.builder();

            validConfig().cardDemoS3ClientCustomizer(staticCredentials()).customize(builder);

            assertThat(builder.overrideConfiguration().apiCallTimeout())
                    .as("the library always sets an override configuration before any customizer runs, so "
                            + "this is the defensive branch rather than the expected one - and the "
                            + "outcome that still applies the deadlines is the right one")
                    .contains(EXPECTED_CALL_DEADLINE);
        }

        @Test
        @DisplayName("the per-attempt deadline is strictly shorter than the whole-call deadline")
        void theTwoDeadlinesAreOrderedSensibly() {
            final S3ClientBuilder builder = S3Client.builder()
                    .overrideConfiguration(libraryConfiguration());

            validConfig().cardDemoS3ClientCustomizer(staticCredentials()).customize(builder);
            final ClientOverrideConfiguration configuration = builder.overrideConfiguration();

            final Duration attempt = configuration.apiCallAttemptTimeout().orElseThrow();
            final Duration whole = configuration.apiCallTimeout().orElseThrow();

            assertThat(attempt)
                    .as("a per-attempt deadline at or above the whole-call deadline makes retries "
                            + "unreachable, which would leave the policy stated but inoperative")
                    .isLessThan(whole);
            assertThat(attempt.multipliedBy(EXPECTED_MAX_ATTEMPTS))
                    .as("and the whole-call budget must accommodate every attempt the policy permits, or "
                            + "the last retry is cut off mid-flight and the retry count is a fiction")
                    .isLessThanOrEqualTo(whole);
        }
    }

    // ==================================================================
    // 6 - The resource-name contracts that share the same constructor.
    //     They gate the same startup, so they are proved in the same place.
    // ==================================================================

    @Nested
    @DisplayName("6. The three buckets, the two queue names and the missing-queue strategy")
    class ResourceNameContracts {

        /**
         * Builds the class under test with valid endpoints and the caller's resource names.
         *
         * @param inputBucket      batch input bucket
         * @param outputBucket     batch output bucket
         * @param statementsBucket statements bucket
         * @param physicalQueue    physical queue name
         * @param logicalQueue     logical queue name
         * @param strategy         missing-queue strategy
         * @return the constructed configuration
         */
        private AwsConfig configWithNames(final String inputBucket, final String outputBucket,
                final String statementsBucket, final String physicalQueue, final String logicalQueue,
                final QueueNotFoundStrategy strategy) {

            return new AwsConfig("us-east-1", inputBucket, outputBucket, statementsBucket, physicalQueue,
                    logicalQueue, "carddemo-notifications", strategy,
                    VALID_ENDPOINT, VALID_ENDPOINT, VALID_ENDPOINT, "", "test", "test");
        }

        @Test
        @DisplayName("two roles sharing one bucket is refused: a job would overwrite its own input")
        void sharedBucketsAreRefused() {
            assertThatThrownBy(() -> configWithNames("carddemo-shared", "carddemo-shared",
                    "carddemo-statements", "carddemo-report-jobs.fifo", "carddemo-report-jobs",
                    QueueNotFoundStrategy.FAIL))
                    .as("batch input is read, batch output is written and versioned, and statements are "
                            + "written separately. Pointing two roles at one bucket would let a job "
                            + "overwrite its own input and would apply the versioning that replaces "
                            + "generation retention to data it was never meant to cover")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("three distinct buckets");
        }

        @Test
        @DisplayName("a physical queue name that is not the logical name, optionally suffixed, is refused")
        void driftedQueueNamesAreRefused() {
            assertThatThrownBy(() -> configWithNames("carddemo-batch-input", "carddemo-batch-output",
                    "carddemo-statements", "carddemo-other-queue.fifo", "carddemo-report-jobs",
                    QueueNotFoundStrategy.FAIL))
                    .as("localstack-init/init-aws.sh derives one name from the other, so a drift would "
                            + "provision one queue and publish to another - a failure that surfaces at the "
                            + "first report submission and reads as a missing queue rather than as a "
                            + "configuration mistake")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sqs.report-queue");
        }

        @Test
        @DisplayName("the FIFO-suffixed physical name is accepted and the unsuffixed one aborts startup")
        void onlyTheFifoSuffixedPhysicalNameIsAccepted() {
            assertThatCode(() -> configWithNames("carddemo-batch-input", "carddemo-batch-output",
                    "carddemo-statements", "carddemo-report-jobs.fifo", "carddemo-report-jobs",
                    QueueNotFoundStrategy.FAIL))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> configWithNames("carddemo-batch-input", "carddemo-batch-output",
                    "carddemo-statements", "carddemo-report-jobs", "carddemo-report-jobs",
                    QueueNotFoundStrategy.FAIL))
                    .as("an unsuffixed name is not a FIFO queue, so the fixed message group that makes "
                            + "submission ordering reproducible is not honoured. This was once a warning "
                            + "followed by acceptance, on the reasoning that the service itself rejects a "
                            + "message group on a non-FIFO queue at the first send; that traded a "
                            + "deterministic startup failure for a runtime one on the very path whose "
                            + "purpose is to reproduce the strictly sequential DISPOSITION(MOD) append of "
                            + "DEFINE TDQUEUE(JOBS), so the refusal is now at startup")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.sqs.report-queue");
        }

        @Test
        @DisplayName("a blank resource name is refused, and the message names the key and not the value")
        void aBlankResourceNameIsRefused() {
            assertThatThrownBy(() -> configWithNames("  ", "carddemo-batch-output", "carddemo-statements",
                    "carddemo-report-jobs.fifo", "carddemo-report-jobs", QueueNotFoundStrategy.FAIL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.aws.s3.batch-input-bucket")
                    .as("a blank value resolves successfully and even out-ranks a profile default, so it "
                            + "would otherwise reach a client as an empty bucket name and surface far from "
                            + "its cause")
                    .hasMessageContaining("resolved to no value");
        }

        @Test
        @DisplayName("a missing-queue strategy that resolved to nothing is refused rather than defaulted")
        void anAbsentStrategyIsRefused() {
            assertThatThrownBy(() -> configWithNames("carddemo-batch-input", "carddemo-batch-output",
                    "carddemo-statements", "carddemo-report-jobs.fifo", "carddemo-report-jobs", null))
                    .as("defensive rather than expected, because the binding carries a FAIL default - but a "
                            + "silent null would leave the publisher on the library default, under which an "
                            + "absent queue is CREATED on first send, and provisioning from Java must never "
                            + "be reachable by accident")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("queue-not-found-strategy");
        }
    }
}
