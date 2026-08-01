/*
 * ******************************************************************
 * Program     : InitAwsScriptGuardTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Executes localstack-init/init-aws.sh with hostile and
 *               with legitimate configuration and asserts that its
 *               endpoint allowlist and its retry-parameter validation
 *               both fail closed. Every case here reaches its verdict
 *               before the script issues a single AWS call, so the suite
 *               needs no container and provisions nothing.
 * Source      : localstack-init/init-aws.sh (the guards under test)
 *               app/csd/CARDDEMO.CSD DEFINE TDQUEUE(JOBS) -> the FIFO
 *               queue the script provisions
 *               app/jcl/DEFGDGB.jcl, DALYREJS.jcl, REPTFILE.jcl -> the
 *               GDG bases that become the three buckets @ 7756d89
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
package com.cardemo.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Asserts that the provisioning hook refuses every configuration it should refuse.
 *
 * <p><strong>Why this is executed rather than read.</strong> The guard that preceded these tests looked
 * correct on inspection: it matched the live AWS service domain and refused it. It was nonetheless wrong in
 * four independent ways, and each way was invisible to reading because each depended on how the shell, DNS
 * and an HTTP client disagree about what a URL's host is. Only running the script exposes that, so every case
 * below launches the real file.
 *
 * <p><strong>Why it is safe to run in the unit tier.</strong> The script validates its whole configuration
 * before it selects a CLI or issues any request. Every rejection case therefore terminates inside the
 * validation block with exit code 2, having created nothing and contacted nothing. The acceptance cases
 * assert only that the guard let the value through, not that provisioning succeeded, so they too need no
 * emulator: an allowlisted host that does not resolve simply fails later at the readiness gate, which is a
 * different exit code and is exactly the distinction being asserted.
 */
@DisplayName("init-aws.sh: the endpoint allowlist and the retry parsing both fail closed")
class InitAwsScriptGuardTest {

    /** The provisioning hook under test, relative to {@code ${basedir}}, which is Surefire's working directory. */
    private static final Path SCRIPT = Path.of("localstack-init", "init-aws.sh");

    /** {@code EXIT_CONFIG} of {@code init-aws.sh}: a configuration value was refused. */
    private static final int EXIT_CONFIG = 2;

    /** No single invocation may hang the build; every case here decides in milliseconds. */
    private static final long TIMEOUT_SECONDS = 60L;

    /**
     * Grace period for the one case whose accepted value is itself the readiness loop bound. It is long
     * enough for the gate to announce its budget and short enough not to shape the suite's runtime.
     */
    private static final long GRACE_SECONDS = 5L;

    /**
     * A host that is on the allowlist but is not expected to resolve outside the compose network, so the
     * acceptance cases can prove the guard passed without reaching an emulator.
     */
    private static final String ALLOWLISTED_UNREACHABLE = "http://carddemo-localstack-004:4566";

    @BeforeAll
    static void theScriptAndAShellMustBothBePresent() {
        assertThat(SCRIPT)
                .as("Surefire runs with ${basedir} as its working directory, so the hook resolves from here")
                .isRegularFile();
        // A skip rather than a failure: the project ships mvnw.cmd, so a Windows checkout is contemplated,
        // and on such a host the absence of bash says nothing about the guards being correct.
        assumeTrue(Path.of("/bin/bash").toFile().canExecute() || commandExists("bash"),
                "bash is unavailable on this platform; the shell guards cannot be executed here");
    }

    // ==================================================================
    // 1 - Hostile endpoints. Each of these was ACCEPTED by the denylist
    //     that preceded the allowlist.
    // ==================================================================

    @Nested
    @DisplayName("1. Hostile endpoints are refused with EXIT_CONFIG, before any AWS call")
    class HostileEndpoints {

