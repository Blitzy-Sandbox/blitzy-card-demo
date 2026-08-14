/*
 * ******************************************************************
 * Program     : AwsEndpointAllowlistTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (cloud endpoint fail-closed guard)
 * Function    : Proves that no cloud client in this application can be
 *               configured to reach live AWS. An absent endpoint override
 *               aborts startup rather than falling back to the SDK's
 *               standard endpoint resolution, and a configured endpoint is
 *               admitted only when its host is an approved emulator host.
 *               Also proves the production profile supplies the three
 *               mandatory overrides with no committed default and carries
 *               no credential block.
 * Source      : Agent Action Plan sections 0.3.2 and 0.8.4 - live AWS
 *               accounts and real credentials are out of scope and no code
 *               path may reach a real AWS endpoint; pom.xml states that
 *               every client targets a LocalStack endpoint override.
 *               Guard under test: config/AwsConfig.java
 *               requireEmulatorEndpoints / requireApprovedEndpoint.
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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the fail-closed endpoint guard on {@link AwsConfig} and the production profile that feeds it.
 *
 * <p>The finding this test pins was High severity and inverted: the production profile documented an
 * "invariant enforced by absence", declaring that it deliberately configured <em>no</em> endpoint override
 * so that "production resolves every AWS service through the SDK's standard endpoint resolution". Standard
 * endpoint resolution is precisely what reaches live AWS, so the documented safeguard was the violation. An
 * absent override is the one setting that silently produces live routing, which is why the guard treats
 * absence as a failure rather than as a neutral default.
 */
@DisplayName("AWS endpoint allowlist: no configuration can reach live AWS")
class AwsEndpointAllowlistTest {

    /** A LocalStack endpoint that must be admitted, used wherever one valid value is needed. */
    private static final String EMULATOR = "http://localhost:4566";

    /** The production profile, read as text so the assertions are about the committed file. */
    private static final Path PROD_PROFILE =
            Path.of("src", "main", "resources", "application-prod.yml");

    /**
     * Returns the mapping that begins at {@code from}, up to the next line indented no further than the
     * mapping key itself. Used so that an endpoint assertion cannot accidentally read a sibling block.
     *
     * @param text the whole profile
     * @param from the index of the first character of the mapping key line
     * @return the mapping's own text, key line included
     */
    private static String nextMapping(final String text, final int from) {
        final int keyLineEnd = text.indexOf('\n', from);
        final String keyLine = keyLineEnd < 0 ? text.substring(from) : text.substring(from, keyLineEnd);
        final int keyIndent = keyLine.length() - keyLine.stripLeading().length();
        int cursor = keyLineEnd < 0 ? text.length() : keyLineEnd + 1;
        while (cursor < text.length()) {
            final int lineEnd = text.indexOf('\n', cursor);
            final String line = lineEnd < 0 ? text.substring(cursor) : text.substring(cursor, lineEnd);
            final int lineIndent = line.length() - line.stripLeading().length();
            if (!line.isBlank() && lineIndent <= keyIndent) {
                return text.substring(from, cursor);
            }
            if (lineEnd < 0) {
                break;
            }
            cursor = lineEnd + 1;
        }
        return text.substring(from);
    }

    /**
     * Builds a configuration with the given per-service and global endpoint values, leaving every other
     * constructor argument at a valid value so that only the endpoint guard can fail.
     *
     * @param s3 the value of {@code spring.cloud.aws.s3.endpoint}
     * @param sqs the value of {@code spring.cloud.aws.sqs.endpoint}
     * @param sns the value of {@code spring.cloud.aws.sns.endpoint}
     * @param global the value of {@code spring.cloud.aws.endpoint}
     * @return the constructed configuration, when the guard admits the values
     */
    private static AwsConfig configure(final String s3, final String sqs, final String sns,
            final String global) {
        return new AwsConfig(
                "us-east-1",
                "carddemo-batch-input",
                "carddemo-batch-output",
                "carddemo-statements",
                "carddemo-report-jobs.fifo",
                "carddemo-report-jobs",
                "carddemo-notifications",
                QueueNotFoundStrategy.FAIL,
                s3, sqs, sns, global, "test", "test");
    }

    /**
     * Builds a configuration in which all three per-service endpoints carry the same value.
     *
     * @param endpoint the value applied to all three per-service endpoint properties
     * @return the constructed configuration, when the guard admits the value
     */
    private static AwsConfig configureAll(final String endpoint) {
        return configure(endpoint, endpoint, endpoint, "");
    }

