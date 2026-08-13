package com.vsergeychik.carddemo.config;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * The module's single data-access seam: one pooled {@link DataSource}, one {@link JdbcTemplate}, and the
 * keyed catalogue of CardDemo dataset bindings that replaces every hard-coded mainframe dataset name in the
 * migrated code.
 *
 * <p>The sign-on comparison that the legacy {@code COSGN00C} performs stays exactly as the COBOL performs
 * it, so no authentication, hashing or credential-management machinery is introduced anywhere in this
 * module - including here.
 *
 * <p>This class is also where the {@code test} profile's two halves are held together. The profile itself
 * is {@code application-test.yml}, which ships inside the artifact; everything about it that must never
 * ship - the in-memory database and its account, the fixture inventory and the pinned conversation-state
 * seal key - lives in {@code carddemo-test-fixtures.yml} under {@code src/test/resources}, which the
 * profile imports optionally. {@link #carddemoTestProfileFixtureDocument(Environment)} refuses startup
 * when the shipped half is in effect and the unpackaged half is not, because that half-configured state is
 * silently wrong rather than loudly broken: a deployment supplies {@code CARDDEMO_DATASOURCE_URL}, so the
 * absent H2 datasource would not have stopped it, and the profile would have run test-shaped settings -
 * dataset names redirected to the {@code CARDDEMO.TEST.*} relations, every batch job disabled - against
 * the real backend.
 */
@Configuration
@EnableConfigurationProperties({ DataSourceProperties.class, DataSourceConfig.DatasetBindings.class })
public class DataSourceConfig {
    /**
     * The self-declaration of the packaged half of the {@code test} profile, defined only by
     * {@code application-test.yml}: {@value}.
     */
    public static final String PROFILE_DOCUMENT_PROPERTY = "carddemo.test.profile-document";

    /**
     * The self-declaration of the unpackaged half of the {@code test} profile, defined only by
     * {@code carddemo-test-fixtures.yml}: {@value}.
     */
    public static final String FIXTURE_DOCUMENT_PROPERTY = "carddemo.test.fixtures-document";

    private static final String HALF_CONFIGURED_TEST_PROFILE_MESSAGE = """
            The 'test' profile is in effect but carddemo-test-fixtures.yml did not resolve, so \
            startup is refused. That document is the other half of the profile: it lives in \
            src/test/resources, is copied to target/test-classes and is deliberately NEVER \
            packaged, and it supplies the in-memory H2 DataSource, the Spring Batch JobRepository \
            schema initialisation, the fixture inventory and the pinned conversation-state seal \
            key. application-test.yml imports it optionally, so its absence does not fail the \
            import - and the missing DataSource would not have stopped a deployment either, \
            because a site that supplies CARDDEMO_DATASOURCE_URL satisfies spring.datasource.url \
            from application.yml. What is left is a set of test-shaped settings with nothing \
            test-shaped behind them: every dataset name redirected to a CARDDEMO.TEST.* relation \
            that does not exist on the real backend, every batch job disabled, and a seal key that \
            must never guard a real conversation. TO RUN THE SUITE OR A LOCAL FIXTURE-BACKED RUN: \
            put target/test-classes on the classpath ahead of target/classes ('mvn -f \
            app/java/pom.xml verify' does this; a plain 'java -jar carddemo.jar' cannot). TO RUN A \
            DEPLOYMENT: do not activate the 'test' profile at all - clear SPRING_PROFILES_ACTIVE \
            (and any spring.profiles.active) and supply spring.datasource.* for the site backend.\
            """;
    private static final String NO_DATASOURCE_URL_MESSAGE = """
            spring.datasource.url is not configured, so no DataSource can be built. This module \
            pins no JDBC driver coordinate on purpose: the CardDemo datasets are VSAM and \
            sequential files, there is no EXEC SQL anywhere in the COBOL estate, and indexed VSAM \
            has no standard published JDBC driver. The URL, the driver class and the credentials \
            for the site's mainframe data-access driver are therefore DEPLOYMENT-TIME INPUTS: \
            supply spring.datasource.url and spring.datasource.driver-class-name (plus \
            spring.datasource.username and spring.datasource.password if the site needs them), \
            which application.yml binds from the CARDDEMO_DATASOURCE_* environment variables. \
            Startup is refused rather than falling back to an embedded database, because a build \
            that silently came up against the wrong backend would report byte-level parity results \
            that mean nothing. To run the suite instead, activate the 'test' profile, whose \
            application-test.yml supplies an in-memory H2 DataSource that also backs the Spring \
            Batch JobRepository - and note that the profile needs src/test/resources on the \
            classpath as well as activating, because it imports its DataSource from a document \
            that lives there and is deliberately never packaged. If this message appears with \
            'test' already active, that classpath is what is missing: run from \
            target/test-classes:target/classes rather than from the packaged jar.""";

    private static final String DRIVER_LOADING_MECHANISM =
            "Place the driver jar in a directory named by the LOADER_PATH environment variable (or "
                    + "-Dloader.path=...) and launch with 'LOADER_PATH=/opt/carddemo/drivers java -jar "
                    + "carddemo.jar': the artifact is a Spring Boot archive launched by "
                    + "PropertiesLauncher, so LOADER_PATH is what extends its classpath. A -cp entry "
                    + "beside -jar is ignored by the JVM and never reaches the application.";

    private static final String DRIVER_IS_A_DEPLOYMENT_INPUT =
            "This module pins no JDBC driver coordinate by design - the CardDemo datasets are VSAM "
                    + "and sequential files, there is no EXEC SQL anywhere in the COBOL estate, and "
                    + "indexed VSAM has no standard published JDBC driver - so the site's "
                    + "mainframe data-access driver is supplied at deployment time. "
                    + DRIVER_LOADING_MECHANISM;

    private static final String UNDETERMINED_DRIVER_MESSAGE = """
            No JDBC driver class could be determined, so no DataSource can be built. \
            spring.datasource.driver-class-name is not set, and the scheme of \
            spring.datasource.url is not one Spring Boot recognises - which is expected for a \
            site-specific mainframe data-access URL. Set spring.datasource.driver-class-name \
            explicitly. NO FALLBACK IS APPLIED: in particular the embedded database on this \
            classpath is never substituted, because it is present only at test scope to back the \
            Spring Batch JobRepository and the parity harness, and a deployment that silently came \
            up against an empty in-memory database would report byte-level parity results that mean \
            nothing.""" + " " + DRIVER_IS_A_DEPLOYMENT_INPUT;

    /**
     * Holds the two halves of the {@code test} profile together. The {@link DataSource} declares
     * {@code @DependsOn} this bean, so a half-configured profile is refused before any connection is
     * pooled - and since the transaction manager, the {@code JobRepository} and all twelve repositories
     * reach their data through that one {@code DataSource}, nothing that touches a dataset can run ahead
     * of the check. Source order alone would not have achieved that: bean creation follows dependencies,
     * not declaration.
     *
     * <p>The check is on the documents, not on the profile name: a profile that is merely named active
     * changes nothing on its own, whereas {@code application-test.yml} being IN EFFECT is what redirects
     * every dataset name, disables every job and pins the seal key. So the shipped half declares itself
     * with {@value #PROFILE_DOCUMENT_PROPERTY} and the unpackaged half declares itself with
     * {@value #FIXTURE_DOCUMENT_PROPERTY}; the first without the second is the state that cannot be
     * allowed to start.
     *
     * @param environment the resolved configuration environment; never {@code null}
     * @return the name of the unpackaged document backing the profile, or
     *     {@link TestProfileFixtureDocument#NOT_REQUIRED} when the packaged half is not in effect and no
     *     such document is expected
     * @throws IllegalStateException if the packaged half of the {@code test} profile is in effect and the
     *     unpackaged half did not resolve
     */
    @Bean(TestProfileFixtureDocument.BEAN_NAME)
    public TestProfileFixtureDocument carddemoTestProfileFixtureDocument(Environment environment) {
        if (!StringUtils.hasText(environment.getProperty(PROFILE_DOCUMENT_PROPERTY))) {
            return TestProfileFixtureDocument.NOT_REQUIRED;
        }
        String fixtureDocument = environment.getProperty(FIXTURE_DOCUMENT_PROPERTY);
        if (!StringUtils.hasText(fixtureDocument)) {
            throw new IllegalStateException(HALF_CONFIGURED_TEST_PROFILE_MESSAGE);
        }
        return new TestProfileFixtureDocument(fixtureDocument);
    }

    /**
     * What {@link #carddemoTestProfileFixtureDocument(Environment)} resolved: the unpackaged document
     * backing the {@code test} profile, so a context test can assert the two halves were both in effect
     * rather than infer it.
     *
     * @param name the resolved document name, or the empty string when the packaged half of the
     *     {@code test} profile is not in effect
     */
    public record TestProfileFixtureDocument(String name) {
        /** The bean name, which {@link DataSourceConfig#dataSource} orders itself behind: {@value}. */
        public static final String BEAN_NAME = "carddemoTestProfileFixtureDocument";

        /** The result when the packaged half of the {@code test} profile is not in effect. */
        public static final TestProfileFixtureDocument NOT_REQUIRED =
                new TestProfileFixtureDocument("");
    }

    /**
     * The one {@link DataSource} in the module: pooled by HikariCP and assembled entirely from
     * configuration.
     *
     * @param properties the {@code spring.datasource.*} binding, registered by this class and by Spring
     *     Boot's JDBC auto-configuration alike; never {@code null}
     * @return the pooled, configuration-bound {@code DataSource}
     * @throws IllegalStateException if {@code spring.datasource.url} is absent, empty or blank, or if no
     *     driver class can be determined for it, or if the determined driver class is not on the runtime
     *     classpath
     */
    @Bean
    @DependsOn(TestProfileFixtureDocument.BEAN_NAME)
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        if (!StringUtils.hasText(properties.getUrl())) {
            throw new IllegalStateException(NO_DATASOURCE_URL_MESSAGE);
        }
        String driverClassName = determineDriverClassName(properties);
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .driverClassName(driverClassName)
                .build();
    }

    private String determineDriverClassName(DataSourceProperties properties) {
        String configured = properties.getDriverClassName();
        String driverClassName = StringUtils.hasText(configured)
                ? configured
                : DatabaseDriver.fromJdbcUrl(properties.getUrl()).getDriverClassName();
        if (!StringUtils.hasText(driverClassName)) {
            throw new IllegalStateException(UNDETERMINED_DRIVER_MESSAGE);
        }
        if (!ClassUtils.isPresent(driverClassName, getClass().getClassLoader())) {
            throw new IllegalStateException("The JDBC driver class '" + driverClassName
                    + "' is not on the classpath, so no DataSource can be built. "
                    + (StringUtils.hasText(configured)
                            ? "It was named by spring.datasource.driver-class-name; check the "
                                    + "spelling and confirm the driver jar is on the launcher's "
                                    + "path."
                            : "It was derived from the scheme of spring.datasource.url; either "
                                    + "deploy that driver or name the correct one explicitly in "
                                    + "spring.datasource.driver-class-name.")
                    + " " + DRIVER_IS_A_DEPLOYMENT_INPUT + " Startup is refused here rather than on "
                    + "the first query, because a pooled DataSource is lazy: it would otherwise be "
                    + "injected into every repository and fail in the middle of a job.");
        }
        return driverClassName;
    }

    /**
     * The one {@link JdbcTemplate} in the module, over the one {@link DataSource} above, and untuned.
     *
     * <p>A template that capped rows or cancelled statements would change what a program observes: a
     * truncated browse is a short file, and a short file is a different report; a cancelled statement is a
     * data-access failure the COBOL has no arm for and therefore no behaviour to reproduce.
     *
     * @param dataSource the pooled {@code DataSource} from {@link #dataSource(DataSourceProperties)}
     * @return the module-wide {@code JdbcTemplate}, untuned
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * The one record-image representation every dataset read, write and comparison operand uses, resolved
     * from {@value RecordImageForm#FORM_PROPERTY}.
     *
     * <p>A COBOL KSDS matches a key by its bytes and browses in ascending order of those bytes, so a
     * deployment choosing {@code CHARACTER} must guarantee that the record-image column's collation is
     * bytewise - binary or code-point - in the code page named by {@code carddemo.charset.dataset}: a keyed
     * read composes a positional {@code LIKE} over that column and a browse composes {@code ORDER BY} it,
     * which makes the backend's collation the key semantics. This build cannot verify that guarantee, and
     * {@code BINARY} is the form that does not need it.
     *
     * @param configured the configured representation name, {@code CHARACTER} or {@code BINARY}
     * @return the resolved representation
     * @throws IllegalArgumentException if the configured value names no representation
     */
    @Bean(RecordImageForm.FORM_BEAN_NAME)
    public RecordImageForm carddemoRecordImageForm(
            @Value("${" + RecordImageForm.FORM_PROPERTY + "}") String configured) {
        return RecordImageForm.parse(configured);
    }

    /**
     * The one physical-record ordinal every physical-sequential read is ordered by, resolved from
     * {@value PhysicalSequence#EXPRESSION_PROPERTY}.
     *
     * @param configured the configured ordinal name
     * @return the resolved ordinal
     * @throws IllegalArgumentException if the configured value is absent, blank or not a single bare SQL
     *     identifier
     */
    @Bean(PhysicalSequence.BEAN_NAME)
    public PhysicalSequence carddemoPhysicalSequence(
            @Value("${" + PhysicalSequence.EXPRESSION_PROPERTY + "}") String configured) {
        return PhysicalSequence.of(configured);
    }

    /**
     * The DD-name-keyed catalogue of CardDemo dataset bindings, bound from the {@code carddemo.datasets}
     * configuration prefix.
     *
     * <p>{@link #afterPropertiesSet()} runs {@link #validate()} immediately after binding, which checks the
     * things no per-property rule can: that the key set is exactly the twenty-seven DD names the migrated
     * code reads, and that each entry is internally coherent and consistent with the entries it refers to.
     */
    @ConfigurationProperties(prefix = "carddemo.datasets", ignoreUnknownFields = false)
    public static class DatasetBindings extends LinkedHashMap<String, DatasetBinding>
            implements InitializingBean {
        private static final long serialVersionUID = 1L;

        static final Set<String> REQUIRED_DD_NAMES = Set.of(
                "ACCTDAT", "CARDAIX", "CARDDAT", "CCXREF", "CUSTDAT", "CXACAIX", "TRANSACT",
                "USRSEC",
                "ACCTFILE", "CARDFILE", "CUSTFILE", "XREFFILE", "XREFFIL1", "CARDXREF", "TRANFILE",
                "DALYTRAN", "DALYREJS", "TCATBALF", "DISCGRP", "TRANTYPE", "TRANCATG", "DATEPARM",
                "TRNXFILE", "TRANREPT", "STMTFILE", "HTMLFILE", "SYSTRAN");

        static final String ALTERNATE_INDEX_ORGANIZATION = "aix-path";

        static final Set<String> VALID_ORGANIZATIONS =
                Set.of("ksds", ALTERNATE_INDEX_ORGANIZATION, "sequential");

        static final Set<String> VALID_RECORD_FORMATS = Set.of("F", "FB");

        /**
         * Validates the whole catalogue once, at context refresh, immediately after binding.
         *
         * @throws IllegalStateException if the catalogue's key set is not exactly
         *     {@link #REQUIRED_DD_NAMES}, or if any entry is internally inconsistent
         */
        @Override
        public void afterPropertiesSet() {
            validate();
        }

        /**
         * The catalogue's whole validity contract, as one method so a unit test can drive it with no
         * application context in the picture.
         *
         * @throws IllegalStateException on the first violation found, describing it and how to fix it
         */
        public void validate() {
            validateKeySet();
            forEach(this::validateEntry);
            forEach(this::validateAlternateIndexRelationship);
            validateKeyGeometry();
        }

        private void validateKeySet() {
            Set<String> missing = new LinkedHashSet<>(REQUIRED_DD_NAMES);
            missing.removeAll(keySet());
            Set<String> unexpected = new LinkedHashSet<>(keySet());
            unexpected.removeAll(REQUIRED_DD_NAMES);
            if (!missing.isEmpty() || !unexpected.isEmpty()) {
                throw new IllegalStateException("The carddemo.datasets catalogue must declare "
                        + "exactly the " + REQUIRED_DD_NAMES.size() + " DD names the migrated code "
                        + "reads - the 8 CICS FILE definitions of app/csd/CARDDEMO.CSD, the 7 batch "
                        + "DD aliases of those same datasets, and the 12 batch-only datasets. "
                        + "Missing: " + sorted(missing) + ". Unexpected: " + sorted(unexpected)
                        + ". A missing name leaves a job unable to resolve its own DD; an unexpected "
                        + "one is a name nothing will ever read, and is usually the typo that "
                        + "explains a missing one. Keys are matched exactly, with no case-insensitive "
                        + "or fuzzy fallback.");
            }
        }

        private void validateEntry(String ddName, DatasetBinding binding) {
            if (binding == null) {
                throw new IllegalStateException(invalid(ddName)
                        + " it declares no properties at all. Every entry must declare at least a "
                        + "dsname, an organization and a record-length.");
            }
            if (!StringUtils.hasText(binding.dsname())) {
                throw new IllegalStateException(invalid(ddName)
                        + " it declares no dsname. Every dataset's location lives in configuration "
                        + "and none is defaulted inside Java, so an entry without one cannot be "
                        + "resolved at all - and a blank value is not an absence to be filled in, it "
                        + "is an unset environment placeholder that has to be supplied.");
            }
            if (!containsIgnoringCase(VALID_ORGANIZATIONS, binding.organization())) {
                throw new IllegalStateException(invalid(ddName) + " its organization is '"
                        + binding.organization() + "', which is not one of " + sorted(
                                VALID_ORGANIZATIONS)
                        + ". Those three are the only access organizations the legacy estate uses, "
                        + "and a fourth would describe an access path no repository implements.");
            }
            if (binding.recordFormat() != null
                    && !containsIgnoringCase(VALID_RECORD_FORMATS, binding.recordFormat())) {
                throw new IllegalStateException(invalid(ddName) + " its record-format is '"
                        + binding.recordFormat() + "', which is not one of " + sorted(
                                VALID_RECORD_FORMATS)
                        + ". Omit the key where the JCL declares no DCB; do not invent a value, and "
                        + "note that RECORDFORMAT(V) from the CSD is deliberately not accepted "
                        + "because this module models no variable-length record.");
            }
            if (binding.recordLength() <= 0) {
                throw new IllegalStateException(invalid(ddName) + " its record-length is "
                        + binding.recordLength() + ". The fixed record width is the single auditable "
                        + "width source for the hand-written codec and the output writers, so it "
                        + "must be a positive number of bytes and can never be inferred: a wrong "
                        + "width silently corrupts every record read or written under this DD name.");
            }
            if (binding.blockSize() != null && binding.blockSize() < 0) {
                throw new IllegalStateException(invalid(ddName) + " its block-size is "
                        + binding.blockSize() + ". Transcribe the JCL DCB verbatim: 0 is meaningful "
                        + "and means system-determined, exactly as BLKSIZE=0 asks, but a negative "
                        + "block size is not something any DCB can declare.");
            }
            if (binding.keyLength() != null && binding.keyLength() <= 0) {
                throw new IllegalStateException(invalid(ddName) + " its key-length is "
                        + binding.keyLength() + ". State a key length only where a source file "
                        + "declares one, and state it as a positive number of bytes; omit the key "
                        + "entirely for a dataset that has no key.");
            }
        }

        private void validateAlternateIndexRelationship(String ddName, DatasetBinding binding) {
            boolean declaresPath =
                    ALTERNATE_INDEX_ORGANIZATION.equalsIgnoreCase(binding.organization());
            boolean namesBase = StringUtils.hasText(binding.base());
            if (declaresPath != namesBase) {
                throw new IllegalStateException(invalid(ddName) + " it declares organization '"
                        + binding.organization() + "' and " + (namesBase
                                ? "names base '" + binding.base() + "'"
                                : "names no base")
                        + ". An alternate-index path must name the base cluster it indexes, and an "
                        + "entry that is not a path must name none: the presence of a base is what "
                        + "marks an entry as an additional access path over an existing repository "
                        + "rather than a dataset in its own right (gate G45).");
            }
            if (!declaresPath) {
                return;
            }
            if (!StringUtils.hasText(binding.alternateKey())) {
                throw new IllegalStateException(invalid(ddName) + " it is an alternate-index path "
                        + "over base '" + binding.base() + "' but names no alternate-key. The "
                        + "copybook field forming the alternate key is what the finder method on the "
                        + "base repository reads, so a path without one cannot be used.");
            }
            DatasetBinding base = get(binding.base());
            if (base == null) {
                throw new IllegalStateException(invalid(ddName) + " it names base '" + binding.base()
                        + "', which is not itself a declared DD name. A path resolves through its "
                        + "base, so the base must be an entry of this catalogue. Configured keys: "
                        + sorted(keySet()) + ".");
            }
            if (ALTERNATE_INDEX_ORGANIZATION.equalsIgnoreCase(base.organization())) {
                throw new IllegalStateException(invalid(ddName) + " it names base '" + binding.base()
                        + "', which is itself an alternate-index path. A path indexes a base cluster, "
                        + "never another path: chaining them would imply an index over an index, "
                        + "which no VSAM definition in app/csd/CARDDEMO.CSD declares.");
            }
        }

        private static String invalid(String ddName) {
            return "The carddemo.datasets entry for DD name '" + ddName + "' is invalid:";
        }

        private static List<String> sorted(Set<String> values) {
            return values.stream().sorted().toList();
        }

        private static boolean containsIgnoringCase(Set<String> permitted, String candidate) {
            return candidate != null
                    && permitted.stream().anyMatch(value -> value.equalsIgnoreCase(candidate));
        }

        /**
         * Resolves a dataset binding by its mainframe DD name.
         *
         * @param key the DD or CICS {@code FILE} name, in the upper case that {@code application.yml}
         *     declares - for example the account master's key
         * @return the configured binding for that key; never {@code null}
         * @throws IllegalStateException if no binding is configured under {@code carddemo.datasets} for
         *     {@code key}
         */
        public DatasetBinding binding(String key) {
            DatasetBinding binding = get(key);
            if (binding == null) {
                throw new IllegalStateException("No dataset binding is configured for DD name '"
                        + key + "'. Every CardDemo dataset - the 8 CICS FILE definitions from "
                        + "app/csd/CARDDEMO.CSD and the batch-only DD names from app/jcl - is "
                        + "declared under the carddemo.datasets configuration prefix, and dataset "
                        + "names are never hard-coded in Java. Add carddemo.datasets." + key
                        + " to application.yml, or correct the DD name at the call site: keys are "
                        + "matched exactly, with no case-insensitive or fuzzy fallback. Configured "
                        + "keys: " + keySet() + ".");
            }
            return binding;
        }

        /**
         * Checks every configured entry at startup, and refuses to start when one is not addressable as
         * configured.
         *
         * @throws IllegalStateException naming the offending entry and the rule it breaks
         */
        public void validateKeyGeometry() {
            for (Map.Entry<String, DatasetBinding> entry : entrySet()) {
                String name = entry.getKey();
                DatasetBinding binding = entry.getValue();
                if (binding.keyed()) {
                    validateKeyed(name, binding);
                } else if (binding.keyLength() != null) {
                    throw new IllegalStateException("Dataset '" + name + "' is configured with "
                            + "organization '" + binding.organization() + "' yet declares key-length "
                            + binding.keyLength() + ". A sequential dataset has no key: it is read "
                            + "front to back. Remove the key-length, or correct the organization to "
                            + DatasetBinding.KSDS + " if the dataset really is indexed.");
                }
                if (DatasetBinding.AIX_PATH.equals(binding.organization())) {
                    validateAlternateIndexPath(name, binding);
                }
            }
        }

        private void validateKeyed(String name, DatasetBinding binding) {
            Integer keyLength = binding.keyLength();
            if (keyLength == null) {
                throw new IllegalStateException("Dataset '" + name + "' is configured with "
                        + "organization '" + binding.organization() + "' but declares no key-length, "
                        + "so nothing states where its key ends and a keyed read against it would be "
                        + "guessing. Transcribe the width from the key field's PICTURE in "
                        + (binding.copybook() == null ? "the copybook defining its layout"
                                : binding.copybook()) + " and cite it beside the value.");
            }
            if (keyLength < 1) {
                throw new IllegalStateException("Dataset '" + name + "' declares key-length "
                        + keyLength + ". A key is at least one byte wide.");
            }
            int offset = binding.keyOffsetOrZero();
            if (offset < 0) {
                throw new IllegalStateException("Dataset '" + name + "' declares key-offset " + offset
                        + ". An offset into a record is zero-based and never negative.");
            }
            if (offset + keyLength > binding.recordLength()) {
                throw new IllegalStateException("Dataset '" + name + "' declares a key at offset "
                        + offset + " of width " + keyLength + ", which ends at byte "
                        + (offset + keyLength) + " of a record that is only "
                        + binding.recordLength() + " bytes. A key must lie inside the record it "
                        + "identifies; check the offset and width against "
                        + (binding.copybook() == null ? "the layout" : binding.copybook()) + ".");
            }
        }

        private void validateAlternateIndexPath(String name, DatasetBinding path) {
            String baseName = path.base();
            if (baseName == null || baseName.isBlank()) {
                throw new IllegalStateException("Dataset '" + name + "' is configured as an "
                        + DatasetBinding.AIX_PATH + " but names no base. An alternate-index path is "
                        + "an additional access path over an existing cluster, so the cluster it "
                        + "indexes has to be named.");
            }
            DatasetBinding base = get(baseName);
            if (base == null) {
                throw new IllegalStateException("Dataset '" + name + "' indexes base '" + baseName
                        + "', which is not configured. Configured keys: " + keySet() + ".");
            }
            if (!DatasetBinding.KSDS.equals(base.organization())) {
                throw new IllegalStateException("Dataset '" + name + "' indexes '" + baseName
                        + "', whose organization is '" + base.organization() + "'. An "
                        + "alternate-index path is built over a base cluster, never over another "
                        + "path.");
            }
            if (path.recordLength() != base.recordLength()) {
                throw new IllegalStateException("Alternate-index path '" + name + "' declares "
                        + "record-length " + path.recordLength() + " but its base '" + baseName
                        + "' declares " + base.recordLength() + ". A path reaches the SAME records as "
                        + "its base, so the two widths cannot differ - one of them would decode the "
                        + "other's bytes against the wrong layout.");
            }
            if (!Objects.equals(path.copybook(), base.copybook())) {
                throw new IllegalStateException("Alternate-index path '" + name + "' declares "
                        + "copybook " + path.copybook() + " but its base '" + baseName
                        + "' declares " + base.copybook() + ". Both address the same records, so both "
                        + "must name the same layout.");
            }
            if (path.alternateKey() == null || path.alternateKey().isBlank()) {
                throw new IllegalStateException("Dataset '" + name + "' is configured as an "
                        + DatasetBinding.AIX_PATH + " but names no alternate-key. The copybook field "
                        + "forming the alternate key is what distinguishes this access path from its "
                        + "base.");
            }
        }
    }

    /**
     * One dataset binding: where a CardDemo dataset lives and what shape its records are.
     *
     * @param dsname the dataset name, or - under a profile that rebinds it, such as the fixture-backed
     *     {@code test} profile - a resolvable resource location
     * @param organization the access organization as configured: an indexed KSDS, an alternate-index path
     *     over a base cluster, or a sequential dataset
     * @param gdg {@code true} when the JCL names a relative generation such as {@code NAME(+1)}, so a write
     *     creates a new generation rather than replacing one; {@code false} when not declared
     * @param recordFormat the record format transcribed from the JCL {@code DCB} - {@code F} or {@code FB}
     * @param blockSize the block size transcribed from the JCL {@code DCB} where one is declared, in which
     *     case {@code 0} is meaningful and means system-determined, exactly as {@code BLKSIZE=0} asks
     * @param recordLength the fixed record width in bytes - the single auditable width source for the
     *     hand-written fixed-width codec and the output writers
     * @param copybook the {@code app/cpy} member defining the layout, so a width can be diffed against its
     *     {@code PICTURE} clauses without leaving the configuration
     * @param keyLength the key width in bytes
     * @param keyOffset the key's zero-based byte offset within the record, for the alternate-index paths
     *     whose key is not at the start of the record
     * @param base for an alternate-index path, the key of the base dataset entry it indexes
     * @param alternateKey for an alternate-index path, the copybook field forming the alternate key;
     *     {@code null} otherwise
     * @param reusable the VSAM {@code REUSE} / {@code NOREUSE} attribute of the cluster, as
     *     {@code app/catlg/LISTCAT.txt} records it
     */
    public record DatasetBinding(
            String dsname,
            String organization,
            boolean gdg,
            String recordFormat,
            Integer blockSize,
            int recordLength,
            String copybook,
            Integer keyLength,
            Integer keyOffset,
            String base,
            String alternateKey,
            boolean reusable) {
        @ConstructorBinding
        public DatasetBinding {
        }

        /**
         * An entry that declares no {@code REUSE} attribute, which is {@code NOREUSE}.
         *
         * @param dsname the dataset name or resolvable resource location
         * @param organization the access organization as configured
         * @param gdg whether the JCL names a relative generation
         * @param recordFormat the JCL {@code DCB} record format, or {@code null}
         * @param blockSize the JCL {@code DCB} block size, or {@code null}
         * @param recordLength the fixed record width in bytes
         * @param copybook the {@code app/cpy} member defining the layout, or {@code null}
         * @param keyLength the key width in bytes, or {@code null} for a sequential dataset
         * @param keyOffset the key's zero-based offset, or {@code null} for offset zero
         * @param base the base dataset entry an alternate-index path indexes, or {@code null}
         * @param alternateKey the copybook field forming the alternate key, or {@code null}
         */
        public DatasetBinding(String dsname, String organization, boolean gdg, String recordFormat,
                Integer blockSize, int recordLength, String copybook, Integer keyLength,
                Integer keyOffset, String base, String alternateKey) {
            this(dsname, organization, gdg, recordFormat, blockSize, recordLength, copybook, keyLength,
                    keyOffset, base, alternateKey, false);
        }

        public static final String KSDS = "ksds";

        public static final String AIX_PATH = "aix-path";

        /**
         * Whether the cluster carries the VSAM {@code REUSE} attribute.
         *
         * @return {@code true} only when the configuration declares {@code reusable: true}
         */
        public boolean reusableCluster() {
            return reusable;
        }

        /**
         * Whether this entry is addressed by key - a base KSDS or an alternate-index path over one.
         *
         * @return {@code true} for {@link #KSDS} and {@link #AIX_PATH}, {@code false} for a sequential
         *     dataset
         */
        public boolean keyed() {
            return KSDS.equals(organization) || AIX_PATH.equals(organization);
        }

        /**
         * The key's zero-based offset within the record: the declared value, or zero when none is declared.
         *
         * @return the offset, never negative
         */
        public int keyOffsetOrZero() {
            return keyOffset == null ? 0 : keyOffset;
        }

        /**
         * The key span, as the repositories address it.
         *
         * @return the offset and length of the key
         * @throws IllegalStateException if this entry declares no key length, which
         *     {@link DatasetBindings#validate()} rejects at startup
         */
        public DatasetRelation.KeySpan keySpan() {
            if (keyLength == null) {
                throw new IllegalStateException("Dataset '" + dsname + "' is configured with "
                        + "organization '" + organization + "' and declares no key-length, so there "
                        + "is no authority for where its key ends. A keyed read against it would be "
                        + "guessing. Declare key-length from the key field's PICTURE in "
                        + (copybook == null ? "its copybook" : copybook) + ".");
            }
            return new DatasetRelation.KeySpan(keyOffsetOrZero(), keyLength);
        }
    }
}