        @ParameterizedTest(name = "[{0}] is refused: {1}")
        @CsvSource({
            "'http://s3.AMAZONAWS.COM',                    'is not an allowlisted'",
            "'https://sqs.us-east-1.AmazonAWS.com',        'is not an allowlisted'",
            "'http://169.254.169.254',                     'is not an allowlisted'",
            "'http://169.254.169.254:4566',                'is not an allowlisted'",
            "'http://[fd00:ec2::254]:4566',                'is not an allowlisted'",
            "'http://evil.example.com:4566',               'is not an allowlisted'",
            "'http://localhost.evil.com:4566',             'is not an allowlisted'",
            "'http://s3.localhost.localstack.cloud:4566',  'is not an allowlisted'",
            "'http://user:pw@localhost:4566@evil.com',     'embeds userinfo before the host'",
            "'http://localhost@evil.com:4566',             'embeds userinfo before the host'",
            "'http://%6c%6f%63alhost:4566',                'percent-encodes its authority'",
            "'http://localhost:4566/../evil',              'carries the path'",
            "'http://localhost:4566/_localstack',          'carries the path'",
            "'http://localhost:4566?host=evil.com',        'carries a query or fragment'",
            "'http://localhost:4566#evil.com',             'carries a query or fragment'",
            "'ftp://localhost:4566',                       'is not an http(s) URL'",
            "'file:///etc/passwd',                         'is not an http(s) URL'",
            "'localhost:4566',                             'is not an http(s) URL'",
            "'http://:4566',                               'empty host'",
            "'http://localhost:99999',                     'invalid port'",
            "'http://localhost:0',                         'invalid port'",
            "'http://localhost:04566',                     'invalid port'",
            "'http://localhost:abc',                       'invalid port'",
        })
        @DisplayName("every hostile endpoint shape is rejected as a configuration error")
        void hostileEndpointsAreRefused(final String endpoint, final String expectedReason) {
            final Result result = run(Map.of("AWS_ENDPOINT_URL", endpoint));

            assertThat(result.exitCode())
                    .as("exit code must be EXIT_CONFIG, which is what proves the refusal happened in the "
                            + "validation block rather than as a downstream API failure. Output:%n%s",
                            result.output())
                    .isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("and the reason must name what was wrong with the value, not merely that "
                            + "something was")
                    .contains(expectedReason);
        }

        @Test
        @DisplayName("an uppercase AWS host is refused even though a case-sensitive denylist missed it")
        void anUppercaseAwsHostIsRefused() {
            final Result result = run(Map.of("AWS_ENDPOINT_URL", "http://s3.AMAZONAWS.COM"));

            assertThat(result.exitCode()).isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("DNS is case insensitive, so the host is lowercased before comparison and the "
                            + "message quotes the lowercased form, which is the form the guard judged")
                    .contains("s3.amazonaws.com");
        }

        @Test
        @DisplayName("a hostile endpoint never reaches provisioning: nothing is created and nothing contacted")
        void aRefusedEndpointReachesNoAwsCall() {
            final Result result = run(Map.of("AWS_ENDPOINT_URL", "http://169.254.169.254"));

            assertThat(result.output())
                    .as("the readiness gate logs before it probes and provisioning logs each resource, so "
                            + "the absence of both proves the script stopped in the validation block")
                    .doesNotContain("[readiness]")
                    .doesNotContain("bucket")
                    .doesNotContain("[summary]");
        }
    }

    // ==================================================================
    // 2 - Legitimate endpoints still pass. An allowlist that refuses
    //     everything is not a fix.
    // ==================================================================

    @Nested
    @DisplayName("2. Every documented endpoint spelling is still accepted by the guard")
    class LegitimateEndpoints {

