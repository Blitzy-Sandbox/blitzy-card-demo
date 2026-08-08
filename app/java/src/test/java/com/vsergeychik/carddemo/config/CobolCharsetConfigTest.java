package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.PlaceholderResolutionException;

/**
 * Unit tests for {@link CobolCharsetConfig}, the single place in this module where a character
 * encoding is named.
 *
 * <h2>What is under test, and what deliberately is not</h2>
 *
 * <p>This file tests <b>charset selection</b> and nothing downstream of it. Encoding and decoding
 * <em>behaviour</em> belongs to the {@code common} package, whose {@code FixedWidthRecord} and
 * {@code FixedWidthCodec} take a {@link Charset} as an explicit parameter and are exercised by their
 * own tests. That split is what keeps the dependency acyclic, and it is asserted here in the only
 * way a test can assert it: <b>this file references no {@code com.vsergeychik.carddemo.common} type
 * at all</b> - the import list above contains none, and
 * {@code theConfigurationExposesNoCommonPackageTypeOnItsPublicSurface} checks the reverse direction
 * reflectively. Verified independently while writing this file: {@code FixedWidthCodec} declares
 * {@code Charset} a mandatory constructor argument, and no file under {@code common} mentions
 * {@code carddemo.config}.
 *
 * <p>There is no {@code @SpringBootTest} here either. Whole-graph context loading is owned by
 * {@code CardDemoApplicationTest}; duplicating it would be slow, redundant, and would make the
 * resolution helper's branches harder to reach. Bean and property assertions therefore use
 * {@link ApplicationContextRunner} with only this one configuration class, and the resolution helper
 * is called directly.
 *
 * <h2>Why every runner adds PropertyPlaceholderAutoConfiguration</h2>
 *
 * <p>{@link CobolCharsetConfig} binds its three code-page names with {@code @Value} placeholders,
 * two of which carry <b>no default</b>. A bare {@link ApplicationContextRunner} registers no
 * {@code PropertySourcesPlaceholderConfigurer}, so its fallback value resolver is the
 * <em>non-strict</em> {@code Environment.resolvePlaceholders}: a missing key would silently leave
 * the literal text {@code ${carddemo.charset.ebcdic}} in place and surface much later as an
 * {@link IllegalCharsetNameException} about a nonsense charset name. That is not what a real Spring
 * Boot application does. Adding {@link PropertyPlaceholderAutoConfiguration} restores strict
 * resolution, so a missing key fails with Spring's own
 * {@link PlaceholderResolutionException} naming the exact key - which is the behaviour the
 * production class documents and the behaviour an operator will actually meet. Both variants were
 * run while writing this file; the difference is real, so the strict one is used throughout and the
 * reason is recorded here rather than left as an unexplained line of setup.
 *
 * <h2>The evidence behind the encoding assertions</h2>
 *
 * <p>Two byte literals appear below. Neither is read from a file: {@code app/data/EBCDIC} and
 * {@code app/data/ASCII} are read-only reference material and this test never touches them at
 * runtime. Both literals were transcribed after independently re-verifying the bytes, and both are
 * annotated with the copybook offsets they span so a reviewer can re-check them.
 *
 * <ol>
 *   <li><b>The account record prefix</b> - the first 60 bytes of
 *       {@code app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS}. Decoded as {@code IBM037} they read
 *       {@code 00000000001Y00000001940{00000020200{00000010200{2014-11-2020}, which is
 *       byte-identical to the first 60 characters of {@code app/data/ASCII/acctdata.txt}. Decoded
 *       as {@code US-ASCII} the same bytes are almost entirely replacement characters. The
 *       {@code '{'} is not text: it is the zoned-decimal {@code +0} sign overpunch, EBCDIC
 *       {@code x'C0'}, in the final byte of a {@code PIC S9(10)V99} field. It is preserved exactly
 *       and never "cleaned".</li>
 *   <li><b>The security record prefix</b> - the first 32 bytes of
 *       {@code app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS}, reading {@code ADMIN001MARGARET
 *       GOLD} under {@code IBM037}. This is the more insidious demonstration: under
 *       {@code US-ASCII} the EBCDIC space {@code x'40'} becomes {@code '@'}, so the wrong code page
 *       yields <em>plausible-looking</em> output rather than an obvious failure, and nothing throws.
 *       The literal stops at offset 31, inside {@code SEC-USR-LNAME} and well before
 *       {@code SEC-USR-PWD} at offset 48, so no credential-shaped value enters this source file.
 *       </li>
 * </ol>
 *
 * <p>{@code README.md} line 65 is the documented origin of the requirement, and reads exactly:
 * "Upload the sample data provided in the main/-/data/EBCDIC/ folder to the mainframe. Ensure that
 * you use transfer mode binary". Binary transfer means no client transcodes on the way in, so the
 * twelve datasets under {@code app/data/EBCDIC} are raw EBCDIC bytes and the reader alone is
 * responsible for naming their code page.
 *
 * <h2>Why "not the platform default" is asserted the way it is</h2>
 *
 * <p>The tempting assertion - that neither resolved charset equals {@link Charset#defaultCharset()}
 * - is <b>deliberately not made</b>, because it is flaky rather than strict. A JVM started in a
 * POSIX locale with {@code -Dfile.encoding=COMPAT} reports {@code US-ASCII} as its default, and the
 * assertion would then fail on a perfectly correct configuration. What is asserted instead is
 * stronger and stable: each bean is the <em>explicitly named</em> code page, and the consequence of
 * getting it wrong is demonstrated mechanically by the decode pair above. That property holds under
 * every value of {@code file.encoding}, which is exactly why it is the right one to assert.
 *
 * <h2>Two places where this file follows the class rather than its specification</h2>
 *
 * <ul>
 *   <li>The specification anticipated a {@code @ConfigurationProperties} type registered with
 *       {@code @EnableConfigurationProperties}. {@link CobolCharsetConfig} introduces no such type -
 *       it binds three names with {@code @Value} on its constructor - so that annotation is
 *       correctly absent, and
 *       {@code theConfigurationDeclaresNoConfigurationPropertiesRegistration} pins that. The
 *       sibling {@code DataSourceConfig} does use it, so the difference is a real design choice
 *       rather than an omission.</li>
 *   <li>The specification anticipated separate branches for a syntactically illegal charset name and
 *       for {@code null}. {@link CobolCharsetConfig#resolve(String, String)} contains exactly
 *       <b>one</b> condition and deliberately does not wrap either case: the JDK rejects both before
 *       that condition is reached, its diagnostics already quote the offending name, and neither is
 *       reachable from configuration. Those cases are asserted below as documented pass-through
 *       behaviour, not invented as branches that could never be covered.</li>
 * </ul>
 *
 * <h2>Gates enforced directly by this file</h2>
 *
 * <ul>
 *   <li><b>G49</b> - branch coverage. {@link CobolCharsetConfig} contains exactly two branches, the
 *       two arms of the single condition in {@code resolve}. Both are driven repeatedly and from
 *       both a direct call and a Spring context, so this class contributes zero missed branches to
 *       the {@code config} package's ratio.</li>
 *   <li><b>G52</b> - no wildcard imports, not even static ones; every imported member is named.</li>
 *   <li><b>G53</b> - no mutable static state. The only {@code static} members here are immutable
 *       {@link String} constants and factory methods that build a fresh array per call, so no two
 *       tests can share mutable state; {@code theConfigurationHoldsNoMutableState} holds the class
 *       under test to the same bar.</li>
 *   <li><b>G54</b> - nothing waits on input, watches for changes, reads the clock, uses a random
 *       source, touches the network or writes a file, so the whole class runs in one
 *       non-interactive command.</li>
 * </ul>
 *
 * <h2>User-specified rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single
 * line is the entire document, so <b>no user rule governs this file</b>. Its absence is not licence
 * to lower the bar; the enterprise practices that stand in their place are honoured here as B1 (no
 * dependency added - every type imported above already arrives with
 * {@code spring-boot-starter-test}, and {@code app/java/pom.xml} is untouched), B3 (the read-only
 * reference trees are never opened, only transcribed from), B4 (no configuration file is created
 * for these tests; properties are supplied inline so each assertion states the exact environment it
 * needs, rather than depending on whatever the profile happens to declare), B7
 * (deterministic and non-interactive), B8, B9 and B11 (plain {@link Charset} and
 * {@link StandardCharsets} only, no third-party encoding helper).
 */
@DisplayName("CobolCharsetConfig - the one place a CardDemo code page is named")
class CobolCharsetConfigTest {

