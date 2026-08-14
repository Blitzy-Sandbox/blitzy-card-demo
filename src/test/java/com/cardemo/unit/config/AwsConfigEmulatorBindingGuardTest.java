/*
 * ****************************************************************************
 * Program     : AwsConfigEmulatorBindingGuardTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies that AwsConfig's startup guard makes the migration's
 *               emulator-only constraint structural: every cloud client must be
 *               bound to an allowlisted local endpoint with static placeholder
 *               credentials, and every other form - a live AWS host, an absent
 *               endpoint, a prefix look-alike, a userinfo form, the cloud
 *               instance-metadata address or the SDK's default credential chain
 *               - aborts the refresh before any client can be created.
 * Source      : app/jcl/DEFGDGB.jcl (7 GDG bases -> 3 S3 buckets)
 *               + app/csd/CARDDEMO.CSD:L499-L503 DEFINE TDQUEUE(JOBS) -> FIFO
 *               + localstack-init/init-aws.sh (the same host allowlist)
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards the one control that makes "no code path may reach a real AWS endpoint" true rather than intended.
 *
 * <p>AAP section 0.3.2 places live AWS accounts and real credentials out of scope and states the constraint
 * outright; section 0.8.4 repeats it. Before {@code AwsConfig}'s startup guard existed, three profiles
 * honoured it by convention and {@code application-prod.yml} deliberately did not - it documented that
 * production resolves every service through the SDK's standard endpoint resolution and let the ambient
 * instance or task credential chain supply a principal. Nothing checked. That was a Blocker.
 *
 * <p>The guard is exercised directly against a {@link MockEnvironment} rather than through a failed context
 * refresh. That is deliberate and is the cheaper, stricter choice: every rejected form can be enumerated
 * instead of sampled, each assertion names the exact defect, and no container, database or credential is
 * needed. The ordering property the guard also carries - that it runs before any client bean exists - is a
 * property of its being a {@code BeanFactoryPostProcessor} and is asserted separately in
 * {@link #theGuardIsPublishedAsABeanFactoryPostProcessor()}.
 */
class AwsConfigEmulatorBindingGuardTest {

    /** A well-formed emulator endpoint on the published edge port. */
    private static final String LOCAL_ENDPOINT = "http://localhost:4566";

    /** The emulator's documented placeholder credentials. Not a secret, and not a real principal. */
    private static final String PLACEHOLDER_CREDENTIAL = "test";