        @ParameterizedTest(name = "[{0}] passes the endpoint guard")
        @CsvSource({
            "'http://localhost:4599'",
            "'http://localhost:4599/'",
            "'http://LOCALHOST:4599'",
            "'http://127.0.0.1:4599'",
            "'http://[::1]:4599'",
            "'http://localstack:4599'",
            "'http://localhost.localstack.cloud:4599'",
            "'http://carddemo-localstack:4599'",
            "'http://carddemo-localstack-004:4599'",
            "'https://localhost:4599'",
        })
        @DisplayName("the guard admits the value and says so, and does not report a configuration error")
        void documentedEndpointsAreAccepted(final String endpoint) {
            // Port 4599 rather than the real 4566, deliberately. The guard judges the HOST, so the port is
            // immaterial to what is being asserted - but a closed port means the readiness gate fails after
            // one attempt instead of the script provisioning against whatever emulator happens to be
            // listening. That keeps each case fast and, more importantly, keeps a unit test from writing to
            // a shared local stack that parallel clones also use.
            final Result result = run(Map.of(
                    "AWS_ENDPOINT_URL", endpoint,
                    "INIT_MAX_ATTEMPTS", "1",
                    "INIT_SLEEP_SECONDS", "0",
                    "INIT_HEALTH_TIMEOUT_SECONDS", "1"));

            assertThat(result.output())
                    .as("the guard logs the host it allowlisted, which is the positive evidence that it "
                            + "ran and passed rather than being skipped. Output:%n%s", result.output())
                    .contains("allowlisted");
            assertThat(result.exitCode())
                    .as("whatever happens afterwards - provisioning against a live edge, or failing at the "
                            + "readiness gate because the name does not resolve here - must not be a "
                            + "configuration rejection. Asserting 'not EXIT_CONFIG' rather than a fixed "
                            + "code is deliberate: it holds whether or not an emulator is running")
                    .isNotEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .doesNotContain("is not an allowlisted")
                    .doesNotContain("config:AWS_ENDPOINT_URL");
        }

        @Test
        @DisplayName("the default endpoint, with AWS_ENDPOINT_URL unset, is accepted")
        void theDefaultEndpointIsAccepted() {
            // This is the one case that cannot substitute a closed port, because the value under test IS
            // the built-in default. If an emulator is listening the script will therefore provision - which
            // is harmless: every operation is check-then-create against the resources this project already
            // owns, so a second run creates nothing and mutates nothing. That idempotency is the script's
            // central design property and is asserted by the fact that this case passes repeatedly.
            final Result result = run(Map.of(
                    "INIT_MAX_ATTEMPTS", "1", "INIT_SLEEP_SECONDS", "0",
                    "INIT_HEALTH_TIMEOUT_SECONDS", "1"));

            assertThat(result.output())
                    .as("the documented default is the local edge on 4566, so an unset variable must not "
                            + "be the one input the guard refuses")
                    .contains("allowlisted");
            assertThat(result.exitCode()).isNotEqualTo(EXIT_CONFIG);
        }
    }

    // ==================================================================
    // 3 - Retry parameters: base 10, no leading zero, finite range.
    // ==================================================================

    @Nested
    @DisplayName("3. Retry parameters are parsed in base 10 and bounded")
    class RetryParameters {

        @ParameterizedTest(name = "{0}={1} is refused: {2}")
        @CsvSource({
            "INIT_SLEEP_SECONDS,          '08',                    'without a leading zero'",
            "INIT_SLEEP_SECONDS,          '09',                    'without a leading zero'",
            "INIT_SLEEP_SECONDS,          '030',                   'without a leading zero'",
            "INIT_MAX_ATTEMPTS,           '08',                    'without a leading zero'",
            "INIT_HEALTH_TIMEOUT_SECONDS, '08',                    'without a leading zero'",
            "INIT_MAX_ATTEMPTS,           'abc',                   'without a leading zero'",
            "INIT_MAX_ATTEMPTS,           '-1',                    'without a leading zero'",
            "INIT_SLEEP_SECONDS,          '2.5',                   'without a leading zero'",
            "INIT_MAX_ATTEMPTS,           '',                      'without a leading zero'",
            "INIT_MAX_ATTEMPTS,           '0',                     'outside the permitted range 1-3600'",
            "INIT_MAX_ATTEMPTS,           '3601',                  'outside the permitted range 1-3600'",
            "INIT_MAX_ATTEMPTS,           '9223372036854775807',   'outside the permitted range 1-3600'",
            "INIT_SLEEP_SECONDS,          '61',                    'outside the permitted range 0-60'",
            "INIT_HEALTH_TIMEOUT_SECONDS, '0',                     'outside the permitted range 1-300'",
            "INIT_HEALTH_TIMEOUT_SECONDS, '301',                   'outside the permitted range 1-300'",
        })
        @DisplayName("a leading zero, a non-integer or an out-of-range value is a configuration error")
        void invalidRetryParametersAreRefused(
                final String variable, final String value, final String expectedReason) {

            final Result result = run(Map.of(
                    "AWS_ENDPOINT_URL", "http://localhost:4566", variable, value));

            assertThat(result.exitCode())
                    .as("%s=%s must be refused. Output:%n%s", variable, value, result.output())
                    .isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("and the message must name the variable, so a compose file with three of these "
                            + "does not have to be bisected")
                    .contains(variable)
                    .contains(expectedReason);
        }

