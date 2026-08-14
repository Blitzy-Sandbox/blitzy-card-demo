/*
 * ****************************************************************************
 * Test        : JpaConfigTest
 * Application : CardDemo
 * Type        : Java unit test - persistence configuration
 * Function    : Assert the persistence-contract guard that fails startup when
 *               any of the nine schema-safety properties is missing or set to
 *               a value that would let Hibernate or Flyway reshape a schema
 *               the frozen VSAM catalogue defines.
 * Source      : app/catlg/LISTCAT.txt - the authoritative physical layout, and
 *               the twelve IDCAMS DEFINE CLUSTER jobs under app/jcl/ that the
 *               three Flyway migrations replace. The schema is derived from
 *               those, so nothing at runtime may be permitted to alter it.
 * ****************************************************************************
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.cardemo.config.JpaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.InitializingBean;

/**
 * Unit tests for {@link JpaConfig}.
 *
 * <p>The class exists to make one class of accident impossible: a property change that lets the running
 * application alter a schema the frozen corpus defines. {@code app/catlg/LISTCAT.txt} and the twelve IDCAMS
 * cluster definitions are the specification for every column width and key length, and the three Flyway
 * migrations are the only sanctioned way to realise them. If {@code ddl-auto} were ever anything but
 * {@code validate}, Hibernate would silently reshape a column and the field contract would be gone with no
 * error anywhere.
 *
 * <p>Each of the nine properties is therefore asserted twice: that the required value is accepted, and that
 * the dangerous value is refused at startup rather than at the first write. A guard that only logs, or that
 * defaults a missing property to something safe-looking, would not be a guard - so the absent case is tested
 * as well as the wrong case.
 */
@DisplayName("JpaConfig - the persistence contract that keeps the frozen schema unalterable")
final class JpaConfigTest {

    /** The nine property values that together satisfy the contract. */
    private static JpaConfig compliantConfig() {
        return new JpaConfig("validate", "false", "false", "UTC",
                "true", "false", "true", "true", "false");
    }

    /** The contract holds when every property is right. */
    @Nested
    @DisplayName("the compliant configuration is accepted")
    final class Compliant {

