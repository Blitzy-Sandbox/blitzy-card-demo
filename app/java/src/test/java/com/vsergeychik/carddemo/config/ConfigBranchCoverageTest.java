package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.nio.charset.Charset;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

/**
 * Decision-level tests for the two configuration classes, {@link CobolCharsetConfig} and
 * {@link DataSourceConfig}.
 *
 * <h2>Why these three decisions in particular</h2>
 * The configuration package contains very little logic - it exists to bind values, not to compute -
 * but the little it does contain is all of the same kind: <em>a guard that refuses to let the
 * application start on a silently wrong default.</em> There are exactly three, and each protects a
 * constraint the migration depends on:
 *
 * <ul>
 *   <li>{@link CobolCharsetConfig#resolve(String, String)} refuses an unsupported charset name rather
 *       than substituting the platform default. Decoding a fixed-width mainframe record in the wrong
 *       code page corrupts every byte of it without any error, so a loud failure at startup is the
 *       only safe behaviour. {@code IBM037} is the case that matters: it comes from the JDK's
 *       {@code jdk.charsets} module, which a trimmed runtime image omits.</li>
 *   <li>{@link DataSourceConfig#dataSource(DataSourceProperties)} refuses to build a
 *       {@link DataSource} with no URL. No JDBC driver coordinate is pinned in this module by design -
 *       the datasets are VSAM and sequential files and there is no {@code EXEC SQL} anywhere in the
 *       COBOL estate - so the URL is a deployment-time input, and its absence has to be reported
 *       rather than defaulted.</li>
 *   <li>{@link DatasetBindings#binding(String)} refuses an unknown DD name rather than returning
 *       {@code null}. Every dataset name lives in {@code application.yml} and none is hard-coded in
 *       Java, so a typo in a DD name must fail where it is looked up.</li>
 * </ul>
 *
 * <h2>Scope</h2>
 * These are plain unit tests: no Spring context is started, no {@code @SpringBootTest} is used and
 * nothing touches a network or a filesystem. Each of the three guards is driven on both sides, which
 * is what a threshold on branch coverage - rather than line coverage - actually asks for.
 */
@DisplayName("config - the three startup guards that refuse a silently wrong default")
class ConfigBranchCoverageTest {

    @Nested
    @DisplayName("CobolCharsetConfig.resolve - never substitutes the platform default")
    class CharsetResolution {

        @Test
        @DisplayName("a supported charset name resolves to that charset")
        void supportedNameResolves() {
            assertThat(CobolCharsetConfig.resolve("US-ASCII",
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY))
                    .isEqualTo(Charset.forName("US-ASCII"));
            assertThat(CobolCharsetConfig.resolve("IBM037",
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .isEqualTo(Charset.forName("IBM037"));
        }

        @Test
        @DisplayName("an unsupported name fails, naming the property and never falling back")
        void unsupportedNameFails() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> CobolCharsetConfig.resolve("NO-SUCH-CODE-PAGE",
                            CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .withMessageContaining("NO-SUCH-CODE-PAGE")
                    .withMessageContaining(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY)
                    .withMessageContaining("platform default charset is never substituted");
        }

        @Test
        @DisplayName("the three declared charset beans each resolve from their own property")
        void beansResolveIndependently() {
            CobolCharsetConfig config = new CobolCharsetConfig("IBM037", "US-ASCII", "US-ASCII");

            assertThat(config.carddemoEbcdicCharset()).isEqualTo(Charset.forName("IBM037"));
            assertThat(config.carddemoAsciiCharset()).isEqualTo(Charset.forName("US-ASCII"));
            assertThat(config.carddemoDatasetCharset()).isEqualTo(Charset.forName("US-ASCII"));
        }

        @Test
        @DisplayName("a bad name reaches the failure through the bean method too")
        void beanMethodPropagatesTheFailure() {
            CobolCharsetConfig config =
                    new CobolCharsetConfig("NOT-A-CHARSET", "US-ASCII", "US-ASCII");

            assertThatIllegalStateException()
                    .isThrownBy(config::carddemoEbcdicCharset)
                    .withMessageContaining("NOT-A-CHARSET");
        }
    }

    @Nested
    @DisplayName("DataSourceConfig.dataSource - the JDBC URL is a deployment-time input")
    class DataSourceGuard {

        @Test
        @DisplayName("a configured URL builds a pooled DataSource")
        void configuredUrlBuildsAPool() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl("jdbc:h2:mem:carddemo-config-guard");
            properties.setDriverClassName("org.h2.Driver");

            DataSource dataSource = new DataSourceConfig().dataSource(properties);

            assertThat(dataSource).isNotNull();
            assertThat(new DataSourceConfig().jdbcTemplate(dataSource).getDataSource())
                    .isSameAs(dataSource);
        }

        @Test
        @DisplayName("an absent URL fails, and explains that no driver is pinned on purpose")
        void absentUrlFails() {
            DataSourceProperties properties = new DataSourceProperties();

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DataSourceConfig().dataSource(properties))
                    .withMessageContaining("spring.datasource.url is not configured")
                    .withMessageContaining("DEPLOYMENT-TIME INPUTS");
        }