        @Test
        @DisplayName("a leading zero is refused rather than crashing the arithmetic that reports the budget")
        void aLeadingZeroDoesNotReachTheArithmetic() {
            final Result result = run(Map.of(
                    "AWS_ENDPOINT_URL", "http://localhost:4566", "INIT_SLEEP_SECONDS", "08"));

            assertThat(result.exitCode()).isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("before the leading-zero rule existed, 08 survived validation and then aborted the "
                            + "readiness-exhaustion message itself with 'value too great for base'. The "
                            + "absence of that string is the regression this case guards")
                    .doesNotContain("value too great for base")
                    .doesNotContain("error token");
        }

        @ParameterizedTest(name = "{0}={1} is accepted")
        @CsvSource({
            "INIT_MAX_ATTEMPTS,           '1'",
            "INIT_SLEEP_SECONDS,          '0'",
            "INIT_SLEEP_SECONDS,          '60'",
            "INIT_HEALTH_TIMEOUT_SECONDS, '1'",
            "INIT_HEALTH_TIMEOUT_SECONDS, '300'",
        })
        @DisplayName("both ends of every permitted range are accepted, the bounds being inclusive")
        void bothEndsOfEveryRangeAreAccepted(final String variable, final String value) {
            final Map<String, String> environment = new LinkedHashMap<>();
            environment.put("AWS_ENDPOINT_URL", ALLOWLISTED_UNREACHABLE);
            environment.put("INIT_MAX_ATTEMPTS", "1");
            environment.put("INIT_SLEEP_SECONDS", "0");
            environment.put("INIT_HEALTH_TIMEOUT_SECONDS", "1");
            environment.put(variable, value);

            final Result result = run(environment);

            assertThat(result.output())
                    .as("a bound that excludes its own endpoint would make the documented default "
                            + "unusable at the edge of the range. Output:%n%s", result.output())
                    .doesNotContain("outside the permitted range")
                    .doesNotContain("without a leading zero");
            assertThat(result.exitCode()).isNotEqualTo(EXIT_CONFIG);
        }

        @Test
        @DisplayName("the attempt ceiling itself is accepted, evidenced by the announced budget")
        void theAttemptCeilingIsAccepted() {
            // INIT_MAX_ATTEMPTS is the loop bound, so running it at its ceiling against a host that does
            // not resolve would poll 3600 times and dominate the suite's runtime. The property under test
            // is only that the VALIDATOR accepted the value, and the readiness gate announces the accepted
            // budget on entry - before the first probe - so the announcement is sufficient evidence. The
            // process is therefore given a short grace period and then terminated: a rejected value would
            // have exited immediately with EXIT_CONFIG and never printed it.
            final Result result = run(
                    Map.of("AWS_ENDPOINT_URL", ALLOWLISTED_UNREACHABLE,
                            "INIT_MAX_ATTEMPTS", "3600",
                            "INIT_SLEEP_SECONDS", "0",
                            "INIT_HEALTH_TIMEOUT_SECONDS", "1"),
                    GRACE_SECONDS);

            assertThat(result.output())
                    .as("the gate logs 'up to <MAX_ATTEMPTS> attempts', so the ceiling appearing there is "
                            + "proof the value survived validation. Output:%n%s", result.output())
                    .contains("up to 3600 attempts")
                    .doesNotContain("outside the permitted range");
            if (!result.timedOut()) {
                assertThat(result.exitCode()).isNotEqualTo(EXIT_CONFIG);
            }
        }

        @Test
        @DisplayName("one past the attempt ceiling is refused, so the ceiling is a real bound")
        void onePastTheAttemptCeilingIsRefused() {
            final Result result = run(Map.of(
                    "AWS_ENDPOINT_URL", ALLOWLISTED_UNREACHABLE, "INIT_MAX_ATTEMPTS", "3601"));

            assertThat(result.exitCode())
                    .as("paired with the case above, this is what makes 3600 an inclusive ceiling rather "
                            + "than an unenforced comment")
                    .isEqualTo(EXIT_CONFIG);
            assertThat(result.output()).contains("outside the permitted range 1-3600");
        }
    }