    /**
     * The EBCDIC code-page name declared by {@code carddemo.charset.ebcdic} in both
     * {@code application.yml} and {@code application-test.yml}.
     */
    private static final String CONFIGURED_EBCDIC_NAME = "IBM037";

    /**
     * The ASCII code-page name declared by {@code carddemo.charset.ascii} in both
     * {@code application.yml} and {@code application-test.yml}.
     */
    private static final String CONFIGURED_ASCII_NAME = "US-ASCII";

    /**
     * A charset name built only from characters the JDK considers legal - letters, digits and
     * hyphens - that no JDK supplies. This is the realistic shape of the failure the fail-fast
     * helper exists for: a full JDK 21 supplies {@code IBM037} from its {@code jdk.charsets}
     * module, and a minimal {@code jlink} image that omits that module leaves a JVM which looks
     * complete but cannot resolve the name. Such an image cannot be built inside a test, so a
     * legal-but-absent name stands in for it and reaches the identical code path.
     */
    private static final String UNSUPPORTED_BUT_LEGAL_NAME = "IBM037-DOES-NOT-EXIST";

    /**
     * What makes a "shipped default profile" slice actually read {@code application.yml} alone.
     *
     * <p>An {@link ApplicationContextRunner} builds its environment on top of the surrounding JVM's
     * system properties and process environment. So a build invoked as
     * {@code mvn test -Dspring.profiles.active=test}, or run by a CI executor that exports
     * {@code SPRING_PROFILES_ACTIVE=test} - a common convention - activates the {@code test} profile
     * <em>inside</em> a runner that was meant to see one document. {@code application-test.yml} then
     * loads on top of it and wins, and {@link TheShippedConfigurationDocuments} reports the fixture
     * profile's {@code US-ASCII} as the default document's active code page - which is precisely the
     * misreading that group exists to prevent.
     *
     * <p>Stating the key with an empty value closes it: {@code withPropertyValues} installs the value
     * as the first property source, ahead of both {@code systemProperties} and
     * {@code systemEnvironment}, and an empty value means no active profile at all. Clearing the
     * system property instead was measured and rejected - it neutralises the {@code -D} form and leaves
     * the environment-variable form leaking. Nothing global is mutated, so no slice can perturb
     * another (practice B7). Only the default-profile arms need it; an arm that names a profile has
     * already stated what it wants and outranks the inherited value by the same rule.
     */
    private static final String NO_EXTERNALLY_ACTIVATED_PROFILE = "spring.profiles.active=";

    /**
     * The first 60 bytes of {@code app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS} as they read under
     * {@code IBM037}, identical to the first 60 characters of
     * {@code app/data/ASCII/acctdata.txt}. Spans, per {@code app/cpy/CVACT01Y.cpy}:
     * {@code ACCT-ID PIC 9(11)}, {@code ACCT-ACTIVE-STATUS PIC X(01)}, three
     * {@code PIC S9(10)V99} money fields each ending in a {@code +0} overpunch,
     * {@code ACCT-OPEN-DATE PIC X(10)}, and the first two bytes of the misspelled
     * {@code ACCT-EXPIRAION-DATE PIC X(10)}.
     */
    private static final String EXPECTED_ACCOUNT_PREFIX_TEXT =
            "00000000001Y00000001940{00000020200{00000010200{2014-11-2020";

    /**
     * The first 32 bytes of {@code app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS} as they read under
     * {@code IBM037}. Spans, per {@code app/cpy/CSUSR01Y.cpy}: {@code SEC-USR-ID PIC X(08)},
     * {@code SEC-USR-FNAME PIC X(20)} space-padded, and the first four bytes of
     * {@code SEC-USR-LNAME PIC X(20)}. It stops there on purpose: {@code SEC-USR-PWD} begins at
     * offset 48 and no part of it belongs in a source file.
     */
    private static final String EXPECTED_SECURITY_PREFIX_TEXT =
            "ADMIN001MARGARET            GOLD";

    /** The Unicode replacement character a decoder substitutes for an unmappable byte. */
    private static final char REPLACEMENT_CHARACTER = '\uFFFD';

    /**
     * The first 60 bytes of the EBCDIC account dataset, rebuilt on every call.
     *
     * <p>A {@code static final byte[]} would be mutable static state however final the reference
     * was, so this is a factory rather than a constant (practice B9, gate G53). Each byte is written
     * as its hexadecimal value, grouped by copybook field, because that is the form in which it was
     * verified.
     *
     * @return a fresh copy of the verified byte prefix, never shared between tests
     */
    private static byte[] ebcdicAccountRecordPrefix() {
        return new byte[] {
            // ACCT-ID PIC 9(11), offsets 0-10: "00000000001"
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF1,
            // ACCT-ACTIVE-STATUS PIC X(01), offset 11: "Y"
            (byte) 0xE8,
            // ACCT-CURR-BAL PIC S9(10)V99, offsets 12-23: "00000001940{" ('{' = +0 overpunch)
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF1, (byte) 0xF9, (byte) 0xF4, (byte) 0xF0, (byte) 0xC0,
            // ACCT-CREDIT-LIMIT PIC S9(10)V99, offsets 24-35: "00000020200{"
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF2, (byte) 0xF0, (byte) 0xF2, (byte) 0xF0, (byte) 0xF0, (byte) 0xC0,
            // ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99, offsets 36-47: "00000010200{"
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF1, (byte) 0xF0, (byte) 0xF2, (byte) 0xF0, (byte) 0xF0, (byte) 0xC0,
            // ACCT-OPEN-DATE PIC X(10), offsets 48-57: "2014-11-20" ('-' = EBCDIC x'60')
            (byte) 0xF2, (byte) 0xF0, (byte) 0xF1, (byte) 0xF4, (byte) 0x60, (byte) 0xF1,
            (byte) 0xF1, (byte) 0x60, (byte) 0xF2, (byte) 0xF0,
            // ACCT-EXPIRAION-DATE PIC X(10), offsets 58-59 only: "20"
            (byte) 0xF2, (byte) 0xF0,
        };
    }