    /** Absence must abort startup, because absence is what produces live routing. */
    @Nested
    @DisplayName("Absence fails closed")
    class AbsenceFailsClosed {

        @Test
        @DisplayName("no endpoint of any kind aborts construction")
        void noEndpointAtAllIsRejected() {
            assertThatThrownBy(() -> configure("", "", "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint")
                    .hasMessageContaining("An absent override is not a neutral setting")
                    .hasMessageContaining("reach live AWS");
        }

        @ParameterizedTest(name = "a null value for endpoint slot {0} aborts construction")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("a null per-service endpoint is treated as absent, not as permission")
        void aNullPerServiceEndpointIsRejected(final int slot) {
            final String[] values = {EMULATOR, EMULATOR, EMULATOR};
            values[slot] = null;
            assertThatThrownBy(() -> configure(values[0], values[1], values[2], ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resolved to no value");
        }

        @ParameterizedTest(name = "blank value \"{0}\" is treated as absent")
        @ValueSource(strings = {" ", "   ", "\t", "\n"})
        @DisplayName("a whitespace-only endpoint is absence, not a value")
        void aBlankEndpointIsRejected(final String blank) {
            assertThatThrownBy(() -> configureAll(blank))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("resolved to no value");
        }

        @Test
        @DisplayName("a global endpoint satisfies a service that declares none")
        void theGlobalEndpointCoversAnAbsentPerServiceValue() {
            assertThatCode(() -> configure("", "", "", EMULATOR)).doesNotThrowAnyException();
        }
    }

    /** A host outside the allowlist must be refused however plausible it looks. */
    @Nested
    @DisplayName("Unapproved hosts are refused")
    class UnapprovedHostsAreRefused {

        @ParameterizedTest(name = "{0} is refused")
        @ValueSource(strings = {
            "https://s3.amazonaws.com",
            "https://s3.us-east-1.amazonaws.com",
            "https://sqs.eu-west-2.amazonaws.com",
            "https://sns.amazonaws.com",
            "https://s3.dualstack.us-east-1.amazonaws.com",
            "http://169.254.169.254",
            "http://example.com:4566",
        })
        @DisplayName("a real AWS or third-party host cannot be configured")
        void aLiveHostIsRefused(final String endpoint) {
            assertThatThrownBy(() -> configureAll(endpoint))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not name one of the approved local emulator hosts");
        }

        @ParameterizedTest(name = "{0} is refused - exact membership, not substring")
        @ValueSource(strings = {
            "http://localhost.attacker.example:4566",
            "http://s3.amazonaws.com.localstack:4566",
            "http://localstack.attacker.example:4566",
            "http://notlocalhost:4566",
            "http://localhostx:4566",
            "http://carddemo-localstackattacker.example:4566",
            "http://evil-carddemo-localstack:4566",
            "http://127.0.0.1.attacker.example:4566",
        })
        @DisplayName("a host that merely contains an approved name is still refused")
        void aLookAlikeHostIsRefused(final String endpoint) {
            assertThatThrownBy(() -> configureAll(endpoint))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not name one of the approved local emulator hosts");
        }

        @Test
        @DisplayName("the refusal names the property and the defect but echoes no part of the value")
        void theRefusalDoesNotEchoTheValue() {
            // Not even the host. Requiring the host to be quoted is tempting on the reasonable ground
            // that it is the diagnosis and is not itself secret. The guard withholds it anyway, and the
            // reason is that a refused endpoint is by definition one nobody vetted: it may carry user
            // information, and the parse that would separate host from credential is the parse this
            // message is reporting the failure of. An operator can read their own configuration to see
            // which value it was; a log aggregator cannot un-see a credential. The property key and the
            // defect are named, so the message still says what to fix rather than only that something broke.
            final String endpoint = "https://storage.example.com/secret-path";
            assertThatThrownBy(() -> configureAll(endpoint))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.s3.endpoint")
                    .hasMessageContaining("carries a path")
                    .hasMessageNotContaining("secret-path")
                    .hasMessageNotContaining("storage.example.com");
        }

        @Test
        @DisplayName("an unapproved global endpoint is refused even when every service declares one")
        void anUnapprovedGlobalEndpointIsRefused() {
            assertThatThrownBy(
                    () -> configure(EMULATOR, EMULATOR, EMULATOR, "https://s3.amazonaws.com"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws.endpoint")
                    .hasMessageContaining("does not name one of the approved local emulator hosts");
        }
    }

    /** Scheme and userinfo are refused independently of the host. */
    @Nested
    @DisplayName("Scheme and userinfo are constrained")
    class SchemeAndUserInfo {

        @ParameterizedTest(name = "scheme in {0} is refused")
        @ValueSource(strings = {
            "ftp://localhost:4566",
            "file://localhost/tmp",
            "ws://localhost:4566",
            "localhost:4566",
        })
        @DisplayName("only http and https are admitted")
        void anUnapprovedSchemeIsRefused(final String endpoint) {
            assertThatThrownBy(() -> configureAll(endpoint))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a lowercase http or https URL");
        }

        @Test
        @DisplayName("credentials in the URL are refused outright")
        void userInfoIsRefused() {
            assertThatThrownBy(() -> configureAll("http://user:secret@localhost:4566"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("embeds userinfo before the host")
                    .hasMessageNotContaining("secret");
        }

        @Test
        @DisplayName("HTTPS on an approved host is admitted, so transport is not the constraint")
        void httpsOnAnApprovedHostIsAdmitted() {
            assertThatCode(() -> configureAll("https://localhost.localstack.cloud:4566"))
                    .doesNotThrowAnyException();
        }
    }

    /** Every host the compose stack and the container test tier actually use must be admitted. */
    @Nested
    @DisplayName("Approved emulator hosts are admitted")
    class ApprovedHostsAreAdmitted {

        @ParameterizedTest(name = "{0} is admitted")
        @ValueSource(strings = {
            "http://localhost:4566",
            "http://127.0.0.1:4566",
            "http://[::1]:4566",
            "http://localhost.localstack.cloud:4566",
            "http://localstack:4566",
            "http://carddemo-localstack:4566",
            "http://carddemo-localstack-000:4566",
        })
        @DisplayName("the compose service, the loopback forms and the emulator DNS name all pass")
        void anApprovedHostIsAdmitted(final String endpoint) {
            assertThatCode(() -> configureAll(endpoint)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an underscore in the authority is refused, having no parseable host")
        void anUnderscoreAuthorityIsRefused() {
            // java.net.URI.getHost() returns null for an authority containing an underscore, so the value
            // never reaches the allowlist comparison. Asserted so that the absence of an underscore branch
            // in AwsConfig is recorded as unreachable rather than as an oversight.
            assertThatThrownBy(() -> configureAll("http://carddemo-localstack_000:4566"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("names no host");
        }

        @Test
        @DisplayName("the host is matched case-insensitively")
        void theHostComparisonIsCaseInsensitive() {
            // Host names are case-insensitive per RFC 3986, and java.net.URI preserves whatever case was
            // written, so the guard lower-cases before the membership test. This is a security property and
            // not a convenience: without it, an upper-cased spelling of a real service host would miss a
            // case-sensitive comparison. The allowlist is unaffected either way - an upper-cased live host
            // lower-cases to a live host, which is still not a member.
            assertThatCode(() -> configureAll("http://LOCALHOST:4566")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the scheme, unlike the host, is compared as written")
        void theSchemeComparisonIsCaseSensitive() {
            // The asymmetry is deliberate. The provisioning script's scheme comparison is case-sensitive,
            // and localstack-init/init-aws.sh is the other half of this allowlist: a value it refuses must
            // not start an application that then cannot be provisioned. Canonicalising the scheme here would
            // have made the two halves disagree on exactly one input shape.
            assertThatThrownBy(() -> configureAll("HTTP://localhost:4566"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a lowercase http or https URL");
        }

        @Test
        @DisplayName("an arbitrary port is admitted, because the host is the security-bearing component")
        void thePortIsNotConstrained() {
            // The integration tier binds to whatever port Testcontainers publishes, so pinning the value to
            // the compose edge would fail that whole tier on a legitimately approved emulator.
            assertThatCode(() -> configureAll("http://localhost:49213")).doesNotThrowAnyException();
            assertThatCode(() -> configureAll("http://localhost:1")).doesNotThrowAnyException();
            assertThatCode(() -> configureAll("http://localhost:65535")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("but a port must be present, because the emulator's edge is neither 80 nor 443")
        void aPortlessEndpointIsRefused() {
            // Unconstrained in value is not the same as optional. Admitting the portless form
            // is a different claim and one nothing in this repository relies on: every
            // endpoint the compose file, the environment template, the four profiles and the provisioning
            // script name carries a port. A portless value would resolve to 80 or 443, reach nothing, and
            // surface as a connection failure at the first call rather than as a named failure at startup.
            assertThatThrownBy(() -> configureAll("http://localhost"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carries no explicit port");
        }

        @Test
        @DisplayName("surrounding whitespace is stripped rather than rejected")
        void surroundingWhitespaceIsStripped() {
            assertThatCode(() -> configureAll("  " + EMULATOR + "  ")).doesNotThrowAnyException();
        }
    }

    /** The committed production profile must supply the overrides and no credentials. */
    @Nested
    @DisplayName("application-prod.yml fails closed")
    class ProductionProfileFailsClosed {

        /**
         * Reads the committed production profile.
         *
         * @return the file content
         * @throws IOException if the profile cannot be read, which is itself a failure
         */
        private String profile() throws IOException {
            assertThat(PROD_PROFILE).as("the production profile must exist").exists();
            return Files.readString(PROD_PROFILE, StandardCharsets.UTF_8);
        }

        @ParameterizedTest(name = "the prod profile declares a mandatory {0} endpoint")
        @ValueSource(strings = {"s3", "sqs", "sns"})
        @DisplayName("each service endpoint is overridden with no committed default")
        void eachServiceEndpointIsMandatory(final String service) throws IOException {
            final String text = profile();
            // The block sits at spring.cloud.aws.<service>, which is six spaces of indent in this file.
            final int serviceAt = text.indexOf("\n      " + service + ":\n");
            assertThat(serviceAt)
                    .as("the prod profile must declare a spring.cloud.aws.%s block", service)
                    .isGreaterThan(-1);
            final String block = nextMapping(text, serviceAt + 1);
            assertThat(block)
                    .as("the %s endpoint must be a mandatory placeholder with no default", service)
                    .contains("endpoint: ${AWS_ENDPOINT_URL}");
        }

        @Test
        @DisplayName("no endpoint placeholder carries a colon default that could resolve to empty")
        void noEndpointPlaceholderCarriesADefault() throws IOException {
            assertThat(profile())
                    .as("a default would restore the silent live-routing fallback")
                    .doesNotContain("endpoint: ${AWS_ENDPOINT_URL:");
        }

        @Test
        @DisplayName("the profile records that an ABSENT override is the live-routing defect")
        void theProfileRecordsWhyAnAbsentOverrideIsTheDefect() throws IOException {
            final String text = profile();
            // The hazard is counter-intuitive: leaving the endpoint unset looks conservative but is the one
            // setting that reaches live AWS, because the SDK's standard resolution supplies the real
            // regional endpoint. The profile must therefore state the positive invariant - the overrides are
            // mandatory and the file fails closed - rather than leave a reader to infer it.
            assertThat(text)
                    .as("the profile must say that an absent override is not a neutral setting")
                    .contains("An absent endpoint override is therefore NOT a neutral setting here")
                    .contains("FAILS CLOSED");
            assertThat(text)
                    .as("the profile must name the code-side allowlist that enforces the boundary")
                    .contains("AwsConfig.requireEmulatorEndpoints");
        }

        @Test
        @DisplayName("the profile still commits no credential material")
        void theProfileCommitsNoCredentials() throws IOException {
            // Assert on live YAML only. The comments discuss access-key and secret-key precisely in order
            // to record that neither is set, so a whole-file text search would fail on the documentation
            // rather than on a leak.
            final List<String> live = profile().lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith("*"))
                    .toList();
            assertThat(live).as("no credential key may be committed")
                    .noneMatch(line -> line.startsWith("access-key")
                            || line.startsWith("secret-key")
                            || line.startsWith("session-token")
                            || line.startsWith("credentials:"));
        }

        @Test
        @DisplayName("every live endpoint value is an environment placeholder, never a literal host")
        void everyLiveEndpointIsAPlaceholder() throws IOException {
            // The trailing space matters: `management.endpoint:` is a bare mapping key with no scalar and
            // is an unrelated part of this profile, so it must not be collected as a fourth endpoint.
            final List<String> endpoints = profile().lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("endpoint: "))
                    .toList();
            assertThat(endpoints).as("the three service overrides must all be present").hasSize(3);
            assertThat(endpoints).allSatisfy(line ->
                    assertThat(line).isEqualTo("endpoint: ${AWS_ENDPOINT_URL}"));
        }
    }
}
