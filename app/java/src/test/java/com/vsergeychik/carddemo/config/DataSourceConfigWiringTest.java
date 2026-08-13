package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DataSourceConfig.TestProfileFixtureDocument;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests for {@link DataSourceConfig}: the pooled {@link DataSource}, the module-wide {@link JdbcTemplate}
 * and the DD-name-keyed dataset catalogue.
 */
@DisplayName("DataSourceConfig - one DataSource, one JdbcTemplate, one dataset catalogue")
class DataSourceConfigWiringTest {
    private static final String H2_URL = "jdbc:h2:mem:datasourceconfigtest";

    @Nested
    @DisplayName("The DataSource is configuration-bound and refuses to guess")
    class DataSourceBinding {
        @Test
        @DisplayName("A configured URL yields a pooled DataSource")
        void aConfiguredUrlYieldsAPooledDataSource() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl(H2_URL);

            DataSource dataSource = new DataSourceConfig().dataSource(properties);

            assertThat(dataSource).isNotNull();
            assertThat(dataSource.getClass().getName()).contains("Hikari");
        }

        @Test
        @DisplayName("An absent URL refuses startup and names the properties an operator must set")
        void anAbsentUrlRefusesStartup() {
            DataSourceProperties properties = new DataSourceProperties();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new DataSourceConfig().dataSource(properties))
                    .withMessageContaining("spring.datasource.url")
                    .withMessageContaining("DEPLOYMENT-TIME INPUTS")
                    .withMessageContaining("'test' profile");
        }

        @Test
        @DisplayName("A blank URL is treated exactly like an absent one")
        void aBlankUrlIsTreatedLikeAnAbsentOne() {
            DataSourceProperties blank = new DataSourceProperties();
            blank.setUrl("   ");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new DataSourceConfig().dataSource(blank))
                    .withMessageContaining("spring.datasource.url");
        }

        @Test
        @DisplayName("An empty URL is treated exactly like an absent one")
        void anEmptyUrlIsTreatedLikeAnAbsentOne() {
            DataSourceProperties empty = new DataSourceProperties();
            empty.setUrl("");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new DataSourceConfig().dataSource(empty))
                    .withMessageContaining("spring.datasource.url");
        }

        @Test
        @DisplayName("The JdbcTemplate is built over the supplied DataSource and is entirely untuned")
        void theJdbcTemplateIsBuiltOverTheSuppliedDataSource() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl(H2_URL);
            DataSourceConfig config = new DataSourceConfig();
            DataSource dataSource = config.dataSource(properties);

            JdbcTemplate template = config.jdbcTemplate(dataSource);

            JdbcTemplate untouched = new JdbcTemplate();
            assertThat(template.getDataSource()).isSameAs(dataSource);
            assertThat(template.getFetchSize()).isEqualTo(untouched.getFetchSize());
            assertThat(template.getMaxRows()).isEqualTo(untouched.getMaxRows());
            assertThat(template.getQueryTimeout()).isEqualTo(untouched.getQueryTimeout());
        }
    }

    @Nested
    @DisplayName("The test profile may not run on one of its two halves")
    class TestProfileHalves {
        private static final String PACKAGED_HALF = "application-test.yml";

        private static final String UNPACKAGED_HALF = "carddemo-test-fixtures.yml";

        private static TestProfileFixtureDocument resolve(MockEnvironment environment) {
            return new DataSourceConfig().carddemoTestProfileFixtureDocument(environment);
        }

        @Test
        @DisplayName("Neither half in effect expects nothing, so a deployment is unaffected")
        void neitherHalfInEffectExpectsNothing() {
            assertThat(resolve(new MockEnvironment()))
                    .as("the shipped profile is what redirects datasets and disables jobs; with it "
                            + "absent there is nothing to hold together")
                    .isEqualTo(TestProfileFixtureDocument.NOT_REQUIRED);
        }

        @Test
        @DisplayName("A blank packaged declaration counts as absent, not as present-and-empty")
        void aBlankPackagedDeclarationCountsAsAbsent() {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty(DataSourceConfig.PROFILE_DOCUMENT_PROPERTY, "   ");

            assertThat(resolve(environment)).isEqualTo(TestProfileFixtureDocument.NOT_REQUIRED);
        }

        @Test
        @DisplayName("Both halves in effect resolve to the unpackaged document, which is the normal run")
        void bothHalvesInEffectResolve() {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty(DataSourceConfig.PROFILE_DOCUMENT_PROPERTY, PACKAGED_HALF)
                    .withProperty(DataSourceConfig.FIXTURE_DOCUMENT_PROPERTY, UNPACKAGED_HALF);

            assertThat(resolve(environment).name())
                    .as("naming what resolved lets a context test assert both halves were in effect "
                            + "rather than infer it from the run having worked")
                    .isEqualTo(UNPACKAGED_HALF);
        }

        @Test
        @DisplayName("The packaged half alone refuses startup, and says how to reach either end state")
        void thePackagedHalfAloneRefusesStartup() {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty(DataSourceConfig.PROFILE_DOCUMENT_PROPERTY, PACKAGED_HALF);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a site that supplies CARDDEMO_DATASOURCE_URL satisfies spring.datasource.url, "
                            + "so the missing in-memory database would not have stopped it: what would "
                            + "have run is test-shaped settings against the real backend")
                    .isThrownBy(() -> resolve(environment))
                    .withMessageContaining(UNPACKAGED_HALF)
                    .withMessageContaining("target/test-classes")
                    .withMessageContaining("SPRING_PROFILES_ACTIVE")
                    .withMessageContaining("CARDDEMO.TEST.*");
        }

        @Test
        @DisplayName("A blank unpackaged declaration is refused exactly like a missing one")
        void aBlankUnpackagedDeclarationIsRefusedLikeAMissingOne() {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty(DataSourceConfig.PROFILE_DOCUMENT_PROPERTY, PACKAGED_HALF)
                    .withProperty(DataSourceConfig.FIXTURE_DOCUMENT_PROPERTY, "  ");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolve(environment))
                    .withMessageContaining(UNPACKAGED_HALF);
        }
    }

    @Nested
    @DisplayName("The dataset catalogue resolves by exact DD name and never defaults")
    class Catalogue {
        private static DatasetBinding accountShapedBinding() {
            return new DatasetBinding("TEST.M2.ACCTDATA.VSAM.KSDS", "ksds", false, "F", 0, 300,
                    "CVACT01Y", 11, null, null, null);
        }

        @Test
        @DisplayName("A configured key resolves to its binding")
        void aConfiguredKeyResolves() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", accountShapedBinding());

            assertThat(bindings.binding("ACCTDAT").recordLength()).isEqualTo(300);
            assertThat(bindings.binding("ACCTDAT").copybook()).isEqualTo("CVACT01Y");
            assertThat(bindings.binding("ACCTDAT").keyLength()).isEqualTo(11);
        }

        @Test
        @DisplayName("An unconfigured key is a configuration defect and is reported as one")
        void anUnconfiguredKeyIsReported() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", accountShapedBinding());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> bindings.binding("NOSUCHDD"))
                    .withMessageContaining("NOSUCHDD")
                    .withMessageContaining("carddemo.datasets")
                    .withMessageContaining("[ACCTDAT]");
        }

        @Test
        @DisplayName("Matching is exact: no case-insensitive and no trimming fallback")
        void matchingIsExact() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", accountShapedBinding());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> bindings.binding("acctdat"));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> bindings.binding(" ACCTDAT"));
        }

        @Test
        @DisplayName("An empty catalogue reports an empty key set rather than returning null")
        void anEmptyCatalogueReportsAnEmptyKeySet() {
            DatasetBindings empty = new DatasetBindings();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> empty.binding("HTMLFILE"))
                    .withMessageContaining("Configured keys: []");
        }

        @Test
        @DisplayName("Iteration follows binding order, deterministically")
        void iterationFollowsBindingOrder() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put("ACCTDAT", accountShapedBinding());
            bindings.put("CARDDAT", accountShapedBinding());
            bindings.put("HTMLFILE", accountShapedBinding());

            assertThat(bindings.keySet()).containsExactly("ACCTDAT", "CARDDAT", "HTMLFILE");
        }

        @Test
        @DisplayName("The sparse components stay distinguishable from zero and from absent")
        void theSparseComponentsStayDistinguishable() {
            DatasetBinding sequential = new DatasetBinding("TEST.M2.TRANREPT", "sequential", true,
                    "FB", 0, 133, null, null, null, null, null);
            DatasetBinding alternateIndex = new DatasetBinding("TEST.M2.CARDDATA.AIX.PATH",
                    "aix-path", false, null, null, 150, "CVACT02Y", 11, null, "CARDDAT",
                    "CARD-ACCT-ID");

            assertThat(sequential.gdg()).isTrue();
            assertThat(sequential.blockSize()).isZero();
            assertThat(sequential.copybook()).isNull();
            assertThat(sequential.base()).isNull();
            assertThat(alternateIndex.blockSize()).isNull();
            assertThat(alternateIndex.recordFormat()).isNull();
            assertThat(alternateIndex.base()).isEqualTo("CARDDAT");
            assertThat(alternateIndex.alternateKey()).isEqualTo("CARD-ACCT-ID");
        }

        @Test
        @DisplayName("Two bindings with the same components are equal, as a value type should be")
        void twoIdenticalBindingsAreEqual() {
            assertThat(accountShapedBinding())
                    .isEqualTo(accountShapedBinding())
                    .hasSameHashCodeAs(accountShapedBinding());
            assertThat(accountShapedBinding().toString()).contains("CVACT01Y");
        }
    }
}
