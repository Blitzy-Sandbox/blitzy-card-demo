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
import java.util.List;
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
                    .as("DNS is case insensitive, so the host is lowercased before being compared against "
                            + "the allowlist, and an unmatched host is refused with the closed reason token")
                    .contains("[reason=HOST_NOT_ALLOWLISTED]");
            assertThat(result.output())
                    .as("""
                        and the rejected host is NOT quoted back. This assertion replaces an earlier one \
                        that required the opposite - it demanded the lowercased host appear in the message, \
                        as evidence that the lowercasing had happened. That evidence was real but the \
                        mechanism was not safe to generalise: the same interpolation carried userinfo \
                        credentials and CR/LF payloads out to stderr for every other rejected shape, which \
                        is the defect RefusalDisclosesNothing below now pins shut. The lowercasing is \
                        instead proved structurally by theEndpointGuardIsFailClosed, which asserts the \
                        literal host="${host,,}" is present in the guard body - a stronger proof than an \
                        echo, because it cannot be satisfied by accident.""")
                    .doesNotContain("s3.amazonaws.com")
                    .doesNotContain("AMAZONAWS");
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

        @Test
        @DisplayName("a newline in the value cannot forge a log line: the bytes are refused before any echo")
        void anEmbeddedNewlineCannotForgeALogLine() {
            final String forged = "[init-aws] 2026-01-01T00:00:00Z [summary] all resources provisioned";
            final Result result = run(Map.of("AWS_ENDPOINT_URL", "http://localhost:4566\r\n" + forged));

            assertThat(result.exitCode())
                    .as("a control byte is a configuration error, so the refusal must carry EXIT_CONFIG. "
                            + "Output:%n%s", result.output())
                    .isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("the whole point of refusing the byte before emitting anything is that the "
                            + "attacker-chosen text never becomes a line of its own in the log. If this "
                            + "assertion fails, a reader of the log can be told provisioning succeeded when "
                            + "it never began")
                    .doesNotContain(forged)
                    .doesNotContain("[summary]");
        }

        @Test
        @DisplayName("a rejected value is never echoed, so an endpoint carrying a secret cannot leak it")
        void aRejectedValueIsNeverEchoed() {
            final String secret = "wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY";
            final Result result = run(Map.of(
                    "AWS_ENDPOINT_URL", "http://AKIAIOSFODNN7EXAMPLE:" + secret + "@localhost:4566"));

            assertThat(result.exitCode()).isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("the guard must still say WHY it refused, because a refusal without a reason is "
                            + "undiagnosable")
                    .contains("embeds userinfo before the host");
            assertThat(result.output())
                    .as("but it must not quote the value back. This shape is exactly the one the reason "
                            + "phrase describes - a URL carrying credentials - so echoing the value to "
                            + "explain the refusal would write the credential into the log it was refused "
                            + "for containing")
                    .doesNotContain(secret)
                    .doesNotContain("AKIAIOSFODNN7EXAMPLE");
        }

        @Test
        @DisplayName("a backslash is refused: it is not an RFC 3986 URL byte and shells treat it specially")
        void aBackslashIsRefused() {
            final Result result = run(Map.of("AWS_ENDPOINT_URL", "http://localhost:4566\\evil"));

            assertThat(result.exitCode())
                    .as("output:%n%s", result.output())
                    .isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("the byte rule is a positive RFC 3986 allowlist, so the backslash is refused for "
                            + "not being a URL byte rather than for matching a denylist entry. Inside a "
                            + "POSIX bracket expression a backslash is an ordinary character rather than an "
                            + "escape, so a rule written as a denylist would have admitted the very byte it "
                            + "was trying to name")
                    .contains("carries a control character, whitespace, a backslash or a non-ASCII byte");
        }

        @Test
        @DisplayName("an over-long value is refused on its length, before it is scanned or logged")
        void anOverLongValueIsRefused() {
            final String padded = "http://localhost:4566" + "a".repeat(400);
            final Result result = run(Map.of("AWS_ENDPOINT_URL", padded));

            assertThat(result.exitCode())
                    .as("output:%n%s", result.output())
                    .isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("the bound is stated in the message so the operator can see both the value's size "
                            + "and the limit it broke, which is diagnosable without reproducing the value")
                    .contains("421 characters")
                    .contains("300-character bound");
            assertThat(result.output())
                    .as("and the value itself is still not echoed, which is what keeps a long "
                            + "credential-bearing value out of the log")
                    .doesNotContain(padded);
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
            assertThat(result.output())
                    .as("and neither refusal diagnostic may appear. This pair is what pins the verdict, "
                            + "because the refusal text also contains the word 'allowlisted' - in the form "
                            + "'is not an allowlisted' - so the assertion above cannot discriminate on its "
                            + "own. Together the three are decisive in every environment. Output:%n%s",
                            result.output())
                    .doesNotContain("is not an allowlisted")
                    .doesNotContain("config:AWS_ENDPOINT_URL");
            if (!stoppedForWantOfAnAwsCli(result)) {
                assertThat(result.exitCode())
                        .as("whatever happens afterwards - provisioning against a live edge, or failing at "
                                + "the readiness gate because the name does not resolve here - must not be "
                                + "a configuration rejection. Asserting 'not EXIT_CONFIG' rather than a "
                                + "fixed code is deliberate: it holds whether or not an emulator is running")
                        .isNotEqualTo(EXIT_CONFIG);
            }
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
                            + "be the one input the guard refuses. Output:%n%s", result.output())
                    .contains("allowlisted")
                    .doesNotContain("is not an allowlisted")
                    .doesNotContain("config:AWS_ENDPOINT_URL");
            if (!stoppedForWantOfAnAwsCli(result)) {
                assertThat(result.exitCode()).isNotEqualTo(EXIT_CONFIG);
            }
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
            if (!stoppedForWantOfAnAwsCli(result)) {
                assertThat(result.exitCode()).isNotEqualTo(EXIT_CONFIG);
            }
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
                    .as("a refused ceiling would have been rejected inside the validation block, naming the "
                            + "range it fell outside. Output:%n%s", result.output())
                    .doesNotContain("outside the permitted range");
            assertThat(result.output())
                    .as("and the run must be shown to have left the validation block behind. Either marker "
                            + "proves that, both lying strictly downstream of it: the readiness gate "
                            + "announces its accepted budget on entry, and the CLI-selection gate is reached "
                            + "only once every configuration value has been accepted. Which of the two "
                            + "appears depends on whether this host happens to have an AWS CLI, which is no "
                            + "property of the ceiling under test. Output:%n%s", result.output())
                    .satisfiesAnyOf(
                            output -> assertThat(output).contains("up to 3600 attempts"),
                            output -> assertThat(output).contains("config:cli"));
            if (!result.timedOut() && !stoppedForWantOfAnAwsCli(result)) {
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
        @DisplayName("credential isolation is unconditional: it is invoked above the CLI branch, not inside it")
        void credentialIsolationPrecedesTheCliBranch() {
            final String source = source();
            final int invocation = source.indexOf("\nisolate_local_credentials\n");
            final int branch = source.indexOf("if command -v awslocal");

            assertThat(invocation)
                    .as("the isolation must be invoked as a bare statement. Placing the call inside either "
                            + "arm of the branch is what left one CLI path unprotected")
                    .isGreaterThan(0);
            assertThat(invocation)
                    .as("awslocal is a thin front end over the same AWS CLI and resolves the same ambient "
                            + "credential chain, so isolating only the fallback arm protects the path that "
                            + "was never at issue and leaves the documented path exposed. Hoisting the call "
                            + "above the branch is what makes the protection apply to whichever CLI is "
                            + "selected")
                    .isLessThan(branch);
            assertThat(source.indexOf("isolate_local_credentials", branch))
                    .as("and there must be no second, per-arm invocation left behind inside the branch, "
                            + "which would suggest the hoist was additive rather than a move")
                    .isEqualTo(-1);
        }

        @Test
        @DisplayName("the endpoint guard emits no part of the value it refused, only its length")
        void theEndpointGuardEchoesNothingItRefused() {
            final String body = extract("require_local_endpoint() {", "\n}\n");

            // The value must still be READ - it is matched against patterns and decomposed into a
            // scheme, an authority and a host. What it must never be is EMITTED. So the assertion
            // is not that the body never mentions the value, which would forbid validating it, but
            // that every mention is a match or an assignment rather than a diagnostic argument.
            final List<String> emittingReferences = body.lines()
                    .map(String::strip)
                    .filter(line -> line.replace("${#url}", "").contains("${url}"))
                    .filter(line -> !line.startsWith("case "))
                    .filter(line -> !line.startsWith("local url="))
                    .filter(line -> !line.contains("=~"))
                    .filter(line -> !line.contains("remainder=\"${url#"))
                    // A bracket test comparing the value against glob patterns is the same category as a
                    // case: it READS the value and emits nothing. The control-byte refusal is written that
                    // way because the three patterns are alternatives of one condition rather than arms with
                    // separate bodies. The emission that would follow such a test is a fail line of its own,
                    // which this filter does not reach, so the guard keeps its teeth.
                    .filter(line -> !(line.startsWith("if [[") || line.startsWith("elif [["))
                            || !(line.contains("==") || line.contains("!=")))
                    .toList();

            assertThat(emittingReferences)
                    .as("a refusal message that quotes the value writes any embedded credential or token "
                            + "into the log, and reproduces whatever control bytes it carried. Every line "
                            + "that names the value must therefore be a pattern match or a decomposition, "
                            + "never a fail argument. Offending lines:%n%s", emittingReferences)
                    .isEmpty();
            assertThat(body)
                    .as("the length is the one url-derived fact that is safe to state, because ${#url} is a "
                            + "count rather than the value, and an operator needs it to understand the "
                            + "bound that was broken")
                    .contains("${#url} characters");
            assertThat(body)
                    .as("the byte rule must be applied before the structural checks, because those checks "
                            + "are the ones whose messages name the host, and a control byte reaching them "
                            + "would be echoed as part of it")
                    .satisfies(text -> assertThat(text.indexOf("ENDPOINT_CHARACTER_PATTERN"))
                            .isGreaterThan(0)
                            .isLessThan(text.indexOf("embeds userinfo before the host")));
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
    // 5 - A refusal must be actionable without being a disclosure.
    //     The guard recognises hostile input; it must not republish it.
    // ==================================================================

    /**
     * The secret-hygiene and log-integrity contract of a refusal.
     *
     * <p>The file header of {@code init-aws.sh} states that nothing in it may ever emit a secret. The endpoint
     * guard used to interpolate the rejected value into every refusal message, which broke that claim in two
     * distinct ways, and the two failure modes are separated below because they need different evidence.
     *
     * <ul>
     *   <li><strong>Disclosure.</strong> {@code http://user:password@host} is refused <em>because</em> it may
     *       carry a credential - and the refusal wrote that credential to stderr, where CI archives it. The
     *       guard identified the secret and published it in the same statement.</li>
     *   <li><strong>Log forgery.</strong> Every line the script writes opens with a fixed
     *       {@code [init-aws] <timestamp> <step>} prefix, so a CR or LF inside an echoed value forges further
     *       lines in exactly the shape a reader trusts. The invalid-port branch made this trivially
     *       reachable, because a value that failed a strict numeric test can contain anything at all.</li>
     * </ul>
     *
     * <p>Each test therefore asserts two things rather than one: that the value is still <em>refused</em>, so
     * hardening the message did not weaken the guard, and that a distinctive sentinel from the input appears
     * nowhere in the output. The sentinels are deliberately improbable strings, so a passing assertion cannot
     * be a coincidence of short substrings.
     */
    @Nested
    @DisplayName("5. A refusal names the variable and a closed reason, never the rejected value")
    class RefusalDisclosesNothing {

        @Test
        @DisplayName("a userinfo credential is refused and appears nowhere in the output")
        void aUserinfoCredentialIsNeverEchoed() {
            final String password = "Zq7-tripwire-PASSWORD-do-not-log";
            final Result result =
                    run(Map.of("AWS_ENDPOINT_URL", "http://operator:" + password + "@localhost:4566"));

            assertThat(result.exitCode())
                    .as("the value is still refused: hardening the message must not soften the guard")
                    .isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("the refusal names the variable and a closed reason token")
                    .contains("AWS_ENDPOINT_URL")
                    .contains("[reason=USERINFO_PRESENT]");
            assertThat(result.output())
                    .as("and neither the password nor the user component reaches the log. This is the whole "
                            + "point: the branch that fires here is the one most likely to be holding a "
                            + "credential, so it is the one that must say least about what it saw.")
                    .doesNotContain(password)
                    .doesNotContain("operator:")
                    .doesNotContain("operator@");
        }

        @ParameterizedTest(name = "a {0} payload is refused as a control character and never echoed")
        @CsvSource({
            "carriage-return-and-newline, '\\r\\n'",
            "bare-newline,                '\\n'",
            "bare-carriage-return,        '\\r'",
            "tab,                         '\\t'",
        })
        @DisplayName("a control character cannot enter the log, so no line can be forged")
        void aControlCharacterCannotForgeALogLine(final String shape, final String escaped) {
            final String injected = escaped
                    .replace("\\r", "\r")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t");
            final String forgery = "[init-aws] 1970-01-01T00:00:00Z summary                FORGED-BY-" + shape;
            final Result result =
                    run(Map.of("AWS_ENDPOINT_URL", "http://localhost:4566" + injected + forgery));

            assertThat(result.exitCode())
                    .as("a control character is refused outright, before any parsing, so no later branch "
                            + "has to be the one that happens to catch it")
                    .isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("the reason is the dedicated closed token rather than an incidental downstream "
                            + "verdict such as an invalid port")
                    .contains("[reason=CONTROL_CHARACTER]");
            assertThat(result.output())
                    .as("and the payload does not appear, so the forged line does not exist")
                    .doesNotContain("FORGED");

            assertThat(result.output().lines().filter(line -> !line.isBlank()).toList())
                    .as("every non-blank line still carries the fixed prefix. This is the log-integrity "
                            + "assertion proper: doesNotContain proves the payload was dropped, and this "
                            + "proves nothing else slipped the line discipline either.")
                    .allSatisfy(line -> assertThat(line).startsWith("[init-aws] "));
        }

        @ParameterizedTest(name = "[{0}] is refused without quoting the sentinel {1}")
        @CsvSource({
            "'http://localhost:4566/Zq7SentinelPath',   'Zq7SentinelPath',   '[reason=PATH_PRESENT]'",
            "'http://localhost:99999Zq7SentinelPort',   'Zq7SentinelPort',   '[reason=PORT_INVALID]'",
            "'http://zq7sentinelhost.example.com:4566', 'zq7sentinelhost',   '[reason=HOST_NOT_ALLOWLISTED]'",
            "'http://localhost:4566?x=Zq7SentinelQry',  'Zq7SentinelQry',    '[reason=QUERY_OR_FRAGMENT]'",
            "'http://%Zq7SentinelEnc:4566',             'Zq7SentinelEnc',    '[reason=PERCENT_ENCODED]'",
            "'gopher://Zq7SentinelScheme:4566',         'Zq7SentinelScheme', '[reason=SCHEME_NOT_HTTP]'",
        })
        @DisplayName("no rejection branch quotes the component it rejected")
        void noRejectionBranchQuotesItsInput(final String endpoint, final String sentinel, final String reason) {
            final Result result = run(Map.of("AWS_ENDPOINT_URL", endpoint));

            assertThat(result.exitCode()).isEqualTo(EXIT_CONFIG);
            assertThat(result.output())
                    .as("each branch reports a token from the closed set, so the reason stays actionable")
                    .contains(reason);
            assertThat(result.output())
                    .as("and none of them quotes the value back. Enumerating every branch matters because "
                            + "the defect was per-branch: one surviving interpolation anywhere would leave "
                            + "the guarantee false while the other branches looked clean.")
                    .doesNotContain(sentinel);
        }

        @Test
        @DisplayName("the guard body interpolates no rejected component into any refusal message")
        void theGuardBodyInterpolatesNoRejectedComponent() {
            final String body = extract("require_local_endpoint() {", "\n}\n")
                    + extract("validate_endpoint_port() {", "\n}\n");

            // Only the lines that form a refusal are examined: the success path legitimately names the
            // ACCEPTED host, which by then has been proved equal to a member of a closed literal set.
            final String refusals = body.lines()
                    .filter(line -> !line.stripLeading().startsWith("#"))
                    .filter(line -> line.contains("AWS_ENDPOINT_URL ") || line.contains("reason="))
                    .reduce("", (left, right) -> left + right + "\n");

            assertThat(refusals)
                    .as("a non-trivial set of refusal lines was found, so an empty pass is impossible")
                    .contains("[reason=");
            assertThat(refusals)
                    .as("""
                        and not one of them interpolates the rejected input. These four expansions are the \
                        exact spellings the previous revision used, so this assertion is a regression pin \
                        on the specific defect rather than a general prohibition: ${url} carried the whole \
                        value including any userinfo, ${path}, ${port} and ${authority} carried the \
                        component each branch had just objected to.""")
                    .doesNotContain("${url}")
                    .doesNotContain("${path}")
                    .doesNotContain("${port}")
                    .doesNotContain("${authority}");
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
     * Whether the run stopped at the CLI-selection gate for want of an AWS CLI on {@code PATH}.
     *
     * <p>{@code init-aws.sh} selects {@code awslocal}, else {@code aws}, else refuses with {@code EXIT_CONFIG}
     * tagged {@code config:cli}. That gate sits strictly after the whole configuration-validation block and
     * strictly before the readiness gate, so reaching it is itself positive proof that validation accepted
     * every value supplied - but it borrows the same exit code a rejection uses, which makes the bare code an
     * ambiguous oracle for the acceptance cases below.
     *
     * <p>It reports an ambient property of the host rather than anything about the script's guards. In a
     * JDK-only build container - which is where this suite runs during a container image build - neither
     * command is present; dropping in a stub {@code aws} whose entire body is {@code exit 255} moves the very
     * same run from code 2 to code 3 without one line of endpoint or range logic executing differently. The
     * acceptance cases therefore rest on the script's own explicit verdict in its output, which is decisive in
     * every environment, and consult the exit code only when this gate did not intercept the run. That keeps
     * the suite true to its stated contract of needing no container and provisioning nothing.
     *
     * @param result the completed run to inspect
     * @return {@code true} when the run ended at the CLI-selection gate rather than at a verdict on its input
     */
    private static boolean stoppedForWantOfAnAwsCli(final Result result) {
        return result.output().contains("config:cli");
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

    // ==================================================================
    // 5 - The delivery boundary: an invisibility window that outlasts the work,
    //     and a topic that has somewhere to deliver to.
    // ==================================================================

    @Nested
    @DisplayName("5. The queue window and the notification subscription are provisioned, not assumed")
    class DeliveryBoundary {

        @Test
        @DisplayName("the FIFO queue is created with an explicit visibility timeout, not the service default")
        void theQueueCarriesAnExplicitVisibilityTimeout() {
            // FINDING M-02, severity High. The queue was created with only FifoQueue and
            // ContentBasedDeduplication set, so its invisibility window was the service default of 30
            // seconds while the job the message triggers runs for minutes. The message therefore became
            // visible again mid-run and was redelivered against its own still-running job.
            final String source = source();

            assertThat(source)
                    .as("the window must be declared as a named constant so the listener can be checked "
                            + "against the same number")
                    .containsPattern("readonly QUEUE_VISIBILITY_TIMEOUT_SECONDS='\\d+'");
            assertThat(extract("readonly QUEUE_ATTRIBUTES=", "\n"))
                    .as("and it must actually be passed on create-queue; a constant nothing reads would "
                            + "leave the default in place exactly as before")
                    .contains("VisibilityTimeout=${QUEUE_VISIBILITY_TIMEOUT_SECONDS}");

            final int declared = Integer.parseInt(source
                    .replaceAll("(?s).*readonly QUEUE_VISIBILITY_TIMEOUT_SECONDS='(\\d+)'.*", "$1"));
            assertThat(declared)
                    .as("a report run backs up the cluster, sorts a generation and writes a report, so the "
                            + "window has to be minutes; and SQS caps it at 12 hours")
                    .isGreaterThan(60)
                    .isLessThanOrEqualTo(43_200);
        }

        @Test
        @DisplayName("the window is read back and converged, so a pre-existing queue is corrected not trusted")
        void theWindowIsReadBackAndConverged() {
            final String body = extract("verify_queue() {", "\n}\n");

            assertThat(body)
                    .as("create-queue is a no-op against an existing queue, so a queue created by an "
                            + "earlier revision would keep the 30-second window that caused the finding. "
                            + "Reading it back is the only way that state is ever corrected")
                    .contains("VisibilityTimeout")
                    .contains("set-queue-attributes");
            assertThat(body)
                    .as("and a divergence that cannot be converged has to be fatal, not logged")
                    .contains("fail ");
        }

        @Test
        @DisplayName("the report queue has a dead-letter target, so a message no consumer can act on leaves "
                + "the group")
        void theReportQueueHasADeadLetterTarget() {
            // FINDING, severity MAJOR. The queue was provisioned with no RedrivePolicy at all, and every
            // submission travels in ONE FIFO message group. An ordered group cannot deliver past the message
            // at its head, so a submission the producer legitimately accepts but the consumer can never run
            // was redelivered every visibility window for the queue's whole four-day retention - roughly 384
            // attempts - and starved every valid submission behind it while the submission endpoint kept
            // answering 202.
            final String source = source();

            assertThat(source)
                    .as("the count must be a named constant, so a reader can see the retry budget rather "
                            + "than find it inlined in a JSON literal")
                    .containsPattern("readonly QUEUE_MAX_RECEIVE_COUNT='\\d+'");
            assertThat(extract("readonly DLQ_PHYSICAL=", "\n"))
                    .as("the dead-letter name must be DERIVED from the report queue's rather than configured "
                            + "separately: two independent variables can be pointed at each other's queue, "
                            + "or at the same one, and a queue that is its own dead-letter target quarantines "
                            + "nothing. It must also end in .fifo, which AWS requires of the target of a "
                            + "FIFO queue")
                    .contains("${QUEUE_LOGICAL}")
                    .contains("-dlq.fifo");

            final int declared = Integer.parseInt(source
                    .replaceAll("(?s).*readonly QUEUE_MAX_RECEIVE_COUNT='(\\d+)'.*", "$1"));
            assertThat(declared)
                    .as("one would quarantine a submission on a single transient store outage; a large count "
                            + "multiplied by the 900-second window is measured in hours of head-of-line "
                            + "blocking")
                    .isGreaterThan(1)
                    .isLessThanOrEqualTo(10);

            final String body = extract("ensure_redrive_policy() {", "\n}\n");
            assertThat(body)
                    .as("the policy has to be APPLIED, not merely described")
                    .contains("set-queue-attributes")
                    .contains("RedrivePolicy");
            assertThat(body)
                    .as("and READ BACK, because RedrivePolicy is mutable and every volume provisioned before "
                            + "this revision carries none - which is exactly the state that produced the "
                            + "finding, and which create-queue against an existing queue never corrects")
                    .contains("get-queue-attributes");
            assertThat(body)
                    .as("a policy that read back without naming the dead-letter queue, or with the wrong "
                            + "count, must be fatal rather than logged: a policy that reads as configured "
                            + "and quarantines nothing is the worst of the three outcomes")
                    .contains("fail ");
            assertThat(body.indexOf("set-queue-attributes"))
                    .as("set then read, in that order; reading first would assert the state this function "
                            + "was called to establish")
                    .isLessThan(body.indexOf("get-queue-attributes"));

            final String resolver = extract("dead_letter_queue_arn() {", "\n}\n");
            assertThat(resolver)
                    .as("the value-returning resolver must not log, for the reason notification_inbox_arn "
                            + "documents: log() writes to stdout, so a captured value would carry the "
                            + "progress lines with it and the policy would name something that is not a queue")
                    .doesNotContain("\n  log ");
            assertThat(resolver)
                    .as("and the captured value must be proven to be an sqs ARN before it is used as a "
                            + "redrive target, not merely proven non-empty")
                    .contains("arn:aws:sqs:*");

            final int dlqEnsured = source.indexOf("ensure_queue \"${DLQ_PHYSICAL}\"");
            final int policyApplied = source.indexOf("ensure_redrive_policy \"${QUEUE_PHYSICAL}\"");
            assertThat(dlqEnsured)
                    .as("main() must provision the dead-letter queue itself, so a fresh stack is correct "
                            + "without a manual step")
                    .isGreaterThan(-1);
            assertThat(dlqEnsured)
                    .as("and provision it BEFORE the policy that names it by ARN, because an ARN cannot be "
                            + "resolved for a queue that does not exist")
                    .isLessThan(policyApplied);
        }

        @Test
        @DisplayName("the topic requires at least one subscription: zero is fatal, not verified")
        void theTopicRequiresASubscriber() {
            // FINDING M-04, severity High. The hook must not assert a count of ZERO subscriptions, which
            // is the one state in which SNS accepts every publish and discards it. Operator notification
            // would then be inert while every publish reported success.
            final String source = source();

            assertThat(source)
                    .as("the function that asserted emptiness must be gone, not merely bypassed")
                    .doesNotContain("verify_no_subscriptions");
            assertThat(source)
                    .as("and the hook must provision the subscriber itself, so a fresh stack is correct "
                            + "without a manual step")
                    .contains("ensure_notification_subscription")
                    .contains("verify_notification_subscription");

            final String verification = extract("verify_notification_subscription() {", "\n}\n");
            assertThat(verification)
                    .as("zero has to be the failing case now")
                    .contains("fail ");
        }

        @Test
        @DisplayName("the subscription is read before it is written, so repeated runs do not accumulate copies")
        void theSubscriptionIsIdempotent() {
            // Guard on the ordering inside the M-04 fix, which is what makes the hook converge whether or
            // not the edge deduplicates a repeated subscribe.
            //
            // This comment previously justified the guard by asserting that the LocalStack edge creates a
            // second subscription for a repeated topic/protocol/endpoint triple, so that each compose cycle
            // added a copy of every notification. That does not reproduce on the pinned
            // localstack/localstack:4.14.0 image: a repeated triple returns the existing subscription ARN
            // and the count stays at one, which is the behaviour AWS documents for Subscribe. The claim is
            // withdrawn rather than restated here or in the script.
            //
            // The assertions below are unchanged, because what they check was never the disputed part.
            // Conformance on this point belongs to the emulator image rather than to the API, so an image
            // bump can change it with nothing in this repository changing; reading the set before writing
            // costs one list call and removes the dependency on that answer entirely. The count itself is
            // asserted from outside, by the provisioned-inventory step of .github/workflows/build.yml.
            final String body = extract("ensure_notification_subscription() {", "\n}\n");

            assertThat(body)
                    .as("the existing set must be consulted first")
                    .contains("list-subscriptions-by-topic");
            assertThat(body.indexOf("list-subscriptions-by-topic"))
                    .as("and consulted BEFORE subscribing, which is the whole property: the ordering is "
                            + "what makes a repeat run a no-op instead of another copy")
                    .isLessThan(body.indexOf("sns subscribe"));
            assertThat(body)
                    .as("an already-subscribed inbox must short-circuit rather than fall through")
                    .contains("return 0");
        }

        @Test
        @DisplayName("the captured inbox ARN is validated as an ARN, because log output goes to stdout")
        void theInboxArnIsValidatedAsAnArn() {
            // Regression guard for the sharper defect the same investigation exposed. log() writes to
            // STDOUT, so a value captured with $(...) from a function that logs contains the progress
            // lines too. SNS accepted that concatenation as an endpoint, so the hook reported a verified
            // subscription pointing at something that was not a queue - finding M-04 reintroduced by its
            // own fix, and invisible to a non-empty check.
            final String resolver = extract("notification_inbox_arn() {", "\n}\n");

            assertThat(resolver)
                    .as("the value-returning resolver must not log; creation and logging belong to the "
                            + "separate ensure_notification_inbox, exactly as the pre-existing topic_arn "
                            + "helper is log-free")
                    .doesNotContain("\n  log ");
            assertThat(source())
                    .as("and the captured value must be proven to be a bare sqs ARN before it is used as "
                            + "an endpoint")
                    .contains("!= arn:aws:sqs:*")
                    .contains("ensure_notification_inbox() {");
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