    // ==================================================================
    // 4 - Structural properties, read from the file rather than executed.
    // ==================================================================

    @Nested
    @DisplayName("4. Structural properties of the guard: fail-closed, base-10, bounded, and AWS-free")
    class StructuralProperties {

        @Test
        @DisplayName("the real AWS service domain is no longer spelled anywhere in the script")
        void theScriptNamesNoLiveAwsDomain() {
            assertThat(source())
                    .as("the denylist needed that literal and documented itself as its only occurrence. An "
                            + "allowlist does not, because everything unlisted is refused, so its removal "
                            + "is the observable difference between the two designs")
                    .doesNotContain("amazonaws.com")
                    .doesNotContain("AMAZONAWS");
        }

        @Test
        @DisplayName("the endpoint guard is fail-closed: its last statement is a refusal, not an acceptance")
        void theEndpointGuardIsFailClosed() {
            final String body = extract("require_local_endpoint() {", "\n}\n");

            assertThat(body)
                    .as("an allowlist whose final branch accepted by default would be a denylist wearing a "
                            + "different name, which is precisely the defect being removed")
                    .contains("is not an allowlisted LocalStack endpoint");
            assertThat(body.lastIndexOf("fail \"${EXIT_CONFIG}\""))
                    .as("the terminal statement must be the failure, so an unmatched host cannot fall "
                            + "through to a return")
                    .isGreaterThan(body.lastIndexOf("return 0"));
            assertThat(body)
                    .as("and the comparison must be on a lowercased host")
                    .contains("host=\"${host,,}\"");
        }

        @Test
        @DisplayName("the ordering that makes the plain-aws fallback safe is still intact")
        void theGuardStillPrecedesTheCliSelection() {
            final String source = source();

            assertThat(source.indexOf("require_local_endpoint \"${ENDPOINT_URL}\""))
                    .as("the fallback passes --endpoint-url straight to the AWS CLI, so it is safe only "
                            + "because the endpoint was already validated. Moving the CLI selection above "
                            + "the guard would reopen the finding, and this is the assertion that would "
                            + "catch that edit")
                    .isGreaterThan(0)
                    .isLessThan(source.indexOf("if command -v awslocal"));
            assertThat(source)
                    .as("and the fallback must not inherit a developer's ambient AWS credential chain")
                    .contains("AWS_EC2_METADATA_DISABLED")
                    .contains("unset AWS_PROFILE");
        }

        @Test
        @DisplayName("the readiness budget is computed in base 10 from provably bounded factors")
        void theBudgetArithmeticIsBaseTenAndBounded() {
            final String source = source();

            assertThat(source)
                    .as("both factors carry an explicit base at the one site they are multiplied")
                    .contains("$((10#${MAX_ATTEMPTS} * 10#${SLEEP_SECONDS}))");
            assertThat(source)
                    .as("and both are bounded, which is what makes the product unable to wrap a signed "
                            + "64-bit integer rather than merely unlikely to")
                    .contains("readonly MAX_ATTEMPTS_CEILING=3600")
                    .contains("readonly SLEEP_SECONDS_CEILING=60")
                    .contains("readonly HEALTH_TIMEOUT_CEILING=300");
            assertThat(source)
                    .as("the permissive digit-only pattern that admitted 08 must be gone")
                    .doesNotContain("=~ ^[0-9]+$");
        }

        @Test
        @DisplayName("the statements-bucket versioning decision is stated with its AAP citation")
        void theVersioningPolicyIsDocumentedAndCited() {
            final String source = source();

            assertThat(source)
                    .as("the finding was that documentation elsewhere claims this checkout versions the "
                            + "statements bucket. The script must therefore state its own policy, cite the "
                            + "requirement it follows, and dispose of the conflicting claim rather than "
                            + "leaving a reader to guess which source is authoritative")
                    .contains("AAP 0.5.1.1")
                    .contains("NO GENERATION")
                    .contains("CONFLICTING CLAIM");
            assertThat(source)
                    .as("and only the output bucket may be versioned")
                    .containsOnlyOnce("enable_and_verify_versioning \"${OUTPUT_BUCKET}\"");
            assertThat(source)
                    .doesNotContain("enable_and_verify_versioning \"${STATEMENTS_BUCKET}\"")
                    .doesNotContain("enable_and_verify_versioning \"${INPUT_BUCKET}\"");
        }
    }

