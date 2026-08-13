package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.stereotype.Repository;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * {@link TrnxRepository} - the {@code TRNXFILE} dataset's access contract.
 *
 * <h2>What is under test, and why every case is a configuration case</h2>
 * <p>This class holds no arithmetic and issues no statement of its own: it resolves one
 * {@code carddemo.datasets} entry, checks it against {@code app/cpy/COSTM01.CPY}, and publishes the
 * dataset name, the record width, the key span and the {@code DatasetRelation} that
 * {@link StatementGenerationJobB} composes its {@code TRNXFILE} statements from. So the whole of its
 * behaviour is <em>which configurations it accepts and which it refuses</em>, and that is what is
 * driven here - both sides of every guard, which is what the branch-coverage gate is counting.
 *
 * <p>Why the guards matter rather than merely exist: {@code CBSTM03B} hands raw record bytes back in an
 * {@code X(1000)} area, so a wrong record width does not fail at the read. It displaces every field the
 * caller then decodes, silently, and the statement run produces plausible output built from the wrong
 * bytes. Refusing the configuration at startup is the only point at which that is visible.
 *
 * <p>No Spring context, no database and no mock: a {@link DatasetBindings} is a plain map and every
 * assertion below is over one.
 *
 * @see TrnxRepository
 * @see StatementGenerationJobB
 */
@DisplayName("TrnxRepository - the TRNXFILE dataset contract, and the configurations it refuses")
class TrnxRepositoryTest {

    /** A well-formed dataset name for the extract; it is test data, never a real dataset. */
    private static final String TRNX_DS = "TEST.M2.CARDDEMO.TRXFL.VSAM.KSDS";

    /**
     * A catalogue whose {@code TRNXFILE} entry agrees with {@code COSTM01} about everything.
     *
     * @return the catalogue
     */
    private static DatasetBindings validBindings() {
        return bindings(binding(TRNX_DS, TrnxRecord.RECORD_LENGTH, TrnxRecord.TRNX_KEY_LENGTH, null));
    }

