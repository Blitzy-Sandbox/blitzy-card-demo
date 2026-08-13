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
 * Unit tests for {@link CobolCharsetConfig}, the single place in this module where a character encoding is
 * named.
 */
@DisplayName("CobolCharsetConfig - the one place a CardDemo code page is named")
class CobolCharsetConfigTest {
    private static final String CONFIGURED_EBCDIC_NAME = "IBM037";

    private static final String CONFIGURED_ASCII_NAME = "US-ASCII";

    private static final String UNSUPPORTED_BUT_LEGAL_NAME = "IBM037-DOES-NOT-EXIST";

    private static final String NO_EXTERNALLY_ACTIVATED_PROFILE = "spring.profiles.active=";

    private static final String EXPECTED_ACCOUNT_PREFIX_TEXT =
            "00000000001Y00000001940{00000020200{00000010200{2014-11-2020";

    private static final String EXPECTED_SECURITY_PREFIX_TEXT =
            "ADMIN001MARGARET            GOLD";

    private static final char REPLACEMENT_CHARACTER = '\uFFFD';

    private static byte[] ebcdicAccountRecordPrefix() {
        return new byte[] {
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF1,
            (byte) 0xE8,
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF1, (byte) 0xF9, (byte) 0xF4, (byte) 0xF0, (byte) 0xC0,
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF2, (byte) 0xF0, (byte) 0xF2, (byte) 0xF0, (byte) 0xF0, (byte) 0xC0,
            (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF1, (byte) 0xF0, (byte) 0xF2, (byte) 0xF0, (byte) 0xF0, (byte) 0xC0,
            (byte) 0xF2, (byte) 0xF0, (byte) 0xF1, (byte) 0xF4, (byte) 0x60, (byte) 0xF1,
            (byte) 0xF1, (byte) 0x60, (byte) 0xF2, (byte) 0xF0,
            (byte) 0xF2, (byte) 0xF0,
        };
    }

    private static byte[] ebcdicSecurityRecordPrefix() {
        return new byte[] {
            (byte) 0xC1, (byte) 0xC4, (byte) 0xD4, (byte) 0xC9, (byte) 0xD5, (byte) 0xF0,
            (byte) 0xF0, (byte) 0xF1,
            (byte) 0xD4, (byte) 0xC1, (byte) 0xD9, (byte) 0xC7, (byte) 0xC1, (byte) 0xD9,
            (byte) 0xC5, (byte) 0xE3,
            (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40,
            (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40, (byte) 0x40,
            (byte) 0xC7, (byte) 0xD6, (byte) 0xD3, (byte) 0xC4,
        };
    }

    private ApplicationContextRunner runnerWith(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(CobolCharsetConfig.class)
                .withPropertyValues(properties);
    }

    private ApplicationContextRunner runnerWithBothCodePagesConfigured() {
        return runnerWith(
                CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + CONFIGURED_EBCDIC_NAME,
                CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME,
                CobolCharsetConfig.DATASET_CHARSET_PROPERTY + "=" + CONFIGURED_ASCII_NAME);
    }

    private ApplicationContextRunner shippedDefaultProfile() {
        return shippedDefaultProfile(context -> { });
    }

    private ApplicationContextRunner shippedDefaultProfile(
            ApplicationContextInitializer<ConfigurableApplicationContext> inheritedEnvironment) {
        return new ApplicationContextRunner()
                .withInitializer(inheritedEnvironment)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(CobolCharsetConfig.class)
                .withPropertyValues(NO_EXTERNALLY_ACTIVATED_PROFILE);
    }

    private static ApplicationContextInitializer<ConfigurableApplicationContext>
            processEnvironmentActivating(String profile) {
        return context -> context.getEnvironment().getPropertySources().addBefore(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("simulatedProcessEnvironment",
                        Map.of("SPRING_PROFILES_ACTIVE", profile)));
    }

    private static List<Field> declaredFieldsOfConfiguration() {
        return Arrays.stream(CobolCharsetConfig.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !field.getName().startsWith("$"))
                .toList();
    }

    private static List<Method> declaredMethodsOfConfiguration() {
        return Arrays.stream(CobolCharsetConfig.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> !method.getName().startsWith("$"))
                .toList();
    }

    @Nested
    @DisplayName("resolve - turning a configured code-page name into a Charset, or failing loudly")
    class ResolvingAConfiguredCodePageName {
        @Test
        @DisplayName("the configured EBCDIC name resolves to the IBM037 code page")
        void theConfiguredEbcdicNameResolvesToIbm037() {
            Charset resolved = CobolCharsetConfig.resolve(
                    CONFIGURED_EBCDIC_NAME, CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY);

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
            assertThatIllegalStateException()
                    .isThrownBy(() -> CobolCharsetConfig.resolve(
                            UNSUPPORTED_BUT_LEGAL_NAME,
                            CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .withMessageContaining(UNSUPPORTED_BUT_LEGAL_NAME)
                    .withMessageContaining(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY)
                    .withMessageContaining("is not supported by this JVM")
                    .withMessageContaining("No fallback is applied")
                    .withMessageContaining("platform default charset is never substituted")
                    .withMessageContaining("jdk.charsets")
                    .withMessageContaining("jlink");
        }

        @ParameterizedTest(name = "an unsupported value of {0} is reported against that key")
        @ValueSource(strings = {
            "carddemo.charset.ebcdic", "carddemo.charset.ascii", "carddemo.charset.dataset",
        })
        @DisplayName("the diagnostic names whichever property supplied the offending value")
        void theDiagnosticNamesTheOffendingProperty(String propertyKey) {
            assertThatIllegalStateException()
                    .isThrownBy(() ->
                            CobolCharsetConfig.resolve(UNSUPPORTED_BUT_LEGAL_NAME, propertyKey))
                    .withMessageContaining("configured by property '" + propertyKey + "'")
                    .withMessageContaining("correct the value of '" + propertyKey + "'");
        }

        @Test
        @DisplayName("the diagnostic says strictly more than the JDK's own would have")
        void theDiagnosticSaysStrictlyMoreThanTheJdksOwnWould() {
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

        private List<String> resolvedCodePageNames(ApplicationContext context) {
            return Stream.of(
                            CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME,
                            CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME,
                            CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                    .map(beanName -> context.getBean(beanName, Charset.class).name())
                    .toList();
        }
    }

    @Nested
    @DisplayName("the configuration keys - required, never defaulted, never blank")
    class RequiredConfigurationKeys {
        @Test
        @DisplayName("the property-key constants match the keys declared in application.yml")
        void thePropertyKeyConstantsMatchApplicationYml() {
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

    @Nested
    @DisplayName("never the platform default - naming the code page decides the bytes")
    class NeverThePlatformDefault {
        @Test
        @DisplayName("the EBCDIC bean decodes a real account record prefix exactly")
        void theEbcdicBeanDecodesARealAccountRecordPrefixExactly() {
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

    @Nested
    @DisplayName("the class boundaries - no I/O, no state, no dependency inversion")
    class ConfigurationBoundaries {
        @Test
        @DisplayName("it publishes no DataSource, JdbcTemplate or transaction manager")
        void itPublishesNoDataSourceJdbcTemplateOrTransactionManager() {
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
            assertThat(CobolCharsetConfig.class
                    .isAnnotationPresent(EnableConfigurationProperties.class))
                    .as("no @ConfigurationProperties type exists to self-register")
                    .isFalse();
        }

        @Test
        @DisplayName("its single constructor is public and binds three names, not three charsets")
        void itsSingleConstructorIsPublicAndBindsThreeNames() {
            Constructor<?>[] constructors = CobolCharsetConfig.class.getDeclaredConstructors();

            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPublic(constructors[0].getModifiers())).isTrue();
            assertThat(constructors[0].getParameterTypes())
                    .containsExactly(String.class, String.class, String.class);
        }
    }
}