    // ==================================================================
    // Process helpers.
    // ==================================================================

    /**
     * One invocation's merged output and exit status.
     *
     * @param exitCode the process exit status, meaningful only when {@code timedOut} is {@code false}
     * @param output   the merged stdout and stderr captured, complete on a clean exit and partial when the
     *                 process had to be terminated
     * @param timedOut whether the grace period elapsed and the process was terminated rather than exiting
     */
    private record Result(int exitCode, String output, boolean timedOut) {
    }

    /**
     * Runs the hook with {@code overrides} applied over a deliberately minimal environment.
     *
     * <p>The inherited environment is cleared of the AWS variables this host exports, because leaving them in
     * place would let an ambient {@code AWS_ENDPOINT_URL} decide what the test actually exercised.
     */
    private static Result run(final Map<String, String> overrides) {
        return run(overrides, TIMEOUT_SECONDS);
    }

    /**
     * Runs the hook with a caller-chosen budget. A process still alive when the budget elapses is terminated
     * and its partial output returned rather than being treated as an error, because for one case that
     * outcome is the expected one - see {@link RetryParameters#theAttemptCeilingIsAccepted()}.
     */
    private static Result run(final Map<String, String> overrides, final long budgetSeconds) {
        // Output goes to a temporary file rather than to a pipe. Two reasons, and the first is not a
        // preference: reading a pipe with readAllBytes blocks until end of stream, so a process still
        // polling would block the test for its whole readiness loop, while draining AFTER
        // destroyForcibly fails outright because the JDK closes the stream when it kills the process.
        // A file is readable in both cases and needs no reader thread.
        final Path capture;
        try {
            capture = Files.createTempFile("blitzy_adhoc_test_init_aws_", ".log");
        } catch (final IOException noTempFile) {
            throw new UncheckedIOException("could not create a capture file", noTempFile);
        }

        final ProcessBuilder builder = new ProcessBuilder("bash", SCRIPT.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(capture.toFile());

        final Map<String, String> environment = builder.environment();
        environment.remove("AWS_ENDPOINT_URL");
        environment.remove("AWS_PROFILE");
        environment.remove("INIT_MAX_ATTEMPTS");
        environment.remove("INIT_SLEEP_SECONDS");
        environment.remove("INIT_HEALTH_TIMEOUT_SECONDS");
        environment.putAll(overrides);

        Process process = null;
        try {
            process = builder.start();
            final boolean exited = process.waitFor(budgetSeconds, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly().waitFor(budgetSeconds, TimeUnit.SECONDS);
            }
            return new Result(
                    exited ? process.exitValue() : Integer.MIN_VALUE,
                    Files.readString(capture, StandardCharsets.UTF_8),
                    !exited);
        } catch (final IOException launchFailure) {
            throw new UncheckedIOException("could not launch " + SCRIPT, launchFailure);
        } catch (final InterruptedException interrupted) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while running " + SCRIPT, interrupted);
        } finally {
            // The capture file is scratch and must never accumulate, so it is removed on every path.
            try {
                Files.deleteIfExists(capture);
            } catch (final IOException undeletable) {
                throw new UncheckedIOException("could not remove the capture file " + capture, undeletable);
            }
        }
    }

    private static String source() {
        try {
            return Files.readString(SCRIPT, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("could not read " + SCRIPT, unreadable);
        }
    }

    /** Returns the text between {@code opening} and the first {@code closing} that follows it. */
    private static String extract(final String opening, final String closing) {
        final String source = source();
        final int start = source.indexOf(opening);
        assertThat(start).as("init-aws.sh must still declare %s", opening).isGreaterThan(-1);
        final int end = source.indexOf(closing, start);
        assertThat(end).as("%s must be terminated by %s", opening, closing).isGreaterThan(start);
        return source.substring(start, end);
    }

    private static boolean commandExists(final String command) {
        try {
            final Process probe = new ProcessBuilder("sh", "-c", "command -v " + command)
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().readAllBytes();
            return probe.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (final IOException unavailable) {
            return false;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