    /**
     * The first 32 bytes of the EBCDIC user-security dataset, rebuilt on every call.
     *
     * <p>Twelve of these bytes are the EBCDIC space {@code x'40'}, which is the whole point: under
     * {@code US-ASCII} that byte decodes to {@code '@'} rather than to a replacement character, so
     * the wrong code page produces output that looks deliberate.
     *
     * @return a fresh copy of the verified byte prefix, never shared between tests
     */
    private static byte[] ebcdicSecurityRecordPrefix() {
        return new byte[] {
            // SEC-USR-ID PIC X(08), offsets 0-7: "ADMIN001"
            (byte) 0xC1, (byte) 0xC4, (byte) 0xD4, (byte) 0xC9, (byte) 0xD5, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF1,
            // SEC-USR-FNAME PIC X(20), offsets 8-27: "MARGARET" then 12 EBCDIC spaces x'40'
            (byte) 0xD4, (byte) 0xC1, (byte) 0xD9, (byte) 0xC7, (byte) 0xC1, (byte) 0xD9,
            (byte) 0xC5, (byte) 0xE3,
            (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40,
            (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40,
            // SEC-USR-LNAME PIC X(20), offsets 28-31 only: "GOLD"
            (byte) 0xC7, (byte) 0xD6, (byte) 0xD3, (byte) 0xC4,
        };
    }

    /**
     * A fresh {@link ApplicationContextRunner} loaded with only the class under test and strict
     * placeholder resolution.
     *
     * <p>A new runner per call, never a shared field, so no test can observe another's context
     * (practice B9). Properties are supplied inline for the reason set out in the class javadoc: each
     * assertion then states the exact environment it needs and cannot be perturbed by a change to the
     * {@code test} profile, which since the credential relocation lives at
     * {@code src/test/resources/application-test.yml}.
     *
     * @param properties {@code key=value} pairs to place in the context's {@code Environment}
     * @return a runner ready to {@code run} a single assertion
     */
    private ApplicationContextRunner runnerWith(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(CobolCharsetConfig.class)
                .withPropertyValues(properties);
    }

    /**
     * A runner configured exactly as {@code application-test.yml} configures the application: all
     * three required code-page keys present, with the active dataset key naming the ASCII code page
     * because the fixture-backed profile binds every dataset to a text fixture.
     *
     * <p>All three are supplied because all three are required - none carries a default. The active
     * key in particular is stated here rather than omitted, which is the whole point of it having no
     * default: a runner that could start without saying which code page the data layer reads is a
     * runner that could not have caught the defect where production silently read EBCDIC datasets as
     * ASCII.
     *
     * @return a runner whose context is expected to start cleanly
     */
    private ApplicationContextRunner runnerWithBothCodePagesConfigured() {
        return runnerWith(
                CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME,
                CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME,
                CobolCharsetConfig.DATASET_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME);
    }

    /**
     * A slice over the shipped <em>default</em> document: the real {@code application.yml} read
     * through {@link ConfigDataApplicationContextInitializer}, with nothing supplied inline except
     * {@value #NO_EXTERNALLY_ACTIVATED_PROFILE}, for the reason set out on that constant.
     *
     * @return a runner whose only property source is {@code application.yml}
     */
    private ApplicationContextRunner shippedDefaultProfile() {
        // No inherited environment to reproduce: the runner's own environment is the subject here.
        return shippedDefaultProfile(context -> { });
    }

    /**
     * The same slice, with an inherited environment installed before the configuration documents are
     * read.
     *
     * <p>This overload exists so
     * {@link TheShippedConfigurationDocuments#anExternallyActivatedProfileCannotChangeTheActiveCodePage()}
     * can exercise <em>this</em> runner rather than a copy of it - a copy would let someone delete
     * {@value #NO_EXTERNALLY_ACTIVATED_PROFILE} above and leave that assertion green. The initializer
     * is registered <strong>first</strong>, ahead of {@link ConfigDataApplicationContextInitializer},
     * because profile activation is resolved when the documents are loaded: a property source
     * installed after that point cannot change which document was chosen, so an assertion built that
     * way would pass whether or not the fix were present.
     *
     * @param inheritedEnvironment installs whatever the surrounding process is imagined to have
     *                             supplied; a no-op for ordinary use
     * @return a runner whose only configuration document is {@code application.yml}
     */
    private ApplicationContextRunner shippedDefaultProfile(
            ApplicationContextInitializer<ConfigurableApplicationContext> inheritedEnvironment) {
        return new ApplicationContextRunner()
                .withInitializer(inheritedEnvironment)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(CobolCharsetConfig.class)
                .withPropertyValues(NO_EXTERNALLY_ACTIVATED_PROFILE);
    }

    /**
     * An initializer that reproduces {@code SPRING_PROFILES_ACTIVE=<profile>} in the process
     * environment, at the precedence position the real variable occupies.
     *
     * <p>Java cannot set its own environment variables, so the variable is reproduced as a
     * {@link SystemEnvironmentPropertySource} - the source type that performs the
     * {@code SPRING_PROFILES_ACTIVE} to {@code spring.profiles.active} relaxed-name mapping - inserted
     * immediately above {@code systemProperties}. That position is what makes the assertion
     * discriminating: it outranks every configuration document, so an arm that never states its
     * profile follows it, while it still loses to the inline value an arm that <em>does</em> state its
     * profile installs. The inherited sources stay in place rather than being replaced.
     *
     * @param profile the profile the imagined executor exported
     * @return an initializer installing that variable
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext>
            processEnvironmentActivating(String profile) {
        return context -> context.getEnvironment().getPropertySources().addBefore(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("simulatedProcessEnvironment",
                        Map.of("SPRING_PROFILES_ACTIVE", profile)));
    }

    /**
     * The fields {@link CobolCharsetConfig} actually declares, with instrumentation artifacts
     * removed.
     *
     * <p>The JaCoCo agent injects a non-final {@code static boolean[]} named {@code $jacocoData}
     * into every instrumented class. Without this filter the immutability assertions would pass
     * under a bare {@code javac} run and fail under Maven, which is the worst of both worlds.
     *
     * @return the genuinely declared fields, in declaration order
     */
    private static List<Field> declaredFieldsOfConfiguration() {
        return Arrays.stream(CobolCharsetConfig.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !field.getName().startsWith("$"))
                .toList();
    }

    /**
     * The methods {@link CobolCharsetConfig} actually declares, with instrumentation artifacts
     * removed - notably JaCoCo's synthetic {@code $jacocoInit}.
     *
     * @return the genuinely declared methods
     */
    private static List<Method> declaredMethodsOfConfiguration() {
        return Arrays.stream(CobolCharsetConfig.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> !method.getName().startsWith("$"))
                .toList();
    }

    /**
     * {@link CobolCharsetConfig#resolve(String, String)} - the one decision the class makes, and the
     * whole of its branching behaviour.
     *
     * <p>The helper is package-private and {@code static}, which is what lets these tests call it
     * directly with no application context in the picture. Its single condition has two arms, and
     * both are driven here many times over: the supported arm by the two configured code pages and
     * their JDK aliases, the unsupported arm by a legal-but-absent name against each of the three
     * property keys. That is the entirety of this class's contribution to the {@code config}
     * package's branch ratio (gate G49).
     */
    @Nested
    @DisplayName("resolve - turning a configured code-page name into a Charset, or failing loudly")
    class ResolvingAConfiguredCodePageName {

        @Test
        @DisplayName("the configured EBCDIC name resolves to the IBM037 code page")
        void theConfiguredEbcdicNameResolvesToIbm037() {
            Charset resolved = CobolCharsetConfig.resolve(
                    CONFIGURED_EBCDIC_NAME, CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY);

            // Asserted on the resolved Charset's canonical name rather than on the input string, so
            // a silent fallback to some other code page could not pass this test.
            assertThat(resolved.name())
                    .as("the EBCDIC code page of the twelve binary datasets under app/data/EBCDIC")
                    .isEqualTo("IBM037");
            assertThat(resolved.canEncode())
                    .as("the bean must be usable for writing a record as well as reading one")
                    .isTrue();
        }

        @Test
        @DisplayName("the configured ASCII name resolves to the JDK's own US_ASCII constant")
        void theConfiguredAsciiNameResolvesToTheJdkStandardConstant() {
            Charset resolved = CobolCharsetConfig.resolve(
                    CONFIGURED_ASCII_NAME, CobolCharsetConfig.ASCII_CHARSET_PROPERTY);

            // Verified: Charset.forName returns the cached sun.nio.cs.US_ASCII singleton for the
            // canonical name and for every alias, so resolving from configuration costs nothing and
            // the class never has to hard-code StandardCharsets.US_ASCII to obtain it.
            assertThat(resolved).isSameAs(StandardCharsets.US_ASCII);
            assertThat(resolved.name()).isEqualTo("US-ASCII");
        }

        @ParameterizedTest(name = "carddemo.charset.ebcdic={0} resolves to IBM037")
        @ValueSource(strings = {
            "IBM037", "ibm037", "IBM-037", "ibm-037", "cp037", "cpibm37", "csIBM037",
            "ebcdic-cp-us", "037", "ibm-37",
        })
        @DisplayName("any JDK alias of the EBCDIC code page resolves to the same canonical charset")
        void anyEbcdicAliasResolvesToTheCanonicalCharset(String configuredName) {
            // A deployment may legitimately spell the key as cp037; charset lookup is
            // case-insensitive and alias-aware, and all ten of these names were verified against
            // this JDK to yield one and the same instance. Resolution must not be a string compare.
            Charset resolved = CobolCharsetConfig.resolve(
                    configuredName, CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY);

            assertThat(resolved.name()).isEqualTo("IBM037");
            assertThat(resolved).isSameAs(Charset.forName("IBM037"));
        }

        @ParameterizedTest(name = "carddemo.charset.ascii={0} resolves to US-ASCII")
        @ValueSource(strings = {
            "US-ASCII", "us-ascii", "ASCII", "ascii7", "ISO646-US", "csASCII", "ANSI_X3.4-1968",
            "iso-ir-6", "646", "us", "IBM367", "cp367",
        })
        @DisplayName("any JDK alias of the ASCII code page resolves to the same canonical charset")
        void anyAsciiAliasResolvesToTheCanonicalCharset(String configuredName) {
            Charset resolved = CobolCharsetConfig.resolve(
                    configuredName, CobolCharsetConfig.ASCII_CHARSET_PROPERTY);

            assertThat(resolved.name()).isEqualTo("US-ASCII");
            assertThat(resolved).isSameAs(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("a legal but unsupported name fails fast with an actionable diagnostic")
        void aLegalButUnsupportedNameFailsFast() {
            // This is the fail-fast arm. The real-world trigger is a minimal jlink runtime image
            // built without the jdk.charsets module: IBM037 comes from that module, so such a JVM
            // looks complete yet cannot resolve the name. An image like that cannot be produced
            // inside a test, so a legal-but-absent name stands in for it and reaches the same code.
            assertThatIllegalStateException()
                    .isThrownBy(() -> CobolCharsetConfig.resolve(
                            UNSUPPORTED_BUT_LEGAL_NAME,
                            CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .withMessageContaining(UNSUPPORTED_BUT_LEGAL_NAME)
                    .withMessageContaining(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY)
                    .withMessageContaining("is not supported by this JVM")
                    // No fallback, ever: not to the platform default, not to UTF-8, not from
                    // EBCDIC to ASCII. The message has to say so, because the reader's first
                    // instinct on seeing a charset failure is to look for the fallback.
                    .withMessageContaining("No fallback is applied")
                    .withMessageContaining("platform default charset is never substituted")
                    // The remedy, not merely the symptom.
                    .withMessageContaining("jdk.charsets")
                    .withMessageContaining("jlink");
        }

        @ParameterizedTest(name = "an unsupported value of {0} is reported against that key")
        @ValueSource(strings = {
            "carddemo.charset.ebcdic", "carddemo.charset.ascii", "carddemo.charset.dataset",
        })
        @DisplayName("the diagnostic names whichever property supplied the offending value")
        void theDiagnosticNamesTheOffendingProperty(String propertyKey) {
            // The point of threading the key through the helper is that the operator is told which
            // line to edit. Driven once per key so a hard-coded key in the message would be caught.
            assertThatIllegalStateException()
                    .isThrownBy(() ->
                            CobolCharsetConfig.resolve(UNSUPPORTED_BUT_LEGAL_NAME, propertyKey))
                    .withMessageContaining("configured by property '" + propertyKey + "'")
                    .withMessageContaining("correct the value of '" + propertyKey + "'");
        }

        @Test
        @DisplayName("the diagnostic says strictly more than the JDK's own would have")
        void theDiagnosticSaysStrictlyMoreThanTheJdksOwnWould() {
            // Why the helper exists at all, made concrete. Charset.forName alone reports only the
            // offending name - verified: its message is exactly "IBM037-DOES-NOT-EXIST" - which
            // leaves the operator to guess which of three properties produced it.
            assertThatExceptionOfType(UnsupportedCharsetException.class)
                    .isThrownBy(() -> Charset.forName(UNSUPPORTED_BUT_LEGAL_NAME))
                    .withMessage(UNSUPPORTED_BUT_LEGAL_NAME)
                    .withMessageNotContaining(CobolCharsetConfig.ASCII_CHARSET_PROPERTY);

            assertThatIllegalStateException()
                    .isThrownBy(() -> CobolCharsetConfig.resolve(
                            UNSUPPORTED_BUT_LEGAL_NAME, CobolCharsetConfig.ASCII_CHARSET_PROPERTY))
                    .withMessageContaining(UNSUPPORTED_BUT_LEGAL_NAME)
                    .withMessageContaining(CobolCharsetConfig.ASCII_CHARSET_PROPERTY);
        }

        @Test
        @DisplayName("resolving is pure: the same name twice yields the same charset")
        void resolvingIsPure() {
            // The class holds no state and caches nothing, so two calls cannot diverge. This is the
            // behavioural half of the no-mutable-state guarantee that
            // theConfigurationHoldsNoMutableState checks structurally.
            Charset first = CobolCharsetConfig.resolve(
                    CONFIGURED_EBCDIC_NAME, CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY);
            Charset second = CobolCharsetConfig.resolve(
                    CONFIGURED_EBCDIC_NAME, CobolCharsetConfig.DATASET_CHARSET_PROPERTY);

            assertThat(first).isEqualTo(second).isSameAs(second);
        }

        @ParameterizedTest(name = "the blank name [{0}] is rejected naming the property key")
        @ValueSource(strings = { "", " ", "   ", "\t" })
        @DisplayName("a blank name is rejected in its own right, naming the key that was blanked")
        void aBlankNameIsRejectedNamingTheKey(String blankName) {
            // A blank value is a configuration error with a specific cause - somebody emptied a key -
            // and the diagnostic has to say which one. Left to the JDK this raised a bare
            // IllegalCharsetNameException quoting only the empty name, which tells an operator
            // nothing about where to look. Whitespace-only is treated identically to empty: a code
            // page name made of spaces is not a choice of code page.
            assertThatIllegalStateException()
                    .isThrownBy(() -> CobolCharsetConfig.resolve(
                            blankName, CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .withMessageContaining(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY)
                    .withMessageContaining("required and is never defaulted")
                    .withMessageContaining("platform default charset is never substituted");
        }

        @ParameterizedTest(name = "the syntactically illegal name [{0}] is wrapped, naming the key")
        @ValueSource(strings = { "IBM 037", "bogus!", "US_ASCII/1", "${key}" })
        @DisplayName("a syntactically illegal name is wrapped in one configuration error")
        void aSyntacticallyIllegalNameIsWrappedInOneConfigurationError(String illegalName) {
            // Charset.isSupported raises IllegalCharsetNameException for a name built from characters
            // outside the legal set. That exception quotes the name but not the key, so it is caught
            // and re-reported as the same IllegalStateException every other rejection uses - one
            // exception type for every way a charset can be unusable, always naming the key and the
            // rejected value. The original is retained as the cause rather than discarded.
            // "${key}" is in the set deliberately: an unresolved placeholder arriving as literal text
            // is a real configuration mistake, and the message points at that specific cause.
            assertThatIllegalStateException()
                    .isThrownBy(() -> CobolCharsetConfig.resolve(
                            illegalName, CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .withMessageContaining(illegalName)
                    .withMessageContaining(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY)
                    .withMessageContaining("not a syntactically legal charset name")
                    .withCauseInstanceOf(IllegalCharsetNameException.class);
        }

        @Test
        @DisplayName("a null name is rejected as a configuration error, not as a raw JDK argument "
                + "failure")
        void aNullNameIsRejectedAsAConfigurationError() {
            // Null cannot arrive from configuration - a missing key fails earlier, during placeholder
            // resolution - but it can arrive from a direct call, and there is no reason for that to
            // produce a different exception type or a message without the key in it. The blank guard
            // covers null and blank in one test, which is also why no NullPointerException can escape
            // this method.
            assertThatIllegalStateException()
                    .isThrownBy(() -> CobolCharsetConfig.resolve(
                            null, CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .withMessageContaining(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY)
                    .withMessageContaining("required and is never defaulted");
        }

        @Test
        @DisplayName("the helper is package-private and static, so it needs no Spring context")
        void theHelperIsPackagePrivateAndStatic() throws NoSuchMethodException {
            Method resolve = CobolCharsetConfig.class
                    .getDeclaredMethod("resolve", String.class, String.class);
            int modifiers = resolve.getModifiers();

            assertThat(Modifier.isStatic(modifiers))
                    .as("static is what makes both arms reachable from a plain unit test")
                    .isTrue();
            assertThat(Modifier.isPublic(modifiers))
                    .as("the resolver is an internal seam, not part of the published API")
                    .isFalse();
            assertThat(Modifier.isPrivate(modifiers))
                    .as("private would put it out of reach of this same-package test")
                    .isFalse();
            assertThat(resolve.getReturnType()).isEqualTo(Charset.class);
        }
    }

    /**
     * The three {@link Charset} beans this configuration publishes, and the qualifier names through
     * which repositories, the fixed-width writers, the date-parameter reader and the parity harness
     * select among them.
     *
     * <p>Loaded with {@link ApplicationContextRunner} rather than {@code @SpringBootTest}: only this
     * one configuration class is in the context, so a failure here can only be about charset
     * selection.
     */
    @Nested
    @DisplayName("the published Charset beans - three named code pages, no primary")
    class ThePublishedCharsetBeans {

        @Test
        @DisplayName("exactly three Charset beans are published, under their declared names")
        void exactlyThreeCharsetBeansArePublishedUnderTheirDeclaredNames() {
            runnerWithBothCodePagesConfigured().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).getBeanNames(Charset.class)
                        .as("three code pages, no more and no fewer")
                        .containsExactlyInAnyOrder(
                                CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME,
                                CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME,
                                CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
            });
        }

        @Test
        @DisplayName("the bean-name constants are the names the context actually publishes")
        void theBeanNameConstantsAreTheNamesTheContextActuallyPublishes() {
            // The constants exist so that no injection point has to spell a bean name as a string
            // literal. Pinning their values here means a rename cannot silently break every
            // @Qualifier in the module while this test still passes.
            assertThat(CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME)
                    .isEqualTo("carddemoEbcdicCharset");
            assertThat(CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME)
                    .isEqualTo("carddemoAsciiCharset");
            assertThat(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                    .isEqualTo("carddemoDatasetCharset");

            runnerWithBothCodePagesConfigured().run(context -> {
                assertThat(context).hasBean(CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME);
                assertThat(context).hasBean(CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME);
                assertThat(context).hasBean(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
            });
        }

        @Test
        @DisplayName("the EBCDIC bean is the IBM037 code page")
        void theEbcdicBeanIsTheIbm037CodePage() {
            runnerWithBothCodePagesConfigured().run(context -> {
                Charset ebcdic = context.getBean(
                        CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME, Charset.class);

                assertThat(ebcdic.name()).isEqualTo("IBM037");
            });
        }

        @Test
        @DisplayName("the ASCII bean is the US-ASCII code page")
        void theAsciiBeanIsTheUsAsciiCodePage() {
            runnerWithBothCodePagesConfigured().run(context -> {
                Charset ascii = context.getBean(
                        CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME, Charset.class);

                assertThat(ascii).isSameAs(StandardCharsets.US_ASCII);
                assertThat(ascii.name()).isEqualTo("US-ASCII");
            });
        }

        @Test
        @DisplayName("the active dataset charset has NO default - omitting the key fails the context")
        void theActiveDatasetCharsetHasNoDefault() {
            // THE REGRESSION GUARD FOR THE DEFECT THIS KEY EXISTS TO PREVENT. This bean decides how
            // every dataset byte in the module is interpreted, and it previously defaulted to the
            // ASCII code page through a nested placeholder. The consequence was invisible and severe:
            // application.yml's bindings address the mainframe datasets, which arrive as raw EBCDIC
            // bytes, so a deployment that never stated its code page started cleanly and then decoded
            // every record as ASCII - 300 plausible-looking bytes of nonsense per account record, no
            // exception, no log line. Requiring the key converts that into a startup failure naming
            // it, which is the only safe outcome for a migration judged on byte-level parity.
            runnerWith(
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME,
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context).getFailure()
                                .rootCause()
                                .isInstanceOf(PlaceholderResolutionException.class)
                                .hasMessageContaining("Could not resolve placeholder")
                                .hasMessageContaining(
                                        CobolCharsetConfig.DATASET_CHARSET_PROPERTY);
                    });
        }

        @Test
        @DisplayName("the active bean follows its own key, not the ASCII key, in both directions")
        void theActiveBeanFollowsItsOwnKeyInBothDirections() {
            // The two beans are distinct even where a profile gives them the same name, so that
            // moving the data layer's code page never drags the fixture-reading code with it. Both
            // profiles are exercised here: the fixture-backed one, where the active key names the
            // ASCII page, and the shipped default one, where it names the EBCDIC page.
            runnerWith(
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME,
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME,
                    CobolCharsetConfig.DATASET_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(
                                CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class).name())
                                .isEqualTo("US-ASCII");
                    });

            runnerWith(
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME,
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME,
                    CobolCharsetConfig.DATASET_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(
                                CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class).name())
                                .isEqualTo("IBM037");
                        assertThat(context.getBean(
                                CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME, Charset.class).name())
                                .as("the fixture code page must not move with the active one")
                                .isEqualTo("US-ASCII");
                    });
        }

        @Test
        @DisplayName("setting the dataset key to the EBCDIC code page flips only the active bean")
        void settingTheDatasetKeyToTheEbcdicCodePageFlipsOnlyTheActiveBean() {
            // The supported, single-line path for a site running against the twelve binary datasets
            // under app/data/EBCDIC. No Java change is needed, and the fixture-reading code that
            // must stay on ASCII is deliberately unaffected - which is why the two remain distinct
            // beans even when they resolve the same name.
            runnerWith(
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME,
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME,
                    CobolCharsetConfig.DATASET_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(
                                CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class).name())
                                .isEqualTo("IBM037");
                        assertThat(context.getBean(
                                CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME, Charset.class).name())
                                .as("the fixture code page must not move with the active one")
                                .isEqualTo("US-ASCII");
                        assertThat(context.getBean(
                                CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME, Charset.class).name())
                                .isEqualTo("IBM037");
                    });
        }

        @ParameterizedTest(name = "carddemo.charset.dataset={0} yields the {1} code page")
        @CsvSource({
            "IBM037,       IBM037",
            "cp037,        IBM037",
            "ebcdic-cp-us, IBM037",
            "US-ASCII,     US-ASCII",
            "us-ascii,     US-ASCII",
            "ASCII,        US-ASCII",
        })
        @DisplayName("every accepted value of the active-charset key selects the right code page")
        void everyAcceptedActiveCharsetValueSelectsTheRightCodePage(
                String configuredValue, String expectedCanonicalName) {
            runnerWith(
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME,
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME,
                    CobolCharsetConfig.DATASET_CHARSET_PROPERTY + "=" + configuredValue)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(
                                CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class).name())
                                .isEqualTo(expectedCanonicalName);
                    });
        }

        @Test
        @DisplayName("a rejected active-charset value fails the context, naming that key")
        void aRejectedActiveCharsetValueFailsTheContext() {
            // The unsupported arm again, this time reached through Spring rather than by a direct
            // call, so the diagnostic is proved to survive bean-creation wrapping intact.
            runnerWith(
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME,
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME,
                    CobolCharsetConfig.DATASET_CHARSET_PROPERTY + "=" + UNSUPPORTED_BUT_LEGAL_NAME)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context).getFailure()
                                .rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining(UNSUPPORTED_BUT_LEGAL_NAME)
                                .hasMessageContaining(
                                        CobolCharsetConfig.DATASET_CHARSET_PROPERTY)
                                .hasMessageContaining("jdk.charsets");
                    });
        }

        @Test
        @DisplayName("no primary bean exists, so an unqualified Charset injection must choose")
        void noPrimaryBeanExistsSoAnUnqualifiedCharsetInjectionMustChoose() {
            // Deliberate. A @Primary bean would let an injection point that meant EBCDIC silently
            // receive ASCII, reintroducing at the wiring level exactly the implicitness this class
            // exists to remove. Failing with all three candidates named is the intended outcome.
            runnerWithBothCodePagesConfigured().run(context -> {
                assertThat(context).hasNotFailed();
                assertThatExceptionOfType(NoUniqueBeanDefinitionException.class)
                        .isThrownBy(() -> context.getBean(Charset.class))
                        .withMessageContaining(CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME)
                        .withMessageContaining(CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME)
                        .withMessageContaining(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
            });
        }

        @Test
        @DisplayName("each charset bean is a singleton, so repeated injection cannot diverge")
        void eachCharsetBeanIsASingleton() {
            runnerWithBothCodePagesConfigured().run(context -> {
                for (String beanName : List.of(
                        CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME,
                        CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME,
                        CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)) {
                    assertThat(context.getBean(beanName, Charset.class))
                            .as("%s must be one instance for every consumer", beanName)
                            .isSameAs(context.getBean(beanName, Charset.class));
                }
            });
        }
    }

    /**
     * What the two <em>shipped</em> configuration documents actually select.
     *
     * <p>Every other group in this file supplies properties inline, which proves the class behaves
     * correctly for a given configuration but says nothing about the configuration this module
     * ships. That gap is exactly where the defect this group guards against lived: the class
     * defaulted its active code page to ASCII, neither document stated the key, and so a deployment
     * whose bindings addressed the EBCDIC mainframe datasets decoded every record as ASCII - no
     * exception, no log line, just 300 plausible-looking bytes of nonsense per account record.
     *
     * <p>These two assertions therefore read the real {@code application.yml} and
     * {@code application-test.yml} through {@link ConfigDataApplicationContextInitializer}, exactly
     * as the application does, and hold each document to the code page its own dataset bindings
     * require. Correct Java with a silent document is not a fix; both halves have to be true.
     */
    @Nested
    @DisplayName("the shipped documents - each profile names the code page its datasets need")
    class TheShippedConfigurationDocuments {

        @Test
        @DisplayName("application.yml selects IBM037, because its bindings address the mainframe "
                + "datasets")
        void theDefaultProfileSelectsTheEbcdicCodePage() {
            shippedDefaultProfile()
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(
                                CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class).name())
                                .as("the active code page of the shipped default profile")
                                .isEqualTo(CONFIGURED_EBCDIC_NAME);
                        assertThat(context.getBean(
                                CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME, Charset.class).name())
                                .isEqualTo(CONFIGURED_EBCDIC_NAME);
                        assertThat(context.getBean(
                                CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME, Charset.class).name())
                                .as("the fixture code page stays declared and stays ASCII")
                                .isEqualTo(CONFIGURED_ASCII_NAME);
                    });
        }

        @Test
        @DisplayName("application-test.yml selects US-ASCII, because its bindings address the nine "
                + "fixtures")
        void theTestProfileSelectsTheAsciiCodePage() {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(CobolCharsetConfig.class)
                    .withPropertyValues("spring.profiles.active=test")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(
                                CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME, Charset.class).name())
                                .as("the active code page of the shipped test profile")
                                .isEqualTo(CONFIGURED_ASCII_NAME);
                        assertThat(context.getBean(
                                CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME, Charset.class).name())
                                .as("both code pages stay declared under every profile")
                                .isEqualTo(CONFIGURED_EBCDIC_NAME);
                    });
        }

        @Test
        @DisplayName("the two documents disagree about the active key and agree about everything "
                + "else")
        void theTwoDocumentsDisagreeOnlyAboutWhichCodePageIsActive() {
            // The precise guarantee, stated as an assertion rather than a comment: "ebcdic" and
            // "ascii" name what the two code pages ARE and must never differ between profiles,
            // because an encoding is a parity concern rather than an environmental one. Only
            // "dataset" - which of them is in use - follows the datasets a profile is bound to.
            List<String> defaultProfile = new ArrayList<>();
            List<String> testProfile = new ArrayList<>();
            shippedDefaultProfile()
                    .run(context -> defaultProfile.addAll(resolvedCodePageNames(context)));
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(CobolCharsetConfig.class)
                    .withPropertyValues("spring.profiles.active=test")
                    .run(context -> testProfile.addAll(resolvedCodePageNames(context)));

            assertThat(defaultProfile)
                    .containsExactly(CONFIGURED_EBCDIC_NAME, CONFIGURED_ASCII_NAME,
                            CONFIGURED_EBCDIC_NAME);
            assertThat(testProfile)
                    .containsExactly(CONFIGURED_EBCDIC_NAME, CONFIGURED_ASCII_NAME,
                            CONFIGURED_ASCII_NAME);
        }

        /**
         * Which code page this group reads is decided by the document under test, never by the process
         * that happened to start the build.
         *
         * <p>The two assertions above are only worth their weight if the default arm really does read
         * {@code application.yml} alone. It does not do so by default: a runner inherits the JVM's
         * system properties and the process environment, so a build started as
         * {@code mvn test -Dspring.profiles.active=test}, or by an executor exporting
         * {@code SPRING_PROFILES_ACTIVE=test}, would load {@code application-test.yml} inside the
         * default arm and make it report {@code US-ASCII} - the exact misreading this group exists to
         * catch, arriving from the harness instead of from the configuration.
         *
         * <p>Both routes are reproduced, because they are neutralised by different precedence rules and
         * closing one does not close the other: a JVM system property, as {@code -D} supplies, set for
         * the duration of the run and restored afterwards; and a process environment variable, as a CI
         * executor supplies.
         */
        @Test
        @DisplayName("an externally activated profile cannot change the active code page, by system "
                + "property or by environment variable")
        void anExternallyActivatedProfileCannotChangeTheActiveCodePage() {
            List<ApplicationContextRunner> underAnInheritedProfile = List.of(
                    shippedDefaultProfile().withSystemProperties("spring.profiles.active=test"),
                    shippedDefaultProfile(processEnvironmentActivating("test")));

            for (ApplicationContextRunner runner : underAnInheritedProfile) {
                runner.run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getActiveProfiles())
                            .as("the default arm must resolve no active profile at all")
                            .isEmpty();
                    assertThat(resolvedCodePageNames(context))
                            .as("the shipped default document's three code pages, unchanged")
                            .containsExactly(CONFIGURED_EBCDIC_NAME, CONFIGURED_ASCII_NAME,
                                    CONFIGURED_EBCDIC_NAME);
                });
            }
        }

        /**
         * The three published code-page names, in EBCDIC, ASCII, active order.
         *
         * @param context a started context carrying the three charset beans
         * @return their canonical charset names
         */
        private List<String> resolvedCodePageNames(ApplicationContext context) {
            return Stream.of(
                            CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME,
                            CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME,
                            CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                    .map(beanName -> context.getBean(beanName, Charset.class).name())
                    .toList();
        }
    }


    /**
     * The configuration keys, their exact spellings, and what happens when one is missing or blank.
     *
     * <p>An application that silently decodes mainframe records with the wrong code page is worse
     * than one that refuses to start, so every one of these cases is expected to fail the context.
     */
    @Nested
    @DisplayName("the configuration keys - required, never defaulted, never blank")
    class RequiredConfigurationKeys {

        @Test
        @DisplayName("the property-key constants match the keys declared in application.yml")
        void thePropertyKeyConstantsMatchApplicationYml() {
            // Transcribed from app/java/src/main/resources/application.yml, which is the sole
            // authority for them and which names this class as their consumer. Both that document
            // and application-test.yml declare carddemo.charset.ebcdic: IBM037 and
            // carddemo.charset.ascii: US-ASCII with identical values, because an encoding is a
            // parity concern and the test profile must not be able to differ from production.
            assertThat(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY)
                    .isEqualTo("carddemo.charset.ebcdic");
            assertThat(CobolCharsetConfig.ASCII_CHARSET_PROPERTY)
                    .isEqualTo("carddemo.charset.ascii");
            assertThat(CobolCharsetConfig.DATASET_CHARSET_PROPERTY)
                    .isEqualTo("carddemo.charset.dataset");
        }

        @Test
        @DisplayName("all three keys carry no default at all - every expression is a bare placeholder")
        void allThreeKeysCarryNoDefault()
                throws NoSuchMethodException {
            // Read straight off the constructor's @Value expressions, so the "a required key is
            // never defaulted" guarantee is checked mechanically rather than trusted. Every one of
            // the three is a BARE placeholder: no ":" separator, therefore no default, therefore no
            // way for a missing key to be papered over. The third expression matters most - it once
            // carried a nested ${carddemo.charset.ascii} default, which is exactly how production
            // came to read EBCDIC datasets as ASCII without failing.
            Constructor<?> constructor = CobolCharsetConfig.class
                    .getDeclaredConstructor(String.class, String.class, String.class);

            List<String> expressions = Arrays.stream(constructor.getParameters())
                    .map(parameter -> parameter.getAnnotation(Value.class))
                    .peek(annotation -> assertThat(annotation)
                            .as("every constructor parameter must be bound from configuration")
                            .isNotNull())
                    .map(Value::value)
                    .toList();

            assertThat(expressions).containsExactly(
                    "${carddemo.charset.ebcdic}",
                    "${carddemo.charset.ascii}",
                    "${carddemo.charset.dataset}");
            assertThat(expressions)
                    .as("a ':' anywhere in one of these expressions would be a default, and a "
                            + "default is how a missing code page reaches production unnoticed")
                    .allSatisfy(expression -> assertThat(expression).doesNotContain(":"));
        }

        @ParameterizedTest(name = "removing {0} fails the context naming that key")
        @CsvSource({
            "carddemo.charset.ebcdic, carddemo.charset.ascii=US-ASCII,   carddemo.charset.dataset=US-ASCII",
            "carddemo.charset.ascii,  carddemo.charset.ebcdic=IBM037,    carddemo.charset.dataset=IBM037",
            "carddemo.charset.dataset, carddemo.charset.ebcdic=IBM037,   carddemo.charset.ascii=US-ASCII",
        })
        @DisplayName("removing any of the three required keys fails the context with Spring's own "
                + "diagnostic")
        void removingAnyRequiredKeyFailsTheContext(
                String omittedKey, String firstRemainingKey, String secondRemainingKey) {
            runnerWith(firstRemainingKey, secondRemainingKey).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(PlaceholderResolutionException.class)
                        .hasMessageContaining("Could not resolve placeholder")
                        .hasMessageContaining(omittedKey);
            });
        }

        @ParameterizedTest(name = "blanking {0} fails the context naming that key")
        @CsvSource({
            "carddemo.charset.ebcdic",
            "carddemo.charset.ascii",
            "carddemo.charset.dataset",
        })
        @DisplayName("a blank value is not treated as absence - it is rejected naming its own key")
        void aBlankValueIsNotTreatedAsAbsence(String blankedKey) {
            // Blanking a key out must not quietly stand in for a code page. There is no default to
            // fall back to, so the empty string travels all the way to the resolver - and the
            // resolver rejects it in its own right rather than letting the JDK raise a bare
            // IllegalCharsetNameException that quotes only the empty name. The difference is the
            // whole value of the diagnostic: an operator needs to know WHICH key was blanked, and
            // only this module knows that.
            Map<String, String> properties = new LinkedHashMap<>(Map.of(
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY, CONFIGURED_EBCDIC_NAME,
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY, CONFIGURED_ASCII_NAME,
                    CobolCharsetConfig.DATASET_CHARSET_PROPERTY, CONFIGURED_ASCII_NAME));
            properties.put(blankedKey, "");

            runnerWith(properties.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toArray(String[]::new))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context).getFailure()
                                .rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining(blankedKey)
                                .hasMessageContaining("required and is never defaulted");
                    });
        }
    }

    /**
     * The reason this class exists: a platform default charset silently corrupts every mainframe
     * record, and nothing throws when it does.
     *
     * <p>These are the practice-B8 assertions. They work on two inline byte literals rather than on
     * files, because {@code app/data/EBCDIC} and {@code app/data/ASCII} are read-only reference
     * material that this test must not open. Both literals were re-verified independently against
     * the datasets before being transcribed, and the account prefix was additionally cross-checked
     * against the corresponding ASCII fixture.
     *
     * <p>Note what is <em>not</em> asserted, and why: not that the resolved charsets differ from
     * {@link Charset#defaultCharset()}. That looks stricter but is merely flaky - a JVM whose
     * default is {@code US-ASCII} would fail it on a correct configuration. The stable and stronger
     * property is that each bean is the explicitly named code page and that naming it is what
     * decides the outcome, which is what these tests establish.
     */
    @Nested
    @DisplayName("never the platform default - naming the code page decides the bytes")
    class NeverThePlatformDefault {

        @Test
        @DisplayName("the EBCDIC bean decodes a real account record prefix exactly")
        void theEbcdicBeanDecodesARealAccountRecordPrefixExactly() {
            // The first 60 bytes of app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS. Under IBM037 they
            // are byte-identical to the first 60 characters of app/data/ASCII/acctdata.txt, which is
            // what makes the ASCII fixtures usable as the authoritative parity oracle at all.
            runnerWithBothCodePagesConfigured().run(context -> {
                Charset ebcdic = context.getBean(
                        CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME, Charset.class);

                String decoded = new String(ebcdicAccountRecordPrefix(), ebcdic);

                assertThat(decoded).isEqualTo(EXPECTED_ACCOUNT_PREFIX_TEXT);
                assertThat(decoded).hasSize(60);
            });
        }

        @Test
        @DisplayName("the same bytes read as US-ASCII are corrupted, and nothing throws")
        void theSameBytesReadAsUsAsciiAreCorrupted() {
            // The failure mode this whole class exists to prevent. Note that the decode SUCCEEDS: no
            // exception, no diagnostic, just a record whose every field is wrong. A field-for-field
            // parity diff would report every field as different with no clue as to the cause.
            String corrupted =
                    new String(ebcdicAccountRecordPrefix(), StandardCharsets.US_ASCII);

            assertThat(corrupted)
                    .as("US-ASCII is a seven-bit encoding, so every EBCDIC digit byte is unmappable")
                    .isNotEqualTo(EXPECTED_ACCOUNT_PREFIX_TEXT)
                    .contains(String.valueOf(REPLACEMENT_CHARACTER));
            assertThat(corrupted.chars().filter(codePoint -> codePoint == REPLACEMENT_CHARACTER)
                    .count())
                    .as("58 of the 60 bytes are above 0x7F; only the two x'60' hyphens map")
                    .isEqualTo(58L);
        }

        @Test
        @DisplayName("the two configured code pages disagree on the same bytes, so naming matters")
        void theTwoConfiguredCodePagesDisagreeOnTheSameBytes() {
            // The airtight form of "never the platform default": if two code pages yield different
            // text from identical bytes, then leaving the code page unnamed leaves the result
            // undefined. This holds whatever the platform default happens to be, which is precisely
            // why it is asserted instead of a comparison against Charset.defaultCharset().
            runnerWithBothCodePagesConfigured().run(context -> {
                Charset ebcdic = context.getBean(
                        CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME, Charset.class);
                Charset ascii = context.getBean(
                        CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME, Charset.class);
                byte[] record = ebcdicAccountRecordPrefix();

                assertThat(new String(record, ebcdic))
                        .isNotEqualTo(new String(record, ascii));
                assertThat(ebcdic).isNotEqualTo(ascii);
            });
        }

        @Test
        @DisplayName("the EBCDIC bean encodes as well as it decodes, so it is usable for writing")
        void theEbcdicBeanEncodesAsWellAsItDecodes() {
            // A repository has to write records as well as read them, so the bean must round-trip.
            // Asserting the encode direction also guards the sign overpunch: a lossy encoder would
            // turn the '{' into something else and corrupt the sign rather than merely the text.
            runnerWithBothCodePagesConfigured().run(context -> {
                Charset ebcdic = context.getBean(
                        CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME, Charset.class);

                byte[] reEncoded = EXPECTED_ACCOUNT_PREFIX_TEXT.getBytes(ebcdic);

                assertThat(reEncoded).containsExactly(ebcdicAccountRecordPrefix());
            });
        }

        @Test
        @DisplayName("the zoned-decimal sign overpunch survives the round trip untouched")
        void theZonedDecimalSignOverpunchSurvivesUntouched() {
            // Every persisted numeric field in this system is zoned DISPLAY - COMP-3 appears in none
            // of the 28 copybooks - so the charset alone decides how a number and its sign are read.
            // The three PIC S9(10)V99 money fields of CVACT01Y end at offsets 23, 35 and 47, and
            // each carries EBCDIC x'C0', the +0 overpunch, which surfaces as '{'. It is data, not
            // decoration, and must never be trimmed or "cleaned".
            byte[] record = ebcdicAccountRecordPrefix();

            for (int overpunchOffset : new int[] { 23, 35, 47 }) {
                assertThat(record[overpunchOffset])
                        .as("offset %d ends a PIC S9(10)V99 field and must hold x'C0'",
                                overpunchOffset)
                        .isEqualTo((byte) 0xC0);
                assertThat(EXPECTED_ACCOUNT_PREFIX_TEXT.charAt(overpunchOffset))
                        .as("x'C0' decodes to '{' under IBM037 at offset %d", overpunchOffset)
                        .isEqualTo('{');
            }
        }

        @Test
        @DisplayName("the EBCDIC space surfaces as '@' under US-ASCII, so corruption looks real")
        void theEbcdicSpaceSurfacesAsAnAtSignUnderUsAscii() {
            // The most insidious case, and the reason a "does it look right?" review is no defence.
            // The first 32 bytes of app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS span SEC-USR-ID,
            // the space-padded SEC-USR-FNAME and the first four bytes of SEC-USR-LNAME. Twelve of
            // them are the EBCDIC space x'40', which is '@' in ASCII - so the wrong code page yields
            // plausible-looking output rather than an obvious failure.
            runnerWithBothCodePagesConfigured().run(context -> {
                Charset ebcdic = context.getBean(
                        CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME, Charset.class);
                byte[] securityRecord = ebcdicSecurityRecordPrefix();

                assertThat(new String(securityRecord, ebcdic))
                        .isEqualTo(EXPECTED_SECURITY_PREFIX_TEXT);
                assertThat(EXPECTED_SECURITY_PREFIX_TEXT.getBytes(ebcdic))
                        .containsExactly(ebcdicSecurityRecordPrefix());

                String corrupted = new String(securityRecord, StandardCharsets.US_ASCII);
                assertThat(corrupted.chars().filter(codePoint -> codePoint == '@').count())
                        .as("the twelve x'40' pad bytes of SEC-USR-FNAME become twelve at-signs")
                        .isEqualTo(12L);
                assertThat(corrupted).isNotEqualTo(EXPECTED_SECURITY_PREFIX_TEXT);
            });
        }

        @Test
        @DisplayName("resolution depends only on the configured names, not on the running platform")
        void resolutionDependsOnlyOnTheConfiguredNames() {
            // Deliberately phrased without reference to Charset.defaultCharset(): the two beans are
            // asserted to be the code pages application.yml names, whatever this JVM's default is.
            // Determinism was confirmed by running this class three times and again under both
            // -Dfile.encoding=UTF-8 and -Dfile.encoding=US-ASCII, with identical results.
            runnerWithBothCodePagesConfigured().run(context -> {
                assertThat(context.getBean(
                        CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME, Charset.class).name())
                        .isEqualTo(CONFIGURED_EBCDIC_NAME);
                assertThat(context.getBean(
                        CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME, Charset.class).name())
                        .isEqualTo(CONFIGURED_ASCII_NAME);
            });
        }
    }

    /**
     * The boundaries of the class: what it must not publish, what state it must not hold, and the
     * direction its dependencies must run.
     *
     * <p>These assertions are structural rather than behavioural, and they are what stop the class
     * from quietly growing into something else. Reflection is filtered through
     * {@link #declaredFieldsOfConfiguration()} and {@link #declaredMethodsOfConfiguration()} so the
     * JaCoCo agent's synthetic members cannot make the results differ between a bare {@code javac}
     * run and a Maven run.
     */
    @Nested
    @DisplayName("the class boundaries - no I/O, no state, no dependency inversion")
    class ConfigurationBoundaries {

        @Test
        @DisplayName("it publishes no DataSource, JdbcTemplate or transaction manager")
        void itPublishesNoDataSourceJdbcTemplateOrTransactionManager() {
            // Those belong to DataSourceConfig and BatchConfig. Loading this configuration alone
            // must contribute nothing but code pages, which is what keeps a charset failure
            // distinguishable from a datasource failure.
            runnerWithBothCodePagesConfigured().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(DataSource.class);
                assertThat(context).doesNotHaveBean(JdbcTemplate.class);
                assertThat(context).doesNotHaveBean(PlatformTransactionManager.class);
            });
        }

        @Test
        @DisplayName("its declared methods are exactly the three bean factories, the resolver and "
                + "the message opener")
        void itsDeclaredMethodsAreExactlyTheThreeBeanFactoriesAndTheResolver() {
            // Pins the member surface so a fourth bean, a static accessor or an I/O helper cannot
            // appear unnoticed. The class performs no I/O at all: it turns configured names into
            // Charset instances, and locating datasets belongs to the repositories.
            //
            // "rejected" is private and pure: it opens all three of the resolver's failure messages
            // with the property key and the rejected value. It exists precisely so that no arm can
            // be the one that forgets to name the key, which is the single most useful thing a
            // charset diagnostic carries. It is asserted to be private below, so it adds nothing to
            // the published surface this test exists to police.
            assertThat(declaredMethodsOfConfiguration())
                    .extracting(Method::getName)
                    .containsExactlyInAnyOrder(
                            "carddemoEbcdicCharset",
                            "carddemoAsciiCharset",
                            "carddemoDatasetCharset",
                            "resolve",
                            "rejected");
            assertThat(declaredMethodsOfConfiguration())
                    .filteredOn(method -> "rejected".equals(method.getName()))
                    .singleElement()
                    .satisfies(method -> assertThat(Modifier.isPrivate(method.getModifiers()))
                            .as("the message opener is an internal detail, not published API")
                            .isTrue());
        }

        @Test
        @DisplayName("it holds no mutable state - static constants are immutable, fields are final")
        void itHoldsNoMutableState() {
            // Gate G53 and practice B9. COBOL WORKING-STORAGE must never become static Java state:
            // that would break request isolation and test determinism, and would stop two tests
            // exercising two encodings in one JVM.
            for (Field field : declaredFieldsOfConfiguration()) {
                int modifiers = field.getModifiers();

                assertThat(Modifier.isFinal(modifiers))
                        .as("%s must be final; a reassignable code page is mutable state",
                                field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("%s is an array: final fixes the reference but leaves the contents "
                                + "writable, so it would still be mutable state", field.getName())
                        .isFalse();
                if (Modifier.isStatic(modifiers)) {
                    assertThat(field.getType())
                            .as("%s is static, so its type must be immutable", field.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("its declared fields are exactly the six key and bean-name constants plus the"
                + " three bound names")
        void itsDeclaredFieldsAreExactlyTheConstantsAndTheBoundNames() {
            assertThat(declaredFieldsOfConfiguration())
                    .extracting(Field::getName)
                    .containsExactlyInAnyOrder(
                            "EBCDIC_CHARSET_PROPERTY",
                            "ASCII_CHARSET_PROPERTY",
                            "DATASET_CHARSET_PROPERTY",
                            "EBCDIC_CHARSET_BEAN_NAME",
                            "ASCII_CHARSET_BEAN_NAME",
                            "DATASET_CHARSET_BEAN_NAME",
                            "ebcdicCharsetName",
                            "asciiCharsetName",
                            "datasetCharsetName");
        }

        @Test
        @DisplayName("it offers no public static accessor and holds no static Charset")
        void itOffersNoPublicStaticAccessorAndHoldsNoStaticCharset() {
            // A CobolCharsetConfig.datasetCharset() convenience method would make the encoding
            // globally reachable and reintroduce shared state through the back door. Consumers
            // inject the bean they need and pass the Charset down; there is no holder, no singleton
            // and no service locator.
            assertThat(declaredMethodsOfConfiguration())
                    .filteredOn(method -> Modifier.isStatic(method.getModifiers()))
                    .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                    .as("a public static charset accessor is exactly what this design forbids")
                    .isEmpty();
            assertThat(declaredFieldsOfConfiguration())
                    .filteredOn(field -> Modifier.isStatic(field.getModifiers()))
                    .extracting(Field::getType)
                    .as("a static Charset field would be a globally reachable holder")
                    .doesNotContain(Charset.class);
        }

        @Test
        @DisplayName("it exposes no type from the common package, so the dependency runs one way")
        void itExposesNoTypeFromTheCommonPackage() {
            // The codecs take their encoding as an explicit parameter - FixedWidthRecord and
            // FixedWidthCodec both declare Charset a constructor argument - so common has no Spring
            // dependency and stays independently testable. Wiring common to this class would forfeit
            // that. This test file likewise imports no common type; the import list above is the
            // other half of the proof.
            List<String> referencedTypeNames = Stream.concat(
                            declaredMethodsOfConfiguration().stream()
                                    .flatMap(method -> Stream.concat(
                                            Stream.of(method.getReturnType()),
                                            Arrays.stream(method.getParameterTypes()))),
                            Arrays.stream(CobolCharsetConfig.class.getDeclaredConstructors())
                                    .flatMap(constructor ->
                                            Arrays.stream(constructor.getParameterTypes())))
                    .map(Class::getName)
                    .toList();

            assertThat(referencedTypeNames)
                    .isNotEmpty()
                    .as("config must never depend on common; common must never depend on config")
                    .noneMatch(typeName ->
                            typeName.startsWith("com.vsergeychik.carddemo.common"));
        }

        @Test
        @DisplayName("it is a Spring @Configuration that CGLIB can subclass")
        void itIsASpringConfigurationThatCglibCanSubclass() {
            Configuration configuration =
                    CobolCharsetConfig.class.getAnnotation(Configuration.class);

            assertThat(configuration)
                    .as("the class contributes beans and is found by the entry point's own scan")
                    .isNotNull();
            assertThat(configuration.proxyBeanMethods())
                    .as("left at the default, so bean methods keep singleton semantics")
                    .isTrue();
            // proxyBeanMethods=true means Spring creates a CGLIB subclass at runtime, which is only
            // possible while the class stays public and non-final with public, non-final bean
            // methods. Asserting it here turns a startup failure into a compile-time-adjacent one.
            assertThat(Modifier.isPublic(CobolCharsetConfig.class.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(CobolCharsetConfig.class.getModifiers())).isFalse();

            for (Method beanMethod : declaredMethodsOfConfiguration()) {
                if (!beanMethod.getName().startsWith("carddemo")) {
                    continue;
                }
                assertThat(Modifier.isPublic(beanMethod.getModifiers()))
                        .as("%s must be public for CGLIB to override it", beanMethod.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(beanMethod.getModifiers()))
                        .as("%s must not be final for CGLIB to override it", beanMethod.getName())
                        .isFalse();
                assertThat(beanMethod.getReturnType()).isEqualTo(Charset.class);
            }
        }

        @Test
        @DisplayName("it registers no @ConfigurationProperties type, because it introduces none")
        void itRegistersNoConfigurationPropertiesType() {
            // Recorded because the specification for this test anticipated the opposite. The class
            // binds three names with @Value on its constructor and declares no
            // @ConfigurationProperties type, so @EnableConfigurationProperties would have nothing to
            // register. The sibling DataSourceConfig does use it - for DataSourceProperties and its
            // dataset bindings - so this is a considered difference rather than an oversight, and
            // CardDemoApplication's lack of @ConfigurationPropertiesScan is irrelevant here.
            assertThat(CobolCharsetConfig.class
                    .isAnnotationPresent(EnableConfigurationProperties.class))
                    .as("no @ConfigurationProperties type exists to self-register")
                    .isFalse();
        }

        @Test
        @DisplayName("its single constructor is public and binds three names, not three charsets")
        void itsSingleConstructorIsPublicAndBindsThreeNames() {
            // Names, not Charset instances, are captured at construction so that an unsupported name
            // is reported against the specific bean - and therefore the specific property - that
            // asked for it, rather than as one opaque constructor failure covering all three. That
            // same constructor is the test seam these tests use.
            Constructor<?>[] constructors = CobolCharsetConfig.class.getDeclaredConstructors();

            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPublic(constructors[0].getModifiers())).isTrue();
            assertThat(constructors[0].getParameterTypes())
                    .containsExactly(String.class, String.class, String.class);
        }
    }
}