        @Test
        @DisplayName("the nine required values pass verification")
        void theRequiredValuesPass() {
            JpaConfig config = compliantConfig();

            assertThatNoException()
                    .as("validate + UTC + Flyway enabled, validating, clean-disabled and in-order is the "
                            + "only combination that leaves the frozen schema unalterable")
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @ParameterizedTest(name = "ddl-auto=[{0}] normalises to validate")
        @ValueSource(strings = {"validate", "VALIDATE", "Validate", " validate ", "validate  "})
        @DisplayName("the required value is compared case-insensitively and after trimming")
        void theRequiredValueIsNormalisedBeforeComparison(final String ddlAuto) {
            JpaConfig config = new JpaConfig(ddlAuto, "false", "false", " UTC ",
                    "true", "false", "true", "true", "false");

            assertThatNoException()
                    .as("a YAML value can pick up incidental whitespace and an operator can type a "
                            + "different case, and neither changes what Hibernate will do. Normalising "
                            + "before comparison keeps the guard from failing a deployment that is in fact "
                            + "compliant - while still refusing every value that genuinely differs")
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @Test
        @DisplayName("the guard bean runs the verification at startup")
        void theGuardBeanRunsAtStartup() throws Exception {
            InitializingBean guard = compliantConfig().persistenceContractGuard();

            assertThat(guard)
                    .as("the check must run during context refresh, not on first use, so a misconfigured "
                            + "deployment fails to start rather than corrupting a column later")
                    .isNotNull();
            guard.afterPropertiesSet();
        }
    }

    /** Each dangerous value is refused. */
    @Nested
    @DisplayName("a value that would let the schema change is refused at startup")
    final class DangerousValues {

        @ParameterizedTest(name = "ddl-auto=[{0}]")
        @ValueSource(strings = {"update", "create", "create-drop", "none"})
        @DisplayName("any ddl-auto other than validate is refused")
        void anyDdlAutoOtherThanValidateIsRefused(final String ddlAuto) {
            JpaConfig config = new JpaConfig(ddlAuto, "false", "false", "UTC",
                    "true", "false", "true", "true", "false");

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("update or create would let Hibernate reshape a column whose width comes from "
                            + "app/catlg/LISTCAT.txt; none would skip the check that the migrations and the "
                            + "entities still agree")
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @ParameterizedTest(name = "hibernate time zone=[{0}]")
        @ValueSource(strings = {"America/New_York", "Europe/London", "GMT"})
        @DisplayName("a JDBC time zone other than UTC is refused")
        void aNonUtcTimeZoneIsRefused(final String timeZone) {
            JpaConfig config = new JpaConfig("validate", "false", "false", timeZone,
                    "true", "false", "true", "true", "false");

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the generated 26-character timestamps must not shift with the host's zone")
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @Test
        @DisplayName("open-in-view enabled is refused, because a view must not hold a session")
        void openInViewEnabledIsRefused() {
            JpaConfig config = new JpaConfig("validate", "true", "false", "UTC",
                    "true", "false", "true", "true", "false");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @Test
        @DisplayName("SQL logging enabled is refused, because statements carry account data")
        void showSqlEnabledIsRefused() {
            JpaConfig config = new JpaConfig("validate", "false", "true", "UTC",
                    "true", "false", "true", "true", "false");

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("bound parameters include card numbers and balances, which the logging policy "
                            + "masks precisely so they are not emitted")
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @Test
        @DisplayName("Flyway disabled is refused, because the migrations are the schema")
        void flywayDisabledIsRefused() {
            JpaConfig config = new JpaConfig("validate", "false", "false", "UTC",
                    "false", "false", "true", "true", "false");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @Test
        @DisplayName("baseline-on-migrate is refused, because it would skip V1 silently")
        void baselineOnMigrateIsRefused() {
            JpaConfig config = new JpaConfig("validate", "false", "false", "UTC",
                    "true", "true", "true", "true", "false");

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("baselining an existing database marks V1 as already applied, so the eleven tables "
                            + "it defines would never be created and the failure would surface as a "
                            + "missing table at first query")
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @Test
        @DisplayName("validate-on-migrate disabled is refused")
        void validateOnMigrateDisabledIsRefused() {
            JpaConfig config = new JpaConfig("validate", "false", "false", "UTC",
                    "true", "false", "false", "true", "false");

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("without checksum validation an edited migration would apply silently, and the "
                            + "three migrations are the only record of the schema")
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @Test
        @DisplayName("clean enabled is refused, because it can drop the schema")
        void cleanEnabledIsRefused() {
            JpaConfig config = new JpaConfig("validate", "false", "false", "UTC",
                    "true", "false", "true", "false", "false");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(config::verifyPersistenceContract);
        }

        @Test
        @DisplayName("out-of-order migration is refused, because ordering is the contract")
        void outOfOrderEnabledIsRefused() {
            JpaConfig config = new JpaConfig("validate", "false", "false", "UTC",
                    "true", "false", "true", "true", "true");

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("V2 creates indexes over tables V1 defines and V3 seeds them, so the order is a "
                            + "dependency and not a preference")
                    .isThrownBy(config::verifyPersistenceContract);
        }
    }

    /** An absent property is a wiring defect, not a default. */
    @Nested
    @DisplayName("an absent property fails construction rather than defaulting")
    final class AbsentProperties {

        @ParameterizedTest(name = "absent ddl-auto [{0}]")
        @CsvSource(value = {"NULL", "''", "'   '"}, nullValues = "NULL")
        @DisplayName("an unset ddl-auto is refused rather than assumed")
        void anUnsetDdlAutoIsRefused(final String ddlAuto) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the @Value default is empty precisely so that a missing property is visible; "
                            + "defaulting it to validate would hide a profile that never set it")
                    .isThrownBy(() -> new JpaConfig(ddlAuto, "false", "false", "UTC",
                            "true", "false", "true", "true", "false"));
        }

        @ParameterizedTest(name = "absent time zone [{0}]")
        @CsvSource(value = {"NULL", "''", "'   '"}, nullValues = "NULL")
        @DisplayName("an unset JDBC time zone is refused")
        void anUnsetTimeZoneIsRefused(final String timeZone) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new JpaConfig("validate", "false", "false", timeZone,
                            "true", "false", "true", "true", "false"));
        }

        @ParameterizedTest(name = "unparseable flag [{0}]")
        @CsvSource(value = {"NULL", "''", "yes", "no", "1", "0", "TRUE_ISH"}, nullValues = "NULL")
        @DisplayName("a boolean property that is neither true nor false is refused")
        void anUnparseableFlagIsRefused(final String flag) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a flag that cannot be read as a boolean must not be silently treated as false, "
                            + "because false is the dangerous value for four of these five properties")
                    .isThrownBy(() -> new JpaConfig("validate", "false", "false", "UTC",
                            flag, "false", "true", "true", "false"));
        }
    }
}
