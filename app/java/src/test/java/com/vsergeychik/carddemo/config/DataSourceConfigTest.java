package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tests for {@link DataSourceConfig}: the module's single data-access seam - one pooled {@link DataSource},
 * one {@link JdbcTemplate}, and the DD-name-keyed catalogue of dataset bindings that replaces every
 * hard-coded mainframe dataset name in the migrated code.
 */
class DataSourceConfigTest {
    private static final List<String> CSD_FILE_KEYS = List.of(
            "ACCTDAT", "CARDAIX", "CARDDAT", "CCXREF", "CUSTDAT", "CXACAIX", "TRANSACT", "USRSEC");

    private static final List<String> BATCH_ALIAS_KEYS = List.of(
            "ACCTFILE", "CARDFILE", "CUSTFILE", "XREFFILE", "XREFFIL1", "CARDXREF", "TRANFILE");

    private static final List<String> BATCH_ONLY_KEYS = List.of(
            "DALYTRAN", "DALYREJS", "TCATBALF", "DISCGRP", "TRANTYPE", "TRANCATG", "DATEPARM",
            "TRNXFILE", "TRANREPT", "STMTFILE", "HTMLFILE", "SYSTRAN");

    private static final List<String> ALL_DATASET_KEYS =
            Stream.of(CSD_FILE_KEYS, BATCH_ALIAS_KEYS, BATCH_ONLY_KEYS).flatMap(List::stream).toList();

    private static final List<String> ALTERNATE_INDEX_PATH_KEYS =
            List.of("CARDAIX", "CXACAIX", "XREFFIL1");

    private static final List<String> KEYS_WITH_A_DECLARED_BLOCK_SIZE =
            List.of("DALYREJS", "TRANREPT", "STMTFILE", "HTMLFILE", "SYSTRAN");

    private static final List<String> GENERATION_DATA_GROUP_KEYS =
            List.of("DALYREJS", "TRANREPT", "SYSTRAN");

    private static final String IN_MEMORY_URL_PROPERTY =
            "spring.datasource.url=jdbc:h2:mem:carddemo_datasourceconfigtest;DB_CLOSE_DELAY=-1";

    /**
     * The record-image representation, supplied inline by every slice that needs a bean graph.
     *
     * <p>{@value RecordImageForm#FORM_PROPERTY} carries no default <em>anywhere</em> - not in this class
     * and not in {@code application.yml}, which binds it from {@code CARDDEMO_RECORD_IMAGE_FORM} with no
     * fallback - so a slice has to state it or no bean graph can be built at all, whether or not a
     * configuration document is present. That is the property {@link RecordImageRepresentation} asserts
     * directly.
     */
    private static final String RECORD_IMAGE_FORM_PROPERTY =
            RecordImageForm.FORM_PROPERTY + "=CHARACTER";

    /**
     * The physical-record ordinal, supplied inline by every slice that needs a bean graph.
     *
     * <p>{@value PhysicalSequence#EXPRESSION_PROPERTY} carries no default for the same reason
     * {@value RecordImageForm#FORM_PROPERTY} does, and equally in both documents, so a slice has to
     * state it before any bean graph can be built - which is the property
     * {@link PhysicalRecordOrdinal} asserts directly. {@code _ROWID_} because these slices run against
     * an in-memory relation, and it is the ordinal that relation actually presents.
     */
    private static final String PHYSICAL_SEQUENCE_PROPERTY =
            PhysicalSequence.EXPRESSION_PROPERTY + "=_ROWID_";

    private static final String NO_EXTERNALLY_ACTIVATED_PROFILE = "spring.profiles.active=";

    static Stream<String> allDatasetKeys() {
        return ALL_DATASET_KEYS.stream();
    }

    static Stream<String> csdFileKeys() {
        return CSD_FILE_KEYS.stream();
    }

    private ApplicationContextRunner shippedDefaultProfile() {
        return shippedDefaultProfile(context -> { });
    }

    private ApplicationContextRunner shippedDefaultProfile(
            ApplicationContextInitializer<ConfigurableApplicationContext> inheritedEnvironment) {
        return new ApplicationContextRunner()
                .withInitializer(inheritedEnvironment)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(IN_MEMORY_URL_PROPERTY, RECORD_IMAGE_FORM_PROPERTY,
                        PHYSICAL_SEQUENCE_PROPERTY, NO_EXTERNALLY_ACTIVATED_PROFILE);
    }

    private static ApplicationContextInitializer<ConfigurableApplicationContext>
            processEnvironmentActivating(String profile) {
        return context -> context.getEnvironment().getPropertySources().addBefore(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("simulatedProcessEnvironment",
                        Map.of("SPRING_PROFILES_ACTIVE", profile)));
    }