        @Test
        @DisplayName("a blank URL is treated as absent, not as a value")
        void blankUrlFails() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl("   ");

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DataSourceConfig().dataSource(properties))
                    .withMessageContaining("spring.datasource.url is not configured");
        }
    }

    @Nested
    @DisplayName("DatasetBindings.binding - an unknown DD name fails where it is looked up")
    class DatasetBindingLookup {

        private static final DatasetBinding ACCTDAT = new DatasetBinding(
                "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS", "KSDS", false, "F", null, 300,
                "CVACT01Y", 11, null, null);

        @Test
        @DisplayName("a configured DD name returns its binding")
        void configuredKeyResolves() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", ACCTDAT);

            assertThat(bindings.binding("ACCTDAT")).isSameAs(ACCTDAT);
            assertThat(bindings.binding("ACCTDAT").recordLength()).isEqualTo(300);
            assertThat(bindings.binding("ACCTDAT").copybook()).isEqualTo("CVACT01Y");
        }

        @Test
        @DisplayName("an unknown DD name fails, listing the keys that are configured")
        void unknownKeyFails() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", ACCTDAT);

            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding("NOSUCHDD"))
                    .withMessageContaining("NOSUCHDD")
                    .withMessageContaining("never hard-coded in Java")
                    .withMessageContaining("ACCTDAT");
        }

        @Test
        @DisplayName("keys are matched exactly - there is no case-insensitive fallback")
        void keysAreCaseSensitive() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", ACCTDAT);

            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding("acctdat"))
                    .withMessageContaining("matched exactly");
        }

        @Test
        @DisplayName("an empty binding map still fails informatively rather than returning null")
        void emptyMapFails() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new DatasetBindings().binding("ACCTDAT"))
                    .withMessageContaining("ACCTDAT");
        }

        @Test
        @DisplayName("the binding record carries the copybook contract, alternate keys included")
        void bindingCarriesTheContract() {
            DatasetBinding path = new DatasetBinding(
                    "AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH", "PATH", false, "F", 1500, 150,
                    "CVACT02Y", 11, "CARDDAT", "CARD-ACCT-ID");

            assertThat(path.base()).isEqualTo("CARDDAT");
            assertThat(path.alternateKey()).isEqualTo("CARD-ACCT-ID");
            assertThat(path.gdg()).isFalse();
            assertThat(path.blockSize()).isEqualTo(1500);
            assertThat(path.organization()).isEqualTo("PATH");
            assertThat(path.recordFormat()).isEqualTo("F");
            assertThat(path.keyLength()).isEqualTo(11);
            assertThat(path.dsname()).endsWith("AIX.PATH");
        }
    }
}