    /**
     * Runs the guard exactly as the container does: through the published {@code @Bean} factory method and the
     * {@code BeanFactoryPostProcessor} it returns. Going through the public wiring rather than reaching into a
     * package-private helper keeps the production surface unwidened and means these tests exercise the same
     * code path a real refresh takes, not a parallel one.
     *
     * @param environment the environment the guard should read
     */
    private static void runGuard(final Environment environment) {
        AwsConfig.cloudEmulatorBindingGuard(environment)
                .postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    /**
     * The seven generation-base prefix keys and the values {@code application.yml} declares for them, in the
     * order the guard reads them. Present in every fixture because the guard proves the whole object-store
     * layout, not only the endpoints: a fixture that omitted them would make every endpoint assertion below
     * fail for an unrelated reason.
     */
    private static final Map<String, String> GENERATION_PREFIXES = Map.of(
            "carddemo.aws.s3.gdg-prefixes.daly-rejs", "gdg/dalyrejs",
            "carddemo.aws.s3.gdg-prefixes.systran", "gdg/systran",
            "carddemo.aws.s3.gdg-prefixes.tcatbalf-bkup", "gdg/tcatbalf-bkup",
            "carddemo.aws.s3.gdg-prefixes.tranrept", "gdg/tranrept",
            "carddemo.aws.s3.gdg-prefixes.transact-bkup", "gdg/transact-bkup",
            "carddemo.aws.s3.gdg-prefixes.transact-combined", "gdg/transact-combined",
            "carddemo.aws.s3.gdg-prefixes.transact-daly", "gdg/transact-daly");

    private static MockEnvironment environmentWith(final String endpoint) {
        final MockEnvironment environment = new MockEnvironment();
        if (endpoint != null) {
            environment.setProperty("spring.cloud.aws.s3.endpoint", endpoint);
            environment.setProperty("spring.cloud.aws.sqs.endpoint", endpoint);
            environment.setProperty("spring.cloud.aws.sns.endpoint", endpoint);
        }
        environment.setProperty("spring.cloud.aws.credentials.access-key", PLACEHOLDER_CREDENTIAL);
        environment.setProperty("spring.cloud.aws.credentials.secret-key", PLACEHOLDER_CREDENTIAL);
        GENERATION_PREFIXES.forEach(environment::setProperty);
        return environment;
    }

    // ---------------------------------------------------------------------------------------------
    // Accepted forms. Every one of these is an address at which the emulator is genuinely reachable
    // in one of the three supported topologies: a host-side run, a run inside the Compose network,
    // or the integration tier against a Testcontainers-published ephemeral port.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost:4566",
        "http://localhost:4566/",
        "https://localhost:4566",
        "http://127.0.0.1:4566",
        "http://127.0.0.1:32773",
        "http://[::1]:4566",
        "http://localhost.localstack.cloud:4566",
        "http://localstack:4566",
        "http://carddemo-localstack:4566",
        "http://carddemo-localstack-007:4566",
        "HTTP://LOCALHOST:4566",
    })
    @DisplayName("accepts every address at which the emulator is actually reachable")
    void acceptsEmulatorAddresses(final String endpoint) {
        assertThatCode(() -> runGuard(environmentWith(endpoint)))
                .doesNotThrowAnyException();
    }

    /**
     * The integration tier binds to whatever host port Testcontainers publishes, which is ephemeral. The port
     * is therefore deliberately unrestricted while the host is not - unlike
     * {@code localstack-init/init-aws.sh}, which additionally pins the port because it only ever talks to the
     * Compose topology. The host is the security control.
     */
    @Test
    @DisplayName("an ephemeral Testcontainers port on a loopback host is accepted")
    void acceptsEphemeralLoopbackPort() {
        assertThatCode(() -> runGuard(environmentWith("http://localhost:49713")))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------------
    // Rejected forms. Each one is a route by which a request could leave the host, and each was a
    // real defect of the denylist design that localstack-init/init-aws.sh's own post-mortem records.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        // The live service edge, in the form a deployment would actually be given.
        "https://s3.us-east-1.amazonaws.com",
        // The same domain in capitals: the case-sensitive glob a denylist used let this through.
        "https://S3.US-EAST-1.AMAZONAWS.COM",
        // Any unforeseen host: a denylist's default branch accepted this.
        "http://evil.example.com:4566",
        // The cloud instance-metadata address, the classic credential-exfiltration target.
        "http://169.254.169.254:80",
        // A prefix look-alike. Starts with an accepted host and is not it, which is exactly why the
        // guard parses the URL instead of matching a prefix.
        "http://localhost.attacker.example:4566",
        "http://localstack.attacker.example:4566",
        // Userinfo hides the real host behind the credentials.
        "http://localhost:4566@attacker.example:80",
        // A clone-index suffix that is not one.
        "http://carddemo-localstack.attacker.example:4566",
        // The Docker host-gateway alias. It was accepted here until it was noticed that
        // localstack-init/init-aws.sh refuses it, so a value that passed this guard then failed provisioning -
        // and it reaches ANY service listening on the developer's host, not only the emulator. The
        // container-to-host topology is served by the Compose service name inside the bridge network, so
        // nothing needs it. Refusing it is what makes the two allowlists genuinely identical.
        "http://host.docker.internal:4566",
        // The fully-qualified spelling of an allowlisted name. localstack-init/init-aws.sh refuses it
        // in as many words - "Nothing else, in any case, with or without a trailing dot" - so accepting it here
        // would be drift pointing the other way: a value that starts the application and then fails
        // provisioning. No profile, compose file or setup instruction spells a host this way.
        "http://localhost.:4566",
    })
    @DisplayName("refuses every endpoint that is not an allowlisted emulator address")
    void refusesNonEmulatorAddresses(final String endpoint) {
        assertThatThrownBy(() -> runGuard(environmentWith(endpoint)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AAP section 0.3.2");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // No port, so the scheme default would be used and the address is not pinned.
        "http://localhost",
        // Not http or https.
        "ftp://localhost:4566",
        // A path beyond a single slash is not the emulator edge.
        "http://localhost:4566/some/path",
        // A query or fragment has no meaning on an endpoint override and hides intent.
        "http://localhost:4566?x=1",
        "http://localhost:4566#f",
        // Not a URL at all.
        "not a url",
    })
    @DisplayName("refuses malformed or under-specified endpoints rather than guessing")
    void refusesMalformedEndpoints(final String endpoint) {
        assertThatThrownBy(() -> runGuard(environmentWith(endpoint)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The exact defect {@code application-prod.yml} used to document as an invariant: no endpoint override at
     * all, so the SDK performs regional endpoint discovery and reaches a live address.
     */
    @Test
    @DisplayName("an absent endpoint override is refused, because the SDK would resolve a live address")
    void refusesAbsentEndpoint() {
        assertThatThrownBy(() -> runGuard(environmentWith(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("standard endpoint resolution");
    }

    /** A per-service key may be absent as long as the single global override supplies an allowlisted value. */
    @Test
    @DisplayName("the global endpoint override is honoured as a fallback and validated the same way")
    void honoursGlobalEndpointFallback() {
        final MockEnvironment accepted = new MockEnvironment();
        accepted.setProperty("spring.cloud.aws.endpoint", LOCAL_ENDPOINT);
        accepted.setProperty("spring.cloud.aws.credentials.access-key", PLACEHOLDER_CREDENTIAL);
        accepted.setProperty("spring.cloud.aws.credentials.secret-key", PLACEHOLDER_CREDENTIAL);
        GENERATION_PREFIXES.forEach(accepted::setProperty);
        assertThatCode(() -> runGuard(accepted)).doesNotThrowAnyException();

        final MockEnvironment refused = new MockEnvironment();
        refused.setProperty("spring.cloud.aws.endpoint", "https://sqs.eu-west-1.amazonaws.com");
        refused.setProperty("spring.cloud.aws.credentials.access-key", PLACEHOLDER_CREDENTIAL);
        refused.setProperty("spring.cloud.aws.credentials.secret-key", PLACEHOLDER_CREDENTIAL);
        assertThatThrownBy(() -> runGuard(refused))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A blank value must be treated as absent rather than accepted. An exported-but-empty variable is the
     * commonest way a required setting silently disappears.
     */
    @Test
    @DisplayName("a blank endpoint is treated as absent, not as configured")
    void refusesBlankEndpoint() {
        assertThatThrownBy(() -> runGuard(environmentWith("   ")))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Every service is checked, not only the first: an unbound queue client is as dangerous as a bucket. */
    @Test
    @DisplayName("each of the three services is checked independently")
    void checksEveryServiceIndependently() {
        for (final String service : new String[] {"s3", "sqs", "sns"}) {
            final MockEnvironment environment = environmentWith(LOCAL_ENDPOINT);
            environment.setProperty("spring.cloud.aws." + service + ".endpoint",
                    "https://" + service + ".us-east-1.amazonaws.com");
            assertThatThrownBy(() -> runGuard(environment))
                    .as(service)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.cloud.aws." + service + ".endpoint");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Credentials. Their presence is what replaces the SDK's default provider chain, so their absence
    // is a live-principal risk rather than a missing convenience.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.cloud.aws.credentials.access-key",
        "spring.cloud.aws.credentials.secret-key",
    })
    @DisplayName("an absent static credential is refused, so the default provider chain can never apply")
    void refusesAmbientCredentialChain(final String credentialKey) {
        final MockEnvironment environment = environmentWith(LOCAL_ENDPOINT);
        environment.setProperty(credentialKey, "");
        assertThatThrownBy(() -> runGuard(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(credentialKey)
                .hasMessageContaining("instance metadata");
    }

    /**
     * A failure message may name a key, a host and a remedy. It may never name a credential value, because a
     * startup failure is written to the log like anything else and Rule 1 Clause D keeps secrets out of logs.
     */
    @Test
    @DisplayName("no rejection message ever reports a credential value")
    void neverReportsACredentialValue() {
        final MockEnvironment environment = environmentWith("https://s3.amazonaws.com");
        environment.setProperty("spring.cloud.aws.credentials.access-key", "AKIAEXAMPLENOTREAL01");
        environment.setProperty("spring.cloud.aws.credentials.secret-key", "an-obvious-fake-secret-value");
        assertThatThrownBy(() -> runGuard(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("AKIAEXAMPLENOTREAL01")
                .hasMessageNotContaining("an-obvious-fake-secret-value");
    }

    // ---------------------------------------------------------------------------------------------
    // Ordering. "Before client creation" is the requirement, and it is a property of the bean type.
    // ---------------------------------------------------------------------------------------------

    /**
     * The guard must be a {@code BeanFactoryPostProcessor} and its factory method must be {@code static}. The
     * first is what puts the check ahead of {@code preInstantiateSingletons}, so no client bean can exist when
     * it runs; the second is what stops the containing configuration class being instantiated during
     * post-processing. Both are asserted reflectively because both are correctness properties of the wiring
     * rather than of any value.
     */
    @Test
    @DisplayName("the guard is a static-factory BeanFactoryPostProcessor, so it runs before any client bean")
    void theGuardIsPublishedAsABeanFactoryPostProcessor() throws NoSuchMethodException {
        final Method factoryMethod =
                AwsConfig.class.getDeclaredMethod("cloudEmulatorBindingGuard", Environment.class);

        assertThat(Modifier.isStatic(factoryMethod.getModifiers()))
                .as("a non-static @Bean method returning a post-processor forces premature instantiation")
                .isTrue();
        assertThat(factoryMethod.getReturnType()).isEqualTo(BeanFactoryPostProcessor.class);
        assertThat(factoryMethod.isAnnotationPresent(Bean.class)).isTrue();

        // The returned post-processor performs the check when invoked, and reports the same defect.
        final BeanFactoryPostProcessor guard =
                AwsConfig.cloudEmulatorBindingGuard(environmentWith(null));
        assertThatThrownBy(() -> guard.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // The generation-prefix layout. A base without a prefix has no namespace of its own, and two
    // bases whose prefixes match or nest share one, so neither can be listed independently. The
    // guard therefore proves all SEVEN bases of app/jcl/DEFGDGB.jcl and app/jcl/DALYREJS.jcl -
    // including AWS.M2.CARDDEMO.TCATBALF.BKUP at DEFGDGB.jcl:L43, the one base no Java job writes,
    // whose producer app/jcl/PRTCATBL.jcl has no COBOL program. Excluding it because nothing writes
    // it would be backwards: it is provisioned in the object store, so its prefix must be reserved
    // or a base that IS written could be given one that collides with it.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "carddemo.aws.s3.gdg-prefixes.daly-rejs",
        "carddemo.aws.s3.gdg-prefixes.systran",
        "carddemo.aws.s3.gdg-prefixes.tcatbalf-bkup",
        "carddemo.aws.s3.gdg-prefixes.tranrept",
        "carddemo.aws.s3.gdg-prefixes.transact-bkup",
        "carddemo.aws.s3.gdg-prefixes.transact-combined",
        "carddemo.aws.s3.gdg-prefixes.transact-daly",
    })
    @DisplayName("an absent generation prefix aborts startup, for every one of the seven bases")
    void anAbsentGenerationPrefixIsRefused(final String key) {
        final MockEnvironment environment = environmentWith(LOCAL_ENDPOINT);
        environment.getPropertySources().addFirst(
                new MapPropertySource("override", Collections.singletonMap(key, "")));

        assertThatThrownBy(() -> runGuard(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key)
                .hasMessageContaining("no namespace of its own");
    }

    /**
     * Equality is the obvious collision; nesting is the one that is easy to miss. Object keys are matched by
     * prefix, so {@code gdg/transact} and {@code gdg/transact-bkup} are distinct strings that do not give
     * distinct namespaces - a listing of the first returns the second's objects. Both forms are refused.
     */
    @Test
    @DisplayName("two generation prefixes that match, or one that nests inside another, abort startup")
    void collidingOrNestingGenerationPrefixesAreRefused() {
        final MockEnvironment equalPrefixes = environmentWith(LOCAL_ENDPOINT);
        equalPrefixes.setProperty("carddemo.aws.s3.gdg-prefixes.tcatbalf-bkup", "gdg/tranrept");
        assertThatThrownBy(() -> runGuard(equalPrefixes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neither match nor nest");

        final MockEnvironment nestedPrefixes = environmentWith(LOCAL_ENDPOINT);
        nestedPrefixes.setProperty("carddemo.aws.s3.gdg-prefixes.transact-bkup", "gdg/transact");
        assertThatThrownBy(() -> runGuard(nestedPrefixes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matched by prefix");
    }

    /**
     * The values {@code application.yml} actually declares must pass, or the guard would be unstartable
     * against the shipped profile. This is the positive half of the two negative cases above.
     */
    @Test
    @DisplayName("the seven prefixes application.yml declares are accepted")
    void theShippedGenerationPrefixesAreAccepted() {
        assertThat(GENERATION_PREFIXES).hasSize(7);
        assertThat(GENERATION_PREFIXES.values()).doesNotHaveDuplicates();
        assertThatCode(() -> runGuard(environmentWith(LOCAL_ENDPOINT))).doesNotThrowAnyException();
    }
}