    private ApplicationContextRunner shippedTestProfile() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues("spring.profiles.active=test");
    }

    private ApplicationContextRunner withInlinePropertiesOnly(String... inlineProperties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(RECORD_IMAGE_FORM_PROPERTY, PHYSICAL_SEQUENCE_PROPERTY)
                .withPropertyValues(inlineProperties);
    }

    private ApplicationContextRunner withValidCatalogueAnd(String... inlineProperties) {
        return withInlinePropertiesOnly(
                Stream.concat(minimalValidCatalogue(), Stream.of(inlineProperties))
                        .toArray(String[]::new));
    }

    private static Stream<String> minimalValidCatalogue() {
        Map<String, String> alternateIndexBases = Map.of(
                "CARDAIX", "CARDDAT", "CXACAIX", "CCXREF", "XREFFIL1", "CCXREF");
        return ALL_DATASET_KEYS.stream().flatMap(ddName -> {
            String prefix = "carddemo.datasets." + ddName + ".";
            String base = alternateIndexBases.get(ddName);
            boolean indexedByAPath = alternateIndexBases.containsValue(ddName);
            String organization = base != null ? "aix-path" : indexedByAPath ? "ksds" : "sequential";
            Stream<String> components = Stream.of(
                    prefix + "dsname=SENTINEL.CATALOGUE." + ddName,
                    prefix + "organization=" + organization,
                    prefix + "record-format=FB",
                    prefix + "record-length=50");
            if (base == null && !indexedByAPath) {
                return components;
            }
            Stream<String> keyGeometry = Stream.of(
                    prefix + "key-length=11",
                    prefix + "key-offset=0");
            return base == null
                    ? Stream.concat(components, keyGeometry)
                    : Stream.concat(Stream.concat(components, keyGeometry), Stream.of(
                            prefix + "base=" + base,
                            prefix + "alternate-key=SENTINEL-ALT-KEY"));
        });
    }

    private static DatasetBinding withLocation(DatasetBinding binding, String location) {
        return new DatasetBinding(location, binding.organization(), binding.gdg(),
                binding.recordFormat(), binding.blockSize(), binding.recordLength(),
                binding.copybook(), binding.keyLength(), binding.keyOffset(), binding.base(),
                binding.alternateKey(), binding.reusable());
    }

    @Nested
    @DisplayName("carddemo.datasets - the twenty-seven-entry dataset catalogue binds in full")
    class DatasetCatalogueCompleteness {
        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.config.DataSourceConfigTest#allDatasetKeys")
        @DisplayName("every configured DD name binds to a usable descriptor")
        void everyConfiguredDdNameBinds(String ddName) {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThat(bindings).containsKey(ddName);
                DatasetBinding binding = bindings.binding(ddName);
                assertThat(binding.dsname()).as("%s location", ddName).isNotBlank();
                assertThat(binding.recordLength()).as("%s record length", ddName).isPositive();
                assertThat(binding.organization()).as("%s organization", ddName)
                        .isIn("ksds", "aix-path", "sequential");
            });
        }

        @Test
        @DisplayName("the catalogue holds exactly twenty-seven keys: eight CICS files, seven batch "
                + "aliases and twelve batch-only datasets")
        void catalogueHoldsExactlyTheTwentySevenConfiguredKeys() {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(DatasetBindings.class).keySet())
                    .containsExactlyInAnyOrderElementsOf(ALL_DATASET_KEYS)
                    .hasSize(27));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.config.DataSourceConfigTest#csdFileKeys")
        @DisplayName("every CICS FILE definition binds as a VSAM access path, never as sequential")
        void everyCicsFileDefinitionIsAVsamAccessPath(String ddName) {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(DatasetBindings.class).binding(ddName).organization())
                    .as("%s organization", ddName)
                    .isIn("ksds", "aix-path"));
        }

        @Test
        @DisplayName("every entry declares a record length - the codec's single auditable width "
                + "source, so an entry without one would leave a repository unable to read")
        void everyEntryDeclaresARecordLength() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThat(bindings).allSatisfy((ddName, binding) ->
                        assertThat(binding.recordLength()).as("%s record length", ddName)
                                .isPositive());
            });
        }

        @Test
        @DisplayName("the record format is transcribed wherever the JCL declares a DCB, and the "
                + "report-date parameter dataset is the one entry whose JCL declares none")
        void recordFormatIsDeclaredExceptWhereTheJclDeclaresNoDcb() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> withoutARecordFormat = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.recordFormat() == null) {
                        withoutARecordFormat.add(ddName);
                    } else {
                        assertThat(binding.recordFormat()).as("%s record format", ddName)
                                .isIn("F", "FB");
                    }
                });
                assertThat(withoutARecordFormat).containsExactly("DATEPARM");
            });
        }

        @Test
        @DisplayName("iteration order is deterministic across independent context loads, so "
                + "iteration and diagnostics never vary between runs of the same build")
        void iterationOrderIsDeterministicAcrossLoads() {
            List<String> firstLoad = new ArrayList<>();
            List<String> secondLoad = new ArrayList<>();
            shippedDefaultProfile().run(context -> firstLoad.addAll(
                    context.getBean(DatasetBindings.class).keySet()));
            shippedDefaultProfile().run(context -> secondLoad.addAll(
                    context.getBean(DatasetBindings.class).keySet()));
            assertThat(firstLoad).hasSize(27).isEqualTo(secondLoad);
        }
    }

    @Nested
    @DisplayName("Record geometry - copybook-fixed widths and JCL DCB attributes, verbatim")
    class RecordGeometry {
        @ParameterizedTest(name = "[{index}] {0} -> {1} bytes, RECFM {2}, copybook {3}")
        @CsvSource(nullValues = "-", value = {
            "ACCTDAT,  300, FB, CVACT01Y",
            "ACCTFILE, 300, FB, CVACT01Y",
            "CARDDAT,  150, FB, CVACT02Y",
            "CARDFILE, 150, FB, CVACT02Y",
            "CARDAIX,  150, FB, CVACT02Y",
            "CCXREF,    50, FB, CVACT03Y",
            "CARDXREF,  50, FB, CVACT03Y",
            "XREFFILE,  50, FB, CVACT03Y",
            "CXACAIX,   50, FB, CVACT03Y",
            "XREFFIL1,  50, FB, CVACT03Y",
            "CUSTDAT,  500, FB, CVCUS01Y",
            "CUSTFILE, 500, FB, CVCUS01Y",
            "TRANSACT, 350, FB, CVTRA05Y",
            "TRANFILE, 350, FB, CVTRA05Y",
            "SYSTRAN,  350, F,  CVTRA05Y",
            "USRSEC,    80, FB, CSUSR01Y",
            "DALYTRAN, 350, FB, CVTRA06Y",
            "DALYREJS, 430, F,  -",
            "TCATBALF,  50, FB, CVTRA01Y",
            "DISCGRP,   50, FB, CVTRA02Y",
            "TRANTYPE,  60, FB, CVTRA03Y",
            "TRANCATG,  60, FB, CVTRA04Y",
            "DATEPARM,  80, -,  -",
            "TRNXFILE, 350, FB, COSTM01",
            "TRANREPT, 133, FB, CVTRA07Y",
            "STMTFILE,  80, FB, -",
            "HTMLFILE, 100, FB, -",
        })
        @DisplayName("copybook-fixed width, JCL record format and copybook member")
        void copybookFixedGeometry(String ddName, int recordLength, String recordFormat,
                String copybook) {
            shippedDefaultProfile().run(context -> {
                DatasetBinding binding = context.getBean(DatasetBindings.class).binding(ddName);
                assertThat(binding.recordLength()).as("%s record length", ddName)
                        .isEqualTo(recordLength);
                assertThat(binding.recordFormat()).as("%s record format", ddName)
                        .isEqualTo(recordFormat);
                assertThat(binding.copybook()).as("%s copybook", ddName).isEqualTo(copybook);
            });
        }

        @ParameterizedTest(name = "[{index}] {0} -> BLKSIZE {1}")
        @CsvSource({
            "DALYREJS, 0",
            "TRANREPT, 0",
            "SYSTRAN,  0",
            "STMTFILE, 8000",
            "HTMLFILE, 800",
        })
        @DisplayName("the JCL DCB block size is transcribed, and a declared zero stays a declared "
                + "zero rather than becoming an absence")
        void declaredBlockSizesAreTranscribedIncludingZero(String ddName, int blockSize) {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(DatasetBindings.class).binding(ddName).blockSize())
                    .as("%s block size", ddName)
                    .isEqualTo(blockSize));
        }

        @Test
        @DisplayName("a block size is present for exactly the five entries whose JCL declares one, "
                + "and absent everywhere else")
        void blockSizeIsAbsentWhereNoDcbDeclaresOne() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> declared = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.blockSize() != null) {
                        declared.add(ddName);
                    }
                });
                assertThat(declared)
                        .containsExactlyInAnyOrderElementsOf(KEYS_WITH_A_DECLARED_BLOCK_SIZE);
            });
        }

        @ParameterizedTest(name = "[{index}] {0} -> {1}-byte key")
        @CsvSource({
            "ACCTDAT,  11",
            "CARDDAT,  16",
            "CARDAIX,  11",
            "CCXREF,   16",
            "CXACAIX,  11",
            "CUSTDAT,   9",
            "TRANSACT, 16",
            "USRSEC,    8",
            "ACCTFILE, 11",
            "CARDFILE, 16",
            "XREFFILE, 16",
            "XREFFIL1, 11",
            "CARDXREF, 16",
            "CUSTFILE,  9",
            "TRANFILE, 16",
            "DISCGRP,  16",
            "TCATBALF, 17",
            "TRANTYPE,  2",
            "TRANCATG,  6",
            "TRNXFILE, 32",
        })
        @DisplayName("every keyed dataset states the key width its copybook declares")
        void verifiableKeyWidthsAreStated(String ddName, int keyLength) {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(DatasetBindings.class).binding(ddName).keyLength())
                    .as("%s key length", ddName)
                    .isEqualTo(keyLength));
        }

        @Test
        @DisplayName("a key width is declared for every keyed entry and for no sequential one")
        void aKeyWidthIsDeclaredForEveryKeyedEntryAndNoOther() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> keyedWithoutWidth = new ArrayList<>();
                List<String> sequentialWithWidth = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.keyed() && binding.keyLength() == null) {
                        keyedWithoutWidth.add(ddName);
                    }
                    if (!binding.keyed() && binding.keyLength() != null) {
                        sequentialWithWidth.add(ddName);
                    }
                });
                assertThat(keyedWithoutWidth).as("keyed entries missing a key width").isEmpty();
                assertThat(sequentialWithWidth).as("sequential entries claiming a key").isEmpty();
            });
        }

        @Test
        @DisplayName("the alternate-index paths declare the offset their key actually begins at")
        void theAlternateIndexPathsDeclareTheirKeyOffset() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThat(bindings.binding("CARDAIX").keyOffsetOrZero()).isEqualTo(16);
                assertThat(bindings.binding("CXACAIX").keyOffsetOrZero()).isEqualTo(25);
                assertThat(bindings.binding("XREFFIL1").keyOffsetOrZero()).isEqualTo(25);
                assertThat(bindings.binding("ACCTDAT").keyOffsetOrZero())
                        .as("a primary key begins at the start of the record")
                        .isZero();
                assertThat(bindings.binding("ACCTDAT").keyOffset())
                        .as("and declares no offset at all, rather than an explicit zero")
                        .isNull();
            });
        }

        @Test
        @DisplayName("every declared key span lies inside its record")
        void everyKeySpanLiesInsideItsRecord() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                bindings.forEach((ddName, binding) -> {
                    if (binding.keyed()) {
                        assertThat(binding.keyOffsetOrZero() + binding.keyLength())
                                .as("%s key ends within its %d-byte record", ddName,
                                        binding.recordLength())
                                .isLessThanOrEqualTo(binding.recordLength());
                    }
                });
            });
        }

        @Test
        @DisplayName("the HTML statement width comes from the creating step - 100 bytes and an "
                + "800-byte block, not the pre-delete step's 80 and 3200")
        void htmlStatementWidthComesFromTheCreatingStep() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                DatasetBinding html = bindings.binding("HTMLFILE");
                assertThat(html.recordLength()).isEqualTo(100).isNotEqualTo(80);
                assertThat(html.blockSize()).isEqualTo(800).isNotEqualTo(3200);
                DatasetBinding text = bindings.binding("STMTFILE");
                assertThat(text.recordLength()).isEqualTo(80);
                assertThat(text.blockSize()).isEqualTo(8000);
            });
        }

        @Test
        @DisplayName("the three relative-generation outputs are flagged as such, and nothing else is")
        void relativeGenerationOutputsAreFlagged() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> generations = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.gdg()) {
                        generations.add(ddName);
                    }
                });
                assertThat(generations)
                        .containsExactlyInAnyOrderElementsOf(GENERATION_DATA_GROUP_KEYS);
            });
        }
    }

    @Nested
    @DisplayName("Aliases and alternate indexes - one dataset under several DD names, and a path is "
            + "not a second table")
    class AliasAndAlternateIndexEquivalence {
        @Test
        @DisplayName("the cross-reference base is one dataset reached under three DD names")
        void crossReferenceBaseIsOneDatasetUnderThreeDdNames() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                String base = bindings.binding("CCXREF").dsname();
                assertThat(bindings.binding("XREFFILE").dsname()).isEqualTo(base);
                assertThat(bindings.binding("CARDXREF").dsname()).isEqualTo(base);
            });
        }

        @Test
        @DisplayName("the cross-reference alternate index is one access path under two DD names, and "
                + "stays distinguishable from its base")
        void crossReferenceAlternateIndexIsOneAccessPathUnderTwoDdNames() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                String path = bindings.binding("CXACAIX").dsname();
                assertThat(bindings.binding("XREFFIL1").dsname()).isEqualTo(path);
                assertThat(path).isNotEqualTo(bindings.binding("CCXREF").dsname());
            });
        }

        @Test
        @DisplayName("the card alternate index is a path over the card base: a different location, "
                + "the same record")
        void cardAlternateIndexIsAPathOverTheCardBase() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                DatasetBinding path = bindings.binding("CARDAIX");
                DatasetBinding base = bindings.binding("CARDDAT");
                assertThat(path.dsname()).isNotEqualTo(base.dsname());
                assertThat(path.base()).isEqualTo("CARDDAT");
                assertThat(path.recordLength()).isEqualTo(base.recordLength());
                assertThat(path.copybook()).isEqualTo(base.copybook());
                assertThat(path.organization()).isEqualTo("aix-path");
                assertThat(base.organization()).isEqualTo("ksds");
            });
        }

        @ParameterizedTest(name = "[{index}] {0} is the batch DD name for {1}")
        @CsvSource({
            "ACCTFILE, ACCTDAT",
            "CARDFILE, CARDDAT",
            "CUSTFILE, CUSTDAT",
            "XREFFILE, CCXREF",
            "CARDXREF, CCXREF",
            "TRANFILE, TRANSACT",
            "XREFFIL1, CXACAIX",
        })
        @DisplayName("each batch DD alias resolves to the same dataset as its CICS FILE definition")
        void batchAliasesResolveToTheirCicsFileDefinition(String alias, String cicsFile) {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThat(bindings.binding(alias).dsname())
                        .as("%s and %s must name one dataset", alias, cicsFile)
                        .isEqualTo(bindings.binding(cicsFile).dsname());
            });
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = { "CARDAIX", "CXACAIX", "XREFFIL1" })
        @DisplayName("every alternate-index path names a configured base, shares that base's record "
                + "geometry, and declares the field forming its alternate key")
        void everyAlternateIndexPathNamesItsBaseAndSharesItsGeometry(String ddName) {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                DatasetBinding path = bindings.binding(ddName);
                assertThat(path.organization()).as("%s organization", ddName).isEqualTo("aix-path");
                assertThat(path.base()).as("%s base", ddName).isNotNull();
                assertThat(path.alternateKey()).as("%s alternate key", ddName).isNotBlank();
                DatasetBinding base = bindings.binding(path.base());
                assertThat(base.base()).as("%s base must be a base cluster, not another path",
                        path.base()).isNull();
                assertThat(path.recordLength()).as("%s record length", ddName)
                        .isEqualTo(base.recordLength());
                assertThat(path.copybook()).as("%s copybook", ddName).isEqualTo(base.copybook());
                assertThat(path.recordFormat()).as("%s record format", ddName)
                        .isEqualTo(base.recordFormat());
            });
        }

        @Test
        @DisplayName("a base is declared by exactly the three alternate-index paths and by nothing "
                + "else, so no base cluster can be mistaken for a path")
        void onlyAlternateIndexPathsDeclareABase() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                List<String> withABase = new ArrayList<>();
                List<String> declaredAsAPath = new ArrayList<>();
                bindings.forEach((ddName, binding) -> {
                    if (binding.base() != null) {
                        withABase.add(ddName);
                    }
                    if ("aix-path".equals(binding.organization())) {
                        declaredAsAPath.add(ddName);
                    }
                });
                assertThat(withABase)
                        .containsExactlyInAnyOrderElementsOf(ALTERNATE_INDEX_PATH_KEYS);
                assertThat(declaredAsAPath)
                        .containsExactlyInAnyOrderElementsOf(ALTERNATE_INDEX_PATH_KEYS);
            });
        }

        @Test
        @DisplayName("the fixture profile resolves the cross-reference base and its alternate index "
                + "to one fixture - same data, different key")
        void fixtureProfileResolvesAnAlternateIndexOntoItsBaseFixture() {
            shippedTestProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                String fixture = bindings.binding("CCXREF").dsname();
                assertThat(bindings.binding("XREFFILE").dsname()).isEqualTo(fixture);
                assertThat(bindings.binding("CARDXREF").dsname()).isEqualTo(fixture);
                assertThat(bindings.binding("CXACAIX").dsname()).isEqualTo(fixture);
                assertThat(bindings.binding("XREFFIL1").dsname()).isEqualTo(fixture);
                assertThat(bindings.binding("CXACAIX").recordLength()).isEqualTo(50);
                assertThat(bindings.binding("CXACAIX").base()).isEqualTo("CCXREF");
            });
        }
    }

    @Nested
    @DisplayName("The keyed lookup - resolves what is configured, fails fast and audibly on what is "
            + "not")
    class FailFastDatasetLookup {
        private static final String SAMPLE_KEY = "ACCTDAT";

        private static final DatasetBinding SAMPLE_BINDING = new DatasetBinding(
                "SENTINEL.LOOKUP.SAMPLE", "ksds", false, "FB", null, 300, "CVACT01Y", null, null, null,
                null);

        private DatasetBindings catalogueWithOneEntry() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put(SAMPLE_KEY, SAMPLE_BINDING);
            return bindings;
        }

        @Test
        @DisplayName("every configured DD name resolves to exactly the descriptor that was bound "
                + "under it")
        void everyConfiguredKeyResolvesToItsBoundDescriptor() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                for (String ddName : ALL_DATASET_KEYS) {
                    assertThat(bindings.binding(ddName)).as("%s", ddName)
                            .isSameAs(bindings.get(ddName));
                }
            });
        }

        @Test
        @DisplayName("an unconfigured DD name fails fast, naming the offending key so the fix is "
                + "unambiguous")
        void unconfiguredDdNameFailsFastNamingTheOffendingKey() {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding("NOSUCHDD"))
                    .withMessageContaining("NOSUCHDD");
        }

        @Test
        @DisplayName("the diagnostic also names the configuration prefix and lists the keys that are "
                + "configured, so an operator never has to guess the spelling")
        void theDiagnosticNamesThePrefixAndTheConfiguredKeys() {
            shippedDefaultProfile().run(context -> {
                DatasetBindings bindings = context.getBean(DatasetBindings.class);
                assertThatIllegalStateException()
                        .isThrownBy(() -> bindings.binding("NOSUCHDD"))
                        .withMessageContaining("NOSUCHDD")
                        .withMessageContaining("carddemo.datasets")
                        .withMessageContaining("ACCTDAT")
                        .withMessageContaining("SYSTRAN");
            });
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = { "acctdat", "AcctDat", "acctDat", "ACCTDAt" })
        @DisplayName("matching is case-sensitive: a differently-cased DD name is not the same DD name")
        void lookupIsCaseSensitive(String differentlyCasedKey) {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThat(bindings).containsKey(SAMPLE_KEY);
            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding(differentlyCasedKey))
                    .withMessageContaining(differentlyCasedKey);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = { " ACCTDAT", "ACCTDAT ", " ACCTDAT " })
        @DisplayName("surrounding whitespace is not trimmed away: a padded DD name is not the same "
                + "DD name either")
        void surroundingWhitespaceIsNotTrimmed(String paddedKey) {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThatIllegalStateException().isThrownBy(() -> bindings.binding(paddedKey));
        }

        @Test
        @DisplayName("a null DD name is rejected exactly like any other unconfigured name, with a "
                + "diagnostic rather than a null-pointer failure")
        void nullDdNameIsRejectedLikeAnyOtherUnconfiguredName() {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding(null))
                    .withMessageContaining("null");
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = { "", " ", "   " })
        @DisplayName("an empty or blank DD name is rejected, not treated as a wildcard or a default")
        void blankDdNameIsRejected(String blankKey) {
            DatasetBindings bindings = catalogueWithOneEntry();
            assertThatIllegalStateException().isThrownBy(() -> bindings.binding(blankKey));
        }

        @Test
        @DisplayName("an empty catalogue still fails fast rather than handing back a null descriptor")
        void anEmptyCatalogueStillFailsFast() {
            DatasetBindings bindings = new DatasetBindings();
            assertThat(bindings).isEmpty();
            assertThatIllegalStateException()
                    .isThrownBy(() -> bindings.binding(SAMPLE_KEY))
                    .withMessageContaining(SAMPLE_KEY);
        }

        @Test
        @DisplayName("the catalogue is usable with no application context in the picture, and "
                + "iterates in a reproducible order")
        void theCatalogueNeedsNoApplicationContext() {
            DatasetBindings bindings = catalogueWithOneEntry();
            bindings.put("CARDDAT", withLocation(SAMPLE_BINDING, "SENTINEL.LOOKUP.SECOND"));
            assertThat(bindings.binding(SAMPLE_KEY)).isSameAs(SAMPLE_BINDING);
            assertThat(bindings.binding("CARDDAT").dsname()).isEqualTo("SENTINEL.LOOKUP.SECOND");
            assertThat(bindings).isInstanceOf(LinkedHashMap.class);
            assertThat(bindings.keySet()).containsExactly(SAMPLE_KEY, "CARDDAT");
        }
    }

    @Nested
    @DisplayName("The template - untuned, with every driver-level bound supplied as configuration")
    class UntunedTemplate {
        private final JdbcTemplate untouched = new JdbcTemplate();

        @Test
        @DisplayName("nothing is configured on the published template: no statement bound, no fetch "
                + "size, no maximum row count")
        void nothingIsConfiguredOnThePublishedTemplate() {
            shippedDefaultProfile().run(context -> {
                JdbcTemplate template = context.getBean(JdbcTemplate.class);

                assertThat(template.getQueryTimeout()).isEqualTo(untouched.getQueryTimeout());
                assertThat(template.getFetchSize()).isEqualTo(untouched.getFetchSize());
                assertThat(template.getMaxRows()).isEqualTo(untouched.getMaxRows());
            });
        }

        @Test
        @DisplayName("the test profile's template is untuned in exactly the same way, so no profile "
                + "validates behaviour the other does not have")
        void theTestProfileTemplateIsUntunedTheSameWay() {
            shippedTestProfile().run(context -> {
                JdbcTemplate template = context.getBean(JdbcTemplate.class);

                assertThat(template.getQueryTimeout()).isEqualTo(untouched.getQueryTimeout());
                assertThat(template.getFetchSize()).isEqualTo(untouched.getFetchSize());
                assertThat(template.getMaxRows()).isEqualTo(untouched.getMaxRows());
            });
        }

        @Test
        @DisplayName("the context starts with no carddemo.jdbc key in the environment at all, which is "
                + "what proves none is read")
        void theContextStartsWithNoJdbcKeyAtAll() {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().containsProperty("carddemo.jdbc.query-timeout-seconds"))
                        .isFalse();
                assertThat(context.getBean(JdbcTemplate.class).getQueryTimeout())
                        .isEqualTo(untouched.getQueryTimeout());
            });
        }

        @Test
        @DisplayName("a stray carddemo.jdbc key in the environment changes nothing, because nothing "
                + "reads it")
        void aStrayJdbcKeyChangesNothing() {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "carddemo.jdbc.query-timeout-seconds=17")
                    .run(context -> assertThat(context.getBean(JdbcTemplate.class).getQueryTimeout())
                            .isEqualTo(untouched.getQueryTimeout()));
        }

        @Test
        @DisplayName("driver-level bounds reach the driver through configuration, so no timeout is "
                + "named in Java either")
        void driverLevelBoundsReachTheDriverThroughConfiguration() {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "spring.datasource.hikari.data-source-properties.socketTimeout=20000",
                    "spring.datasource.hikari.data-source-properties.loginTimeout=5")
                    .run(context -> {
                        HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);

                        assertThat(pool.getDataSourceProperties())
                                .containsEntry("socketTimeout", "20000")
                                .containsEntry("loginTimeout", "5");
                        assertThat(context.getBean(JdbcTemplate.class).getQueryTimeout())
                                .isEqualTo(untouched.getQueryTimeout());
                    });
        }

        @Test
        @DisplayName("it is still the only JdbcOperations definition, with no @Primary anywhere")
        void thereIsStillExactlyOneTemplate() {
            shippedDefaultProfile().run(context -> {
                assertThat(context).hasSingleBean(JdbcTemplate.class);
                assertThat(context.getBeanNamesForType(JdbcOperations.class)).hasSize(1);
            });
        }
    }

    @Nested
    @DisplayName("The pool and the template - one of each, built from configuration, with the driver "
            + "supplied at deployment time")
    class DataSourceAndJdbcTemplateWiring {
        @Test
        @DisplayName("exactly one pooled DataSource is contributed, and it is the connection pool the "
                + "JDBC starter brings transitively")
        void exactlyOnePooledDataSourceIsContributed() {
            shippedDefaultProfile().run(context -> {
                assertThat(context).hasSingleBean(DataSource.class);
                assertThat(context.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
            });
        }

        @Test
        @DisplayName("the pool's settings are taken from configuration, not set in Java")
        void poolSettingsAreTakenFromConfiguration() {
            shippedDefaultProfile().run(context -> {
                HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                assertThat(pool.getPoolName()).isEqualTo("carddemo-pool");
                assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
                assertThat(pool.getMinimumIdle()).isEqualTo(2);
                assertThat(pool.getConnectionTimeout()).isEqualTo(30000L);
                assertThat(pool.isAutoCommit()).isFalse();
            });
        }

        @Test
        @DisplayName("pool settings supplied inline round-trip onto the pool, which is what proves "
                + "they are bound rather than hard-coded")
        void poolSettingsSuppliedInlineRoundTrip() {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "spring.datasource.hikari.pool-name=carddemo-inline-pool",
                    "spring.datasource.hikari.maximum-pool-size=3",
                    "spring.datasource.hikari.minimum-idle=1")
                    .run(context -> {
                        HikariDataSource pool =
                                (HikariDataSource) context.getBean(DataSource.class);
                        assertThat(pool.getPoolName()).isEqualTo("carddemo-inline-pool");
                        assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
                        assertThat(pool.getMinimumIdle()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("exactly one JdbcTemplate is contributed, over the very same pool instance - one "
                + "pool, not two")
        void exactlyOneJdbcTemplateOverTheSamePoolInstance() {
            shippedDefaultProfile().run(context -> {
                assertThat(context).hasSingleBean(JdbcTemplate.class);
                assertThat(context.getBean(JdbcTemplate.class).getDataSource())
                        .isSameAs(context.getBean(DataSource.class));
            });
        }

        @Test
        @DisplayName("the URL comes from configuration and the driver class is inferred from it, so "
                + "no driver coordinate is pinned in Java")
        void urlComesFromConfigurationAndTheDriverIsInferredFromIt() {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY).run(context -> {
                HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                assertThat(pool.getJdbcUrl()).isEqualTo(
                        IN_MEMORY_URL_PROPERTY.substring("spring.datasource.url=".length()));
                assertThat(pool.getDriverClassName()).isEqualTo("org.h2.Driver");
            });
        }

        @Test
        @DisplayName("an explicitly configured driver class wins over the one the URL would imply")
        void anExplicitlyConfiguredDriverClassWins() {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "spring.datasource.driver-class-name=org.h2.jdbcx.JdbcDataSource")
                    .run(context -> {
                        HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                        assertThat(pool.getDriverClassName())
                                .isEqualTo("org.h2.jdbcx.JdbcDataSource");
                    });
        }

        @Test
        @DisplayName("a driver class that is not on the classpath refuses startup, naming the class "
                + "and the property that named it")
        void anAbsentDriverClassRefusesStartup() {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "spring.datasource.driver-class-name=com.example.NoSuchMainframeDriver")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("com.example.NoSuchMainframeDriver")
                                .hasMessageContaining("is not on the classpath")
                                .hasMessageContaining("spring.datasource.driver-class-name")
                                .hasMessageContaining("pins no JDBC driver coordinate by design")
                                .hasMessageContaining("LOADER_PATH")
                                .hasMessageContaining("loader.path")
                                .hasMessageContaining("-cp entry beside -jar is ignored");
                    });
        }

        @Test
        @DisplayName("an unrecognised URL scheme with no driver named refuses startup rather than "
                + "silently substituting the embedded database on the classpath")
        void anUndeterminableDriverRefusesStartupRatherThanFallingBackToH2() {
            withValidCatalogueAnd("spring.datasource.url=jdbc:carddemo-vsam://mainframe/PROD")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("No JDBC driver class could be determined")
                                .hasMessageContaining("spring.datasource.driver-class-name")
                                .hasMessageContaining("NO FALLBACK IS APPLIED")
                                .hasMessageContaining("embedded database on this classpath is never "
                                        + "substituted")
                                .hasMessageContaining("LOADER_PATH")
                                .hasMessageContaining("mean nothing. This module pins");
                    });
        }

        @Test
        @DisplayName("the pool is functional: a trivial query succeeds without any data definition "
                + "being issued first")
        void thePoolIsFunctionalWithoutAnyDataDefinition() {
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(JdbcTemplate.class).queryForObject("SELECT 1", Integer.class))
                    .isEqualTo(1));
        }

        @Test
        @DisplayName("the shipped test profile supplies its own pool with no inline property at all, "
                + "and that pool is functional too")
        void theShippedTestProfileSuppliesItsOwnFunctionalPool() {
            shippedTestProfile().run(context -> {
                HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
                assertThat(pool.getPoolName()).isEqualTo("carddemo-test-pool");
                assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
                assertThat(pool.getMinimumIdle()).isEqualTo(1);
                assertThat(pool.isAutoCommit()).isFalse();
                assertThat(context.getBean(JdbcTemplate.class)
                        .queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("with no URL configured at all the context refuses to start, naming the "
                + "properties an operator has to supply")
        void absentUrlRefusesToStartNamingTheMissingProperty() {
            withInlinePropertiesOnly().run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("spring.datasource.url")
                        .hasMessageContaining("spring.datasource.driver-class-name")
                        .hasMessageContaining("DEPLOYMENT-TIME INPUTS");
            });
        }

        @Test
        @DisplayName("the shipped default profile on its own cannot build a pool either: its URL is "
                + "an environment placeholder with an empty default, by design")
        void theShippedDefaultProfileAloneCannotBuildAPool() {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(DataSourceConfig.class)
                    .withPropertyValues(NO_EXTERNALLY_ACTIVATED_PROFILE)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("spring.datasource.url");
                    });
        }

        @Test
        @DisplayName("an externally activated profile cannot reach the default-profile slice, by "
                + "system property or by environment variable")
        void anExternallyActivatedProfileCannotReachTheDefaultSlice() {
            List<ApplicationContextRunner> underAnInheritedProfile = List.of(
                    shippedDefaultProfile().withSystemProperties("spring.profiles.active=test"),
                    shippedDefaultProfile(processEnvironmentActivating("test")));

            for (ApplicationContextRunner runner : underAnInheritedProfile) {
                runner.run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getActiveProfiles())
                            .as("the slice must resolve no active profile at all")
                            .isEmpty();
                    assertThat(((HikariDataSource) context.getBean(DataSource.class)).getPoolName())
                            .as("the shipped default pool name, not the test profile's")
                            .isEqualTo("carddemo-pool");
                    assertThat(context.getBean(DatasetBindings.class).binding("ACCTDAT").dsname())
                            .as("the shipped default dataset location, not the fixture profile's")
                            .doesNotStartWith("CARDDEMO.TEST.");
                    assertThat(context.getEnvironment()
                            .getProperty("spring.batch.jdbc.initialize-schema"))
                            .isEqualTo("never");
                });
            }
        }

        @Test
        @DisplayName("an empty URL is treated exactly like an absent one")
        void anEmptyUrlIsTreatedLikeAnAbsentOne() {
            withInlinePropertiesOnly("spring.datasource.url=").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("spring.datasource.url");
            });
        }

        @Test
        @DisplayName("a whitespace-only URL is treated exactly like an absent one too, which is what "
                + "an unresolved environment placeholder actually produces")
        void aWhitespaceOnlyUrlIsTreatedLikeAnAbsentOne() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DataSourceConfig.class)
                    .withInitializer(context -> context.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("whitespaceOnlyUrl",
                                    Map.of("spring.datasource.url", "   "))))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("spring.datasource.url");
                    });
        }
    }

    @Nested
    @DisplayName("The negative contract - seven beans, no transaction manager, and nothing "
            + "schema-shaped")
    class NegativeContract {
        private static final List<Class<?>> PERMITTED_BEAN_TYPES = List.of(
                DataSourceConfig.class, HikariDataSource.class, JdbcTemplate.class,
                DataSourceProperties.class, DatasetBindings.class, RecordImageForm.class,
                PhysicalSequence.class);

        @Test
        @DisplayName("the configuration contributes exactly seven beans, and each is one of the seven "
                + "it is answerable for")
        void theConfigurationContributesExactlySevenBeans() {
            shippedDefaultProfile().run(context -> {
                List<String> contributed = new ArrayList<>();
                for (String beanName : context.getBeanDefinitionNames()) {
                    if (!beanName.startsWith("org.springframework.")) {
                        contributed.add(beanName);
                    }
                }
                List<Class<?>> matched = new ArrayList<>();
                for (String beanName : contributed) {
                    Class<?> beanType = context.getBeanFactory().getType(beanName);
                    assertThat(beanType).as("type of bean '%s'", beanName).isNotNull();
                    PERMITTED_BEAN_TYPES.stream()
                            .filter(permitted -> permitted.isAssignableFrom(beanType))
                            .forEach(matched::add);
                }
                assertThat(matched).as("beans contributed: %s", contributed)
                        .containsExactlyInAnyOrderElementsOf(PERMITTED_BEAN_TYPES);
                assertThat(contributed).hasSameSizeAs(PERMITTED_BEAN_TYPES);
            });
        }

        @Test
        @DisplayName("the record-image representation is one bean, and it is the only one of its kind")
        void theRecordImageRepresentationIsTheOnlyOneOfItsKind() {
            shippedDefaultProfile().run(context -> {
                assertThat(context.getBeanNamesForType(RecordImageForm.class))
                        .containsExactly(RecordImageForm.FORM_BEAN_NAME);
                assertThat(context.getBean(RecordImageForm.class))
                        .isSameAs(RecordImageForm.CHARACTER);
            });
        }

        @Test
        @DisplayName("the physical-record ordinal is one bean too, for the same reason: two would let "
                + "two components order the same dataset differently")
        void thePhysicalOrdinalIsTheOnlyOneOfItsKind() {
            shippedDefaultProfile().run(context -> {
                assertThat(context.getBeanNamesForType(PhysicalSequence.class))
                        .containsExactly(PhysicalSequence.BEAN_NAME);
                assertThat(context.getBean(PhysicalSequence.class).expression())
                        .as("the ordinal this slice supplied - the document supplies none")
                        .isEqualTo("_ROWID_");
            });
        }

        @Test
        @DisplayName("the fixture-backed test profile states the representation, because there the "
                + "relations really are character columns")
        void theTestProfileStatesTheRepresentationAsWell() {
            // The test profile MAY state it, and does, because it owns its own relations and knows what
            // they present. The default profile may not: it describes a site's gateway driver, which it
            // cannot know - see theShippedDefaultDocumentRequiresItFromTheEnvironment.
            shippedTestProfile().run(context ->
                    assertThat(context.getBean(RecordImageForm.class))
                            .isSameAs(RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("the shipped default document requires the representation from the environment and "
                + "supplies no value of its own")
        void theShippedDefaultDocumentRequiresItFromTheEnvironment() {
            // The document once carried CHARACTER on the line itself, and a packaged default is
            // indistinguishable from a decision: a gateway presenting binary columns would have had every
            // record decoded as text in the configured code page, successfully and wrongly. It now binds
            // CARDDEMO_RECORD_IMAGE_FORM with no fallback, so the whole document plus a buildable URL and
            // an ordinal is NOT enough to start.
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(DataSourceConfig.class)
                    .withPropertyValues(IN_MEMORY_URL_PROPERTY, PHYSICAL_SEQUENCE_PROPERTY,
                            NO_EXTERNALLY_ACTIVATED_PROFILE)
                    .run(context -> assertThat(context)
                            .as("the shipped document must not default the representation")
                            .hasFailed());
        }

        @Test
        @DisplayName("with the representation unstated the context refuses to start, naming the key")
        void anUnstatedRepresentationRefusesStartup() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DataSourceConfig.class)
                    .withPropertyValues(Stream.concat(minimalValidCatalogue(),
                            Stream.of(IN_MEMORY_URL_PROPERTY)).toArray(String[]::new))
                    .run(context -> assertThat(context).hasFailed());
        }

        @ParameterizedTest(name = "carddemo.record-image.form={0} is refused")
        @ValueSource(strings = { "CHAR", "BINARY_LARGE_OBJECT", "TEXT", "utf8", "1" })
        @DisplayName("a representation the module does not implement is refused by name, never "
                + "defaulted")
        void anUnknownRepresentationRefusesStartup(String configured) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    RecordImageForm.FORM_PROPERTY + "=" + configured)
                    .run(context -> assertThat(context).hasFailed());
        }

        @ParameterizedTest(name = "carddemo.record-image.form={0} resolves")
        @ValueSource(strings = { "CHARACTER", "BINARY", "character", " binary ", "Character" })
        @DisplayName("both representations resolve, case-insensitively and whitespace-tolerantly")
        void bothRepresentationsResolve(String configured) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    RecordImageForm.FORM_PROPERTY + "=" + configured)
                    .run(context -> assertThat(context.getBean(RecordImageForm.class))
                            .isSameAs(RecordImageForm.parse(configured)));
        }

        @Test
        @DisplayName("no transaction manager is declared here - the batch configuration owns the only "
                + "one the module has")
        void noTransactionManagerIsDeclaredHere() {
            shippedDefaultProfile().run(context ->
                    assertThat(context).doesNotHaveBean(PlatformTransactionManager.class));
        }

        @Test
        @DisplayName("the default profile initialises nothing against the configured backend, and "
                + "creates no framework metadata there either")
        void theDefaultProfileInitialisesNothing() {
            shippedDefaultProfile().run(context -> {
                assertThat(context.getEnvironment().getProperty("spring.sql.init.mode"))
                        .isEqualTo("never");
                assertThat(context.getEnvironment()
                        .getProperty("spring.batch.jdbc.initialize-schema")).isEqualTo("never");
            });
        }

        @Test
        @DisplayName("the dataset descriptor exposes a location, a geometry and one cluster attribute - "
                + "exactly twelve components, and no thirteenth")
        void theDescriptorExposesOnlyLocationAndGeometry() {
            List<String> componentNames = Stream.of(DatasetBinding.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            assertThat(componentNames).containsExactly("dsname", "organization", "gdg",
                    "recordFormat", "blockSize", "recordLength", "copybook", "keyLength",
                    "keyOffset", "base", "alternateKey", "reusable");
        }

        @Test
        @DisplayName("the descriptor is an immutable value with value-based equality, which is what "
                + "lets two profiles' geometry be compared directly")
        void theDescriptorIsAnImmutableValue() {
            assertThat(DatasetBinding.class.isRecord()).isTrue();
            assertThat(Modifier.isFinal(DatasetBinding.class.getModifiers())).isTrue();
            DatasetBinding one = new DatasetBinding("SENTINEL.VALUE.ONE", "ksds", false, "FB", null,
                    300, "CVACT01Y", null, null, null, null);
            DatasetBinding same = new DatasetBinding("SENTINEL.VALUE.ONE", "ksds", false, "FB", null,
                    300, "CVACT01Y", null, null, null, null);
            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same)
                    .isNotEqualTo(withLocation(one, "SENTINEL.VALUE.TWO"));
        }
    }

    @Nested
    @DisplayName("The physical-record ordinal - configured, required, and grammar-checked")
    class PhysicalRecordOrdinal {
        @Test
        @DisplayName("the shipped default document requires it from the environment and supplies no "
                + "value of its own")
        void theShippedDefaultDocumentRequiresItFromTheEnvironment() {
            // The document once carried RECORD_ORDINAL on the line itself, which no gateway is obliged to
            // present under that name: a deployment whose ordinal is spelled differently would have
            // started and then either failed at its first physical-sequential read or resolved a
            // same-named column of its own and ordered a report by something that is not the record's
            // position. It now binds CARDDEMO_PHYSICAL_SEQUENCE_EXPRESSION with no fallback, so the whole
            // document plus a buildable URL is NOT enough to start - which is the fail-fast this
            // paragraph of application.yml claims and, until this assertion existed, could not have.
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(DataSourceConfig.class)
                    .withPropertyValues(IN_MEMORY_URL_PROPERTY, RECORD_IMAGE_FORM_PROPERTY,
                            NO_EXTERNALLY_ACTIVATED_PROFILE)
                    .run(context -> assertThat(context)
                            .as("the shipped document must not default the ordinal")
                            .hasFailed());
        }

        @Test
        @DisplayName("the same document starts once the environment supplies the ordinal")
        void theSameDocumentStartsOnceTheEnvironmentSuppliesIt() {
            // The companion arm, and what makes the refusal above a derived outcome rather than a
            // document that cannot start at all: one property more and the same document builds the bean.
            shippedDefaultProfile().run(context -> assertThat(
                    context.getBean(PhysicalSequence.class).expression()).isEqualTo("_ROWID_"));
        }

        @Test
        @DisplayName("the fixture-backed test profile states H2's own row identifier")
        void theTestProfileStatesTheRowIdentifier() {
            shippedTestProfile().run(context ->
                    assertThat(context.getBean(PhysicalSequence.class).expression())
                            .isEqualTo("_ROWID_"));
        }

        @Test
        @DisplayName("with the ordinal unstated the context refuses to start rather than reading a "
                + "physical-sequential dataset in whatever order the backend scanned")
        void anUnstatedOrdinalRefusesStartup() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DataSourceConfig.class)
                    .withPropertyValues(Stream.concat(minimalValidCatalogue(),
                                    Stream.of(IN_MEMORY_URL_PROPERTY, RECORD_IMAGE_FORM_PROPERTY))
                            .toArray(String[]::new))
                    .run(context -> assertThat(context).hasFailed());
        }

        @ParameterizedTest(name = "carddemo.physical-sequence.expression={0} is refused")
        @ValueSource(strings = {
            "RRN DESC",
            "RRN;DROP TABLE X",
            "RRN,SEQ",
            "COUNT(*)",
            "1RRN",
            "'RRN'",
            "\"RRN\"",
        })
        @DisplayName("a value that is not a bare identifier is refused at startup, never rendered")
        void aNonIdentifierRefusesStartup(String configured) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    PhysicalSequence.EXPRESSION_PROPERTY + "=" + configured)
                    .run(context -> assertThat(context).hasFailed());
        }

        @ParameterizedTest(name = "carddemo.physical-sequence.expression={0} resolves")
        @ValueSource(strings = { "_ROWID_", "RRN", "RECORD_ORDINAL", "seq9", "#POS", "@ORD", "$N" })
        @DisplayName("every shape a gateway ordinal legitimately takes resolves, whitespace-tolerantly")
        void everyLegitimateOrdinalResolves(String configured) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    PhysicalSequence.EXPRESSION_PROPERTY + "= " + configured + " ")
                    .run(context -> assertThat(context.getBean(PhysicalSequence.class).expression())
                            .isEqualTo(configured));
        }
    }

    @Nested
    @DisplayName("Dataset locations come from configuration - nothing is defaulted inside Java")
    class DatasetNameExternalisation {
        @ParameterizedTest(name = "[{index}] {0} -> {1}")
        @CsvSource({
            "ACCTDAT,  SENTINEL.EXTERNALISED.ACCTDAT",
            "CARDAIX,  SENTINEL.EXTERNALISED.CARDAIX",
            "CCXREF,   SENTINEL.EXTERNALISED.CCXREF",
            "XREFFIL1, SENTINEL.EXTERNALISED.XREFFIL1",
            "HTMLFILE, SENTINEL.EXTERNALISED.HTMLFILE",
        })
        @DisplayName("a location that could not be a default survives binding unchanged")
        void sentinelLocationsSurviveBindingUnchanged(String ddName, String sentinelLocation) {
            String prefix = "carddemo.datasets." + ddName + ".";
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY, prefix + "dsname=" + sentinelLocation)
                    .run(context -> {
                        DatasetBindings bindings = context.getBean(DatasetBindings.class);
                        assertThat(bindings.keySet())
                                .containsExactlyInAnyOrderElementsOf(ALL_DATASET_KEYS);
                        DatasetBinding binding = bindings.binding(ddName);
                        assertThat(binding.dsname()).isEqualTo(sentinelLocation);
                        assertThat(binding.recordLength()).isEqualTo(50);
                        assertThat(binding.recordFormat()).isEqualTo("FB");
                    });
        }

        @Test
        @DisplayName("an entirely unconfigured catalogue refuses startup, listing every DD name the "
                + "migrated code reads")
        void anUnconfiguredCatalogueRefusesStartup() {
            withInlinePropertiesOnly(IN_MEMORY_URL_PROPERTY).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must declare exactly the 27 DD names")
                        .hasMessageContaining("Missing: [ACCTDAT,")
                        .hasMessageContaining("Unexpected: []");
            });
        }

        @Test
        @DisplayName("one missing DD name refuses startup, naming exactly that one")
        void oneMissingDdNameRefusesStartup() {
            String[] withoutTcatbalf = minimalValidCatalogue()
                    .filter(property -> !property.startsWith("carddemo.datasets.TCATBALF."))
                    .toArray(String[]::new);
            withInlinePropertiesOnly(Stream.concat(
                            Stream.of(withoutTcatbalf), Stream.of(IN_MEMORY_URL_PROPERTY))
                            .toArray(String[]::new))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Missing: [TCATBALF]")
                                .hasMessageContaining("Unexpected: []");
                    });
        }

        @Test
        @DisplayName("an unexpected DD name refuses startup, and is reported alongside the name it "
                + "was probably a typo for")
        void anUnexpectedDdNameRefusesStartup() {
            String[] misspelled = minimalValidCatalogue()
                    .map(property -> property.replace("carddemo.datasets.TCATBALF.",
                            "carddemo.datasets.TCATBLAF."))
                    .toArray(String[]::new);
            withInlinePropertiesOnly(Stream.concat(
                            Stream.of(misspelled), Stream.of(IN_MEMORY_URL_PROPERTY))
                            .toArray(String[]::new))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Missing: [TCATBALF]")
                                .hasMessageContaining("Unexpected: [TCATBLAF]");
                    });
        }

        @Test
        @DisplayName("an unknown property on an otherwise valid entry refuses startup rather than "
                + "being silently discarded")
        void anUnknownPropertyOnAnEntryRefusesStartup() {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY,
                    "carddemo.datasets.ACCTDAT.record-lenght=300")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .isInstanceOf(ConfigurationPropertiesBindException.class);
                    });
        }

        @ParameterizedTest(name = "[{index}] {0} is rejected: {1}")
        @CsvSource(delimiter = '|', value = {
            "carddemo.datasets.ACCTDAT.dsname=                 | it declares no dsname",
            "carddemo.datasets.ACCTDAT.organization=vsam       | its organization is 'vsam'",
            "carddemo.datasets.ACCTDAT.record-format=V         | its record-format is 'V'",
            "carddemo.datasets.ACCTDAT.record-length=0         | its record-length is 0",
            "carddemo.datasets.ACCTDAT.record-length=-1        | its record-length is -1",
            "carddemo.datasets.ACCTDAT.block-size=-1           | its block-size is -1",
            "carddemo.datasets.ACCTDAT.key-length=0            | its key-length is 0",
        })
        @DisplayName("an invalid component on any entry refuses startup, naming the DD name and the "
                + "component")
        void anInvalidComponentRefusesStartup(String override, String expectedDiagnostic) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY, override.trim()).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("entry for DD name 'ACCTDAT' is invalid")
                        .hasMessageContaining(expectedDiagnostic.trim());
            });
        }

        @ParameterizedTest(name = "[{index}] {1}")
        @CsvSource(delimiter = '|', value = {
            "carddemo.datasets.ACCTDAT.base=CARDDAT         | names base 'CARDDAT'",
            "carddemo.datasets.CARDAIX.base=                | names no base",
            "carddemo.datasets.CARDAIX.alternate-key=       | names no alternate-key",
            "carddemo.datasets.CARDAIX.base=NOSUCHDD        | which is not itself a declared DD name",
            "carddemo.datasets.CARDAIX.base=CXACAIX        "
                    + "| which is itself an alternate-index path",
        })
        @DisplayName("an incoherent alternate-index relationship refuses startup (gate G45)")
        void anIncoherentAlternateIndexRelationshipRefusesStartup(
                String override, String expectedDiagnostic) {
            withValidCatalogueAnd(IN_MEMORY_URL_PROPERTY, override.trim()).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("is invalid")
                        .hasMessageContaining(expectedDiagnostic.trim());
            });
        }

        @Test
        @DisplayName("a profile overrides where a dataset lives and never what shape its records are")
        void aProfileOverridesLocationButNeverGeometry() {
            Map<String, DatasetBinding> shipped = new LinkedHashMap<>();
            Map<String, DatasetBinding> underTestProfile = new LinkedHashMap<>();
            shippedDefaultProfile().run(context ->
                    shipped.putAll(context.getBean(DatasetBindings.class)));
            shippedTestProfile().run(context ->
                    underTestProfile.putAll(context.getBean(DatasetBindings.class)));

            assertThat(underTestProfile.keySet())
                    .containsExactlyInAnyOrderElementsOf(shipped.keySet());
            for (String ddName : ALL_DATASET_KEYS) {
                DatasetBinding shippedBinding = shipped.get(ddName);
                DatasetBinding testBinding = underTestProfile.get(ddName);
                assertThat(testBinding.dsname())
                        .as("%s must be repointed by the fixture profile", ddName)
                        .isNotEqualTo(shippedBinding.dsname());
                assertThat(withLocation(testBinding, shippedBinding.dsname()))
                        .as("%s geometry must survive the profile untouched", ddName)
                        .isEqualTo(shippedBinding);
            }
        }
    }
}