    /**
     * A catalogue holding exactly one entry, under the DD name this repository looks up.
     *
     * @param trnx the binding to publish
     * @return the catalogue
     */
    private static DatasetBindings bindings(DatasetBinding trnx) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(TrnxRepository.DD_NAME, trnx);
        return catalogue;
    }

    /**
     * One KSDS binding, with every component this repository reads stated explicitly.
     *
     * @param dsname       the dataset name, which may be {@code null} or blank
     * @param recordLength the declared record width
     * @param keyLength    the declared key width, which may be {@code null}
     * @param keyOffset    the declared key offset, which may be {@code null} for zero
     * @return the binding
     */
    private static DatasetBinding binding(String dsname, int recordLength, Integer keyLength,
                                          Integer keyOffset) {
        return new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null, recordLength,
                TrnxRepository.COPYBOOK, keyLength, keyOffset, null, null);
    }

    @Nested
    @DisplayName("A well-formed binding is resolved, and everything it publishes comes from it")
    class TheResolvedContract {

        @Test
        @DisplayName("the dataset name is the configured one, unchanged (gate G46)")
        void theDatasetNameIsTheConfiguredOne() {
            TrnxRepository repository = new TrnxRepository(validBindings());

            assertThat(repository.datasetName())
                    .isEqualTo(TRNX_DS)
                    .doesNotContain("AWS.M2.CARDDEMO");
            assertThat(repository.relation().dsname()).isEqualTo(TRNX_DS);
        }

        @Test
        @DisplayName("the geometry is the copybook's: 350 bytes, a 32-byte key at offset 0")
        void theGeometryIsTheCopybookGeometry() {
            TrnxRepository repository = new TrnxRepository(validBindings());

            // app/cpy/COSTM01.CPY: TRNX-CARD-NUM X(16) + TRNX-ID X(16) = the 32-byte composite key,
            // then X(318) of data. 32 + 318 = 350, which is what app/cbl/CBSTM03B.CBL:58-63 splits its
            // FD record to.
            assertThat(repository.recordLength()).isEqualTo(350);
            assertThat(repository.recordLength()).isEqualTo(TrnxRecord.RECORD_LENGTH);
            assertThat(repository.keySpan().offset()).isZero();
            assertThat(repository.keySpan().length()).isEqualTo(32);
            assertThat(repository.keySpan().length()).isEqualTo(TrnxRecord.TRNX_KEY_LENGTH);
        }

        @Test
        @DisplayName("an explicitly stated key offset of zero is accepted as readily as an absent one")
        void anExplicitZeroKeyOffsetIsAccepted() {
            // Both spellings mean the same thing, and a deployment that states the default explicitly
            // must not be refused for saying out loud what the other spelling leaves implied.
            assertThatNoException().isThrownBy(() -> new TrnxRepository(bindings(
                    binding(TRNX_DS, TrnxRecord.RECORD_LENGTH, TrnxRecord.TRNX_KEY_LENGTH, 0))));
        }

        @Test
        @DisplayName("the relation is the same instance every caller is handed, so one dataset means "
                + "one contract")
        void theRelationIsOneInstance() {
            TrnxRepository repository = new TrnxRepository(validBindings());

            assertThat(repository.relation()).isSameAs(repository.relation());
            assertThat(repository.keySpan()).isSameAs(repository.keySpan());
        }
    }

    @Nested
    @DisplayName("Configuration defects are refused at startup, not at the first read")
    class TheGuards {

        @Test
        @DisplayName("the catalogue itself is required")
        void theCatalogueIsRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TrnxRepository(null))
                    .withMessageContaining("carddemo.datasets");
        }

        @Test
        @DisplayName("an absent TRNXFILE entry is refused, naming the key to add")
        void anAbsentEntryIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TrnxRepository(new DatasetBindings()))
                    .withMessageContaining(TrnxRepository.DD_NAME);
        }

        @ParameterizedTest(name = "a record length of {0} is refused - gate G19")
        @DisplayName("a record width other than the copybook's is refused")
        @ValueSource(ints = {1, 349, 351, 500})
        void aWrongRecordLengthIsRefused(int wrong) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TrnxRepository(bindings(
                            binding(TRNX_DS, wrong, TrnxRecord.TRNX_KEY_LENGTH, null))))
                    .withMessageContaining("record length")
                    .withMessageContaining(TrnxRepository.COPYBOOK)
                    .withMessageContaining(String.valueOf(TrnxRecord.RECORD_LENGTH));
        }

        @Test
        @DisplayName("an absent key length is refused, because an indexed browse is ordered by the key")
        void anAbsentKeyLengthIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TrnxRepository(bindings(
                            binding(TRNX_DS, TrnxRecord.RECORD_LENGTH, null, null))))
                    .withMessageContaining("key-length")
                    .withMessageContaining("ORGANIZATION IS INDEXED");
        }

        @ParameterizedTest(name = "a key length of {0} is refused")
        @DisplayName("a key width other than the RECORD KEY's is refused")
        @ValueSource(ints = {16, 31, 33, 50})
        void aWrongKeyLengthIsRefused(int wrong) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TrnxRepository(bindings(
                            binding(TRNX_DS, TrnxRecord.RECORD_LENGTH, wrong, null))))
                    .withMessageContaining("key-length")
                    .withMessageContaining(String.valueOf(TrnxRecord.TRNX_KEY_LENGTH));
        }

        @ParameterizedTest(name = "a key offset of {0} is refused")
        @DisplayName("a non-zero key offset is refused - FD-TRNXFILE-KEY is the first FD field")
        @ValueSource(ints = {1, 4, 16, 32})
        void aNonZeroKeyOffsetIsRefused(int wrong) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TrnxRepository(bindings(
                            binding(TRNX_DS, TrnxRecord.RECORD_LENGTH, TrnxRecord.TRNX_KEY_LENGTH,
                                    wrong))))
                    .withMessageContaining("key-offset");
        }

        @ParameterizedTest(name = "a dataset name of ''{0}'' is refused - gate G46")
        @DisplayName("a blank dataset name is refused, because an unconfigured deployment yields blank "
                + "rather than null")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        void aBlankDatasetNameIsRefused(String blank) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TrnxRepository(bindings(
                            binding(blank, TrnxRecord.RECORD_LENGTH, TrnxRecord.TRNX_KEY_LENGTH,
                                    null))))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("an absent dataset name is refused too, not only a blank one")
        void anAbsentDatasetNameIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new TrnxRepository(bindings(
                            binding(null, TrnxRecord.RECORD_LENGTH, TrnxRecord.TRNX_KEY_LENGTH,
                                    null))))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("a name that is not a z/OS dataset name is refused by the shared grammar")
        void aMalformedDatasetNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TrnxRepository(bindings(
                            binding("9BAD..NAME", TrnxRecord.RECORD_LENGTH,
                                    TrnxRecord.TRNX_KEY_LENGTH, null))));
        }
    }

    @Nested
    @DisplayName("The bean contract - a proxyable @Repository holding no mutable state")
    class TheBeanContract {

        @Test
        @DisplayName("it is a @Repository, which is what puts it in the twelve-repository inventory")
        void itIsARepository() {
            assertThat(TrnxRepository.class.isAnnotationPresent(Repository.class))
                    .as("TRNXFILE is a base dataset, so its access contract is a @Repository (gate G10)")
                    .isTrue();
        }

        @Test
        @DisplayName("it is not final, so the persistence-exception proxy can subclass it")
        void itIsNotFinal() {
            // Spring Boot registers a PersistenceExceptionTranslationPostProcessor, which proxies every
            // @Repository bean through CGLIB. A final class cannot be subclassed, and the context fails
            // to start with "Could not generate CGLIB subclass" - which is a startup failure a unit test
            // would never see, so it is asserted here rather than discovered there.
            assertThat(Modifier.isFinal(TrnxRepository.class.getModifiers()))
                    .as("a final @Repository cannot be proxied")
                    .isFalse();
        }

        @Test
        @DisplayName("every field is final and none is static, so one instance serves every run")
        void everyFieldIsFinalAndNoneIsStaticState() {
            for (Field field : TrnxRepository.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field '%s' must be final", field.getName())
                        .isTrue();
                if (Modifier.isStatic(field.getModifiers())) {
                    // Constants only - a DD name and a copybook path, both immutable strings. Static
                    // mutable state is forbidden outright (practice B9, gate G53).
                    assertThat(field.getType())
                            .as("static field '%s' must be an immutable constant", field.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("the DD name is a configuration key, and no dataset name is written in Java")
        void theDdNameIsAConfigurationKey() {
            assertThat(TrnxRepository.DD_NAME).isEqualTo("TRNXFILE");
            assertThat(TrnxRepository.COPYBOOK).isEqualTo("app/cpy/COSTM01.CPY");
            assertThat(TrnxRepository.DD_NAME).doesNotContain(".");
        }
    }
}
