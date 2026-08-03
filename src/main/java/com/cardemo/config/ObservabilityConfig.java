/*
 * ******************************************************************
 * Program     : ObservabilityConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (observability and time surface)
 * Function    : Registers the observability layer's shared collaborators and
 *               publishes the single production time source that replaces the
 *               COBOL FUNCTION CURRENT-DATE intrinsic. Replaces the
 *               DISPLAY-only instrumentation that is the corpus's entire
 *               observability surface.
 * Source      : app/cbl/CBTRN02C.cbl:L714-L731 (9910-DISPLAY-IO-STATUS, the
 *                 only status renderer in the corpus)
 *               + app/cbl/CBTRN02C.cbl:L236-L243 (the end-of-run DISPLAY
 *                 counters that become Micrometer counters)
 *               + app/cbl/CSUTLDTC.cbl:L343 and app/cbl/CBSTM03A.CBL:L286
 *                 (FUNCTION CURRENT-DATE, the local-time intrinsic this
 *                 file's Clock bean replaces)
 *               + app/jcl/OPENFIL.jcl + app/jcl/CLOSEFIL.jcl (file
 *                 availability, now composite health)
 *               @ 7756d89
 * Replaces    : the DISPLAY statements and FUNCTION CURRENT-DATE intrinsic
 *               calls scattered through the 28 programs of app/cbl
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
package com.cardemo.config;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registration site for the observability layer and the owner of the application's single production time
 * source.
 *
 * <h2>What it does</h2>
 *
 * <p>Two responsibilities, both of them registration rather than behaviour.
 *
 * <ol>
 *   <li><strong>It publishes exactly one {@link Clock} bean.</strong> Thirteen online, security and batch
 *       components take a {@code Clock} through their constructors so that "now" is injected rather than
 *       read from a static, and every one of them resolves to the bean declared here. Before this file
 *       existed the context could not refresh at all: the failure surfaced as an unsatisfied constructor
 *       parameter on the first component the container happened to build.</li>
 *   <li><strong>It is the declared wiring owner of {@code com.cardemo.observability}.</strong> The three
 *       classes in that package register themselves - {@code CorrelationIdFilter} as an ordered filter,
 *       {@code MetricsConfig} as the definition site of the four counters, {@code HealthIndicators} as the
 *       composite health contributors - so this file deliberately declares no duplicate of any of them.
 *       See the troubleshooting note below, which exists because a duplicate here is the one way to break
 *       that arrangement.</li>
 *   </ol>
 *
 * <h2>Why the time source lives here, and why it is not {@code Clock.systemUTC()}</h2>
 *
 * <p>The corpus reads the wall clock through the {@code FUNCTION CURRENT-DATE} intrinsic - for example at
 * {@code app/cbl/CSUTLDTC.cbl:L343} and {@code app/cbl/CBSTM03A.CBL:L286} - and that intrinsic returns the
 * <strong>local</strong> date and time of the executing system, not UTC. Every rendered date and time in
 * the target therefore has to be derived from the deployment's own zone if it is to match the legacy
 * output byte for byte: the screen header pair {@code CURDATE X(8)} and {@code CURTIME X(9)}, the 26
 * character {@code DB2-FORMAT-TS} timestamp of {@code app/cbl/CBTRN02C.cbl:L692-L705}, and the report and
 * statement headings all fall out of it. {@link Clock#systemDefaultZone()} is consequently the
 * parity-preserving choice and {@link Clock#systemUTC()} is not, and this is the same choice the rest of
 * the tree already documents and defaults to - see {@code MainMenuService}, {@code AdminMenuService},
 * {@code CardDetailService}, {@code TransactionPostingProcessor} and {@code InterestCalculationProcessor},
 * each of which supplies {@code Clock.systemDefaultZone()} from its no-clock convenience constructor.
 *
 * <p>The zone is immaterial to the one consumer that might appear to want UTC. A JSON web token's
 * {@code iat} and {@code exp} claims are seconds since the epoch, which is a zone-free instant, so
 * {@code JwtTokenProvider} produces identical tokens under either clock; its documentation mentions
 * {@code systemUTC()} only as an example of a system clock. One bean therefore serves both the parity
 * consumers and the instant consumers, which is what keeps this declaration singular.
 *
 * <p><strong>Determinism.</strong> The returned clock is {@link Clock#systemDefaultZone()}, which is
 * immutable, thread safe and free of any per-call allocation of state. It is a singleton bean because a
 * clock has no per-request identity. Tests never receive it: every consumer also accepts a
 * {@link Clock#fixed} instance, which is how the suite pins "now" without touching this file.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Build with {@code ./mvnw -B clean compile}; the compiler runs with {@code -Xlint:all -Werror} and
 * {@code failOnWarning}, so any warning fails the build. Test with {@code ./mvnw -B clean test}, and the
 * whole gate with {@code ./mvnw -B clean verify}, which additionally enforces the JaCoCo line floor. Run
 * the application with {@code java -jar target/carddemo-1.0.0.jar} once {@code JWT_SIGNING_KEY} and the
 * database and object-store variables of {@code .env.example} are exported.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This class reads <strong>no</strong> configuration property, no environment variable and no system
 * property. That is deliberate: a configurable zone would let a deployment silently change every rendered
 * date and time and so silently break parity, and there is no legacy property to reproduce because the
 * mainframe zone was a system attribute rather than an application setting. The effective zone is
 * therefore the JVM default, {@link ZoneId#systemDefault()}, which a deployment sets the ordinary way -
 * through the container's {@code TZ} or the JVM's {@code user.timezone} - and which is logged once at
 * startup so that a parity discrepancy can be diagnosed from the log rather than guessed at.
 *
 * <p><strong>A deployment may also pin the zone explicitly.</strong> {@value #KEY_CLOCK_ZONE} - environment
 * variable {@code CARDDEMO_TIME_ZONE} - overrides the inherited default, and an unrecognised value aborts
 * startup with the offending text named rather than being silently replaced. The property exists because
 * inheriting whatever the host happens to be set to is fine for a container whose zone is declared and
 * wrong for one whose zone nobody chose, and because a baseline captured under one zone can then be
 * reproduced under another host deliberately. It is left <em>unset</em> in every shipped profile, so the
 * parity default above is what actually runs.
 *
 * <p><strong>UTC is deliberately not the default, and the storage layer is not an argument for making it
 * one.</strong> {@code spring.jpa.properties.hibernate.jdbc.time_zone} is {@code UTC}, so every instant
 * reaches PostgreSQL as UTC whatever this zone is - the provider converts. What this zone decides is the
 * <em>rendered text</em>: {@code CURDATE}, {@code CURTIME} and the generated 26-character timestamp that
 * Gate 1 compares byte for byte. The intrinsic those replace returns local civil time, so a UTC clock would
 * shift every one of them by the host's offset while changing nothing about what is stored.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup aborts naming {@value #KEY_CLOCK_ZONE}</dt>
 *   <dd>The property was pinned to something this runtime does not recognise, or to a placeholder whose
 *       variable was never exported. The message names the offending text. Unset the property to fall back
 *       to the runtime default rather than guessing at a replacement.</dd>
 *   <dt>The context fails with "expected single matching bean but found 2" for {@code java.time.Clock}</dt>
 *   <dd>A second {@code Clock} bean was declared elsewhere. Remove that one, not this one: this file is
 *       the designated declaration site, and {@code SecurityConfig} documents at its own findings register
 *       why it deliberately declares none. A test that needs a fixed clock must publish it inside a
 *       {@code @TestConfiguration} annotated {@code @Primary}, never as a second unqualified bean.</dd>
 *   <dt>The context fails with an unsatisfied constructor parameter of type {@code java.time.Clock}</dt>
 *   <dd>This class was not scanned. It lives in {@code com.cardemo.config}, which is below the
 *       {@code com.cardemo} root that {@code CardDemoApplication} scans, so the usual cause is a sliced
 *       test that imported only some configuration classes. Import this one too, or publish a fixed clock
 *       in the slice.</dd>
 *   <dt>Rendered dates or times differ from the legacy baseline by a whole number of hours</dt>
 *   <dd>The deployment's zone differs from the baseline's. The zone this class resolved is on the startup
 *       line below; compare it against the zone the baseline was captured under. Do not "fix" it by
 *       switching this bean to {@code systemUTC()} - that changes every rendering rather than aligning
 *       one - set the container zone instead.</dd>
 *   <dt>A duplicate bean is reported for a counter, a filter or a health indicator</dt>
 *   <dd>Remove it from here. {@code MetricsConfig} is the plan-designated definition site for the four
 *       counters, {@code CorrelationIdFilter} registers and orders itself, and {@code HealthIndicators}
 *       contributes the composite health beans. This file registers none of them again.</dd>
 *   </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>This class holds no state at all: it declares no field, mutable or otherwise, and its single bean
 * method is a pure function returning an immutable, thread-safe clock. Rule 1 Clause B's prohibition on
 * global mutable state therefore holds by construction.
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Startup diagnostic logger. It emits exactly one event, naming the resolved zone, because that zone
     * is the one input to every rendered date and time in the application and a parity discrepancy is
     * otherwise diagnosed by guesswork.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ObservabilityConfig.class);

    /**
     * Property key by which a deployment may pin the zone the application {@link Clock} ticks in.
     *
     * <p>Left unset in every shipped profile, so the runtime default documented on this class is what
     * actually runs. It exists so that a zone can be chosen deliberately rather than inherited, and so that
     * a baseline captured under one zone can be reproduced under a host configured for another.
     */
    private static final String KEY_CLOCK_ZONE = "carddemo.time.zone";

    /**
     * The opening of a Spring property placeholder, matched to catch a value that reached this class
     * unresolved.
     *
     * <p>A lenient placeholder resolver hands the literal text through instead of failing, and a zone of
     * {@code CARDDEMO_TIME_ZONE} left unexported would then be reported only as an unknown identifier -
     * true, but it sends an operator looking for a typo in a value they never typed.
     */
    private static final String UNRESOLVED_PLACEHOLDER_PREFIX = "${";

    /**
     * Creates the configuration class.
     *
     * <p>Declared explicitly rather than left implicit so that it can be documented: the container is the
     * only caller, there is nothing to inject, and no work is done here. Every decision this class makes
     * is made by {@link #clock(String)} at bean-creation time.
     */
    public ObservabilityConfig() {
        // Intentionally empty. This class holds no state: the one property it reads arrives as a parameter of
        // the bean method rather than as a field, so nothing here has to be initialised and the bean method
        // stays callable with an explicit value by a test that needs no container.
    }

    /**
     * The application's single production time source, replacing every {@code FUNCTION CURRENT-DATE}
     * intrinsic call in the corpus.
     *
     * <p>{@link Clock#systemDefaultZone()} rather than {@link Clock#systemUTC()}, because the intrinsic it
     * replaces returns local time - see the class documentation for the full argument and for the
     * consumers that fix the choice. The resolved zone is logged once here rather than at every call site,
     * so the value that governs every rendered date and time appears exactly once in the startup log.
     *
     * <p>When {@value #KEY_CLOCK_ZONE} is bound to a non-blank value, that zone is used instead and is
     * validated first: an unresolved placeholder or an identifier this runtime does not recognise aborts
     * startup with the offending text reported, because a zone identifier is public information and a
     * deployment that asked for one zone and silently received another would write rendered timestamps that
     * disagree with its own stored rows by the host's offset. When the property is unbound - which is how
     * every shipped profile leaves it - the JVM default applies and nothing is validated, because there is
     * nothing a deployment could have mistyped.
     *
     * @param zoneId the raw value of {@value #KEY_CLOCK_ZONE}; blank or absent selects the JVM default
     * @return the system clock in the resolved zone; never {@code null}, immutable and safe for concurrent
     *     use by every injected consumer
     * @throws IllegalStateException if {@value #KEY_CLOCK_ZONE} is bound to something that is not a zone
     *     identifier this runtime recognises
     */
    @Bean
    public Clock clock(@Value("${" + KEY_CLOCK_ZONE + ":}") final String zoneId) {
        final boolean pinned = zoneId != null && !zoneId.isBlank();
        final Clock systemClock =
                pinned ? Clock.system(validatedZone(zoneId)) : Clock.systemDefaultZone();
        LOG.info("CardDemo time source bound to the system clock in zone {} ({}), reproducing the local-time "
                        + "semantics of FUNCTION CURRENT-DATE (app/cbl/CSUTLDTC.cbl:L343); every rendered "
                        + "date, time and 26-character timestamp derives from it",
                systemClock.getZone(),
                pinned ? "pinned by " + KEY_CLOCK_ZONE : "inherited from the runtime default");
        return systemClock;
    }

    /**
     * Resolves and validates an explicitly pinned zone identifier.
     *
     * <p>Two rejections are distinguished because they have two different remedies: an unresolved
     * placeholder means the context resolves placeholders leniently and the variable behind it was never
     * exported, while an unknown identifier means the value is a typo or a deprecated alias. The value is
     * reported in both messages because a zone identifier carries nothing sensitive.
     *
     * @param zoneId the raw property value, known to be non-blank
     * @return the resolved zone, never {@code null}
     * @throws IllegalStateException if the value is an unresolved placeholder or is not a zone identifier
     *     this runtime recognises - aborting startup in either case
     */
    private static ZoneId validatedZone(final String zoneId) {
        if (zoneId.contains(UNRESOLVED_PLACEHOLDER_PREFIX)) {
            throw clockZoneRejected(zoneId, "is bound to an unresolved property placeholder", null);
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (final DateTimeException cause) {
            throw clockZoneRejected(zoneId, "does not name a zone this runtime recognises", cause);
        }
    }

    /**
     * Builds the one clock-zone failure message, so both rejection paths word the remedy identically.
     *
     * @param value the offending value, reported because a zone identifier is not sensitive
     * @param defect the condition observed, phrased to complete the sentence "the property ... {defect}"
     * @param cause the underlying failure, or {@code null} when the defect was detected by inspection
     * @return the exception to throw, never {@code null}
     */
    private static IllegalStateException clockZoneRejected(final String value, final String defect,
            final DateTimeException cause) {
        return new IllegalStateException(String.format(
                Locale.ROOT,
                "Property '%s' is '%s', which %s. Set it to a java.time zone identifier such as "
                        + "'America/New_York', or unset it to accept the runtime default this application "
                        + "documents. It is validated rather than defaulted silently because a deployment "
                        + "that asked for one zone and silently got another would render timestamps that "
                        + "disagree with its own stored rows by the host's offset.",
                KEY_CLOCK_ZONE,
                value,
                defect), cause);
    }
}
